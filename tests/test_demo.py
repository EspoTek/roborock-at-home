"""The demo robots, driven through a recording Sink on a fast clock."""

from __future__ import annotations

import asyncio
import math
import time
from collections.abc import AsyncIterator, Callable

import pytest

from roborock_at_home import rrmap
from roborock_at_home.demo import DemoBackend, FAN_OPTIONS
from roborock_at_home.model import (
    PX_FLOOR,
    ROOM_BASE,
    Command,
    MapFrame,
    Observation,
    RobotError,
    RobotInfo,
    Room,
)

SPEEDUP = 2000.0  # a 1 s tick every 0.5 ms


class RecordingSink:
    def __init__(self) -> None:
        self.found: dict[str, RobotInfo] = {}
        self.rooms: dict[str, list[Room]] = {}
        self.statuses: dict[str, list[Observation]] = {}
        self.maps: dict[str, list[MapFrame]] = {}
        self.unreachable: dict[str, int] = {}
        self.calls = 0

    def robot_found(self, info: RobotInfo) -> None:
        self.calls += 1
        self.found[info.id] = info

    def robot_status(self, robot_id: str, obs: Observation) -> None:
        self.calls += 1
        assert robot_id not in self.unreachable, "a robot answered after it stopped answering"
        self.statuses.setdefault(robot_id, []).append(obs)

    def robot_unreachable(self, robot_id: str, reason: str) -> None:
        self.calls += 1
        self.unreachable[robot_id] = self.unreachable.get(robot_id, 0) + 1

    def robot_rooms(self, robot_id: str, rooms: list[Room]) -> None:
        self.calls += 1
        self.rooms[robot_id] = rooms

    def robot_map(self, robot_id: str, raw: bytes) -> None:
        self.calls += 1
        frame = rrmap.parse(raw)
        if frame.robot is not None:
            # Every position ever reported must be on the floor.
            x, y = frame.robot
            c = frame.index[math.floor(y) * frame.width + math.floor(x)]
            assert c == PX_FLOOR or c >= ROOM_BASE, f"{robot_id} reported on class {c} at {frame.robot}"
        self.maps.setdefault(robot_id, []).append(frame)

    def account_problem(self, message: str | None) -> None:
        self.calls += 1

    def last(self, robot_id: str) -> Observation:
        return self.statuses[robot_id][-1]


@pytest.fixture
async def demo() -> AsyncIterator[tuple[DemoBackend, RecordingSink]]:
    backend = DemoBackend(speedup=SPEEDUP)
    sink = RecordingSink()
    await backend.start(sink)
    backend.set_watched(True)
    yield backend, sink
    await backend.stop()


async def until(cond: Callable[[], bool], timeout: float = 10.0) -> None:
    deadline = time.monotonic() + timeout
    while not cond():
        if time.monotonic() > deadline:
            raise AssertionError("timed out waiting for the demo")
        await asyncio.sleep(0.001)


async def test_robots_appear_with_rooms_status_and_map(demo: tuple[DemoBackend, RecordingSink]) -> None:
    _backend, sink = demo
    await until(lambda: len(sink.maps) == 4)
    assert sorted(sink.found) == ["downstairs", "garage", "studio", "upstairs"]
    for rid in sink.found:
        rooms = sink.rooms[rid]
        assert 5 <= len(rooms) <= 8
        frame = sink.maps[rid][0]
        assert set(frame.rooms) == {r.id for r in rooms}
        assert frame.charger is not None and frame.robot is not None
        assert len(frame.no_go) == 1 and len(frame.walls) == 1
        assert 200 <= frame.width <= 300 and 180 <= frame.height <= 240
    assert len({sink.maps[r][0].digest for r in sink.found}) == 4  # a floor plan each

    one, two, three, four = (sink.statuses[r][0] for r in ("downstairs", "upstairs", "garage", "studio"))
    assert one.activity == "docked" and one.charging and 85 <= one.battery <= 95
    assert two.activity == "cleaning" and len(sink.maps["upstairs"][0].path) > 100
    assert three.activity == "error" and three.error is not None and three.error.code == 5
    assert three.error.label == "Main brush jammed" and 35 <= three.battery <= 45
    assert sink.maps["garage"][0].robot_room is not None
    assert four.activity == "idle" and four.battery == 4 and four.water_options == ()
    assert one.fan_options == FAN_OPTIONS and one.water_options


