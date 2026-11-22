"""The real robots: a Backend over python-roborock (pinned 7.22.0).

This is the only module that imports python-roborock; the app imports it
lazily, once an account is linked, so the demo never pays for it.

Two decisions a reader needs:

- Status is read with a raw GET_STATUS rather than the status trait's
  `refresh()`, because the library parses `error_code` into an enum that turns
  every code it does not know into 0 ("none"): a robot with a new fault would
  read as healthy. The raw ints go through the pure functions below
  (`activity_of`, `state_label`, `error_of`, `observe`, `command_request`),
  which the tests cover without a device. The trait is still used for what
  only it knows: the fan and water codes this model offers.
- A robot is polled only once the library has connected to it (its "ready"
  callback). Until then nothing is sent to it; it is reported unreachable at
  the slow cadence and the library keeps trying to connect in the background
  on its own backoff. An offline robot therefore costs no network traffic
  here, and a robot that is up gets its first read the moment it connects,
  without waiting for the others.

The library's cache (home data, each robot's LAN address and features) is
kept in a JSON file of our own, written atomically: its `FileCache` pickles,
and in 7.22.0 the cache holds a live trait object that cannot be pickled.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import pathlib
from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any

import aiohttp
from roborock.const import NO_MAP
from roborock.data import UserData, get_water_slide_mode_name
from roborock.data.v1.v1_code_mappings import RoborockErrorCode, RoborockStateCode
from roborock.devices.cache import Cache, CacheData
from roborock.devices.device import RoborockDevice
from roborock.devices.device_manager import DeviceManager, UserParams, create_device_manager
from roborock.devices.traits.v1 import PropertiesApi
from roborock.exceptions import (
    RoborockAccountDoesNotExist,
    RoborockConnectionException,
    RoborockException,
    RoborockInvalidCode,
    RoborockInvalidCredentials,
    RoborockInvalidEmail,
    RoborockInvalidStatus,
    RoborockInvalidUserAgreement,
    RoborockMissingParameters,
    RoborockNoResponseFromBaseURL,
    RoborockNoUserAgreement,
    RoborockRateLimit,
    RoborockTooFrequentCodeRequests,
    RoborockUnsupportedFeature,
)
from roborock.mqtt.session import MqttSessionException
from roborock.roborock_typing import RoborockCommand
from roborock.web_api import RoborockApiClient

from .model import COMMANDS, Backend, Command, ErrorInfo, Observation, RobotError, RobotInfo, Room, Sink

_LOGGER = logging.getLogger(__name__)

# --------------------------------------------------------------------------
# Cadence. Seconds throughout.
# --------------------------------------------------------------------------

#: Status while a robot is moving or paused mid-job, or someone is looking:
#: fast enough that the page feels live. A LAN read costs about 16 ms.
STATUS_ACTIVE_S = 5.0
#: Status while docked or idle and nobody is looking: a change here is rare
#: and nobody is waiting to see it.
STATUS_IDLE_S = 30.0
#: After a failed read. A dead robot costs a full timeout per try, so it is
#: not hammered; the Hub only needs a steady stream of failures to call it
#: offline.
STATUS_FAILED_S = 60.0
#: Our cap on one status read. The library waits 10 s per transport (LAN,
#: then cloud); stopping a little earlier means one stuck robot never holds
#: up anything else and the next try comes on our schedule, not the library's.
STATUS_TIMEOUT_S = 8.0
#: Reads right after a command: at once, then as the robot changes state
#: (it takes a moment to start moving, and a little longer to report it).
COMMAND_FOLLOW_UP_S = (0.0, 2.0, 6.0)
#: Cap on a command. Long enough for the library to try the LAN and then the
#: cloud (10 s each), so a robot whose LAN link died quietly is still reached.
COMMAND_TIMEOUT_S = 21.0

#: Map while the robot moves: the path is what a person watches. Each map is
#: about 430 kB through Roborock's cloud, so no more often than this.
MAP_ACTIVE_S = 10.0
#: Map otherwise: only something like a map edit in the Roborock app changes
#: it while the robot sits still.
MAP_IDLE_S = 15 * 60.0
#: Cap on one map read (it took 2.1 s on the real robot; the library gives up
#: at 10 s).
MAP_TIMEOUT_S = 12.0

#: After a failed map read (MQTT down, robot slow), try again this much later.
MAP_RETRY_S = 60.0

#: Rooms change only when someone edits the map in the Roborock app, or the
#: robot switches maps (caught at once through `current_map`).
ROOMS_S = 30 * 60.0
#: Rooms are read over the LAN, but the first read also asks the web API for
#: the room names.
ROOMS_TIMEOUT_S = 15.0
#: After a failed rooms read, try again this much later.
ROOMS_RETRY_S = 60.0

#: Retry creating the device manager while the network is down (expected at
#: boot): start quick, settle at a few minutes.
START_RETRY_MIN_S = 5.0
START_RETRY_MAX_S = 300.0

_MOVING = frozenset({"cleaning", "returning"})
_ACTIVE = frozenset({"cleaning", "returning", "paused"})

LOGIN_EXPIRED = "The Roborock login stopped working. Link the account again to keep controlling the robots."

# --------------------------------------------------------------------------
# Login
# --------------------------------------------------------------------------

#: Library exceptions that mean "what you typed was wrong", with what to do.
_LOGIN_INPUT_ERRORS: dict[type[RoborockException], str] = {
    RoborockInvalidEmail: "That email address is not valid.",
    RoborockMissingParameters: "Enter the email address of your Roborock account.",
    RoborockAccountDoesNotExist: "Roborock has no account with that email address.",
    RoborockNoResponseFromBaseURL: "Roborock has no account with that email address.",
    RoborockTooFrequentCodeRequests: "Too many codes were requested. Wait a few minutes and try again.",
    RoborockRateLimit: "Too many login attempts. Wait a few minutes and try again.",
    RoborockInvalidCode: "That code is not right. Check the latest email from Roborock and try again.",
    RoborockNoUserAgreement: "Accept Roborock's user agreement in the Roborock app, then try again.",
    RoborockInvalidUserAgreement: (
        "Accept Roborock's user agreement again in the Roborock app, then try again. "
        "A Mi Home account cannot be used here."
    ),
}


def _login_error(exc: RoborockException) -> RobotError:
    for kind, message in _LOGIN_INPUT_ERRORS.items():
        if isinstance(exc, kind):
            return RobotError("bad_request", message)
    raise exc


async def request_code(email: str) -> None:
    """Roborock emails a login code to that address."""
    client = RoborockApiClient(username=email)
    try:
        await client.request_code_v4()
    except RoborockException as exc:
        raise _login_error(exc) from exc


async def login_with_code(email: str, code: str) -> dict[str, Any]:
    """Log in with the emailed code. Returns the account the app stores:
    `{"email": str, "user_data": dict, "base_url": str}`, JSON-serialisable."""
    client = RoborockApiClient(username=email)
    try:
        user_data = await client.code_login_v4(code)
        base_url = await client.base_url
    except RoborockException as exc:
        raise _login_error(exc) from exc
    return {"email": email, "user_data": user_data.as_dict(), "base_url": base_url}


# --------------------------------------------------------------------------
# Status -> Observation (pure)
# --------------------------------------------------------------------------

_S = RoborockStateCode

#: Every RoborockStateCode member, by name so a renamed or added member fails
#: the "all mapped" test instead of falling through.
ACTIVITY_BY_STATE: dict[str, str] = {
    "unknown": "idle",
    "starting": "cleaning",
    "charger_disconnected": "idle",
    "idle": "idle",
    "remote_control_active": "cleaning",
    "cleaning": "cleaning",
    "returning_home": "returning",
    "manual_mode": "cleaning",
    "charging": "docked",
    "charging_problem": "error",
    "paused": "paused",
    "spot_cleaning": "cleaning",
    "error": "error",
    "shutting_down": "idle",
    "updating": "docked",
    "docking": "returning",
    "going_to_target": "cleaning",
    "zoned_cleaning": "cleaning",
    "segment_cleaning": "cleaning",
    "emptying_the_bin": "docked",
    "washing_the_mop": "docked",
    "washing_the_mop_2": "docked",
    "going_to_wash_the_mop": "returning",
    # A video call through the robot's camera: it is off the dock and driven.
    "in_call": "cleaning",
    "mapping": "cleaning",
    # An easter egg game; the robot drives about.
    "egg_attack": "cleaning",
    # Camera patrol: driving a route.
    "patrol": "cleaning",
    "attaching_the_mop": "docked",
    "detaching_the_mop": "docked",
    "charging_complete": "docked",
    "device_offline": "idle",
    "locked": "error",
    "air_drying_stopping": "docked",
    "robot_status_mopping": "cleaning",
    "clean_mop_cleaning": "cleaning",
    "clean_mop_mopping": "cleaning",
    "segment_mopping": "cleaning",
    "segment_clean_mop_cleaning": "cleaning",
    "segment_clean_mop_mopping": "cleaning",
    "zoned_mopping": "cleaning",
    "zoned_clean_mop_cleaning": "cleaning",
    "zoned_clean_mop_mopping": "cleaning",
    "back_to_dock_washing_duster": "returning",
}

#: Labels where the member's name does not read as a sentence.
_STATE_LABELS: dict[str, str] = {
    "remote_control_active": "Remote control",
    "in_call": "In a video call",
    "egg_attack": "Playing a game",
    "patrol": "Patrolling",
    "device_offline": "Offline",
    "air_drying_stopping": "Stopping the mop drying",
    "robot_status_mopping": "Mopping",
    "clean_mop_cleaning": "Vacuuming and mopping",
    "clean_mop_mopping": "Mopping",
    "segment_mopping": "Mopping rooms",
    "segment_clean_mop_cleaning": "Vacuuming and mopping rooms",
    "segment_clean_mop_mopping": "Mopping rooms",
    "zoned_mopping": "Mopping a zone",
    "zoned_clean_mop_cleaning": "Vacuuming and mopping a zone",
    "zoned_clean_mop_mopping": "Mopping a zone",
    "back_to_dock_washing_duster": "Returning to wash the mop",
    "segment_cleaning": "Cleaning rooms",
    "zoned_cleaning": "Cleaning a zone",
    "spot_cleaning": "Spot cleaning",
}

#: Labels where the member's name does not read as a sentence; the wording
#: follows the library's comments on the enum.
_ERROR_LABELS: dict[str, str] = {
    "dock": "Dock not connected to power",
    "strainer_error": "Filter is wet or blocked",
    "compass_error": "Strong magnetic field detected",
    "optical_flow_sensor_dirt": "Optical flow sensor dirty",
    "visual_sensor": "Camera error",
    "light_touch": "Wall sensor error",
    "vibrarise_jammed": "VibraRise jammed",
    "nogo_zone_detected": "No-go zone detected",
    "collect_dust_error_3": "Clean the auto-empty dock",
    "collect_dust_error_4": "Auto-empty dock voltage error",
    "mopping_roller_1": "Wash roller may be jammed",
    "mopping_roller_2": "Wash roller may be jammed",
    "mopping_roller_error_2": "Wash roller not lowered properly",
    "clear_water_box_hoare": "Check the clean water tank",
    "dirty_water_box_hoare": "Check the dirty water tank",
    "sink_strainer_hoare": "Reinstall the water filter",
    "clear_water_box_exception": "Clean water tank empty",
    "clear_brush_exception": "Check the water filter is installed correctly",
    "clear_brush_exception_2": "Positioning button error",
    "filter_screen_exception": "Clean the dock water filter",
    "up_water_exception": "Water supply error",
    "drain_water_exception": "Water drain error",
    "temperature_protection": "Temperature protection",
    "clean_carousel_exception": "Cleaning tray error",
    "clean_carousel_water_full": "Cleaning tray full of water",
    "water_carriage_drop": "Mop carriage dropped",
    "check_clean_carouse": "Check the cleaning tray",
}

_CHARGING = int(_S.charging)
_CHARGE_STATUS_CHARGING = 1  # RoborockChargeStatus.charging: on the dock's contacts
_UNKNOWN_STATES_WARNED: set[int] = set()


def _sentence(name: str) -> str:
    words = name.replace("_", " ").strip()
    return words[:1].upper() + words[1:]


def _state_member_or_none(code: int) -> RoborockStateCode | None:
    """The enum member for a raw state, or None for a state this library
    version does not know (newer firmware does report those)."""
    return _S(code) if code in _S._value2member_map_ else None


def activity_of(state: int, error_code: int, charge_status: int | None) -> str:
    """What the robot is doing, as one of model.ACTIVITIES.

    A state the library does not know reads as idle, as the library itself
    reads it (`RoborockStateCode.unknown`); the label still shows its code.
    `charge_status` is absent on firmware that does not report it.
    """
    if error_code != 0:
        return "error"
    member = _state_member_or_none(state)
    if member is None:
        if state not in _UNKNOWN_STATES_WARNED:
            _UNKNOWN_STATES_WARNED.add(state)
            _LOGGER.warning("Robot reported state %s, which python-roborock does not know", state)
        member = _S.unknown
    activity = ACTIVITY_BY_STATE[member.name]
    # A robot sitting on its dock's contacts with nothing to do reports "idle"
    # or "charger disconnected" (seen on a real S7 Max Ultra), yet it is home.
    if activity == "idle" and charge_status == _CHARGE_STATUS_CHARGING:
        return "docked"
    return activity


def state_label(state: int) -> str:
    """The robot's own state in sentence case: "Washing the mop"."""
    member = _state_member_or_none(state)
    if member is None:
        return f"State {state}"
    return _STATE_LABELS.get(member.name) or _sentence(member.display_name)


