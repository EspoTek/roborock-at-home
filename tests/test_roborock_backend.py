"""The Roborock backend's pure mappings, and its command / status handling
over fake devices. No network."""

from __future__ import annotations

import asyncio
import pathlib
from types import SimpleNamespace
from typing import Any

import pytest
from roborock.data.v1.v1_code_mappings import RoborockErrorCode, RoborockStateCode
from roborock.exceptions import (
    RoborockAccountDoesNotExist,
    RoborockConnectionException,
    RoborockException,
    RoborockInvalidCode,
    RoborockInvalidEmail,
    RoborockInvalidStatus,
    RoborockTooFrequentCodeRequests,
    RoborockUnsupportedFeature,
)
from roborock.mqtt.session import MqttSessionException
from roborock.roborock_typing import RoborockCommand
from roborock.web_api import RoborockApiClient

from roborock_at_home import roborock_backend as rb
from roborock_at_home.model import ACTIVITIES, COMMANDS, Command, ErrorInfo, Observation, RobotError, RobotInfo

# --------------------------------------------------------------------------
# activity / labels
# --------------------------------------------------------------------------


def test_every_state_code_is_mapped() -> None:
    names = {member.name for member in RoborockStateCode}
    assert names == set(rb.ACTIVITY_BY_STATE), "python-roborock's RoborockStateCode changed: map the new members"
    assert set(rb.ACTIVITY_BY_STATE.values()) <= set(ACTIVITIES)


@pytest.mark.parametrize(
    ("state", "activity"),
    [
        (RoborockStateCode.cleaning, "cleaning"),
        (RoborockStateCode.segment_cleaning, "cleaning"),
        (RoborockStateCode.zoned_cleaning, "cleaning"),
        (RoborockStateCode.spot_cleaning, "cleaning"),
        (RoborockStateCode.going_to_target, "cleaning"),
        (RoborockStateCode.remote_control_active, "cleaning"),
        (RoborockStateCode.mapping, "cleaning"),
        (RoborockStateCode.starting, "cleaning"),
        (RoborockStateCode.returning_home, "returning"),
        (RoborockStateCode.docking, "returning"),
        (RoborockStateCode.going_to_wash_the_mop, "returning"),
        (RoborockStateCode.paused, "paused"),
        (RoborockStateCode.charging, "docked"),
        (RoborockStateCode.charging_complete, "docked"),
        (RoborockStateCode.emptying_the_bin, "docked"),
        (RoborockStateCode.washing_the_mop, "docked"),
        (RoborockStateCode.washing_the_mop_2, "docked"),
        (RoborockStateCode.updating, "docked"),
        (RoborockStateCode.error, "error"),
        (RoborockStateCode.charging_problem, "error"),
        (RoborockStateCode.locked, "error"),
        (RoborockStateCode.idle, "idle"),
        (RoborockStateCode.charger_disconnected, "idle"),
        (RoborockStateCode.shutting_down, "idle"),
    ],
)
def test_activity_of(state: RoborockStateCode, activity: str) -> None:
    assert rb.activity_of(int(state), 0, 0) == activity


def test_idle_on_the_dock_contacts_is_docked() -> None:
    # What the real S7 Max Ultra reports while it sits on its dock.
    assert rb.activity_of(int(RoborockStateCode.idle), 0, 1) == "docked"
    assert rb.activity_of(int(RoborockStateCode.charger_disconnected), 0, 1) == "docked"
    assert rb.activity_of(int(RoborockStateCode.idle), 0, None) == "idle"
    # Charge status does not turn a moving robot into a docked one.
    assert rb.activity_of(int(RoborockStateCode.cleaning), 0, 1) == "cleaning"


def test_error_code_wins_over_state() -> None:
    assert rb.activity_of(int(RoborockStateCode.cleaning), 5, 0) == "error"
    assert rb.activity_of(int(RoborockStateCode.charging), 999, 1) == "error"


def test_unknown_state_reads_as_idle_with_its_code() -> None:
    assert rb.activity_of(4242, 0, 0) == "idle"
    assert rb.activity_of(4242, 0, 1) == "docked"
    assert rb.state_label(4242) == "State 4242"


