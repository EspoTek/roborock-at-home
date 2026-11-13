"""The contract every part of the app is written against.

Three layers, each knowing only the one below through these types:

    server.py  (HTTP, SSE, auth)            reads Hub
    hub.py     (robot state, attention,     implements Sink, drives a Backend
                persistence, history)
    Backend    (where robots come from)     RoborockBackend (real) / DemoBackend

A backend never decides what a reading *means*. It reports facts through the
Sink (a status read worked, a read failed, here are the rooms, here is a raw
map) and the Hub derives everything a person sees: online or not, what needs
attention, the last known position. That keeps the real and the simulated
robots on one code path.

Nothing here imports python-roborock, so the demo never loads it.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Protocol

# --------------------------------------------------------------------------
# Robots
# --------------------------------------------------------------------------

#: What a robot is doing, reduced to what the UI needs to tell apart.
#:   cleaning   moving and cleaning (whole home, rooms, zone, spot, remote)
#:   returning  on its way to the dock
#:   paused     stopped mid-job, off the dock, will resume
#:   docked     on the dock (charging, full, washing the mop, emptying)
#:   idle       off the dock, doing nothing
#:   error      the robot reports a fault
#: "offline" is never reported by a backend: the Hub derives it from failed
#: reads (see Sink.robot_unreachable).
ACTIVITIES = ("cleaning", "returning", "paused", "docked", "idle", "error")


@dataclass(frozen=True, slots=True)
class RobotInfo:
    """A robot's identity. `id` is stable across restarts and URL-safe."""

    id: str
    name: str
    model: str  # e.g. "roborock.vacuum.a65"
    model_name: str  # e.g. "S7 Max Ultra"


@dataclass(frozen=True, slots=True)
class ErrorInfo:
    code: int
    label: str  # sentence case, no full stop: "Main brush jammed"


@dataclass(frozen=True, slots=True)
class Observation:
    """One successful status read."""

    activity: str  # one of ACTIVITIES
    state_label: str  # the robot's own state, sentence case: "Washing the mop"
    battery: int  # 0-100
    charging: bool
    error: ErrorInfo | None
    link: str  # "local" (LAN) or "cloud"
    fan: str | None = None  # current suction level, one of fan_options
    fan_options: tuple[str, ...] = ()
    water: str | None = None  # current mop water level, one of water_options
    water_options: tuple[str, ...] = ()  # empty when the robot cannot mop
    clean_area_m2: float | None = None  # this job so far
    clean_seconds: int | None = None


@dataclass(frozen=True, slots=True)
class Room:
    id: int  # the robot's segment id, what clean_rooms takes
    name: str


# --------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------

#: name -> which fields it uses.
COMMANDS: dict[str, tuple[str, ...]] = {
    "start": (),  # clean the whole home
    "pause": (),
    "resume": (),
    "stop": (),
    "dock": (),  # return to the dock
    "locate": (),  # play the "I am here" sound
    "clean_rooms": ("rooms", "repeat"),
    "set_fan": ("value",),
    "set_water": ("value",),
}


@dataclass(frozen=True, slots=True)
class Command:
    name: str  # a key of COMMANDS
    rooms: tuple[int, ...] = ()
    repeat: int = 1  # 1-3 passes, clean_rooms only
    value: str | None = None  # set_fan / set_water: one of the robot's options


class RobotError(Exception):
    """A command or request that cannot be carried out.

    `code` is machine-readable and part of the HTTP API (docs: API.md):
        not_found          no such robot / schedule
        bad_request        malformed command, unknown room, value not offered
        robot_unreachable  the robot did not answer
        rejected           the robot answered and refused
        unsupported        this robot cannot do that
    """

    STATUS = {
        "not_found": 404,
        "bad_request": 400,
        "robot_unreachable": 503,
        "rejected": 409,
        "unsupported": 409,
    }

    def __init__(self, code: str, message: str) -> None:
        if code not in self.STATUS:
            raise ValueError(f"unknown RobotError code {code!r}")
        super().__init__(message)
        self.code = code
        self.message = message

    @property
    def http_status(self) -> int:
        return self.STATUS[self.code]


