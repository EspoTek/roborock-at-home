"""The one place robot state lives.

The Hub implements `Sink`: a backend reports facts (a read worked, a read
failed, rooms, a raw map) and the Hub derives what a person sees: online or
not, what needs attention, the last known position, the history.

Requests must cost nothing, so the work happens when data changes, not when
it is read. Each robot keeps its JSON as ready-made bytes and the
`/api/state` body is spliced from those (rebuilt at most once per change,
gzipped lazily at most once per change).

The event stream carries as little as possible: a `robot` event is a patch
holding only the top-level fields that changed since the last one, and the
path goes out as the points appended since the last `path` event. `seen`
moves on every read but never causes an event by itself: it is current in
/api/state and rides along in the next patch that something else causes.

Per robot it holds the last Observation, rooms, the map's geometry and its
index gzipped (what `map.bin` serves), the position and the path. Never the
raw blob or the inflated index.
"""

from __future__ import annotations

import asyncio
import gzip
import json
import logging
import time
import zlib
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, replace
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from . import rrmap
from .config import atomic_write, check_robot_id
from .model import (
    COMMANDS,
    MM_PER_PX,
    Backend,
    Command,
    ErrorInfo,
    MapFrame,
    Observation,
    RobotError,
    RobotInfo,
    Room,
    RoomGeom,
)

log = logging.getLogger(__name__)

OFFLINE_AFTER_FAILURES = 3
OFFLINE_AFTER_SECONDS = 90.0
LOW_BATTERY = 15
FLAT_BATTERY = 10
HISTORY_KEEP = 200
# Persistence is debounced: a cleaning robot changes every second and an SD
# card should not be written that often.
FLUSH_EVERY = 5.0
# The backend keeps polling fast for a while after the last page closes, so
# a reload or a quick tab switch does not drop it to its slow cadence.
WATCH_GRACE = 30.0
# Events a slow client may have waiting before it is dropped.
QUEUE_DEPTH = 32

_DUMPS = json.JSONEncoder(separators=(",", ":")).encode


def dumps(obj: object) -> bytes:
    return _DUMPS(obj).encode()


def join_names(names: list[str]) -> str:
    """"Kitchen", "Kitchen and Hall", "Kitchen, Hall and Study"."""
    if len(names) <= 1:
        return "".join(names)
    return ", ".join(names[:-1]) + " and " + names[-1]


def _lower_first(text: str) -> str:
    # "Main brush jammed" -> "main brush jammed", but "LDS jammed" stays.
    if len(text) > 1 and text[0].isupper() and text[1].islower():
        return text[0].lower() + text[1:]
    return text


@dataclass(slots=True)
class Robot:
    """One robot's state. Read-only outside this module."""

    info: RobotInfo
    obs: Observation | None = None
    online: bool = False
    seen: int | None = None  # last successful read
    last_ok: float | None = None  # same, as a clock value, for the 90 s rule
    failures: int = 0  # failed reads in a row
    rooms: tuple[Room, ...] = ()
    geom: MapFrame | None = None  # the parsed map with an empty index
    map_gz: bytes | None = None  # the index, gzipped: what map.bin serves
    map_json: dict | None = None
    pos: dict | None = None
    pos_read: float = 0.0  # when the latest map that placed the robot arrived
    path: tuple[float, ...] = ()
    path_rev: int = 0
    path_body: bytes | None = None
    attention: dict | None = None
    job: bool = False  # between "started" and "finished"
    offline_logged: bool = False
    full: dict | None = None  # the robot object as last published
    fragment: bytes = b""  # the same, serialised
    sent_seen: int | None = None  # `seen` as the event stream last carried it
    parsing: bool = False
    pending_map: bytes | None = None
    offline_timer: asyncio.TimerHandle | None = None

    def room_name_or_none(self, room_id: int | None) -> str | None:
        for room in self.rooms:
            if room.id == room_id:
                return room.name
        return None


