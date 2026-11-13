"""Schedules: "clean the kitchen every weekday at 09:30".

One task sleeps until the earliest `next_run` and wakes early only when the
schedules or the time zone change (or once a minute, to notice the wall clock
jumping, as it does after the machine sleeps). Times are wall-clock times in
the app's zone: a time the spring-forward day skips runs at the next minute
that exists, a time the fall-back day has twice runs at the first. A run more
than 120 s late (the app was down, the machine slept) is skipped, never run
late: a robot setting off at 3 am because the power came back is worse than
a missed clean.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import secrets
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from .config import atomic_write
from .hub import Hub, join_names
from .model import Command, RobotError

log = logging.getLogger(__name__)

LATE_LIMIT = 120.0
# The longest single sleep: if the machine sleeps or its clock is set, the
# loop notices within this.
MAX_SLEEP = 60.0

_TIME = re.compile(r"([01]\d|2[0-3]):([0-5]\d)")
_FIELDS = {"robot", "name", "enabled", "days", "time", "action"}
_IGNORED = {"id", "next_run", "last_run"}  # the page may send a schedule back whole
_ACTION_FIELDS = {"type", "rooms", "repeat", "fan", "water"}
_DAY_NAMES = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
_DAY_PLURALS = ("Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")


@dataclass(slots=True)
class Schedule:
    id: str
    robot: str
    name: str
    enabled: bool
    days: tuple[int, ...]
    time: str  # "HH:MM"
    type: str  # "clean_all" or "clean_rooms"
    rooms: tuple[int, ...]
    repeat: int
    fan: str | None
    water: str | None
    next_run: int | None
    last_run: dict | None

    def to_json(self) -> dict:
        return {
            "id": self.id,
            "robot": self.robot,
            "name": self.name,
            "enabled": self.enabled,
            "days": list(self.days),
            "time": self.time,
            "action": {
                "type": self.type,
                "rooms": list(self.rooms),
                "repeat": self.repeat,
                "fan": self.fan,
                "water": self.water,
            },
            "next_run": self.next_run,
            "last_run": self.last_run,
        }

    def to_file(self) -> dict:
        data = self.to_json()
        del data["next_run"]  # computed from the clock, never stored
        return data


def wall_time_to_ts(day: date, hour: int, minute: int, zone: ZoneInfo) -> int:
    """The instant a wall-clock time happens on a day. A time that does not
    exist (spring forward) gives the next minute that does; a time that
    happens twice (fall back) gives the first."""
    wall = datetime(day.year, day.month, day.day, hour, minute)
    for step in range(24 * 60):
        candidate = wall + timedelta(minutes=step)
        ts = candidate.replace(tzinfo=zone, fold=0).timestamp()
        if datetime.fromtimestamp(ts, zone).replace(tzinfo=None) == candidate:
            return int(ts)
    raise ValueError(f"no valid time at or after {wall} in {zone.key}")


def next_occurrence(days: tuple[int, ...], hhmm: str, zone: ZoneInfo, after: float) -> int:
    """The first run strictly after `after`."""
    hour, minute = int(hhmm[:2]), int(hhmm[3:])
    start = datetime.fromtimestamp(after, zone).date()
    for offset in range(8):
        day = start + timedelta(days=offset)
        if day.weekday() not in days:
            continue
        ts = wall_time_to_ts(day, hour, minute, zone)
        if ts > after:
            return ts
    raise ValueError(f"no run within a week for days {days}")


def describe_days(days: tuple[int, ...]) -> str:
    if days == (0, 1, 2, 3, 4, 5, 6):
        return "every day"
    if days == (0, 1, 2, 3, 4):
        return "weekdays"
    if days == (5, 6):
        return "weekends"
    if len(days) == 1:
        return _DAY_PLURALS[days[0]]
    return join_names([_DAY_NAMES[d] for d in days])


def _report_crash(task: asyncio.Task[None]) -> None:
    # The loop never ends by itself: anything but a cancel is a bug, and
    # schedules silently stopping would be the worst way to find out.
    if not task.cancelled() and task.exception() is not None:
        log.critical("The scheduler stopped", exc_info=task.exception())


class Scheduler:
    def __init__(self, hub: Hub, path: Path, clock: Callable[[], float] = time.time) -> None:
        self._hub = hub
        self._path = path
        self._clock = clock
        self._zone: ZoneInfo | None = None
        self._schedules: dict[str, Schedule] = {}
        self._wake = asyncio.Event()
        self._task: asyncio.Task[None] | None = None
        self._save_lock = asyncio.Lock()

    # -- lifecycle ---------------------------------------------------------

    def load(self, tz: str | None) -> None:
        self._zone = None if tz is None else ZoneInfo(tz)
        if self._path.is_file():
            for d in json.loads(self._path.read_bytes()):
                action = d["action"]
                schedule = Schedule(
                    id=d["id"],
                    robot=d["robot"],
                    name=d["name"],
                    enabled=d["enabled"],
                    days=tuple(d["days"]),
                    time=d["time"],
                    type=action["type"],
                    rooms=tuple(action["rooms"]),
                    repeat=action["repeat"],
                    fan=action["fan"],
                    water=action["water"],
                    next_run=None,
                    last_run=d["last_run"],
                )
                self._schedules[schedule.id] = schedule
        now = self._clock()
        for schedule in self._schedules.values():
            self._plan(schedule, now)
        self._publish()

    def start(self) -> None:
        self._task = asyncio.get_running_loop().create_task(self._loop())
        self._task.add_done_callback(_report_crash)

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None

    # -- reading -----------------------------------------------------------

    def as_json(self) -> list[dict]:
        return [s.to_json() for s in self._schedules.values()]

    def get(self, schedule_id: str) -> Schedule:
        schedule = self._schedules.get(schedule_id)  # absent: answered as not_found
        if schedule is None:
            raise RobotError("not_found", f"There is no schedule {schedule_id}.")
        return schedule

    # -- changes -----------------------------------------------------------

    async def set_tz(self, tz: str) -> None:
        self._zone = ZoneInfo(tz)
        await self._changed()

    async def create(self, body: dict) -> dict:
        fields = self._validate(body)
        schedule_id = f"s_{secrets.token_hex(3)}"
        while schedule_id in self._schedules:
            schedule_id = f"s_{secrets.token_hex(3)}"
        schedule = Schedule(id=schedule_id, next_run=None, last_run=None, **fields)
        self._schedules[schedule_id] = schedule
        await self._changed()
        return schedule.to_json()

    async def update(self, schedule_id: str, body: dict) -> dict:
        schedule = self.get(schedule_id)
        fields = self._validate(body)
        for key, value in fields.items():
            setattr(schedule, key, value)
        await self._changed()
        return schedule.to_json()

    async def delete(self, schedule_id: str) -> None:
        self.get(schedule_id)
        del self._schedules[schedule_id]
        await self._changed()

    async def remove_all(self) -> None:
        """The account was unlinked: its robots, and so their schedules, are gone."""
        self._schedules.clear()
        await self._changed()

    async def run_now(self, schedule_id: str) -> None:
        """Run it now; a robot's refusal is recorded and raised."""
        try:
            await self._execute(self.get(schedule_id))
        finally:
            await self._changed()

    # -- internals ---------------------------------------------------------

    def _plan(self, schedule: Schedule, after: float) -> None:
        if not schedule.enabled:
            schedule.next_run = None
            return
        if self._zone is None:
            raise ValueError("schedules exist but no time zone is set")
        schedule.next_run = next_occurrence(schedule.days, schedule.time, self._zone, after)

    def _publish(self) -> None:
        self._hub.publish_schedules(self.as_json())

    async def _changed(self) -> None:
        now = self._clock()
        for schedule in self._schedules.values():
            self._plan(schedule, now)
        self._publish()
        self._wake.set()
        async with self._save_lock:
            data = json.dumps([s.to_file() for s in self._schedules.values()], indent=1).encode()
            await asyncio.to_thread(atomic_write, self._path, data)

    async def _loop(self) -> None:
        while True:
            now = self._clock()
            due = [
                s for s in self._schedules.values() if s.next_run is not None and s.next_run <= now
            ]
            if due:
                runs = []
                for schedule in due:
                    assert schedule.next_run is not None
                    late = now - schedule.next_run
                    self._plan(schedule, now)
                    if late > LATE_LIMIT:
                        log.warning("Skipped schedule %s: it came due %d s ago", schedule.name, late)
                    else:
                        runs.append(self._run_scheduled(schedule))
                await asyncio.gather(*runs)
                await self._changed()
            upcoming = [s.next_run for s in self._schedules.values() if s.next_run is not None]
            delay = MAX_SLEEP
            if upcoming:
                delay = min(delay, max(0.0, min(upcoming) - self._clock()))
            self._wake.clear()
            try:
                await asyncio.wait_for(self._wake.wait(), delay)
            except TimeoutError:
                pass

    async def _run_scheduled(self, schedule: Schedule) -> None:
        try:
            await self._execute(schedule)
        except RobotError as exc:
            # Recorded on the schedule and in the history; nothing to raise to.
            log.warning("Schedule %s did not run: %s", schedule.name, exc.message)

    async def _execute(self, schedule: Schedule) -> None:
        robot = schedule.robot
        try:
            if schedule.fan is not None:
                await self._hub.command(robot, Command("set_fan", value=schedule.fan))
            if schedule.water is not None:
                await self._hub.command(robot, Command("set_water", value=schedule.water))
            if schedule.type == "clean_all":
                await self._hub.command(robot, Command("start"))
            else:
                await self._hub.command(
                    robot, Command("clean_rooms", rooms=schedule.rooms, repeat=schedule.repeat)
                )
        except RobotError as exc:
            schedule.last_run = {"ts": int(self._clock()), "ok": False, "message": exc.message}
            self._hub.add_history(robot, "schedule", f"Schedule {schedule.name} failed: {exc.message}")
            raise
        schedule.last_run = {"ts": int(self._clock()), "ok": True, "message": "Started"}
        self._hub.add_history(robot, "schedule", f"Schedule {schedule.name} started")

    def _validate(self, body: dict) -> dict:
        def bad(message: str) -> RobotError:
            return RobotError("bad_request", message)

        if self._zone is None:
            raise bad("Set the time zone first.")
        for key in body:
            if key not in _FIELDS and key not in _IGNORED:
                raise bad(f"Unknown field {key!r}.")
        for key in ("robot", "days", "time", "action"):
            if key not in body:
                raise bad(f"{key!r} is required.")

        robot_id = body["robot"]
        if not isinstance(robot_id, str):
            raise bad("'robot' is a robot id.")
        try:
            robot = self._hub.robot(robot_id)
        except RobotError as exc:
            raise bad(exc.message) from exc
        obs = robot.obs

        enabled = body.get("enabled", True)  # optional in the request, on by default
        if not isinstance(enabled, bool):
            raise bad("'enabled' is true or false.")

        days = body["days"]
        if (
            not isinstance(days, list)
            or not days
            or not all(isinstance(d, int) and not isinstance(d, bool) and 0 <= d <= 6 for d in days)
            or len(set(days)) != len(days)
        ):
            raise bad("'days' is a list of different days, 0 (Monday) to 6 (Sunday), at least one.")
        days_t = tuple(sorted(days))

        hhmm = body["time"]
        if not isinstance(hhmm, str) or not _TIME.fullmatch(hhmm):
            raise bad("'time' is HH:MM, from 00:00 to 23:59.")

        action = body["action"]
        if not isinstance(action, dict):
            raise bad("'action' is an object.")
        for key in action:
            if key not in _ACTION_FIELDS:
                raise bad(f"Unknown field 'action.{key}'.")
        kind = action.get("type")
        if kind not in ("clean_all", "clean_rooms"):
            raise bad("'action.type' is \"clean_all\" or \"clean_rooms\".")
        rooms = action.get("rooms", [])  # optional for clean_all
        if not isinstance(rooms, list) or not all(
            isinstance(r, int) and not isinstance(r, bool) for r in rooms
        ):
            raise bad("'action.rooms' is a list of room ids.")
        known = {room.id: room.name for room in robot.rooms}
        if kind == "clean_rooms":
            if not rooms:
                raise bad("Choose at least one room.")
            for room_id in rooms:
                if room_id not in known:
                    raise bad(f"Room {room_id} is not on {robot.info.name}'s map.")
        elif rooms:
            raise bad("A whole-home clean takes no rooms.")
        repeat = action.get("repeat", 1)  # optional, one pass by default
        if not isinstance(repeat, int) or isinstance(repeat, bool) or not 1 <= repeat <= 3:
            raise bad("'action.repeat' is 1, 2 or 3.")
        fan = action.get("fan")  # optional: leave the robot's setting alone
        water = action.get("water")  # optional, as fan
        if fan is not None and (obs is None or fan not in obs.fan_options):
            raise bad(f"{robot.info.name} has no suction level {fan!r}.")
        if water is not None and (obs is None or water not in obs.water_options):
            raise bad(f"{robot.info.name} has no mop water level {water!r}.")

        name = body.get("name")  # optional: made from the action and days
        if name is not None and not isinstance(name, str):
            raise bad("'name' is text.")
        name = (name or "").strip()
        if len(name) > 100:
            raise bad("A name is at most 100 characters.")
        if not name:
            what = "Whole home" if kind == "clean_all" else join_names([known[r] for r in rooms])
            name = f"{what}, {describe_days(days_t)} {hhmm}"

        return {
            "robot": robot_id,
            "name": name,
            "enabled": enabled,
            "days": days_t,
            "time": hhmm,
            "type": kind,
            "rooms": tuple(rooms),
            "repeat": repeat,
            "fan": fan,
            "water": water,
        }