# --------------------------------------------------------------------------
# Backend <-> Hub
# --------------------------------------------------------------------------


class Sink(Protocol):
    """What a backend reports to. Implemented by the Hub.

    Every method is synchronous, cheap, and must be called on the event loop.
    """

    def robot_found(self, info: RobotInfo) -> None:
        """A robot exists (called once per robot, again if its name changes)."""

    def robot_status(self, robot_id: str, obs: Observation) -> None:
        """A status read succeeded."""

    def robot_unreachable(self, robot_id: str, reason: str) -> None:
        """A status read failed. Call it for every failed read; the Hub decides
        when that amounts to "offline"."""

    def robot_rooms(self, robot_id: str, rooms: list[Room]) -> None:
        """The rooms of the robot's current map."""

    def robot_map(self, robot_id: str, raw: bytes) -> None:
        """A raw map blob in Roborock's "rr" format, as the robot sent it
        (already gunzipped). The Hub parses it with rrmap.parse()."""

    def account_problem(self, message: str | None) -> None:
        """The account behind this backend stopped working (login expired),
        or works again (None)."""


class Backend(ABC):
    """A source of robots."""

    @abstractmethod
    async def start(self, sink: Sink) -> None:
        """Begin discovering and polling. Must return promptly: connecting to
        robots happens in background tasks, never in here."""

    @abstractmethod
    async def stop(self) -> None:
        """Cancel every task and close every connection."""

    @abstractmethod
    async def command(self, robot_id: str, command: Command) -> None:
        """Carry out a command, or raise RobotError. Returns once the robot
        has accepted it. The backend then re-reads the status on its own, so
        the change reaches the Sink without the caller polling."""

    @abstractmethod
    def set_watched(self, watched: bool) -> None:
        """Whether anyone is looking (a UI is connected). Unwatched and with
        every robot docked, a backend should slow right down."""


# --------------------------------------------------------------------------
# Maps
# --------------------------------------------------------------------------

#: Classes of a pixel in the index image (MapFrame.index). A room pixel is
#: ROOM_BASE + the room's segment id (segment ids are 0-31).
PX_OUTSIDE = 0
PX_WALL = 1
PX_FLOOR = 2  # floor that belongs to no room
# Space the laser saw but the robot never drove: beyond a wall, through a
# window, under furniture. A real map has large patches of it; drawn as wall
# they bury the floor plan, so it is a class of its own to draw faintly.
PX_SEEN = 3
ROOM_BASE = 32

#: Roborock maps are 50 mm per pixel.
MM_PER_PX = 50


@dataclass(frozen=True, slots=True)
class RoomGeom:
    """Where a room is, in index-image pixels."""

    # Where a label goes: always on a pixel of the room, on the row nearest
    # its centre of mass, in the middle of the widest run of that row.
    cx: float
    cy: float
    x0: int  # bounding box, inclusive
    y0: int
    x1: int
    y1: int
    area_px: int


@dataclass(frozen=True, slots=True)
class MapFrame:
    """One parsed map.

    All coordinates are in pixels of the index image: origin top-left, x
    right, y down, floats (a robot is rarely on a pixel centre). Pixel (i, j)
    covers [i, i+1) x [j, j+1), so drawing the index at scale s puts a point
    at (x * s, y * s) and the pixel under a point is (floor(x), floor(y)).
    """

    width: int
    height: int
    index: bytes  # width * height bytes, row-major, classes above
    digest: str  # 8 hex chars identifying `index` + geometry; the map "rev"
    rooms: dict[int, RoomGeom] = field(default_factory=dict)  # by segment id
    robot: tuple[float, float] | None = None
    robot_angle: int | None = None  # degrees, as the robot reports it
    robot_room: int | None = None  # segment id under the robot, if any
    charger: tuple[float, float] | None = None
    path: tuple[float, ...] = ()  # x0, y0, x1, y1, ... of the current job
    no_go: tuple[tuple[float, ...], ...] = ()  # each: 4 corners, 8 floats
    walls: tuple[tuple[float, float, float, float], ...] = ()  # virtual walls