class Hub:
    def __init__(
        self,
        data_dir: Path,
        app_info: Callable[[], dict],
        clock: Callable[[], float] = time.time,
        watch_grace: float = WATCH_GRACE,
    ) -> None:
        self._data_dir = data_dir
        self._maps_dir = data_dir / "maps"
        self._app_info = app_info
        self._clock = clock
        self._watch_grace = watch_grace
        self._zone: ZoneInfo | None = None
        self._robots: dict[str, Robot] = {}  # in the order found
        self._history: deque[dict] = deque(maxlen=HISTORY_KEEP)  # newest first
        self._backend: Backend | None = None
        self.account_problem_message: str | None = None

        self.rev = 0
        self._app = b"{}"
        self._schedules = b"[]"
        self._state_rev = -1
        self._state = b""
        self._state_gz: bytes | None = None
        self._robots_body = b"[]"

        self._subscribers: set[asyncio.Queue[bytes | None]] = set()
        self._watched = False
        self._grace_timer: asyncio.TimerHandle | None = None

        self._dirty = False
        self._flush_task: asyncio.Task[None] | None = None
        self._flush_lock = asyncio.Lock()
        self._last_flush = 0.0
        self._tasks: set[asyncio.Task[None]] = set()

    # ------------------------------------------------------------------
    # Reading
    # ------------------------------------------------------------------

    def robot(self, robot_id: str) -> Robot:
        robot = self._robots.get(robot_id)  # absent: answered as not_found
        if robot is None:
            raise RobotError("not_found", f"There is no robot {robot_id}.")
        return robot

    @property
    def robots(self) -> list[Robot]:
        return list(self._robots.values())

    def state(self) -> bytes:
        if self._state_rev != self.rev:
            self._robots_body = b"[" + b",".join(r.fragment for r in self._robots.values()) + b"]"
            self._state = b'{"rev":%d,"app":%s,"robots":%s,"schedules":%s}' % (
                self.rev,
                self._app,
                self._robots_body,
                self._schedules,
            )
            self._state_gz = None
            self._state_rev = self.rev
        return self._state

    def state_gz(self) -> bytes:
        body = self.state()
        if self._state_gz is None:
            self._state_gz = gzip.compress(body, 6, mtime=0)
        return self._state_gz

    def robots_body(self) -> bytes:
        self.state()
        return self._robots_body

    @property
    def schedules_body(self) -> bytes:
        return self._schedules

    def history(self, limit: int) -> list[dict]:
        return list(self._history)[:limit]

    def path_body(self, robot_id: str) -> bytes:
        robot = self.robot(robot_id)
        if robot.path_body is None:
            robot.path_body = dumps({"rev": robot.path_rev, "points": robot.path})
        return robot.path_body

    def render_key(self, robot_id: str) -> tuple:
        """Names what a drawing of this robot's map shows right now."""
        robot = self.robot(robot_id)
        if robot.geom is None:
            raise RobotError("not_found", f"{robot.info.name} has no map yet.")
        pos = robot.pos
        return (
            robot_id,
            robot.geom.digest,
            None if pos is None else (pos["x"], pos["y"], pos["angle"]),
            robot.path_rev,
        )

    def frame_for_render(self, robot_id: str) -> MapFrame:
        """A complete MapFrame to draw: the index inflated, the last known
        position and the path in it."""
        robot = self.robot(robot_id)
        if robot.geom is None or robot.map_gz is None:
            raise RobotError("not_found", f"{robot.info.name} has no map yet.")
        pos = robot.pos
        return replace(
            robot.geom,
            index=zlib.decompress(robot.map_gz, 31),
            robot=None if pos is None else (pos["x"], pos["y"]),
            robot_angle=None if pos is None else pos["angle"],
            path=robot.path,
        )

    @property
    def clients(self) -> int:
        return len(self._subscribers)

    # ------------------------------------------------------------------
    # Sink
    # ------------------------------------------------------------------

    def robot_found(self, info: RobotInfo) -> None:
        check_robot_id(info.id)
        robot = self._robots.get(info.id)  # absent: a new robot
        if robot is None:
            self._robots[info.id] = robot = Robot(info)
        elif robot.info == info:
            return
        robot.info = info
        self._refresh(robot)

    def robot_status(self, robot_id: str, obs: Observation) -> None:
        robot = self._known(robot_id)
        now = self._clock()
        prev = robot.obs
        was_online = robot.online
        robot.obs = obs
        robot.online = True
        robot.failures = 0
        robot.last_ok = now
        robot.seen = int(now)
        if robot.offline_timer is not None:
            robot.offline_timer.cancel()
            robot.offline_timer = None
        if robot.offline_logged:
            robot.offline_logged = False
            self.add_history(robot_id, "online", "Answering again")
        self._transitions(robot, prev, obs)
        if obs.activity == "error" and robot.pos is not None and robot.pos["live"]:
            self._pos_stale(robot)
        if not was_online and obs.activity == "docked":
            self._pos_on_dock(robot, now)
        self._refresh(robot)

    def _pos_on_dock(self, robot: Robot, now: float) -> None:
        """Back online and docked: it is on its dock, so say so now rather
        than show a stale pin until the next map (15 minutes when docked)."""
        geom = robot.geom
        if geom is None or geom.charger is None or robot.map_gz is None:
            return
        x, y = geom.charger
        index = zlib.decompress(robot.map_gz, 31)  # about 0.1 ms, once per reconnect
        robot.pos = {
            "x": round(x, 1),
            "y": round(y, 1),
            "angle": None if robot.pos is None else robot.pos["angle"],
            "room": rrmap.room_at(replace(geom, index=index), x, y),
            "ts": int(now),
            "live": True,
        }
        robot.pos_read = now

    def robot_unreachable(self, robot_id: str, reason: str) -> None:
        robot = self._known(robot_id)
        log.info("%s did not answer: %s", robot.info.name, reason)
        robot.failures += 1
        if not robot.online:
            return
        assert robot.last_ok is not None  # online is only ever set by a read
        since_ok = self._clock() - robot.last_ok
        if robot.failures >= OFFLINE_AFTER_FAILURES or since_ok >= OFFLINE_AFTER_SECONDS:
            self._go_offline(robot)
        elif robot.offline_timer is None:
            robot.offline_timer = asyncio.get_running_loop().call_later(
                OFFLINE_AFTER_SECONDS - since_ok, self._offline_due, robot
            )

    def robot_rooms(self, robot_id: str, rooms: list[Room]) -> None:
        robot = self._known(robot_id)
        rooms_t = tuple(rooms)
        if rooms_t != robot.rooms:
            robot.rooms = rooms_t
            self._refresh(robot)

    def robot_map(self, robot_id: str, raw: bytes) -> None:
        robot = self._known(robot_id)
        if robot.parsing:
            # Only the newest map matters; one still being parsed is enough.
            robot.pending_map = raw
            return
        robot.parsing = True
        self._spawn(self._parse_maps(robot, raw))

    def account_problem(self, message: str | None) -> None:
        self.account_problem_message = message
        self.app_changed()

    # ------------------------------------------------------------------
    # Changes from the rest of the app
    # ------------------------------------------------------------------

    def app_changed(self) -> None:
        body = dumps(self._app_info())
        if body != self._app:
            self._app = body
            self._bump()
            self._emit(b"app", body)

    def publish_schedules(self, schedules: list[dict]) -> None:
        body = dumps(schedules)
        if body != self._schedules:
            self._schedules = body
            self._bump()
            self._emit(b"schedules", body)

    def set_tz(self, name: str | None) -> None:
        self._zone = None if name is None else ZoneInfo(name)
        for robot in self._robots.values():
            self._refresh(robot)

    def add_history(self, robot_id: str, kind: str, text: str) -> None:
        entry = {"ts": int(self._clock()), "robot": robot_id, "kind": kind, "text": text}
        self._history.appendleft(entry)
        self._emit(b"history", dumps(entry))
        self._mark_dirty()

    async def command(self, robot_id: str, command: Command, source: str | None = None) -> None:
        """Check a command against what is known, then send it. `source`
        names an API token; its commands go into the history."""
        robot = self.robot(robot_id)
        name = robot.info.name
        if command.name not in COMMANDS:
            raise RobotError("bad_request", f"There is no command {command.name!r}.")
        if command.name == "clean_rooms":
            if not command.rooms:
                raise RobotError("bad_request", "Choose at least one room.")
            known = {room.id for room in robot.rooms}
            for room_id in command.rooms:
                if room_id not in known:
                    raise RobotError("bad_request", f"Room {room_id} is not on {name}'s map.")
            if not 1 <= command.repeat <= 3:
                raise RobotError("bad_request", "Repeat is 1, 2 or 3.")
        elif command.name in ("set_fan", "set_water"):
            fan = command.name == "set_fan"
            obs = robot.obs
            options = () if obs is None else (obs.fan_options if fan else obs.water_options)
            if not options:
                what = "suction levels" if fan else "mop water levels"
                raise RobotError("unsupported", f"{name} has no {what} to choose from.")
            if command.value not in options:
                raise RobotError(
                    "bad_request", f"{command.value!r} is not one of {', '.join(options)}."
                )
        if self._backend is None:
            raise RobotError("robot_unreachable", "Not connected to any robots.")
        await self._backend.command(robot_id, command)
        if source is not None:
            self.add_history(robot_id, "command", f"{describe(robot, command)}, sent by {source}")

    # ------------------------------------------------------------------
    # Backend and watchers
    # ------------------------------------------------------------------

    async def set_backend(self, backend: Backend | None) -> None:
        """Replace the backend. None forgets every robot, its map and the
        history (the account was unlinked)."""
        old, self._backend = self._backend, None
        if old is not None:
            await old.stop()
        if backend is None:
            await self._forget_all()
            return
        self._backend = backend
        await backend.start(self)
        backend.set_watched(self._watched)

    async def stop(self) -> None:
        """Stop the backend (keeping what it reported) and write everything."""
        old, self._backend = self._backend, None
        if old is not None:
            await old.stop()
        for task in list(self._tasks):
            task.cancel()
        for robot in self._robots.values():
            if robot.offline_timer is not None:
                robot.offline_timer.cancel()
                robot.offline_timer = None
        if self._grace_timer is not None:
            self._grace_timer.cancel()
            self._grace_timer = None
        if self._flush_task is not None:
            self._flush_task.cancel()
            self._flush_task = None
        await self.flush()
        self.close_subscribers()

    def subscribe(self) -> asyncio.Queue[bytes | None]:
        queue: asyncio.Queue[bytes | None] = asyncio.Queue(QUEUE_DEPTH)
        self._subscribers.add(queue)
        self._update_watched()
        return queue

    def unsubscribe(self, queue: asyncio.Queue[bytes | None]) -> None:
        self._subscribers.discard(queue)
        self._update_watched()

    def close_subscribers(self) -> None:
        for queue in list(self._subscribers):
            self._drop(queue)

    def event(self, name: bytes, data: bytes) -> bytes:
        return b"event: %s\nid: %d\ndata: %s\n\n" % (name, self.rev, data)

    # ------------------------------------------------------------------
    # Persistence
    # ------------------------------------------------------------------

    def load(self) -> None:
        """Robots and history from the last run. Robots appear offline until
        the backend reports them."""
        robots_file = self._data_dir / "robots.json"
        if robots_file.is_file():
            for rec in json.loads(robots_file.read_bytes()):
                robot = _robot_from_json(rec)
                if robot.geom is not None:
                    robot.map_gz = (self._maps_dir / f"{robot.info.id}.bin.gz").read_bytes()
                self._robots[robot.info.id] = robot
                self._refresh(robot)
        history_file = self._data_dir / "history.json"
        if history_file.is_file():
            self._history.extend(json.loads(history_file.read_bytes()))
        self._dirty = False

    async def flush(self) -> None:
        async with self._flush_lock:
            if not self._dirty:
                return
            self._dirty = False
            robots = dumps([_robot_to_json(r) for r in self._robots.values()])
            history = dumps(list(self._history))
            await asyncio.to_thread(self._write_state, robots, history)
            self._last_flush = asyncio.get_running_loop().time()

    def _write_state(self, robots: bytes, history: bytes) -> None:
        atomic_write(self._data_dir / "robots.json", robots)
        atomic_write(self._data_dir / "history.json", history)

    def _mark_dirty(self) -> None:
        self._dirty = True
        if self._flush_task is None:
            self._flush_task = asyncio.get_running_loop().create_task(self._flush_later())

    async def _flush_later(self) -> None:
        loop = asyncio.get_running_loop()
        await asyncio.sleep(max(0.0, self._last_flush + FLUSH_EVERY - loop.time()))
        self._flush_task = None
        await self.flush()

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _known(self, robot_id: str) -> Robot:
        # A backend reports a robot before anything else about it; anything
        # else is a bug in the backend.
        return self._robots[robot_id]

    def _spawn(self, coro) -> None:
        task = asyncio.get_running_loop().create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    def _bump(self) -> None:
        self.rev += 1

    def _emit(self, name: bytes, data: bytes) -> None:
        if not self._subscribers:
            return
        message = self.event(name, data)
        for queue in list(self._subscribers):
            try:
                queue.put_nowait(message)
            except asyncio.QueueFull:
                log.info("Dropped a client that stopped reading events")
                self._drop(queue)

    def _drop(self, queue: asyncio.Queue[bytes | None]) -> None:
        # Empty it so the None (close) gets in and the memory goes now.
        while not queue.empty():
            queue.get_nowait()
        queue.put_nowait(None)
        self._subscribers.discard(queue)
        self._update_watched()

    def _update_watched(self) -> None:
        if self._subscribers:
            if self._grace_timer is not None:
                self._grace_timer.cancel()
                self._grace_timer = None
            if not self._watched:
                self._watched = True
                if self._backend is not None:
                    self._backend.set_watched(True)
        elif self._watched and self._grace_timer is None:
            self._grace_timer = asyncio.get_running_loop().call_later(
                self._watch_grace, self._unwatch
            )

    def _unwatch(self) -> None:
        self._grace_timer = None
        if not self._subscribers and self._watched:
            self._watched = False
            if self._backend is not None:
                self._backend.set_watched(False)

    async def _forget_all(self) -> None:
        for robot in self._robots.values():
            if robot.offline_timer is not None:
                robot.offline_timer.cancel()
        ids = list(self._robots)
        self._robots.clear()
        self._history.clear()
        await asyncio.to_thread(self._delete_maps, ids)
        self._bump()
        self._emit(b"state", self.state())
        self._mark_dirty()

    def _delete_maps(self, ids: list[str]) -> None:
        for robot_id in ids:
            (self._maps_dir / f"{robot_id}.bin.gz").unlink(missing_ok=True)

    def _offline_due(self, robot: Robot) -> None:
        robot.offline_timer = None
        if (
            robot.online
            and robot.failures > 0
            and robot.last_ok is not None
            and self._clock() - robot.last_ok >= OFFLINE_AFTER_SECONDS
        ):
            self._go_offline(robot)

    def _go_offline(self, robot: Robot) -> None:
        robot.online = False
        if robot.offline_timer is not None:
            robot.offline_timer.cancel()
            robot.offline_timer = None
        if robot.pos is not None and robot.pos["live"]:
            self._pos_stale(robot)
        text = "Stopped answering"
        room = robot.room_name_or_none(None if robot.pos is None else robot.pos["room"])
        if room is not None:
            text += f" in {room}"
        if robot.obs is not None:
            text += f", battery at {robot.obs.battery}%"
        robot.offline_logged = True
        self.add_history(robot.info.id, "offline", text)
        self._refresh(robot)

    def _pos_stale(self, robot: Robot) -> None:
        assert robot.pos is not None
        robot.pos = {**robot.pos, "live": False, "ts": int(robot.pos_read)}

    def _transitions(self, robot: Robot, prev: Observation | None, new: Observation) -> None:
        robot_id = robot.info.id
        if new.error is not None and (prev is None or prev.error is None or prev.error.code != new.error.code):
            room = robot.room_name_or_none(None if robot.pos is None else robot.pos["room"])
            self.add_history(
                robot_id, "error", new.error.label if room is None else f"{new.error.label} in {room}"
            )
        elif prev is not None and prev.error is not None and new.error is None:
            self.add_history(robot_id, "cleared", "Error cleared")
        if not robot.job and new.activity == "cleaning":
            robot.job = True
            self.add_history(robot_id, "started", "Started cleaning")
        elif robot.job and new.activity in ("returning", "docked", "idle"):
            robot.job = False
            # A robot back at the dock may already report no job: use the last figures.
            last = prev if prev is not None else new
            area = new.clean_area_m2 if new.clean_area_m2 is not None else last.clean_area_m2
            secs = new.clean_seconds if new.clean_seconds is not None else last.clean_seconds
            if area is not None and secs is not None:
                text = f"Finished: {area:.1f} m² in {max(1, round(secs / 60))} min"
            else:
                text = "Finished cleaning"
            self.add_history(robot_id, "finished", text)
        if prev is not None and new.activity == "docked" and prev.activity != "docked":
            self.add_history(robot_id, "docked", "Back on the dock")

    async def _parse_maps(self, robot: Robot, raw: bytes | None) -> None:
        try:
            while raw is not None:
                known = None if robot.geom is None else robot.geom.digest
                try:
                    frame, gz = await asyncio.to_thread(_parse_and_pack, raw, known)
                except ValueError as exc:
                    # A robot without a finished map sends one with no image;
                    # keep showing the last good map.
                    log.warning("Could not read a map from %s: %s", robot.info.name, exc)
                else:
                    raw = None  # let the blob go while the map is stored
                    if self._robots.get(robot.info.id) is robot:
                        await self._apply_map(robot, frame, gz)
                raw, robot.pending_map = robot.pending_map, None
        finally:
            robot.parsing = False
            robot.pending_map = None

    async def _apply_map(self, robot: Robot, frame: MapFrame, gz: bytes | None) -> None:
        now = self._clock()
        robot.geom = frame
        robot.map_json = _map_json(frame)
        if frame.robot is not None:
            obs = robot.obs
            live = robot.online and (obs is None or obs.activity != "error")
            x, y = round(frame.robot[0], 1), round(frame.robot[1], 1)
            robot.pos_read = now
            old = robot.pos
            if old is None or (old["x"], old["y"], old["angle"], old["room"], old["live"]) != (
                x,
                y,
                frame.robot_angle,
                frame.robot_room,
                live,
            ):
                robot.pos = {
                    "x": x,
                    "y": y,
                    "angle": frame.robot_angle,
                    "room": frame.robot_room,
                    "ts": int(now),
                    "live": live,
                }
        path_event = None
        if frame.path != robot.path:
            old, new = robot.path, frame.path
            if len(new) > len(old) and new[: len(old)] == old:
                start, points = len(old), new[len(old) :]
            else:  # a new job, or a path the robot redrew
                start, points = 0, new
            robot.path = new
            robot.path_rev += 1
            robot.path_body = None
            path_event = dumps(
                {"id": robot.info.id, "rev": robot.path_rev, "start": start, "points": points}
            )
        if gz is not None:
            robot.map_gz = gz
            self._maps_dir.mkdir(mode=0o700, exist_ok=True)
            await asyncio.to_thread(
                atomic_write, self._maps_dir / f"{robot.info.id}.bin.gz", gz
            )
            if self._robots.get(robot.info.id) is not robot:
                return
            self._refresh(robot)
            if path_event is not None:
                self._emit(b"path", path_event)
            # robots.json must name the map that is on disk now.
            await self.flush()
            return
        self._refresh(robot)
        if path_event is not None:
            self._emit(b"path", path_event)
            self._mark_dirty()

    def _refresh(self, robot: Robot) -> None:
        """Recompute what follows from the robot's state and publish it if
        its JSON changed."""
        robot.attention = self._attention(robot)
        full = _robot_json(robot)
        prev = robot.full
        if prev is None:
            patch = full  # not announced yet: the whole robot
        else:
            patch = {k: v for k, v in full.items() if k != "seen" and v != prev[k]}
            if not patch:
                if full["seen"] != prev["seen"]:
                    # Current in /api/state, but no event, revision or write.
                    robot.full = full
                    robot.fragment = dumps(full)
                    self._state_rev = -1
                return
            patch = {"id": full["id"], **patch}
            if full["seen"] != robot.sent_seen:
                patch["seen"] = full["seen"]
        robot.full = full
        robot.fragment = dumps(full)
        robot.sent_seen = full["seen"]
        self._bump()
        self._emit(b"robot", dumps(patch))
        self._mark_dirty()

    def _hhmm(self, ts: float) -> str:
        # Until a time zone is set, the machine's own.
        return datetime.fromtimestamp(ts, self._zone).strftime("%H:%M")

    def _attention(self, robot: Robot) -> dict | None:
        obs = robot.obs
        if obs is None:
            return None
        pos = robot.pos
        room_id = None if pos is None else pos["room"]
        room = robot.room_name_or_none(room_id)
        at = None if pos is None else self._hhmm(pos["ts"])  # when it was last placed
        battery = obs.battery
        if not robot.online:
            off_dock = obs.activity != "docked" and not obs.charging
            if not off_dock and battery > FLAT_BATTERY:
                return None
            kind = "offline"
            title = "Battery flat" if battery <= FLAT_BATTERY else "Not answering"
            assert robot.seen is not None  # an observation implies a read
            summary = f"Offline since {self._hhmm(robot.seen)}. Battery was at {battery}%"
            if room is not None:
                summary += f", last seen in {room}."
            elif at is not None:
                summary += f", last seen at {at}."
            else:
                summary += "."
            started = robot.seen
        elif obs.error is not None or obs.activity == "error":
            kind = "error"
            title = obs.error.label if obs.error is not None else obs.state_label
            summary = f"Stuck: {_lower_first(title)}. "
            if room is not None:
                summary += f"Last seen in {room} at {at}, battery {battery}%."
            elif at is not None:
                summary += f"Last seen at {at}, battery {battery}%."
            else:
                summary += f"Battery {battery}%."
            started = int(self._clock())
        elif battery <= LOW_BATTERY and obs.activity != "returning" and not obs.charging:
            # On the dock too: a dock that has lost power ("charger
            # disconnected") lets the battery run down where nobody looks.
            kind = "low_battery"
            title = "Battery low"
            if obs.activity == "docked":
                summary = f"On the dock but not charging. Battery at {battery}%."
            else:
                summary = f"Battery at {battery}% and not on the dock."
                if room is not None:
                    summary += f" Last seen in {room} at {at}."
                elif at is not None:
                    summary += f" Last seen at {at}."
            started = int(self._clock())
        else:
            return None
        old = robot.attention
        since = old["since"] if old and old["kind"] == kind and old["title"] == title else started
        return {
            "kind": kind,
            "title": title,
            "summary": summary,
            "since": since,
            "room": room_id,
            "battery": battery,
        }