def error_of(error_code: int) -> ErrorInfo | None:
    """The robot's fault, or None when it reports none (code 0)."""
    if error_code == 0:
        return None
    # Not RoborockErrorCode(code): the library turns a code it does not know
    # into 0 ("none").
    if error_code not in RoborockErrorCode._value2member_map_:
        return ErrorInfo(error_code, f"Error {error_code}")
    member = RoborockErrorCode(error_code)
    return ErrorInfo(error_code, _ERROR_LABELS.get(member.name) or _sentence(member.name))


#: Modes the app does not offer: per-room settings ("custom" / "customized")
#: and the free 0-100 water flow, which takes a value of its own.
_HIDDEN_LEVELS = frozenset({"custom", "customized", "custom_water_flow"})


def level_codes(mapping: Mapping[int, str]) -> dict[str, int]:
    """name -> code for the levels the app offers, in the robot's order."""
    out: dict[str, int] = {}
    for code, name in mapping.items():
        name = name.lower()
        if name not in _HIDDEN_LEVELS and name not in out:
            out[name] = code
    return out


def observe(
    raw: Mapping[str, Any],
    *,
    fan_codes: Mapping[str, int],
    fan_name: str | None,
    water_codes: Mapping[str, int],
    water_name: str | None,
    local: bool,
) -> Observation:
    """One GET_STATUS answer as an Observation.

    `raw` is the robot's dict. `state`, `battery` and `error_code` are sent
    by every v1 robot; the rest vary by model and firmware, so a missing one
    means "not reported" and stays None. `fan_name` / `water_name` are the
    current levels' names (None when the robot reports no level); a current
    level the app does not offer (per-room "custom") reads as None.
    `water_codes` is empty for a robot that reports no water box mode.
    """
    state = int(raw["state"])
    battery = int(raw["battery"])
    error_code = int(raw["error_code"])
    charge_status = raw.get("charge_status")
    activity = activity_of(state, error_code, charge_status)
    in_cleaning = raw.get("in_cleaning")
    in_job = activity in ("cleaning", "paused") or bool(in_cleaning)
    clean_area = raw.get("clean_area")  # mm²
    clean_time = raw.get("clean_time")  # seconds
    fan = fan_name.lower() if fan_name is not None else None
    water = water_name.lower() if water_name is not None else None
    return Observation(
        activity=activity,
        state_label=state_label(state),
        battery=battery,
        # As the library's dock state has it: only the charging state means
        # current is flowing. A real S7 Max Ultra sat on its contacts as
        # "charger disconnected" (charge_status 1) with its battery falling.
        charging=state == _CHARGING and battery < 100,
        error=error_of(error_code),
        link="local" if local else "cloud",
        fan=fan if fan in fan_codes else None,
        fan_options=tuple(fan_codes),
        water=water if water in water_codes else None,
        water_options=tuple(water_codes),
        clean_area_m2=round(clean_area / 1_000_000, 1) if in_job and clean_area is not None else None,
        clean_seconds=int(clean_time) if in_job and clean_time is not None else None,
    )