@pytest.mark.parametrize(
    ("state", "label"),
    [
        (RoborockStateCode.washing_the_mop, "Washing the mop"),
        (RoborockStateCode.washing_the_mop_2, "Washing the mop"),
        (RoborockStateCode.charging, "Charging"),
        (RoborockStateCode.returning_home, "Returning home"),
        (RoborockStateCode.charger_disconnected, "Charger disconnected"),
        (RoborockStateCode.segment_cleaning, "Cleaning rooms"),
    ],
)
def test_state_label(state: RoborockStateCode, label: str) -> None:
    assert rb.state_label(int(state)) == label


def test_every_label_reads_as_a_sentence() -> None:
    for state in RoborockStateCode:
        label = rb.state_label(int(state))
        assert label[0].isupper() and "_" not in label and not label.endswith("."), label
    for code in RoborockErrorCode:
        if code == 0:
            continue
        error = rb.error_of(int(code))
        assert error is not None
        assert error.label[0].isupper() and "_" not in error.label and not error.label.endswith("."), error


def test_error_of() -> None:
    assert rb.error_of(0) is None
    assert rb.error_of(5) == ErrorInfo(5, "Main brush jammed")
    assert rb.error_of(38) == ErrorInfo(38, "Check the clean water tank")
    # Unknown to the library (which would read it as 0, "none").
    assert rb.error_of(33) == ErrorInfo(33, "Error 33")
    assert rb.error_of(999) == ErrorInfo(999, "Error 999")


# --------------------------------------------------------------------------
# levels and observe()
# --------------------------------------------------------------------------

FAN = {101: "quiet", 102: "balanced", 103: "turbo", 104: "max", 108: "max_plus", 105: "off", 106: "custom"}
WATER = {200: "off", 201: "mild", 202: "standard", 203: "intense", 204: "custom", 207: "custom_water_flow"}


def test_level_codes_drop_per_room_and_free_flow_modes() -> None:
    assert rb.level_codes(FAN) == {"quiet": 101, "balanced": 102, "turbo": 103, "max": 104, "max_plus": 108, "off": 105}
    assert list(rb.level_codes(WATER)) == ["off", "mild", "standard", "intense"]
    assert rb.level_codes({106: "Customized", 101: "Quiet"}) == {"quiet": 101}


def _raw(**fields: Any) -> dict[str, Any]:
    raw = {
        "state": 8,
        "battery": 64,
        "error_code": 0,
        "clean_time": 1800,
        "clean_area": 23_460_000,
        "in_cleaning": 0,
        "fan_power": 102,
        "water_box_mode": 202,
        "charge_status": 1,
        "map_status": 3,
    }
    raw.update(fields)
    return raw


def _observe(raw: dict[str, Any], *, water: bool = True, local: bool = True) -> Observation:
    fan_codes = rb.level_codes(FAN)
    water_codes = rb.level_codes(WATER) if water else {}
    return rb.observe(
        raw,
        fan_codes=fan_codes,
        fan_name=FAN.get(raw["fan_power"]),
        water_codes=water_codes,
        water_name=WATER.get(raw["water_box_mode"]) if water else None,
        local=local,
    )


def test_observe_docked_and_charging() -> None:
    obs = _observe(_raw())
    assert obs == Observation(
        activity="docked",
        state_label="Charging",
        battery=64,
        charging=True,
        error=None,
        link="local",
        fan="balanced",
        fan_options=("quiet", "balanced", "turbo", "max", "max_plus", "off"),
        water="standard",
        water_options=("off", "mild", "standard", "intense"),
        clean_area_m2=None,  # not in a job
        clean_seconds=None,
    )


def test_observe_charging_flag_follows_the_charging_state_only() -> None:
    assert not _observe(_raw(state=8, battery=100)).charging
    # Sitting on the contacts as "charger disconnected", battery falling.
    obs = _observe(_raw(state=2, battery=14, charge_status=1))
    assert obs.activity == "docked" and not obs.charging and obs.state_label == "Charger disconnected"