def describe(robot: Robot, command: Command) -> str:
    """A command in a few words, for the history."""
    match command.name:
        case "start":
            return "Clean the whole home"
        case "pause":
            return "Pause"
        case "resume":
            return "Resume"
        case "stop":
            return "Stop"
        case "dock":
            return "Back to the dock"
        case "locate":
            return "Locate"
        case "clean_rooms":
            names = [robot.room_name_or_none(r) or f"room {r}" for r in command.rooms]
            passes = "" if command.repeat == 1 else f", {command.repeat} passes"
            return f"Clean {join_names(names)}{passes}"
        case "set_fan":
            return f"Suction to {command.value}"
        case "set_water":
            return f"Mop water to {command.value}"
    raise ValueError(f"unknown command {command.name!r}")


def _parse_and_pack(raw: bytes, known_digest: str | None) -> tuple[MapFrame, bytes | None]:
    """Off the loop: parse, gzip the index when the floor plan is new, and
    drop the inflated index before it reaches the Hub."""
    frame = rrmap.parse(raw)
    gz = None if frame.digest == known_digest else gzip.compress(frame.index, 9, mtime=0)
    return replace(frame, index=b""), gz


def _map_json(frame: MapFrame) -> dict:
    return {
        "rev": frame.digest,
        "w": frame.width,
        "h": frame.height,
        "mm_per_px": MM_PER_PX,
        "rooms": {
            str(room_id): {
                "cx": round(g.cx, 1),
                "cy": round(g.cy, 1),
                "x0": g.x0,
                "y0": g.y0,
                "x1": g.x1,
                "y1": g.y1,
            }
            for room_id, g in sorted(frame.rooms.items())
        },
        "charger": None
        if frame.charger is None
        else {"x": round(frame.charger[0], 1), "y": round(frame.charger[1], 1)},
        "no_go": [list(area) for area in frame.no_go],
        "walls": [list(wall) for wall in frame.walls],
    }