# --------------------------------------------------------------------------
# Command -> RoborockCommand (pure)
# --------------------------------------------------------------------------

_SIMPLE: dict[str, RoborockCommand] = {
    "start": RoborockCommand.APP_START,
    "pause": RoborockCommand.APP_PAUSE,
    "stop": RoborockCommand.APP_STOP,
    "dock": RoborockCommand.APP_CHARGE,
    "locate": RoborockCommand.FIND_ME,
}

# RoborockInCleaning: a job that was interrupted, by kind.
_IN_CLEANING_ZONE = 2
_IN_CLEANING_SEGMENT = 3


def command_request(
    command: Command,
    *,
    in_cleaning: int | None,
    fan_codes: Mapping[str, int],
    water_codes: Mapping[str, int],
) -> tuple[RoborockCommand, list[Any] | None]:
    """The RoborockCommand and params for a Command, or RobotError.

    `in_cleaning` is the last status's interrupted-job kind (None before the
    first read), the codes the robot's offered levels (`level_codes`). Which
    rooms exist is the Hub's question: it has the map as well as the names,
    and has already checked them.
    """
    name = command.name
    if name not in COMMANDS:
        raise RobotError("bad_request", f"Unknown command {name!r}.")
    if name in _SIMPLE:
        return _SIMPLE[name], None
    if name == "resume":
        # As Home Assistant's roborock integration does: a paused room or
        # zone job resumes as itself; APP_START would clean the whole home.
        if in_cleaning == _IN_CLEANING_SEGMENT:
            return RoborockCommand.RESUME_SEGMENT_CLEAN, None
        if in_cleaning == _IN_CLEANING_ZONE:
            return RoborockCommand.RESUME_ZONED_CLEAN, None
        return RoborockCommand.APP_START, None
    if name == "clean_rooms":
        if not command.rooms:
            raise RobotError("bad_request", "Choose at least one room.")
        if not 1 <= command.repeat <= 3:
            raise RobotError("bad_request", "Repeat must be 1, 2 or 3.")
        return RoborockCommand.APP_SEGMENT_CLEAN, [{"segments": list(command.rooms), "repeat": command.repeat}]
    if name == "set_fan":
        return RoborockCommand.SET_CUSTOM_MODE, [_level_code("suction", command.value, fan_codes)]
    if name == "set_water":
        if not water_codes:
            raise RobotError("unsupported", "This robot cannot mop.")
        return RoborockCommand.SET_WATER_BOX_CUSTOM_MODE, [_level_code("water", command.value, water_codes)]
    raise AssertionError(f"command {name!r} is in COMMANDS but not mapped")


