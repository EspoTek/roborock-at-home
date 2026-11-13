from __future__ import annotations

import asyncio
import gzip
import json
from dataclasses import replace
from pathlib import Path

import pytest

from roborock_at_home import hub as hub_module
from roborock_at_home.hub import Hub
from roborock_at_home.model import (
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

NOW = 1_800_000_000.0  # 19:00 in Melbourne

INFO = RobotInfo("r1", "Downstairs", "roborock.vacuum.a65", "S7 Max Ultra")
ROOMS = [Room(16, "Kitchen"), Room(17, "Hall")]

DOCKED = Observation(
    activity="docked",
    state_label="Charging",
    battery=90,
    charging=True,
    error=None,
    link="local",
    fan="balanced",
    fan_options=("quiet", "balanced", "turbo", "max"),
    water="standard",
    water_options=("off", "mild", "standard", "intense"),
)
CLEANING = replace(DOCKED, activity="cleaning", state_label="Cleaning", charging=False, clean_area_m2=3.0, clean_seconds=200)
STUCK = replace(CLEANING, activity="error", state_label="Error", battery=41, error=ErrorInfo(5, "Main brush jammed"))


class Clock:
    def __init__(self, t: float = NOW) -> None:
        self.t = t

    def __call__(self) -> float:
        return self.t


class FakeBackend(Backend):
    def __init__(self) -> None:
        self.sink = None
        self.commands: list[tuple[str, Command]] = []
        self.watched: list[bool] = []
        self.stopped = False
        self.fail_with: RobotError | None = None

    async def start(self, sink) -> None:
        self.sink = sink

    async def stop(self) -> None:
        self.stopped = True

    async def command(self, robot_id: str, command: Command) -> None:
        if self.fail_with is not None:
            raise self.fail_with
        self.commands.append((robot_id, command))

    def set_watched(self, watched: bool) -> None:
        self.watched.append(watched)


def frame(robot: tuple[float, float] | None = (3.5, 2.5), room: int | None = 16, digest: str = "aaaa1111",
          path: tuple[float, ...] = ()) -> MapFrame:
    """A 10 x 8 map: walls round the edge, Kitchen on the left, Hall on the right."""
    rows = []
    for y in range(8):
        if y in (0, 7):
            rows.append(bytes([1] * 10))
        else:
            rows.append(bytes([1] + [32 + 16] * 4 + [32 + 17] * 4 + [1]))
    return MapFrame(
        width=10,
        height=8,
        index=b"".join(rows),
        digest=digest,
        rooms={16: RoomGeom(2.5, 3.5, 1, 1, 4, 6, 24), 17: RoomGeom(6.5, 3.5, 5, 1, 8, 6, 24)},
        robot=robot,
        robot_angle=90,
        robot_room=room,
        charger=(1.5, 1.5),
        path=path,
        no_go=((1.0, 1.0, 2.0, 1.0, 2.0, 2.0, 1.0, 2.0),),
        walls=((5.0, 1.0, 5.0, 6.0),),
    )


FRAMES: dict[bytes, MapFrame] = {}


def fake_parse(raw: bytes) -> MapFrame:
    if raw not in FRAMES:
        raise ValueError("not an rr map")
    return FRAMES[raw]


@pytest.fixture(autouse=True)
def _fake_parser(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(hub_module.rrmap, "parse", fake_parse)


def app_info() -> dict:
    return {"version": "test"}


async def settle(hub: Hub) -> None:
    while hub._tasks:
        await asyncio.gather(*list(hub._tasks))


async def make_hub(tmp_path: Path, clock: Clock | None = None) -> tuple[Hub, FakeBackend, Clock]:
    clock = clock or Clock()
    hub = Hub(tmp_path, app_info=app_info, clock=clock, watch_grace=0.05)
    hub.set_tz("Australia/Melbourne")
    hub.app_changed()
    backend = FakeBackend()
    await hub.set_backend(backend)
    hub.robot_found(INFO)
    hub.robot_rooms("r1", ROOMS)
    return hub, backend, clock


def robot_json(hub: Hub, robot_id: str = "r1") -> dict:
    return json.loads(hub.robot(robot_id).fragment)


async def send_map(hub: Hub, f: MapFrame, key: bytes = b"m") -> None:
    FRAMES[key] = f
    hub.robot_map("r1", key)
    await settle(hub)


async def test_status_and_unchanged_poll(tmp_path: Path) -> None:
    hub, _, clock = await make_hub(tmp_path)
    hub.robot_status("r1", DOCKED)
    r = robot_json(hub)
    assert r["online"] and r["activity"] == "docked" and r["link"] == "local"
    assert r["battery"] == 90 and r["seen"] == int(NOW) and r["attention"] is None
    assert r["rooms"] == [{"id": 16, "name": "Kitchen"}, {"id": 17, "name": "Hall"}]
    assert r["map"] is None and r["pos"] is None and r["clean"] is None
    rev = hub.rev
    queue = hub.subscribe()
    hub._dirty = False
    clock.t += 5
    hub.robot_status("r1", DOCKED)  # nothing changed but the time it answered
    assert hub.rev == rev and queue.empty() and not hub._dirty
    state = json.loads(hub.state())
    assert state["rev"] == rev and state["app"] == {"version": "test"} and state["schedules"] == []
    assert state["robots"][0]["seen"] == int(NOW) + 5  # current, without causing an event
    assert gzip.decompress(hub.state_gz()) == hub.state()


async def test_robot_before_any_status(tmp_path: Path) -> None:
    hub, _, _ = await make_hub(tmp_path)
    r = robot_json(hub)
    assert r["online"] is False and r["activity"] == "offline" and r["link"] == "none"
    assert r["battery"] is None and r["charging"] is None and r["fan_options"] == []


async def test_offline_after_three_failures_keeps_last_values(tmp_path: Path) -> None:
    hub, _, clock = await make_hub(tmp_path)
    hub.robot_status("r1", replace(CLEANING, battery=4))
    await send_map(hub, frame())
    assert robot_json(hub)["pos"]["live"] is True
    clock.t += 10
    await send_map(hub, frame(), b"m2")  # same position, read again
    clock.t += 5
    hub.robot_unreachable("r1", "timeout")
    hub.robot_unreachable("r1", "timeout")
    assert robot_json(hub)["online"] is True
    hub.robot_unreachable("r1", "timeout")
    r = robot_json(hub)
    assert r["online"] is False and r["activity"] == "offline" and r["link"] == "none"
    assert r["battery"] == 4 and r["state_label"] == "Cleaning" and r["fan"] == "balanced"
    assert r["seen"] == int(NOW)
    assert r["pos"] == {"x": 3.5, "y": 2.5, "angle": 90, "room": 16, "ts": int(NOW) + 10, "live": False}
    att = r["attention"]
    assert att["kind"] == "offline" and att["title"] == "Battery flat"
    assert att["summary"] == "Offline since 19:00. Battery was at 4%, last seen in Kitchen."
    assert att["since"] == int(NOW) and att["room"] == 16 and att["battery"] == 4
    kinds = [h["kind"] for h in hub.history(10)]
    assert kinds[0] == "offline" and hub.history(1)[0]["text"] == "Stopped answering in Kitchen, battery at 4%"
    # More failures change nothing.
    rev = hub.rev
    clock.t += 60
    hub.robot_unreachable("r1", "timeout")
    assert hub.rev == rev
    # Back again.
    hub.robot_status("r1", replace(CLEANING, battery=4))
    assert robot_json(hub)["online"] is True
    assert hub.history(1)[0]["kind"] == "online"


async def test_offline_after_90_seconds(tmp_path: Path) -> None:
    hub, _, clock = await make_hub(tmp_path)
    hub.robot_status("r1", CLEANING)
    clock.t += 91
    hub.robot_unreachable("r1", "timeout")
    assert robot_json(hub)["online"] is False
    att = robot_json(hub)["attention"]
    assert att["kind"] == "offline" and att["title"] == "Not answering"
    assert att["summary"] == "Offline since 19:00. Battery was at 90%."


async def test_offline_timer_fires(tmp_path: Path) -> None:
    hub, _, clock = await make_hub(tmp_path)
    hub.robot_status("r1", CLEANING)
    clock.t += 89.95
    hub.robot_unreachable("r1", "timeout")  # one failure, 89.95 s: not yet
    assert robot_json(hub)["online"] is True
    clock.t += 1
    await asyncio.sleep(0.1)  # the timer was set for 0.05 s
    assert robot_json(hub)["online"] is False


async def test_offline_on_the_dock_is_not_attention(tmp_path: Path) -> None:
    hub, _, _ = await make_hub(tmp_path)
    hub.robot_status("r1", DOCKED)
    for _ in range(3):
        hub.robot_unreachable("r1", "timeout")
    r = robot_json(hub)
    assert r["online"] is False and r["attention"] is None


async def test_error_attention_and_since_is_stable(tmp_path: Path) -> None:
    hub, _, clock = await make_hub(tmp_path)
    hub.robot_status("r1", CLEANING)
    await send_map(hub, frame())
    clock.t += 120
    hub.robot_status("r1", STUCK)
    r = robot_json(hub)
    att = r["attention"]
    assert att["kind"] == "error" and att["title"] == "Main brush jammed"
    assert att["summary"] == "Stuck: main brush jammed. Last seen in Kitchen at 19:00, battery 41%."
    assert att["since"] == int(NOW) + 120
    assert r["pos"]["live"] is False  # an error makes the position stale
    assert hub.history(1)[0] == {"ts": int(NOW) + 120, "robot": "r1", "kind": "error", "text": "Main brush jammed in Kitchen"}
    clock.t += 300
    hub.robot_status("r1", replace(STUCK, battery=40))
    att = robot_json(hub)["attention"]
    assert att["since"] == int(NOW) + 120 and att["battery"] == 40
    hub.robot_status("r1", CLEANING)
    assert robot_json(hub)["attention"] is None
    assert hub.history(1)[0]["kind"] == "cleared"


async def test_error_without_position(tmp_path: Path) -> None:
    hub, _, _ = await make_hub(tmp_path)
    hub.robot_status("r1", STUCK)
    assert robot_json(hub)["attention"]["summary"] == "Stuck: main brush jammed. Battery 41%."


async def test_low_battery(tmp_path: Path) -> None:
    hub, _, _ = await make_hub(tmp_path)
    hub.robot_status("r1", replace(CLEANING, battery=12))
    await send_map(hub, frame(robot=(6.5, 3.0), room=None))
    att = robot_json(hub)["attention"]
    assert att["kind"] == "low_battery" and att["title"] == "Battery low"
    assert att["summary"] == "Battery at 12% and not on the dock. Last seen at 19:00."
    hub.robot_status("r1", replace(CLEANING, battery=12, activity="returning"))
    assert robot_json(hub)["attention"] is None
    # On a dock that is not charging it (the charger lost power).
    hub.robot_status("r1", replace(DOCKED, battery=14, charging=False, state_label="Charger disconnected"))
    att = robot_json(hub)["attention"]
    assert att["kind"] == "low_battery" and att["title"] == "Battery low"
    assert att["summary"] == "On the dock but not charging. Battery at 14%."
    hub.robot_status("r1", replace(DOCKED, battery=14))  # charging again
    assert robot_json(hub)["attention"] is None


async def test_back_online_on_the_dock_is_placed_on_the_dock(tmp_path: Path) -> None:
    hub, _, clock = await make_hub(tmp_path)
    hub.robot_status("r1", CLEANING)
    await send_map(hub, frame(robot=(6.5, 3.5), room=17))
    for _ in range(3):
        hub.robot_unreachable("r1", "timeout")
    assert robot_json(hub)["pos"]["live"] is False
    clock.t += 600
    hub.robot_status("r1", DOCKED)
    assert robot_json(hub)["pos"] == {
        "x": 1.5, "y": 1.5, "angle": 90, "room": 16, "ts": int(NOW) + 600, "live": True,
    }


async def test_back_online_off_the_dock_keeps_the_stale_position(tmp_path: Path) -> None:
    hub, _, _ = await make_hub(tmp_path)
    hub.robot_status("r1", CLEANING)
    await send_map(hub, frame(robot=(6.5, 3.5), room=17))
    for _ in range(3):
        hub.robot_unreachable("r1", "timeout")
    hub.robot_status("r1", replace(CLEANING, activity="idle"))
    pos = robot_json(hub)["pos"]
    assert pos["live"] is False and pos["x"] == 6.5


async def test_history_of_a_job(tmp_path: Path) -> None:
    hub, _, _ = await make_hub(tmp_path)
    hub.robot_status("r1", DOCKED)
    hub.robot_status("r1", CLEANING)
    hub.robot_status("r1", replace(CLEANING, clean_area_m2=18.4, clean_seconds=1260))
    hub.robot_status("r1", replace(CLEANING, activity="returning", clean_area_m2=None, clean_seconds=None))
    hub.robot_status("r1", DOCKED)
    texts = [(h["kind"], h["text"]) for h in reversed(hub.history(10))]
    assert texts == [
        ("started", "Started cleaning"),
        ("finished", "Finished: 18.4 m² in 21 min"),
        ("docked", "Back on the dock"),
    ]


async def test_map_position_and_path(tmp_path: Path) -> None:
    hub, _, clock = await make_hub(tmp_path)
    hub.robot_status("r1", CLEANING)
    await send_map(hub, frame(path=(1.0, 1.0, 2.0, 1.0)))
    r = robot_json(hub)
    assert r["map"]["rev"] == "aaaa1111" and r["map"]["w"] == 10 and r["map"]["mm_per_px"] == 50
    assert r["map"]["rooms"]["16"] == {"cx": 2.5, "cy": 3.5, "x0": 1, "y0": 1, "x1": 4, "y1": 6}
    assert r["map"]["charger"] == {"x": 1.5, "y": 1.5}
    assert r["map"]["no_go"] == [[1.0, 1.0, 2.0, 1.0, 2.0, 2.0, 1.0, 2.0]]
    assert "path_rev" not in r and hub.robot("r1").path_rev == 1
    assert json.loads(hub.path_body("r1")) == {"rev": 1, "points": [1.0, 1.0, 2.0, 1.0]}
    gz_file = tmp_path / "maps" / "r1.bin.gz"
    assert gzip.decompress(gz_file.read_bytes()) == frame().index
    assert gzip.decompress(hub.robot("r1").map_gz) == frame().index
    assert hub.robot("r1").geom.index == b""  # the inflated index is not held
    mtime = gz_file.stat().st_mtime_ns

    clock.t += 1
    await send_map(hub, frame(robot=(6.5, 3.5), room=17, path=(1.0, 1.0, 2.0, 1.0, 6.5, 3.5)), b"m2")
    r = robot_json(hub)
    assert r["pos"]["room"] == 17 and r["pos"]["ts"] == int(NOW) + 1 and hub.robot("r1").path_rev == 2
    assert gz_file.stat().st_mtime_ns == mtime  # same floor plan: not rewritten

    # No robot in the map: the position stays.
    await send_map(hub, frame(robot=None, room=None, path=(1.0, 1.0, 2.0, 1.0, 6.5, 3.5)), b"m3")
    assert robot_json(hub)["pos"]["room"] == 17

    # A broken map keeps the old one.
    hub.robot_map("r1", b"garbage")
    await settle(hub)
    assert robot_json(hub)["map"]["rev"] == "aaaa1111"

    # A new floor plan is stored.
    await send_map(hub, replace(frame(), digest="bbbb2222", index=bytes(80)), b"m4")
    assert robot_json(hub)["map"]["rev"] == "bbbb2222"
    assert gzip.decompress(gz_file.read_bytes()) == bytes(80)


async def test_only_newest_pending_map_is_parsed(tmp_path: Path) -> None:
    hub, _, _ = await make_hub(tmp_path)
    hub.robot_status("r1", CLEANING)
    parsed: list[bytes] = []
    FRAMES.update({b"a": frame(), b"b": frame(robot=(5.5, 2.5)), b"c": frame(robot=(7.5, 2.5))})

    def counting(raw: bytes) -> MapFrame:
        parsed.append(raw)
        return fake_parse(raw)

    hub_module.rrmap.parse = counting
    hub.robot_map("r1", b"a")
    hub.robot_map("r1", b"b")
    hub.robot_map("r1", b"c")
    await settle(hub)
    assert parsed == [b"a", b"c"]
    assert robot_json(hub)["pos"]["x"] == 7.5


async def test_persistence_round_trip(tmp_path: Path) -> None:
    hub, _, clock = await make_hub(tmp_path)
    hub.robot_status("r1", replace(CLEANING, battery=8))
    await send_map(hub, frame(path=(1.0, 2.0)))
    clock.t += 30
    for _ in range(3):
        hub.robot_unreachable("r1", "timeout")
    before = robot_json(hub)
    await hub.stop()

    again = Hub(tmp_path, app_info=app_info, clock=Clock(NOW + 3600))
    again.set_tz("Australia/Melbourne")
    again.load()
    after = json.loads(again.robot("r1").fragment)
    assert after == before
    assert after["attention"]["since"] == int(NOW)
    assert again.robot("r1").map_gz == hub.robot("r1").map_gz
    assert json.loads(again.path_body("r1"))["points"] == [1.0, 2.0]
    assert [h["kind"] for h in again.history(10)] == [h["kind"] for h in hub.history(10)]
    # It comes back: the history says so.
    again.robot_found(INFO)
    again.robot_status("r1", DOCKED)
    assert [h["kind"] for h in again.history(3)] == ["docked", "finished", "online"]
    await again.stop()


async def test_persisted_robot_alive_at_shutdown_loads_offline(tmp_path: Path) -> None:
    hub, _, _ = await make_hub(tmp_path)
    hub.robot_status("r1", DOCKED)
    await send_map(hub, frame())
    await hub.stop()
    again = Hub(tmp_path, app_info=app_info, clock=Clock(NOW + 60))
    again.load()
    r = json.loads(again.robot("r1").fragment)
    assert r["online"] is False and r["pos"]["live"] is False and r["attention"] is None


async def test_debounced_flush(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(hub_module, "FLUSH_EVERY", 0.05)
    hub, _, _ = await make_hub(tmp_path)
    hub.robot_status("r1", DOCKED)
    assert not (tmp_path / "robots.json").exists()
    await asyncio.sleep(0.15)
    assert json.loads((tmp_path / "robots.json").read_text())[0]["info"]["id"] == "r1"


async def test_commands_are_checked(tmp_path: Path) -> None:
    hub, backend, _ = await make_hub(tmp_path)
    hub.robot_status("r1", replace(DOCKED, water_options=(), water=None))

    async def code(robot_id: str, command: Command) -> str:
        with pytest.raises(RobotError) as e:
            await hub.command(robot_id, command)
        return e.value.code

    assert await code("nope", Command("start")) == "not_found"
    assert await code("r1", Command("fly")) == "bad_request"
    assert await code("r1", Command("clean_rooms", rooms=(99,))) == "bad_request"
    assert await code("r1", Command("clean_rooms", rooms=())) == "bad_request"
    assert await code("r1", Command("clean_rooms", rooms=(16,), repeat=4)) == "bad_request"
    assert await code("r1", Command("set_fan", value="ludicrous")) == "bad_request"
    assert await code("r1", Command("set_water", value="mild")) == "unsupported"
    assert backend.commands == []
    await hub.command("r1", Command("clean_rooms", rooms=(16, 17), repeat=2))
    await hub.command("r1", Command("set_fan", value="turbo"), source="Agent")
    assert [c.name for _, c in backend.commands] == ["clean_rooms", "set_fan"]
    assert hub.history(1)[0]["text"] == "Suction to turbo, sent by Agent"
    backend.fail_with = RobotError("robot_unreachable", "Downstairs did not answer.")
    assert await code("r1", Command("start")) == "robot_unreachable"


async def test_watchers_drive_the_backend_with_grace(tmp_path: Path) -> None:
    hub, backend, _ = await make_hub(tmp_path)
    assert backend.watched == [False]
    q1 = hub.subscribe()
    q2 = hub.subscribe()
    assert backend.watched == [False, True]
    hub.unsubscribe(q1)
    hub.unsubscribe(q2)
    await asyncio.sleep(0.01)
    assert backend.watched == [False, True]  # still in the grace period
    q3 = hub.subscribe()
    hub.unsubscribe(q3)
    await asyncio.sleep(0.1)
    assert backend.watched == [False, True, False]


async def test_events_are_encoded_once_and_slow_clients_dropped(tmp_path: Path) -> None:
    hub, _, _ = await make_hub(tmp_path)
    fast, slow = hub.subscribe(), hub.subscribe()
    hub.robot_status("r1", DOCKED)
    message = fast.get_nowait()
    assert message.startswith(b"event: robot\nid: %d\ndata: {" % hub.rev) and message.endswith(b"}\n\n")
    assert slow.get_nowait() is message  # the same bytes object for everyone
    for battery in range(hub_module.QUEUE_DEPTH + 5):
        hub.robot_status("r1", replace(DOCKED, battery=battery))
        while not fast.empty():
            fast.get_nowait()
    assert slow.get_nowait() is None  # told to close
    assert hub.clients == 1


async def test_unlink_forgets_everything(tmp_path: Path) -> None:
    hub, backend, _ = await make_hub(tmp_path)
    hub.robot_status("r1", CLEANING)
    await send_map(hub, frame())
    queue = hub.subscribe()
    await hub.set_backend(None)
    assert backend.stopped
    assert hub.robots == [] and hub.history(10) == []
    assert not (tmp_path / "maps" / "r1.bin.gz").exists()
    messages = []
    while not queue.empty():
        messages.append(queue.get_nowait())
    assert messages[-1].startswith(b"event: state\n")
    assert json.loads(hub.state())["robots"] == []


async def test_time_zone_change_rewrites_summaries(tmp_path: Path) -> None:
    hub, _, _ = await make_hub(tmp_path)
    hub.robot_status("r1", STUCK)
    await send_map(hub, frame())
    hub.robot_status("r1", replace(STUCK, battery=40))
    assert "19:00" in robot_json(hub)["attention"]["summary"]
    hub.set_tz("UTC")
    assert "08:00" in robot_json(hub)["attention"]["summary"]


def drain(queue: asyncio.Queue) -> list[tuple[str, dict]]:
    out = []
    while not queue.empty():
        message = queue.get_nowait()
        head, data = message.split(b"\ndata: ")
        out.append((head.split(b"\n")[0][len("event: "):].decode(), json.loads(data)))
    return out


async def test_robot_events_are_patches(tmp_path: Path) -> None:
    hub, _, clock = await make_hub(tmp_path)
    queue = hub.subscribe()
    hub.robot_found(RobotInfo("r2", "Upstairs", "roborock.vacuum.a70", "S8"))
    [(kind, whole)] = drain(queue)
    assert kind == "robot" and whole["id"] == "r2" and "fan_options" in whole  # new: whole

    hub.robot_status("r1", CLEANING)
    events = drain(queue)
    robot_patches = [d for k, d in events if k == "robot"]
    assert set(robot_patches[0]) == {
        "id", "activity", "state_label", "online", "link", "battery", "charging",
        "fan", "fan_options", "water", "water_options", "clean", "seen",
    }
    clock.t += 1
    hub.robot_status("r1", replace(CLEANING, battery=89))
    assert drain(queue) == [("robot", {"id": "r1", "battery": 89, "seen": int(NOW) + 1})]

    clock.t += 1
    hub.robot_status("r1", replace(CLEANING, battery=89))  # unchanged poll: nothing at all
    assert drain(queue) == []

    clock.t += 1
    hub.robot_status("r1", replace(CLEANING, battery=89, clean_area_m2=3.5))
    assert drain(queue) == [
        ("robot", {"id": "r1", "clean": {"area_m2": 3.5, "seconds": 200}, "seen": int(NOW) + 3})
    ]

    await send_map(hub, frame())
    [(kind, patch)] = [e for e in drain(queue) if e[0] == "robot"]
    assert set(patch) == {"id", "map", "pos"}  # seen did not move
    clock.t += 1
    await send_map(hub, frame(robot=(4.5, 2.5)), b"m2")
    assert drain(queue) == [
        ("robot", {"id": "r1", "pos": {"x": 4.5, "y": 2.5, "angle": 90, "room": 16, "ts": int(NOW) + 4, "live": True}})
    ]
    clock.t += 1
    await send_map(hub, frame(robot=(4.5, 2.5)), b"m3")  # same place: nothing
    assert drain(queue) == []


async def test_path_events_append_and_replace(tmp_path: Path) -> None:
    hub, _, _ = await make_hub(tmp_path)
    hub.robot_status("r1", CLEANING)
    queue = hub.subscribe()

    def paths() -> list[dict]:
        return [d for k, d in drain(queue) if k == "path"]

    await send_map(hub, frame(path=(1.0, 1.0, 2.0, 1.0)))
    assert paths() == [{"id": "r1", "rev": 1, "start": 0, "points": [1.0, 1.0, 2.0, 1.0]}]
    await send_map(hub, frame(path=(1.0, 1.0, 2.0, 1.0, 3.0, 1.0)), b"m2")
    assert paths() == [{"id": "r1", "rev": 2, "start": 4, "points": [3.0, 1.0]}]
    await send_map(hub, frame(path=(1.0, 1.0, 2.0, 1.0, 3.0, 1.0)), b"m3")
    assert paths() == []  # unchanged: nothing
    await send_map(hub, frame(path=(5.0, 5.0)), b"m4")  # a new job
    assert paths() == [{"id": "r1", "rev": 3, "start": 0, "points": [5.0, 5.0]}]
    await send_map(hub, frame(path=(5.0, 6.0, 7.0, 7.0)), b"m5")  # redrawn, not appended
    assert paths() == [{"id": "r1", "rev": 4, "start": 0, "points": [5.0, 6.0, 7.0, 7.0]}]
    assert json.loads(hub.path_body("r1")) == {"rev": 4, "points": [5.0, 6.0, 7.0, 7.0]}