def _robot_json(robot: Robot) -> dict:
    info, obs, online = robot.info, robot.obs, robot.online
    if obs is None:
        activity, label = "offline", "Not heard from yet"
    else:
        activity, label = (obs.activity if online else "offline"), obs.state_label
    clean = None
    if obs is not None and (obs.clean_area_m2 is not None or obs.clean_seconds is not None):
        clean = {"area_m2": obs.clean_area_m2, "seconds": obs.clean_seconds}
    return {
        "id": info.id,
        "name": info.name,
        "model": info.model,
        "model_name": info.model_name,
        "activity": activity,
        "state_label": label,
        "online": online,
        "link": obs.link if online and obs is not None else "none",
        "battery": None if obs is None else obs.battery,
        "charging": None if obs is None else obs.charging,
        "error": None
        if obs is None or obs.error is None
        else {"code": obs.error.code, "label": obs.error.label},
        "attention": robot.attention,
        "fan": None if obs is None else obs.fan,
        "fan_options": [] if obs is None else list(obs.fan_options),
        "water": None if obs is None else obs.water,
        "water_options": [] if obs is None else list(obs.water_options),
        "clean": clean,
        "rooms": [{"id": room.id, "name": room.name} for room in robot.rooms],
        "map": robot.map_json,
        "pos": robot.pos,
        "seen": robot.seen,
    }