def _level_code(what: str, value: str | None, codes: Mapping[str, int]) -> int:
    if value is None or value not in codes:
        offered = ", ".join(codes) or "none known yet"
        raise RobotError("bad_request", f"{value!r} is not a {what} level this robot offers ({offered}).")
    return codes[value]


def _robot_answered(exc: RoborockException) -> bool:
    """Whether a failed command was refused by the robot, as opposed to not
    reaching it. A refusal is decoded from the robot's reply and raised
    as is; transport failures (timeout, MQTT down, LAN down) carry their
    cause or are connection errors."""
    if isinstance(exc, RoborockInvalidStatus):
        return True
    return exc.__cause__ is None and not isinstance(exc, RoborockConnectionException)


# --------------------------------------------------------------------------
# The backend
# --------------------------------------------------------------------------


class JsonFileCache(Cache):
    """python-roborock's cache, kept in memory and written to a JSON file on
    `flush()`.

    Not the library's FileCache: that pickles, and the data holds the
    DeviceFeaturesTrait itself (with its RPC channel), which pickle refuses.
    `as_dict()` keeps only the dataclass fields. The write goes to a
    temporary file that then replaces the old one, so a power cut leaves
    either the old cache or the new one, never half of one.
    """

    def __init__(self, path: pathlib.Path) -> None:
        self._path = path
        self._data: CacheData | None = None

    async def get(self) -> CacheData:
        if self._data is None:
            self._data = await asyncio.to_thread(self._load)
        return self._data

    async def set(self, value: CacheData) -> None:
        self._data = value

    async def flush(self) -> None:
        if self._data is None:
            return
        payload = json.dumps(self._data.as_dict(), separators=(",", ":")).encode()
        await asyncio.to_thread(self._write, payload)

    def _load(self) -> CacheData:
        try:
            text = self._path.read_bytes()
        except FileNotFoundError:
            return CacheData()  # the first start with this account
        data = CacheData.from_dict(json.loads(text))
        if data is None:
            raise ValueError(f"{self._path} does not hold a JSON object")
        return data

    def _write(self, payload: bytes) -> None:
        tmp = self._path.with_name(self._path.name + ".tmp")
        with open(tmp, "wb") as f:
            f.write(payload)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self._path)