async def test_first_reports_arrive_without_waiting_for_a_tick() -> None:
    backend = DemoBackend(speedup=1.0)  # real time: a tick would take a second
    sink = RecordingSink()
    await backend.start(sink)
    await asyncio.sleep(0)
    assert len(sink.found) == len(sink.statuses) == len(sink.maps) == len(sink.rooms) == 4
    await backend.stop()


async def test_clean_rooms_goes_there_and_comes_home(demo: tuple[DemoBackend, RecordingSink]) -> None:
    backend, sink = demo
    await until(lambda: "downstairs" in sink.maps)
    rooms = sink.rooms["downstairs"]
    target = rooms[-1].id  # the far side of the hall from the dock
    before = len(sink.statuses["downstairs"])
    await backend.command("downstairs", Command("clean_rooms", rooms=(target,), repeat=1))
    # The status follows the command at once, not at the next tick.
    assert len(sink.statuses["downstairs"]) == before + 1
    assert sink.last("downstairs").activity == "cleaning"
    assert sink.last("downstairs").state_label == "Cleaning rooms"
    first_map = len(sink.maps["downstairs"])
    await until(lambda: sink.last("downstairs").activity == "docked", timeout=30)
    frames = sink.maps["downstairs"][first_map - 1 :]
    assert len(frames[0].path) <= 2  # a new job starts a new path
    assert target in {f.robot_room for f in frames}
    visited = {f.robot_room for f in frames} - {None}
    assert visited <= {r.id for r in rooms}
    seen = [s for s in sink.statuses["downstairs"][before:]]
    assert any(s.activity == "returning" for s in seen)
    done = sink.last("downstairs")
    assert done.clean_area_m2 is not None and done.clean_area_m2 > 3
    assert done.clean_seconds is not None and done.clean_seconds > 60
    assert frames[-1].robot is not None and frames[-1].charger is not None
    assert math.dist(frames[-1].robot, frames[-1].charger) < 8


async def test_full_clean_visits_every_room_and_docks(demo: tuple[DemoBackend, RecordingSink]) -> None:
    backend, sink = demo
    await until(lambda: "studio" in sink.maps)
    await backend.command("downstairs", Command("start"))
    start = len(sink.maps["downstairs"]) - 1
    battery = sink.last("downstairs").battery
    await until(lambda: sink.last("downstairs").activity == "docked", timeout=60)
    frames = sink.maps["downstairs"][start:]
    assert {f.robot_room for f in frames} >= {r.id for r in sink.rooms["downstairs"]}
    assert min(s.battery for s in sink.statuses["downstairs"][-50:]) < battery
    # Robot 2 finishes the job it was on and goes home too.
    await until(lambda: sink.last("upstairs").activity == "docked", timeout=60)


async def test_pause_resume_and_dock_mid_clean(demo: tuple[DemoBackend, RecordingSink]) -> None:
    backend, sink = demo
    await until(lambda: "upstairs" in sink.maps)
    await backend.command("upstairs", Command("pause"))
    assert sink.last("upstairs").activity == "paused"
    where = sink.maps["upstairs"][-1].robot
    n = len(sink.statuses["upstairs"])
    await until(lambda: len(sink.statuses["upstairs"]) > n + 5)
    assert sink.maps["upstairs"][-1].robot == where
    await backend.command("upstairs", Command("resume"))
    assert sink.last("upstairs").activity == "cleaning"
    await until(lambda: sink.maps["upstairs"][-1].robot != where)
    await backend.command("upstairs", Command("dock"))
    assert sink.last("upstairs").activity == "returning"
    await until(lambda: sink.last("upstairs").activity == "docked", timeout=30)
    with pytest.raises(RobotError) as err:
        await backend.command("upstairs", Command("resume"))
    assert err.value.code == "rejected"
    await backend.command("upstairs", Command("set_fan", value="turbo"))
    await backend.command("upstairs", Command("set_water", value="intense"))
    await backend.command("upstairs", Command("locate"))
    assert (sink.last("upstairs").fan, sink.last("upstairs").water) == ("turbo", "intense")