# --------------------------------------------------------------------------
# robots.json
# --------------------------------------------------------------------------


def _obs_to_json(obs: Observation) -> dict:
    return {
        "activity": obs.activity,
        "state_label": obs.state_label,
        "battery": obs.battery,
        "charging": obs.charging,
        "error": None if obs.error is None else [obs.error.code, obs.error.label],
        "link": obs.link,
        "fan": obs.fan,
        "fan_options": list(obs.fan_options),
        "water": obs.water,
        "water_options": list(obs.water_options),
        "clean_area_m2": obs.clean_area_m2,
        "clean_seconds": obs.clean_seconds,
    }


def _obs_from_json(d: dict) -> Observation:
    error = d["error"]
    return Observation(
        activity=d["activity"],
        state_label=d["state_label"],
        battery=d["battery"],
        charging=d["charging"],
        error=None if error is None else ErrorInfo(error[0], error[1]),
        link=d["link"],
        fan=d["fan"],
        fan_options=tuple(d["fan_options"]),
        water=d["water"],
        water_options=tuple(d["water_options"]),
        clean_area_m2=d["clean_area_m2"],
        clean_seconds=d["clean_seconds"],
    )


def _geom_to_json(frame: MapFrame) -> dict:
    return {
        "digest": frame.digest,
        "width": frame.width,
        "height": frame.height,
        "rooms": {
            str(k): [g.cx, g.cy, g.x0, g.y0, g.x1, g.y1, g.area_px] for k, g in frame.rooms.items()
        },
        "charger": None if frame.charger is None else list(frame.charger),
        "no_go": [list(a) for a in frame.no_go],
        "walls": [list(w) for w in frame.walls],
    }