def test_observe_job_area_and_time() -> None:
    obs = _observe(_raw(state=18, in_cleaning=3, charge_status=0), local=False)
    assert obs.activity == "cleaning"
    assert obs.clean_area_m2 == 23.5
    assert obs.clean_seconds == 1800
    assert obs.link == "cloud"
    # Docked to recharge mid-job: the job is still under way.
    assert _observe(_raw(state=8, in_cleaning=1)).clean_seconds == 1800


def test_observe_custom_levels_read_as_none() -> None:
    obs = _observe(_raw(fan_power=106, water_box_mode=204))
    assert obs.fan is None and obs.water is None


def test_observe_robot_that_cannot_mop() -> None:
    obs = _observe(_raw(), water=False)
    assert obs.water is None and obs.water_options == ()


def test_observe_error() -> None:
    obs = _observe(_raw(state=12, error_code=8, charge_status=0))
    assert obs.activity == "error"
    assert obs.error == ErrorInfo(8, "Robot trapped")


def test_observe_requires_the_core_fields() -> None:
    raw = _raw()
    del raw["battery"]
    with pytest.raises(KeyError):
        _observe(raw)


# --------------------------------------------------------------------------
# command_request()
# --------------------------------------------------------------------------


def _request(command: Command, in_cleaning: int | None = 0) -> tuple[RoborockCommand, list[Any] | None]:
    return rb.command_request(
        command,
        in_cleaning=in_cleaning,
        fan_codes=rb.level_codes(FAN),
        water_codes=rb.level_codes(WATER),
    )


@pytest.mark.parametrize(
    ("name", "method"),
    [
        ("start", RoborockCommand.APP_START),
        ("pause", RoborockCommand.APP_PAUSE),
        ("stop", RoborockCommand.APP_STOP),
        ("dock", RoborockCommand.APP_CHARGE),
        ("locate", RoborockCommand.FIND_ME),
    ],
)
def test_simple_commands(name: str, method: RoborockCommand) -> None:
    assert _request(Command(name)) == (method, None)


def test_resume_resumes_the_interrupted_job() -> None:
    assert _request(Command("resume"), in_cleaning=3) == (RoborockCommand.RESUME_SEGMENT_CLEAN, None)
    assert _request(Command("resume"), in_cleaning=2) == (RoborockCommand.RESUME_ZONED_CLEAN, None)
    assert _request(Command("resume"), in_cleaning=1) == (RoborockCommand.APP_START, None)
    assert _request(Command("resume"), in_cleaning=0) == (RoborockCommand.APP_START, None)
    assert _request(Command("resume"), in_cleaning=None) == (RoborockCommand.APP_START, None)


def test_clean_rooms() -> None:
    assert _request(Command("clean_rooms", rooms=(16, 18), repeat=2)) == (
        RoborockCommand.APP_SEGMENT_CLEAN,
        [{"segments": [16, 18], "repeat": 2}],
    )


@pytest.mark.parametrize(
    "command",
    [
        Command("clean_rooms", rooms=()),
        Command("clean_rooms", rooms=(16,), repeat=0),
        Command("clean_rooms", rooms=(16,), repeat=4),
        Command("set_fan", value="custom"),
        Command("set_fan", value=None),
        Command("set_fan", value="loud"),
        Command("set_water", value="custom_water_flow"),
        Command("vacuum_the_cat"),
    ],
)
def test_bad_requests(command: Command) -> None:
    with pytest.raises(RobotError) as err:
        _request(command)
    assert err.value.code == "bad_request"


def test_set_levels() -> None:
    assert _request(Command("set_fan", value="max_plus")) == (RoborockCommand.SET_CUSTOM_MODE, [108])
    assert _request(Command("set_water", value="intense")) == (RoborockCommand.SET_WATER_BOX_CUSTOM_MODE, [203])


def test_set_water_on_a_robot_that_cannot_mop() -> None:
    with pytest.raises(RobotError) as err:
        rb.command_request(
            Command("set_water", value="low"), in_cleaning=0, fan_codes={}, water_codes={}
        )
    assert err.value.code == "unsupported"