async def test_stuck_robot_carries_on_after_start(demo: tuple[DemoBackend, RecordingSink]) -> None:
    backend, sink = demo
    await until(lambda: "garage" in sink.maps)
    where = sink.maps["garage"][-1].robot
    await backend.command("garage", Command("start"))
    obs = sink.last("garage")
    assert obs.activity == "cleaning" and obs.error is None
    await until(lambda: sink.maps["garage"][-1].robot != where)
    await backend.command("garage", Command("stop"))
    assert sink.last("garage").activity == "idle"


async def test_stuck_robot_docks_when_told(demo: tuple[DemoBackend, RecordingSink]) -> None:
    backend, sink = demo
    await until(lambda: "garage" in sink.maps)
    await backend.command("garage", Command("dock"))
    assert sink.last("garage").activity == "returning" and sink.last("garage").error is None
    await until(lambda: sink.last("garage").activity == "docked", timeout=30)


async def test_flat_robot_goes_quiet_for_good(demo: tuple[DemoBackend, RecordingSink]) -> None:
    backend, sink = demo
    await until(lambda: sink.unreachable.get("studio", 0) >= 3)
    reads = len(sink.statuses["studio"])
    assert reads >= 10  # it answered for about 20 s first
    last = sink.maps["studio"][-1]
    assert last.robot is not None and last.robot_room is not None
    for cmd in (Command("dock"), Command("locate"), Command("set_fan", value="max")):
        with pytest.raises(RobotError) as err:
            await backend.command("studio", cmd)
        assert err.value.code == "robot_unreachable"


async def test_bad_requests(demo: tuple[DemoBackend, RecordingSink]) -> None:
    backend, sink = demo
    await until(lambda: "downstairs" in sink.rooms)
    cases = [
        ("downstairs", Command("clean_rooms", rooms=(99,)), "bad_request"),
        ("downstairs", Command("clean_rooms", rooms=()), "bad_request"),
        ("downstairs", Command("clean_rooms", rooms=(16,), repeat=4), "bad_request"),
        ("downstairs", Command("set_fan", value="hurricane"), "bad_request"),
        ("downstairs", Command("set_fan"), "bad_request"),
        ("downstairs", Command("set_water", value="flood"), "bad_request"),
        ("downstairs", Command("dance"), "bad_request"),
        ("downstairs", Command("pause"), "rejected"),
        ("studio", Command("set_water", value="mild"), "unsupported"),
        ("nobody", Command("start"), "not_found"),
    ]
    for robot_id, cmd, code in cases:
        with pytest.raises(RobotError) as err:
            await backend.command(robot_id, cmd)
        assert err.value.code == code, (robot_id, cmd)


async def test_unwatched_and_still_means_silent() -> None:
    backend = DemoBackend(speedup=SPEEDUP)
    sink = RecordingSink()
    await backend.start(sink)
    await until(lambda: "studio" in sink.unreachable)  # robot 4 has died
    await backend.command("upstairs", Command("stop"))
    await asyncio.sleep(0.05)  # let any tick in flight finish
    calls = sink.calls
    await asyncio.sleep(0.2)  # hundreds of ticks' worth
    assert sink.calls == calls
    backend.set_watched(True)
    await until(lambda: sink.calls > calls)
    await backend.stop()


def test_tick_cost_with_every_robot_moving() -> None:
    class NullSink:
        """Takes the reports without looking at them, to time the demo alone."""

        size = 0

        def robot_found(self, info: RobotInfo) -> None: ...
        def robot_status(self, robot_id: str, obs: Observation) -> None: ...
        def robot_unreachable(self, robot_id: str, reason: str) -> None: ...
        def robot_rooms(self, robot_id: str, rooms: list[Room]) -> None: ...
        def account_problem(self, message: str | None) -> None: ...
        def robot_map(self, robot_id: str, raw: bytes) -> None:
            self.size = len(raw)

    async def scenario() -> tuple[float, int]:
        backend = DemoBackend()
        sink = NullSink()
        backend._sink = sink  # drive the clock by hand, without the loop
        for rid in ("downstairs", "garage", "studio"):
            await backend.command(rid, Command("start"))
        best = math.inf
        for _ in range(10):
            t = time.perf_counter()
            backend._tick(1.0)
            best = min(best, time.perf_counter() - t)
        return best * 1000, sink.size

    ms, size = asyncio.run(scenario())
    print(f"\none tick, four robots moving: {ms:.2f} ms; one encoded map: {size} bytes")
    assert ms < 5