def _geom_from_json(d: dict) -> MapFrame:
    charger = d["charger"]
    return MapFrame(
        width=d["width"],
        height=d["height"],
        index=b"",
        digest=d["digest"],
        rooms={int(k): RoomGeom(*v) for k, v in d["rooms"].items()},
        charger=None if charger is None else (charger[0], charger[1]),
        no_go=tuple(tuple(a) for a in d["no_go"]),
        walls=tuple((w[0], w[1], w[2], w[3]) for w in d["walls"]),
    )


def _robot_to_json(robot: Robot) -> dict:
    info = robot.info
    pos = robot.pos
    if pos is not None and pos["live"]:
        # What it will be on the next start: stale, read when last placed.
        pos = {**pos, "live": False, "ts": int(robot.pos_read)}
    return {
        "info": {"id": info.id, "name": info.name, "model": info.model, "model_name": info.model_name},
        "obs": None if robot.obs is None else _obs_to_json(robot.obs),
        "seen": robot.seen,
        "rooms": [[room.id, room.name] for room in robot.rooms],
        "map": None if robot.geom is None else _geom_to_json(robot.geom),
        "pos": pos,
        "path": list(robot.path),
        "path_rev": robot.path_rev,
        "attention": robot.attention,
        "job": robot.job,
        "offline_logged": robot.offline_logged,
    }


def _robot_from_json(rec: dict) -> Robot:
    info = rec["info"]
    robot = Robot(RobotInfo(check_robot_id(info["id"]), info["name"], info["model"], info["model_name"]))
    robot.obs = None if rec["obs"] is None else _obs_from_json(rec["obs"])
    robot.seen = rec["seen"]
    robot.rooms = tuple(Room(room_id, name) for room_id, name in rec["rooms"])
    if rec["map"] is not None:
        robot.geom = _geom_from_json(rec["map"])
        robot.map_json = _map_json(robot.geom)
    robot.pos = rec["pos"]
    if robot.pos is not None:
        robot.pos_read = robot.pos["ts"]
    robot.path = tuple(rec["path"])
    robot.path_rev = rec["path_rev"]
    robot.attention = rec["attention"]
    robot.job = rec["job"]
    robot.offline_logged = rec["offline_logged"]
    return robot