def test_every_command_is_mapped() -> None:
    for name in COMMANDS:
        command = Command(name, rooms=(16,), value={"set_fan": "quiet", "set_water": "off"}.get(name))
        method, _ = _request(command)
        assert isinstance(method, RoborockCommand)


def _transport_failure(cause: BaseException) -> RoborockException:
    try:
        raise RoborockException("transport failed") from cause
    except RoborockException as exc:
        return exc


def test_robot_answered() -> None:
    assert rb._robot_answered(RoborockException({"code": -10000, "message": "data for api is not valid"}))
    assert rb._robot_answered(RoborockException("Unexpected API Result: failed"))
    assert rb._robot_answered(RoborockInvalidStatus({"code": -10007}))
    assert not rb._robot_answered(_transport_failure(TimeoutError()))
    assert not rb._robot_answered(_transport_failure(MqttSessionException("MQTT client not connected")))
    assert not rb._robot_answered(RoborockConnectionException("Not connected to device"))


# --------------------------------------------------------------------------
# login
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    "exc",
    [
        RoborockInvalidEmail("x"),
        RoborockAccountDoesNotExist("x"),
        RoborockTooFrequentCodeRequests("x"),
    ],
)
async def test_request_code_input_errors(monkeypatch: pytest.MonkeyPatch, exc: RoborockException) -> None:
    async def fail(self: RoborockApiClient) -> None:
        raise exc

    monkeypatch.setattr(RoborockApiClient, "request_code_v4", fail)
    with pytest.raises(RobotError) as err:
        await rb.request_code("someone@example.com")
    assert err.value.code == "bad_request"
    assert err.value.message.endswith(".")


