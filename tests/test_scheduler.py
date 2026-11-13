from __future__ import annotations

import asyncio
import json
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
from test_hub import DOCKED, INFO, ROOMS, Clock, FakeBackend, app_info

from roborock_at_home.hub import Hub
from roborock_at_home.model import RobotError
from roborock_at_home.scheduler import (
    Scheduler,
    describe_days,
    next_occurrence,
    wall_time_to_ts,
)

MEL = ZoneInfo("Australia/Melbourne")
NY = ZoneInfo("America/New_York")


def local(ts: int, zone: ZoneInfo) -> str:
    return datetime.fromtimestamp(ts, zone).strftime("%Y-%m-%d %H:%M %z")


def ts_of(text: str, zone: ZoneInfo) -> float:
    return datetime.strptime(text, "%Y-%m-%d %H:%M").replace(tzinfo=zone).timestamp()


def test_plain_weekdays() -> None:
    friday_evening = ts_of("2026-11-13 20:00", MEL)
    nxt = next_occurrence((0, 1, 2, 3, 4), "09:30", MEL, friday_evening)
    assert local(nxt, MEL) == "2026-11-16 09:30 +1100"
    # Strictly after: at exactly the time, the next one.
    assert local(next_occurrence((0, 1, 2, 3, 4), "09:30", MEL, nxt), MEL) == "2026-11-17 09:30 +1100"


def test_melbourne_spring_forward() -> None:
    # 2026-10-04: 02:00 AEST becomes 03:00 AEDT; 02:30 does not exist.
    assert local(wall_time_to_ts(date(2026, 10, 4), 2, 30, MEL), MEL) == "2026-10-04 03:00 +1100"
    before = ts_of("2026-10-03 12:00", MEL)
    assert local(next_occurrence((6,), "02:30", MEL, before), MEL) == "2026-10-04 03:00 +1100"
    # The week after is normal again.
    nxt = next_occurrence((6,), "02:30", MEL, ts_of("2026-10-04 12:00", MEL))
    assert local(nxt, MEL) == "2026-10-11 02:30 +1100"


def test_melbourne_fall_back_runs_once() -> None:
    # 2027-04-04: 03:00 AEDT becomes 02:00 AEST; 02:30 happens twice.
    first = next_occurrence((6,), "02:30", MEL, ts_of("2027-04-03 12:00", MEL))
    assert local(first, MEL) == "2027-04-04 02:30 +1100"
    again = next_occurrence((6,), "02:30", MEL, first)
    assert local(again, MEL) == "2027-04-11 02:30 +1000"  # not the second 02:30 an hour later


def test_new_york_spring_forward_and_fall_back() -> None:
    # 2026-03-08: 02:00 EST becomes 03:00 EDT.
    nxt = next_occurrence((6,), "02:15", NY, ts_of("2026-03-07 12:00", NY))
    assert local(nxt, NY) == "2026-03-08 03:00 -0400"
    # 2026-11-01: 02:00 EDT becomes 01:00 EST; 01:30 happens twice.
    first = next_occurrence((6,), "01:30", NY, ts_of("2026-10-31 12:00", NY))
    assert local(first, NY) == "2026-11-01 01:30 -0400"
    assert local(next_occurrence((6,), "01:30", NY, first), NY) == "2026-11-08 01:30 -0500"
    # A daily 09:00 keeps its wall time across the change.
    a = next_occurrence(tuple(range(7)), "09:00", NY, ts_of("2026-10-31 10:00", NY))
    b = next_occurrence(tuple(range(7)), "09:00", NY, a)
    assert local(a, NY) == "2026-11-01 09:00 -0500" and b - a == 86400
    assert local(b, NY) == "2026-11-02 09:00 -0500"


def test_describe_days() -> None:
    assert describe_days((0, 1, 2, 3, 4)) == "weekdays"
    assert describe_days((5, 6)) == "weekends"
    assert describe_days(tuple(range(7))) == "every day"
    assert describe_days((2,)) == "Wednesdays"
    assert describe_days((0, 2, 4)) == "Mon, Wed and Fri"


async def make(tmp_path: Path, tz: str | None = "Australia/Melbourne", t: str = "2026-11-13 09:00"):
    clock = Clock(ts_of(t, MEL))
    hub = Hub(tmp_path, app_info=app_info, clock=clock)
    backend = FakeBackend()
    await hub.set_backend(backend)
    hub.robot_found(INFO)
    hub.robot_rooms("r1", ROOMS)
    hub.robot_status("r1", DOCKED)
    scheduler = Scheduler(hub, tmp_path / "schedules.json", clock=clock)
    scheduler.load(tz)
    return scheduler, hub, backend, clock


BODY = {
    "robot": "r1",
    "enabled": True,
    "days": [0, 1, 2, 3, 4],
    "time": "09:30",
    "action": {"type": "clean_rooms", "rooms": [16, 17], "repeat": 1, "fan": None, "water": None},
}