@dataclass(slots=True)
class _Robot:
    device: RoborockDevice
    v1: PropertiesApi
    info: RobotInfo
    ready: bool = False  # the library has connected (features known)
    reachable: bool = False  # the last status read worked
    activity: str | None = None
    in_cleaning: int | None = None
    current_map: int | None = None
    fan_codes: dict[str, int] = field(default_factory=dict)
    water_codes: dict[str, int] = field(default_factory=dict)
    next_status: float = 0.0
    next_map: float = 0.0
    next_rooms: float = 0.0
    follow_ups: list[float] = field(default_factory=list)
    wake: asyncio.Event = field(default_factory=asyncio.Event)
    task: asyncio.Task[None] | None = None
    map_task: asyncio.Task[None] | None = None


class RoborockBackend(Backend):
    """Robots of one Roborock account."""

    def __init__(self, account: dict[str, Any], cache_file: pathlib.Path) -> None:
        self._params = UserParams(
            username=account["email"],
            user_data=UserData.from_dict(account["user_data"]),
            base_url=account["base_url"],
        )
        self._cache = JsonFileCache(cache_file)
        self._sink: Sink | None = None
        self._manager: DeviceManager | None = None
        self._robots: dict[str, _Robot] = {}
        self._watched = False
        self._login_refused = False
        self._main: asyncio.Task[None] | None = None
        self._flush: asyncio.Task[None] | None = None

    # -- Backend --------------------------------------------------------

    async def start(self, sink: Sink) -> None:
        self._sink = sink
        self._main = asyncio.create_task(self._connect(), name="roborock-connect")
        self._main.add_done_callback(_surface_crash)

    async def stop(self) -> None:
        tasks = [t for t in (self._main, self._flush) if t is not None]
        for robot in self._robots.values():
            tasks.extend(t for t in (robot.task, robot.map_task) if t is not None)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if self._manager is not None:
            await self._manager.close()
            self._manager = None
        await self._cache.flush()
        self._robots.clear()

    def set_watched(self, watched: bool) -> None:
        self._watched = watched
        if not watched:
            return
        now = asyncio.get_running_loop().time()
        for robot in self._robots.values():
            # A robot that is not answering keeps its back-off: waking it
            # would only add a timeout before the page shows it offline.
            if robot.reachable and robot.next_status > now:
                robot.next_status = now
                robot.wake.set()

    async def command(self, robot_id: str, command: Command) -> None:
        robot = self._robots.get(robot_id)
        if robot is None:
            raise RobotError("not_found", f"No robot {robot_id!r}.")
        method, params = command_request(
            command,
            in_cleaning=robot.in_cleaning,
            fan_codes=robot.fan_codes,
            water_codes=robot.water_codes,
        )
        name = robot.info.name
        if not robot.ready:
            raise RobotError("robot_unreachable", f"{name} is not connected.")
        try:
            async with asyncio.timeout(COMMAND_TIMEOUT_S):
                await robot.v1.command.send(method, params=params)
        except TimeoutError as exc:
            raise RobotError("robot_unreachable", f"{name} did not answer.") from exc
        except RoborockUnsupportedFeature as exc:
            raise RobotError("unsupported", f"{name} does not support that.") from exc
        except RoborockException as exc:
            if _robot_answered(exc):
                raise RobotError("rejected", f"{name} refused the command ({exc}).") from exc
            raise RobotError("robot_unreachable", f"{name} did not answer.") from exc
        now = asyncio.get_running_loop().time()
        robot.follow_ups = [now + d for d in COMMAND_FOLLOW_UP_S]
        robot.wake.set()

    # -- connecting -----------------------------------------------------

    async def _connect(self) -> None:
        delay = START_RETRY_MIN_S
        while True:
            try:
                manager = await create_device_manager(
                    self._params,
                    cache=self._cache,
                    ready_callback=self._on_ready,
                    mqtt_session_unauthorized_hook=self._on_unauthorized,
                    prefer_cache=True,
                )
            except RoborockInvalidCredentials:
                return  # the library has already called _on_unauthorized
            except RoborockException as exc:
                if not isinstance(exc.__cause__, aiohttp.ClientError | OSError | TimeoutError):
                    raise
                _LOGGER.info("Roborock unreachable (%s); retrying in %.0f s", exc, delay)
                await asyncio.sleep(delay)
                delay = min(delay * 2, START_RETRY_MAX_S)
                continue
            break
        self._manager = manager
        if not self._login_refused:
            self._sink_().account_problem(None)
        # Robots the library has not connected to yet (offline ones) are
        # reported now, so the Hub knows they exist.
        for device in await manager.get_devices():
            self._track(device)
        self._schedule_flush()

    def _on_ready(self, device: RoborockDevice) -> None:
        robot = self._track(device)
        if robot is None:
            return
        robot.ready = True
        # The trait's mappings come from the device features, known only now
        # (the trait caches them on first use).
        robot.fan_codes = level_codes(robot.v1.status.fan_speed_mapping)
        robot.next_status = asyncio.get_running_loop().time()
        robot.wake.set()
        self._schedule_flush()

    def _track(self, device: RoborockDevice) -> _Robot | None:
        """The robot for a device, reporting and starting it the first time.
        None for a device that is not a v1 vacuum."""
        v1 = device.v1_properties
        if v1 is None:
            return None
        robot = self._robots.get(device.duid)
        if robot is not None:
            return robot
        info = RobotInfo(
            id=device.duid,
            name=device.name,
            model=device.product.model,
            model_name=device.product.name,
        )
        robot = _Robot(device=device, v1=v1, info=info)
        self._robots[info.id] = robot
        self._sink_().robot_found(info)
        robot.task = asyncio.create_task(self._poll(robot), name=f"roborock-poll-{info.id}")
        robot.task.add_done_callback(_surface_crash)
        return robot

    def _on_unauthorized(self) -> None:
        # Called by the library's web API and MQTT session alike; both are on
        # the event loop.
        self._login_refused = True
        self._sink_().account_problem(LOGIN_EXPIRED)

    def _cloud_worked(self) -> None:
        if self._login_refused:
            self._login_refused = False
            self._sink_().account_problem(None)

    def _schedule_flush(self) -> None:
        # The cache only writes on flush(); network info and device features
        # land in it as robots connect, which is what makes the next start fast.
        if self._flush is None or self._flush.done():
            self._flush = asyncio.create_task(self._cache.flush(), name="roborock-cache-flush")
            self._flush.add_done_callback(_surface_crash)

    def _sink_(self) -> Sink:
        if self._sink is None:
            raise RuntimeError("RoborockBackend used before start()")
        return self._sink

    # -- polling --------------------------------------------------------

    async def _poll(self, robot: _Robot) -> None:
        loop = asyncio.get_running_loop()
        while True:
            now = loop.time()
            if robot.follow_ups and robot.follow_ups[0] <= now:
                robot.follow_ups = [t for t in robot.follow_ups if t > now]
                await self._read_status(robot)
            elif robot.next_status <= now:
                await self._read_status(robot)
            now = loop.time()
            if robot.reachable:
                if robot.next_rooms <= now:
                    await self._read_rooms(robot)
                # A map takes seconds through the cloud: fetched beside the
                # status reads, never in their way.
                if robot.next_map <= now and not _running(robot.map_task):
                    robot.next_map = now + (MAP_ACTIVE_S if robot.activity in _MOVING else MAP_IDLE_S)
                    robot.map_task = asyncio.create_task(self._read_map(robot), name=f"roborock-map-{robot.info.id}")
                    robot.map_task.add_done_callback(_surface_crash)
            due = [robot.next_status, *robot.follow_ups[:1]]
            if robot.reachable:
                due.append(robot.next_rooms)
                if not _running(robot.map_task):  # else its end wakes us
                    due.append(robot.next_map)
            robot.wake.clear()
            delay = min(due) - loop.time()
            if delay > 0:
                try:
                    async with asyncio.timeout(delay):
                        await robot.wake.wait()
                except TimeoutError:
                    pass  # the normal end of the sleep

    async def _read_status(self, robot: _Robot) -> None:
        sink = self._sink_()
        loop = asyncio.get_running_loop()
        if not robot.ready:
            robot.next_status = loop.time() + STATUS_FAILED_S
            sink.robot_unreachable(robot.info.id, f"{robot.info.name} has not connected since the app started.")
            return
        status = robot.v1.status
        try:
            async with asyncio.timeout(STATUS_TIMEOUT_S):
                response = await status.rpc_channel.send_command(RoborockCommand.GET_STATUS)
        except (RoborockException, TimeoutError) as exc:
            robot.reachable = False
            robot.next_status = loop.time() + STATUS_FAILED_S
            reason = "No answer." if isinstance(exc, TimeoutError) else str(exc)
            sink.robot_unreachable(robot.info.id, reason)
            return
        raw = response[0] if isinstance(response, list) else response
        if not isinstance(raw, dict):
            raise RoborockException(f"GET_STATUS answered {type(raw).__name__}, not a dict")
        water_mode = raw.get("water_box_mode")
        if water_mode is None:
            robot.water_codes = {}
            water_name = None
        else:
            robot.water_codes = level_codes(status.water_mode_mapping)
            if robot.v1.device_features.is_water_slide_mode_supported:
                water_name = get_water_slide_mode_name(water_mode)
            else:
                water_name = status.water_mode_mapping.get(water_mode)
        fan_power = raw.get("fan_power")
        local = robot.device.is_local_connected
        obs = observe(
            raw,
            fan_codes=robot.fan_codes,
            fan_name=status.fan_speed_mapping.get(fan_power) if fan_power is not None else None,
            water_codes=robot.water_codes,
            water_name=water_name,
            local=local,
        )
        now = loop.time()
        was_moving = robot.activity in _MOVING
        robot.reachable = True
        robot.activity = obs.activity
        robot.in_cleaning = raw.get("in_cleaning")
        current_map = _current_map(raw.get("map_status"))
        if current_map != robot.current_map:
            robot.next_rooms = now
        robot.current_map = current_map
        moving = obs.activity in _MOVING
        if moving and not was_moving:
            robot.next_map = min(robot.next_map, now + MAP_ACTIVE_S)
        if was_moving and not moving:
            robot.next_map = now  # once more, where it stopped
        fast = obs.activity in _ACTIVE or self._watched
        robot.next_status = now + (STATUS_ACTIVE_S if fast else STATUS_IDLE_S)
        sink.robot_status(robot.info.id, obs)
        if not local:
            self._cloud_worked()

    async def _read_rooms(self, robot: _Robot) -> None:
        loop = asyncio.get_running_loop()
        trait = robot.v1.rooms
        try:
            async with asyncio.timeout(ROOMS_TIMEOUT_S):
                await trait.refresh()
        except (RoborockException, TimeoutError) as exc:
            _LOGGER.info("Rooms of %s not read: %s", robot.info.id, exc or "timed out")
            robot.next_rooms = loop.time() + ROOMS_RETRY_S
            return
        rooms = [Room(id=r.segment_id, name=r.name) for r in trait.rooms or ()]
        robot.next_rooms = loop.time() + ROOMS_S
        self._sink_().robot_rooms(robot.info.id, rooms)

    async def _read_map(self, robot: _Robot) -> None:
        """One map. The poller set the next one when it started this; a status
        change while it ran may have moved that earlier, which `wake` lets the
        poller see."""
        try:
            async with asyncio.timeout(MAP_TIMEOUT_S):
                raw = await robot.v1.map_content.rpc_channel.send_command(RoborockCommand.GET_MAP_V1)
        except (RoborockException, TimeoutError) as exc:
            # Maps come through the cloud only. While MQTT is down there is
            # nothing to do but try again later; the status reads say whether
            # the robot itself is there.
            if isinstance(exc, RoborockException) and isinstance(exc.__cause__, MqttSessionException):
                _LOGGER.debug("Map of %s skipped: MQTT is down", robot.info.id)
            else:
                _LOGGER.info("Map of %s not read: %s", robot.info.id, exc or "timed out")
            retry = asyncio.get_running_loop().time() + MAP_RETRY_S
            robot.next_map = min(robot.next_map, retry)
            return
        finally:
            robot.wake.set()
        if not isinstance(raw, bytes):
            raise RoborockException(f"GET_MAP_V1 answered {type(raw).__name__}, not bytes")
        self._sink_().robot_map(robot.info.id, raw)
        self._cloud_worked()


def _running(task: asyncio.Task[Any] | None) -> bool:
    return task is not None and not task.done()


def _current_map(map_status: int | None) -> int | None:
    """The map flag in a status's `map_status` (as StatusV2.current_map)."""
    if map_status is None:
        return None
    flag = map_status >> 2
    return None if flag == NO_MAP else flag


def _surface_crash(task: asyncio.Task[Any]) -> None:
    """Re-raise a background task's failure into the loop's exception handler
    (logged with its traceback) instead of losing it until garbage collection."""
    if not task.cancelled() and task.exception() is not None:
        task.result()