async def test_login_wrong_code(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fail(self: RoborockApiClient, code: str) -> None:
        raise RoborockInvalidCode("Invalid code")

    monkeypatch.setattr(RoborockApiClient, "code_login_v4", fail)
    with pytest.raises(RobotError) as err:
        await rb.login_with_code("someone@example.com", "000000")
    assert err.value.code == "bad_request"


async def test_login_network_failure_propagates(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fail(self: RoborockApiClient) -> None:
        raise _transport_failure(OSError("network is down"))

    monkeypatch.setattr(RoborockApiClient, "request_code_v4", fail)
    with pytest.raises(RoborockException):
        await rb.request_code("someone@example.com")


# --------------------------------------------------------------------------
# the backend over a fake device
# --------------------------------------------------------------------------


class FakeSink:
    def __init__(self) -> None:
        self.events: list[tuple[str, Any]] = []

    def robot_found(self, info: RobotInfo) -> None:
        self.events.append(("found", info))

    def robot_status(self, robot_id: str, obs: Observation) -> None:
        self.events.append(("status", obs))

    def robot_unreachable(self, robot_id: str, reason: str) -> None:
        self.events.append(("unreachable", reason))

    def robot_rooms(self, robot_id: str, rooms: list[Any]) -> None:
        self.events.append(("rooms", rooms))

    def robot_map(self, robot_id: str, raw: bytes) -> None:
        self.events.append(("map", raw))

    def account_problem(self, message: str | None) -> None:
        self.events.append(("account", message))


class FakeChannel:
    def __init__(self) -> None:
        self.answer: Any = [_raw()]
        self.sent: list[tuple[Any, Any]] = []

    async def send_command(self, method: Any, params: Any = None) -> Any:
        self.sent.append((method, params))
        if isinstance(self.answer, BaseException):
            raise self.answer
        return self.answer


ACCOUNT = {"email": "someone@example.com", "user_data": {}, "base_url": "https://example.invalid"}


def _backend(tmp_path: pathlib.Path) -> tuple[rb.RoborockBackend, FakeSink, rb._Robot, FakeChannel]:
    backend = object.__new__(rb.RoborockBackend)  # skip UserData parsing; no account needed here
    backend._sink = sink = FakeSink()
    backend._watched = False
    backend._login_refused = False
    channel = FakeChannel()
    status = SimpleNamespace(rpc_channel=channel, fan_speed_mapping=FAN, water_mode_mapping=WATER)
    v1 = SimpleNamespace(
        status=status,
        command=SimpleNamespace(send=channel.send_command),
        device_features=SimpleNamespace(is_water_slide_mode_supported=False),
    )
    device = SimpleNamespace(is_local_connected=True)
    info = RobotInfo(id="r1", name="Upstairs", model="roborock.vacuum.a65", model_name="S7 Max Ultra")
    robot = rb._Robot(device=device, v1=v1, info=info, ready=True)  # type: ignore[arg-type]
    robot.fan_codes = rb.level_codes(FAN)
    backend._robots = {"r1": robot}
    return backend, sink, robot, channel


async def test_status_read_reaches_the_sink(tmp_path: pathlib.Path) -> None:
    backend, sink, robot, channel = _backend(tmp_path)
    await backend._read_status(robot)
    [(kind, obs)] = sink.events
    assert kind == "status" and obs.activity == "docked" and obs.water == "standard"
    assert robot.reachable and robot.current_map == 0
    now = asyncio.get_running_loop().time()
    assert robot.next_status == pytest.approx(now + rb.STATUS_IDLE_S, abs=1)
    assert robot.next_rooms <= now  # the map flag was new


async def test_failed_status_read_backs_off(tmp_path: pathlib.Path) -> None:
    backend, sink, robot, channel = _backend(tmp_path)
    channel.answer = _transport_failure(TimeoutError())
    await backend._read_status(robot)
    assert sink.events == [("unreachable", "transport failed")]
    assert not robot.reachable
    now = asyncio.get_running_loop().time()
    assert robot.next_status == pytest.approx(now + rb.STATUS_FAILED_S, abs=1)


async def test_robot_not_connected_costs_nothing(tmp_path: pathlib.Path) -> None:
    backend, sink, robot, channel = _backend(tmp_path)
    robot.ready = False
    await backend._read_status(robot)
    assert channel.sent == []
    assert [kind for kind, _ in sink.events] == ["unreachable"]
    with pytest.raises(RobotError) as err:
        await backend.command("r1", Command("start"))
    assert err.value.code == "robot_unreachable" and "Upstairs" in err.value.message
    assert channel.sent == []


async def test_command_sends_and_schedules_follow_up_reads(tmp_path: pathlib.Path) -> None:
    backend, sink, robot, channel = _backend(tmp_path)
    channel.answer = ["ok"]
    await backend.command("r1", Command("clean_rooms", rooms=(16,)))
    assert channel.sent == [(RoborockCommand.APP_SEGMENT_CLEAN, [{"segments": [16], "repeat": 1}])]
    now = asyncio.get_running_loop().time()
    assert [round(t - now) for t in robot.follow_ups] == [0, 2, 6]
    assert robot.wake.is_set()


async def test_command_errors(tmp_path: pathlib.Path) -> None:
    backend, sink, robot, channel = _backend(tmp_path)
    with pytest.raises(RobotError) as err:
        await backend.command("nope", Command("start"))
    assert err.value.code == "not_found"

    channel.answer = _transport_failure(TimeoutError())
    with pytest.raises(RobotError) as err:
        await backend.command("r1", Command("start"))
    assert err.value.code == "robot_unreachable" and err.value.message == "Upstairs did not answer."

    channel.answer = RoborockInvalidStatus({"code": -10007, "message": "invalid status"})
    with pytest.raises(RobotError) as err:
        await backend.command("r1", Command("start"))
    assert err.value.code == "rejected"

    channel.answer = RoborockUnsupportedFeature("The method called is not recognized by the device.")
    with pytest.raises(RobotError) as err:
        await backend.command("r1", Command("locate"))
    assert err.value.code == "unsupported"
    assert robot.follow_ups == []


async def test_poller_reads_status_rooms_and_map_then_sleeps(tmp_path: pathlib.Path) -> None:
    backend, sink, robot, channel = _backend(tmp_path)
    map_channel = FakeChannel()
    map_channel.answer = b"rr" + bytes(100)
    rooms = SimpleNamespace(rooms=[SimpleNamespace(segment_id=16, name="Room 16")])

    async def refresh_rooms() -> None:
        pass

    rooms.refresh = refresh_rooms
    robot.v1.rooms = rooms
    robot.v1.map_content = SimpleNamespace(rpc_channel=map_channel)
    robot.task = asyncio.create_task(backend._poll(robot))
    # A busy loop would starve this sleep; the poller must be asleep after one pass.
    await asyncio.wait_for(asyncio.sleep(0.3), timeout=2)
    robot.task.cancel()
    assert [kind for kind, _ in sink.events] == ["status", "rooms", "map"]
    assert channel.sent == [(RoborockCommand.GET_STATUS, None)]
    assert map_channel.sent == [(RoborockCommand.GET_MAP_V1, None)]
    assert [(room.id, room.name) for room in dict(sink.events)["rooms"]] == [(16, "Room 16")]
    now = asyncio.get_running_loop().time()
    assert robot.next_map == pytest.approx(now + rb.MAP_IDLE_S, abs=2)


async def test_set_watched(tmp_path: pathlib.Path) -> None:
    backend, sink, robot, channel = _backend(tmp_path)
    now = asyncio.get_running_loop().time()
    robot.reachable, robot.next_status = False, now + 50
    backend.set_watched(True)
    assert robot.next_status == now + 50 and not robot.wake.is_set()
    robot.reachable = True
    backend.set_watched(True)
    assert robot.next_status <= asyncio.get_running_loop().time() and robot.wake.is_set()


def _connect_backend(tmp_path: pathlib.Path) -> tuple[rb.RoborockBackend, FakeSink]:
    backend, sink, _, _ = _backend(tmp_path)
    backend._robots = {}
    backend._params = None  # type: ignore[assignment]
    backend._cache = rb.JsonFileCache(tmp_path / "cache.json")
    backend._manager = None
    backend._flush = None
    return backend, sink


async def test_connect_retries_while_the_network_is_down(
    tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    backend, sink = _connect_backend(tmp_path)
    calls = 0

    class Manager:
        async def get_devices(self) -> list[Any]:
            return []

    async def create(*args: Any, **kwargs: Any) -> Manager:
        nonlocal calls
        calls += 1
        if calls < 3:
            raise _transport_failure(OSError("network is unreachable"))
        return Manager()

    monkeypatch.setattr(rb, "create_device_manager", create)
    monkeypatch.setattr(rb, "START_RETRY_MIN_S", 0.01)
    await asyncio.wait_for(backend._connect(), timeout=2)
    assert calls == 3
    assert sink.events == [("account", None)]


async def test_connect_stops_on_an_expired_login(tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch) -> None:
    backend, sink = _connect_backend(tmp_path)
    from roborock.exceptions import RoborockInvalidCredentials

    async def create(*args: Any, mqtt_session_unauthorized_hook: Any, **kwargs: Any) -> None:
        mqtt_session_unauthorized_hook()  # as UserWebApiClient does before raising
        raise RoborockInvalidCredentials("Invalid credentials")

    monkeypatch.setattr(rb, "create_device_manager", create)
    await asyncio.wait_for(backend._connect(), timeout=2)
    assert sink.events == [("account", rb.LOGIN_EXPIRED)]


async def test_connect_lets_other_failures_propagate(tmp_path: pathlib.Path, monkeypatch: pytest.MonkeyPatch) -> None:
    backend, sink = _connect_backend(tmp_path)

    async def create(*args: Any, **kwargs: Any) -> None:
        raise RoborockException("home_response result was an unexpected type")

    monkeypatch.setattr(rb, "create_device_manager", create)
    with pytest.raises(RoborockException):
        await backend._connect()


async def test_json_cache_round_trip(tmp_path: pathlib.Path) -> None:
    from roborock.devices.cache import CacheData, DeviceCacheData

    path = tmp_path / "cache.json"
    cache = rb.JsonFileCache(path)
    assert await cache.get() == CacheData()
    data = CacheData(device_info={"dev": DeviceCacheData(trait_data={"dock_type": 3})})
    await cache.set(data)
    await cache.flush()
    assert not path.with_name("cache.json.tmp").exists()
    again = await rb.JsonFileCache(path).get()
    assert again.device_info["dev"].trait_data == {"dock_type": 3}