async def test_create_validates(tmp_path: Path) -> None:
    scheduler, _, _, _ = await make(tmp_path)

    async def message(**changes) -> str:
        body = {**BODY, **changes}
        with pytest.raises(RobotError) as e:
            await scheduler.create(body)
        assert e.value.code == "bad_request"
        return e.value.message

    assert "robot" in await message(robot="nope")
    assert "days" in await message(days=[])
    assert "days" in await message(days=[1, 1])
    assert "days" in await message(days=[7])
    assert "time" in await message(time="9:30")
    assert "time" in await message(time="24:00")
    assert "Room 99" in await message(action={**BODY["action"], "rooms": [99]})
    assert "at least one room" in await message(action={**BODY["action"], "rooms": []})
    assert "repeat" in await message(action={**BODY["action"], "repeat": 4})
    assert "suction" in await message(action={**BODY["action"], "fan": "ludicrous"})
    assert "Unknown field" in await message(colour="red")
    assert "whole-home" in await message(action={"type": "clean_all", "rooms": [16]})
    assert scheduler.as_json() == []


async def test_needs_a_time_zone(tmp_path: Path) -> None:
    scheduler, _, _, _ = await make(tmp_path, tz=None)
    with pytest.raises(RobotError) as e:
        await scheduler.create(BODY)
    assert e.value.message == "Set the time zone first."


async def test_create_names_and_persists(tmp_path: Path) -> None:
    scheduler, hub, _, _ = await make(tmp_path)
    s = await scheduler.create(BODY)
    assert s["id"].startswith("s_") and s["name"] == "Kitchen and Hall, weekdays 09:30"
    assert local(s["next_run"], MEL) == "2026-11-13 09:30 +1100"
    assert s["last_run"] is None
    whole = await scheduler.create({**BODY, "name": "  ", "days": [5, 6], "action": {"type": "clean_all"}})
    assert whole["name"] == "Whole home, weekends 09:30"
    assert whole["action"] == {"type": "clean_all", "rooms": [], "repeat": 1, "fan": None, "water": None}
    assert json.loads(hub.schedules_body) == scheduler.as_json()

    off = await scheduler.update(whole["id"], {**BODY, "enabled": False, "name": "Off one"})
    assert off["next_run"] is None and off["name"] == "Off one"

    stored = json.loads((tmp_path / "schedules.json").read_text())
    assert len(stored) == 2 and "next_run" not in stored[0]
    again, _, _, _ = await make(tmp_path)
    assert again.as_json() == scheduler.as_json()

    await scheduler.delete(s["id"])
    with pytest.raises(RobotError) as e:
        await scheduler.delete(s["id"])
    assert e.value.code == "not_found"


async def test_run_order_and_record(tmp_path: Path) -> None:
    scheduler, hub, backend, clock = await make(tmp_path)
    s = await scheduler.create({**BODY, "action": {**BODY["action"], "fan": "turbo", "water": "mild", "repeat": 2}})
    await scheduler.run_now(s["id"])
    assert [(c.name, c.value, c.rooms, c.repeat) for _, c in backend.commands] == [
        ("set_fan", "turbo", (), 1),
        ("set_water", "mild", (), 1),
        ("clean_rooms", None, (16, 17), 2),
    ]
    got = scheduler.get(s["id"]).to_json()
    assert got["last_run"] == {"ts": int(clock.t), "ok": True, "message": "Started"}
    assert hub.history(1)[0]["kind"] == "schedule"

    backend.fail_with = RobotError("robot_unreachable", "Downstairs did not answer.")
    with pytest.raises(RobotError):
        await scheduler.run_now(s["id"])
    got = scheduler.get(s["id"]).to_json()
    assert got["last_run"]["ok"] is False and got["last_run"]["message"] == "Downstairs did not answer."
    assert hub.history(1)[0]["text"].endswith("failed: Downstairs did not answer.")


async def test_loop_runs_on_time_and_skips_late(tmp_path: Path) -> None:
    scheduler, _, backend, clock = await make(tmp_path)
    s = await scheduler.create({**BODY, "action": {"type": "clean_all"}})
    due = s["next_run"]
    scheduler.start()
    try:
        await asyncio.sleep(0.01)
        assert backend.commands == []
        clock.t = due + 5  # a few seconds late is fine
        scheduler._wake.set()
        await asyncio.sleep(0.05)
        assert [c.name for _, c in backend.commands] == ["start"]
        nxt = scheduler.get(s["id"]).next_run
        assert local(nxt, MEL) == "2026-11-16 09:30 +1100"

        clock.t = nxt + 121  # the machine slept through it
        scheduler._wake.set()
        await asyncio.sleep(0.05)
        assert len(backend.commands) == 1
        assert local(scheduler.get(s["id"]).next_run, MEL) == "2026-11-17 09:30 +1100"
    finally:
        await scheduler.stop()


async def test_time_zone_change_moves_next_run(tmp_path: Path) -> None:
    scheduler, _, _, _ = await make(tmp_path)
    s = await scheduler.create(BODY)
    await scheduler.set_tz("Europe/London")
    moved = scheduler.get(s["id"]).next_run
    assert moved != s["next_run"]
    assert datetime.fromtimestamp(moved, ZoneInfo("Europe/London")).strftime("%H:%M") == "09:30"


async def test_remove_all(tmp_path: Path) -> None:
    scheduler, hub, _, _ = await make(tmp_path)
    await scheduler.create(BODY)
    await scheduler.remove_all()
    assert scheduler.as_json() == [] and json.loads(hub.schedules_body) == []
