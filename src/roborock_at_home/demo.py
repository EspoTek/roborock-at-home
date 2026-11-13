"""Simulated robots: what `roborock-at-home serve --demo` runs on.

Four robots on floor plans generated here, reporting through exactly the Sink
calls a real backend makes (status reads, rooms, raw rr maps from
`rrmap.encode`), so the Hub, the API and the page cannot tell them apart.

Movement never needs a grid search. Every room is a rectangle, and each keeps
an inner rectangle (the room less the robot's radius, the strip behind a
virtual wall and the depth of a no-go area) that is convex, so a straight line
between two points inside it stays on the floor. A route is then the rooms to
pass through (a breadth-first search over the doors) and, per door, a point on
each side of it, which are joined by a straight line through the doorway.
"""

from __future__ import annotations

import asyncio
import contextlib
import math
from collections import deque
from dataclasses import dataclass, field

from . import rrmap
from .model import (
    Backend,
    Command,
    ErrorInfo,
    Observation,
    RobotError,
    RobotInfo,
    Room,
    Sink,
)

WATCHED_TICK_S = 1.0
UNWATCHED_TICK_S = 5.0

SPEED_PX_PER_S = 6.0  # 30 cm/s at 50 mm a pixel
LANE_PX = 6  # 30 cm between lanes
RADIUS_PX = 3  # how close the robot's centre comes to a wall
DOOR_PX = 16  # 80 cm doorways
CLEAN_M2_PER_PX = 0.05 * 0.3  # one pixel driven along a lane, 30 cm wide
DRAIN_S_PER_PCT = 40.0
CHARGE_S_PER_PCT = 12.0
DEAD_AFTER_S = 20.0  # robot 4's battery gives out
OUTSIDE_PX = 8  # outside border round the house in the map image

FAN_OPTIONS = ("quiet", "balanced", "turbo", "max")
WATER_OPTIONS = ("off", "mild", "standard", "intense")
FIRST_ROOM_ID = 16  # Roborock numbers segments from 16 on the robots we have seen

_WALL = 0x01


def _room_px(room_id: int) -> int:
    return (room_id << 3) | 0x07


# --------------------------------------------------------------------------
# Floor plans
# --------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class _PlanSpec:
    """A floor plan in pixels: rooms as (name, x0, y0, x1, y1) rectangles on
    wall centre lines that tile the house, doors as pairs of room names."""

    size: tuple[int, int]
    rooms: tuple[tuple[str, int, int, int, int], ...]
    doors: tuple[tuple[str, str], ...]
    dock: tuple[str, str, float]  # room, side, where along that side (0-1)
    no_go: tuple[str, str, float, float, int]  # room, side, span along it (0-1), depth
    wall: tuple[str, str, int]  # virtual wall: room, side, distance from that side


_SPECS = (
    _PlanSpec(  # Downstairs
        size=(280, 220),
        rooms=(
            ("Living room", 0, 0, 150, 110),
            ("Kitchen", 150, 0, 280, 110),
            ("Hall", 0, 110, 280, 150),
            ("Study", 0, 150, 90, 220),
            ("Bathroom", 90, 150, 150, 220),
            ("Laundry", 150, 150, 200, 220),
            ("Guest room", 200, 150, 280, 220),
        ),
        doors=(
            ("Living room", "Kitchen"),
            ("Living room", "Hall"),
            ("Kitchen", "Hall"),
            ("Hall", "Study"),
            ("Hall", "Bathroom"),
            ("Hall", "Laundry"),
            ("Hall", "Guest room"),
        ),
        dock=("Living room", "top", 0.25),
        no_go=("Kitchen", "right", 0.0, 0.4, 16),
        wall=("Guest room", "bottom", 10),
    ),
    _PlanSpec(  # Upstairs
        size=(270, 220),
        rooms=(
            ("Main bedroom", 0, 0, 120, 100),
            ("Ensuite", 120, 0, 180, 100),
            ("Bedroom", 180, 0, 270, 100),
            ("Landing", 0, 100, 270, 140),
            ("Kids room", 0, 140, 110, 220),
            ("Study", 110, 140, 190, 220),
            ("Toilet", 190, 140, 225, 220),
            ("Storage", 225, 140, 270, 220),
        ),
        doors=(
            ("Main bedroom", "Ensuite"),
            ("Main bedroom", "Landing"),
            ("Bedroom", "Landing"),
            ("Landing", "Kids room"),
            ("Landing", "Study"),
            ("Landing", "Toilet"),
            ("Landing", "Storage"),
        ),
        dock=("Landing", "left", 0.5),
        no_go=("Kids room", "bottom", 0.55, 1.0, 14),
        wall=("Main bedroom", "left", 8),
    ),
    _PlanSpec(  # Garage
        size=(260, 200),
        rooms=(
            ("Garage", 0, 0, 150, 200),
            ("Workshop", 150, 0, 260, 90),
            ("Mudroom", 150, 90, 210, 140),
            ("Storage", 210, 90, 260, 140),
            ("Gym", 150, 140, 260, 200),
        ),
        doors=(
            ("Garage", "Workshop"),
            ("Garage", "Mudroom"),
            ("Mudroom", "Storage"),
            ("Mudroom", "Gym"),
        ),
        dock=("Garage", "left", 0.8),
        no_go=("Workshop", "top", 0.0, 0.4, 14),
        wall=("Gym", "bottom", 10),
    ),
    _PlanSpec(  # Studio
        size=(240, 200),
        rooms=(
            ("Living room", 0, 0, 140, 120),
            ("Kitchen", 140, 0, 240, 80),
            ("Dining room", 140, 80, 240, 120),
            ("Bedroom", 0, 120, 110, 200),
            ("Bathroom", 110, 120, 170, 200),
            ("Entry", 170, 120, 240, 200),
        ),
        doors=(
            ("Living room", "Kitchen"),
            ("Living room", "Dining room"),
            ("Kitchen", "Dining room"),
            ("Living room", "Bedroom"),
            ("Dining room", "Entry"),
            ("Entry", "Bathroom"),
        ),
        dock=("Entry", "right", 0.5),
        no_go=("Kitchen", "top", 0.6, 1.0, 12),
        wall=("Bathroom", "bottom", 8),
    ),
)


@dataclass(frozen=True, slots=True)
class _Rect:
    """Where the robot's centre may be, in continuous image pixels."""

    x0: float
    y0: float
    x1: float
    y1: float

    def contains(self, x: float, y: float) -> bool:
        return self.x0 <= x <= self.x1 and self.y0 <= y <= self.y1


@dataclass(frozen=True, slots=True)
class _Wp:
    """A waypoint: where, in which room, and whether driving to it cleans."""

    x: float
    y: float
    room: int
    cleaning: bool


@dataclass(frozen=True, slots=True)
class _Geo:
    """Where the map image sits in the robot's mm coordinates."""

    height: int
    top: int
    left: int

    def to_mm(self, x: float, y: float) -> tuple[int, int]:
        """Image pixels (pixel i covers [i, i+1)) to the robot's mm; the inverse
        of what rrmap.parse does before it crops."""
        return (
            round((x - 0.5 + self.left) * 50),
            round(((self.height - 0.5) - y + self.top) * 50),
        )


@dataclass(frozen=True, slots=True)
class _Plan:
    rooms: list[Room]
    inner: dict[int, _Rect]
    neighbours: dict[int, list[int]]
    crossings: dict[tuple[int, int], tuple[_Wp, _Wp]]  # (from, to) -> point each side of the door
    dock_room: int
    rest: tuple[float, float]  # where the robot sits on the dock
    geo: _Geo
    image_block: bytes  # encoded once: only the small blocks change as a robot moves
    charger_mm: tuple[int, int, int]
    no_go_mm: tuple[tuple[int, ...], ...]
    walls_mm: tuple[tuple[int, ...], ...]


def _build_plan(spec: _PlanSpec) -> _Plan:
    hw, hh = spec.size
    w, h = hw + 2 * OUTSIDE_PX, hh + 2 * OUTSIDE_PX
    o = OUTSIDE_PX
    rows = [bytearray(w) for _ in range(h)]

    def fill(x0: int, y0: int, x1: int, y1: int, v: int) -> None:
        for y in range(y0, y1 + 1):
            rows[y][x0 : x1 + 1] = bytes((v,)) * (x1 - x0 + 1)

    fill(o - 2, o - 2, o + hw + 1, o + hh + 1, _WALL)
    ids = {name: FIRST_ROOM_ID + i for i, (name, *_r) in enumerate(spec.rooms)}
    interior: dict[int, tuple[int, int, int, int]] = {}  # inclusive pixel box of each room's floor
    for name, x0, y0, x1, y1 in spec.rooms:
        box = (o + x0 + 1, o + y0 + 1, o + x1 - 2, o + y1 - 2)
        interior[ids[name]] = box
        fill(*box, _room_px(ids[name]))

    inner = {
        rid: _Rect(bx0 + RADIUS_PX + 0.5, by0 + RADIUS_PX + 0.5, bx1 - RADIUS_PX + 0.5, by1 - RADIUS_PX + 0.5)
        for rid, (bx0, by0, bx1, by1) in interior.items()
    }

    def shrink(rid: int, side: str, by: float) -> None:
        r = inner[rid]
        inner[rid] = _Rect(
            r.x0 + by if side == "left" else r.x0,
            r.y0 + by if side == "top" else r.y0,
            r.x1 - by if side == "right" else r.x1,
            r.y1 - by if side == "bottom" else r.y1,
        )

    def side_line(rid: int, side: str, depth: float) -> tuple[float, float, float, float]:
        """The line `depth` pixels in from a side of a room's floor, end to end."""
        bx0, by0, bx1, by1 = interior[rid]
        return {
            "top": (bx0, by0 + depth, bx1 + 1, by0 + depth),
            "bottom": (bx0, by1 + 1 - depth, bx1 + 1, by1 + 1 - depth),
            "left": (bx0 + depth, by0, bx0 + depth, by1 + 1),
            "right": (bx1 + 1 - depth, by0, bx1 + 1 - depth, by1 + 1),
        }[side]

    # Doors: a gap in the shared wall, and a point on each side in line with it.
    neighbours: dict[int, list[int]] = {rid: [] for rid in interior}
    crossings: dict[tuple[int, int], tuple[_Wp, _Wp]] = {}
    door_sides: set[tuple[int, str]] = set()
    for na, nb in spec.doors:
        a, b = ids[na], ids[nb]
        ra, rb = inner[a], inner[b]
        if interior[a][2] < interior[b][0]:  # a left of b
            lo, hi = max(ra.y0, rb.y0), min(ra.y1, rb.y1)
            yc = math.floor((lo + hi) / 2)
            fill(interior[a][2] + 1, yc - DOOR_PX // 2, interior[b][0] - 1, yc + DOOR_PX // 2 - 1, _room_px(a))
            pa, pb = (ra.x1, yc + 0.5), (rb.x0, yc + 0.5)
            door_sides |= {(a, "right"), (b, "left")}
        elif interior[b][2] < interior[a][0]:  # b left of a
            lo, hi = max(ra.y0, rb.y0), min(ra.y1, rb.y1)
            yc = math.floor((lo + hi) / 2)
            fill(interior[b][2] + 1, yc - DOOR_PX // 2, interior[a][0] - 1, yc + DOOR_PX // 2 - 1, _room_px(a))
            pa, pb = (ra.x0, yc + 0.5), (rb.x1, yc + 0.5)
            door_sides |= {(a, "left"), (b, "right")}
        elif interior[a][3] < interior[b][1]:  # a above b
            lo, hi = max(ra.x0, rb.x0), min(ra.x1, rb.x1)
            xc = math.floor((lo + hi) / 2)
            fill(xc - DOOR_PX // 2, interior[a][3] + 1, xc + DOOR_PX // 2 - 1, interior[b][1] - 1, _room_px(a))
            pa, pb = (xc + 0.5, ra.y1), (xc + 0.5, rb.y0)
            door_sides |= {(a, "bottom"), (b, "top")}
        else:  # a below b
            lo, hi = max(ra.x0, rb.x0), min(ra.x1, rb.x1)
            xc = math.floor((lo + hi) / 2)
            fill(xc - DOOR_PX // 2, interior[b][3] + 1, xc + DOOR_PX // 2 - 1, interior[a][1] - 1, _room_px(a))
            pa, pb = (xc + 0.5, ra.y0), (xc + 0.5, rb.y1)
            door_sides |= {(a, "top"), (b, "bottom")}
        if hi - lo < DOOR_PX:
            raise ValueError(f"demo plan: {na} and {nb} share too little wall for a door")
        neighbours[a].append(b)
        neighbours[b].append(a)
        wa, wb = _Wp(*pa, a, False), _Wp(*pb, b, False)
        crossings[(a, b)] = (wa, wb)
        crossings[(b, a)] = (wb, wa)

    # The no-go area and the virtual wall keep the robot out of a strip along
    # one side of a room, so they must not sit in front of a door.
    room, side, s0, s1, depth = spec.no_go
    rid = ids[room]
    if (rid, side) in door_sides:
        raise ValueError(f"demo plan: the no-go area in {room} covers a door")
    ax0, ay0, ax1, ay1 = side_line(rid, side, 0)
    bx0, by0, bx1, by1 = side_line(rid, side, depth)
    if side in ("top", "bottom"):
        xa, xb = ax0 + (ax1 - ax0) * s0, ax0 + (ax1 - ax0) * s1
        no_go_px = (xa, ay0, xb, ay0, xb, by0, xa, by0)
    else:
        ya, yb = ay0 + (ay1 - ay0) * s0, ay0 + (ay1 - ay0) * s1
        no_go_px = (ax0, ya, ax0, yb, bx0, yb, bx0, ya)
    shrink(rid, side, depth)

    room, side, depth = spec.wall
    rid = ids[room]
    if (rid, side) in door_sides:
        raise ValueError(f"demo plan: the virtual wall in {room} blocks a door")
    wall_px = side_line(rid, side, depth)
    shrink(rid, side, depth)

    room, side, along = spec.dock
    dock_room = ids[room]
    bx0, by0, bx1, by1 = interior[dock_room]
    r = inner[dock_room]
    if side in ("top", "bottom"):
        cx = bx0 + round((bx1 - bx0) * along)
        charger_px = (cx + 0.5, (by0 if side == "top" else by1) + 0.5)
        rest = (cx + 0.5, r.y0 if side == "top" else r.y1)
    else:
        cy = by0 + round((by1 - by0) * along)
        charger_px = ((bx0 if side == "left" else bx1) + 0.5, cy + 0.5)
        rest = (r.x0 if side == "left" else r.x1, cy + 0.5)
    if (dock_room, side) in door_sides:
        raise ValueError(f"demo plan: the dock in {room} stands in a doorway")

    for rid, rect in inner.items():
        if rect.x1 - rect.x0 < LANE_PX or rect.y1 - rect.y0 < LANE_PX:
            raise ValueError(f"demo plan: room {rid} is too small to clean")
    for wa, wb in crossings.values():
        if not inner[wa.room].contains(wa.x, wa.y):
            raise ValueError(f"demo plan: a door of room {wa.room} is outside where the robot can go")

    geo = _Geo(height=h, top=510 - h // 2, left=510 - w // 2)  # near where real maps put the origin
    pixels = b"".join(bytes(row) for row in reversed(rows))  # rr images start at the bottom row
    return _Plan(
        rooms=[Room(ids[name], name) for name, *_r in spec.rooms],
        inner=inner,
        neighbours=neighbours,
        crossings=crossings,
        dock_room=dock_room,
        rest=rest,
        geo=geo,
        image_block=rrmap.encode_image(pixels, top=geo.top, left=geo.left, width=w, height=h),
        charger_mm=(*geo.to_mm(*charger_px), 0),
        no_go_mm=(tuple(v for i in range(0, 8, 2) for v in geo.to_mm(no_go_px[i], no_go_px[i + 1])),),
        walls_mm=((*geo.to_mm(wall_px[0], wall_px[1]), *geo.to_mm(wall_px[2], wall_px[3])),),
    )


# --------------------------------------------------------------------------
# Routes
# --------------------------------------------------------------------------


def _hops(plan: _Plan, start: int) -> dict[int, int | None]:
    """Breadth-first over the doors: room -> the room it is reached from."""
    came: dict[int, int | None] = {start: None}
    queue = deque([start])
    while queue:
        r = queue.popleft()
        for n in plan.neighbours[r]:
            if n not in came:
                came[n] = r
                queue.append(n)
    return came


def _chain(came: dict[int, int | None], dst: int) -> list[int]:
    """The rooms from the search's start to `dst`, both included."""
    if dst not in came:
        raise ValueError(f"demo plan: room {dst} cannot be reached")
    chain = [dst]
    while (prev := came[chain[-1]]) is not None:
        chain.append(prev)
    chain.reverse()
    return chain


def _travel(plan: _Plan, src: int, dst: int) -> list[_Wp]:
    """Waypoints from anywhere in room `src` to the doorway into `dst`."""
    chain = _chain(_hops(plan, src), dst)
    out: list[_Wp] = []
    for a, b in zip(chain, chain[1:]):
        out.extend(plan.crossings[(a, b)])
    return out


def _lanes(plan: _Plan, rid: int, x: float, y: float) -> list[_Wp]:
    """Back and forth across the room, starting from the corner nearest (x, y)."""
    r = plan.inner[rid]
    n = int((r.y1 - r.y0) // LANE_PX)
    ys = [r.y0 + k * LANE_PX for k in range(n + 1)]
    if r.y1 - ys[-1] > 1:
        ys.append(r.y1)
    if abs(y - r.y1) < abs(y - r.y0):
        ys.reverse()
    left_first = abs(x - r.x0) <= abs(x - r.x1)
    out: list[_Wp] = []
    for k, ly in enumerate(ys):
        a, b = (r.x0, r.x1) if (k % 2 == 0) == left_first else (r.x1, r.x0)
        out.append(_Wp(a, ly, rid, True))
        out.append(_Wp(b, ly, rid, True))
    return out


def _job(plan: _Plan, src: int, x: float, y: float, rooms: list[int], repeat: int) -> tuple[list[_Wp], int]:
    """Clean `rooms` (nearest first), each `repeat` times, then go home.
    Returns the waypoints and the index where the way home starts."""
    route: list[_Wp] = []
    todo = list(rooms)
    here = src
    while todo:
        came = _hops(plan, here)
        nxt = min(todo, key=lambda rid: len(_chain(came, rid)))
        todo.remove(nxt)
        route += _travel(plan, here, nxt)
        for _ in range(repeat):
            px, py = (route[-1].x, route[-1].y) if route else (x, y)
            route += _lanes(plan, nxt, px, py)
        here = nxt
    home = len(route)
    route += _home(plan, here)
    return route, home


def _home(plan: _Plan, src: int) -> list[_Wp]:
    return [*_travel(plan, src, plan.dock_room), _Wp(*plan.rest, plan.dock_room, False)]


# --------------------------------------------------------------------------
# Robots
# --------------------------------------------------------------------------


@dataclass(slots=True)
class _Robot:
    info: RobotInfo
    plan: _Plan
    link: str
    water_options: tuple[str, ...]
    x: float = 0.0
    y: float = 0.0
    angle: int = 0
    room: int = 0  # the room the robot is in, or whose side of a door it last reached
    mode: str = "docked"  # docked, idle, running, paused, error
    whole_home: bool = True
    route: list[_Wp] = field(default_factory=list)
    next_wp: int = 0
    home_from: int = 0  # route index where the way back to the dock starts
    error: ErrorInfo | None = None
    battery: float = 100.0
    fan: str = "balanced"
    water: str | None = "standard"
    clean_m2: float = 0.0
    clean_s: float = 0.0
    path: list[int] = field(default_factory=list)  # mm, x0, y0, x1, y1, ...
    dead_at: float | None = None  # sim time it stops answering for good
    sequence: int = 0
    map_dirty: bool = True

    @property
    def activity(self) -> str:
        if self.mode == "running":
            return "cleaning" if self.next_wp < self.home_from else "returning"
        return self.mode

    @property
    def moving(self) -> bool:
        return self.mode == "running"

    def dock_at_rest(self) -> None:
        self.x, self.y = self.plan.rest
        self.room = self.plan.dock_room
        self.mode = "docked"

    # -- jobs ---------------------------------------------------------------

    def _start_point(self) -> tuple[list[_Wp], int, float, float]:
        """Where a new route begins: here, or across the doorway first when
        the robot is in the middle of one."""
        if self.next_wp < len(self.route) and self.route[self.next_wp].room != self.room:
            wp = self.route[self.next_wp]
            return [wp], wp.room, wp.x, wp.y
        return [], self.room, self.x, self.y

    def begin_job(self, rooms: list[int], repeat: int, whole_home: bool) -> None:
        lead, room, x, y = self._start_point()
        route, home = _job(self.plan, room, x, y, rooms, repeat)
        self.route, self.next_wp, self.home_from = lead + route, 0, len(lead) + home
        self.whole_home = whole_home
        self.mode, self.error = "running", None
        self.clean_m2 = self.clean_s = 0.0
        self.path = list(self.plan.geo.to_mm(self.x, self.y))
        self.map_dirty = True

    def go_home(self) -> None:
        lead, room, _x, _y = self._start_point()
        self.route = lead + _home(self.plan, room)
        self.next_wp = self.home_from = 0
        self.mode, self.error = "running", None

    # -- time ---------------------------------------------------------------

    def advance(self, dt: float) -> None:
        if self.mode == "docked":
            self.battery = min(100.0, self.battery + dt / CHARGE_S_PER_PCT)
            return
        if self.mode != "running":
            return
        if self.next_wp < self.home_from:
            self.clean_s += dt
        self.battery = max(0.0, self.battery - dt / DRAIN_S_PER_PCT)
        budget = SPEED_PX_PER_S * dt
        while budget > 0 and self.next_wp < len(self.route):
            wp = self.route[self.next_wp]
            dx, dy = wp.x - self.x, wp.y - self.y
            d = math.hypot(dx, dy)
            if d > 0:
                self.angle = round(math.degrees(math.atan2(-dy, dx)))
            step = min(d, budget)
            if wp.cleaning and self.next_wp > 0 and self.route[self.next_wp - 1].cleaning:
                self.clean_m2 += step * CLEAN_M2_PER_PX
            budget -= step
            if step == d:
                self.x, self.y, self.room = wp.x, wp.y, wp.room
                self.next_wp += 1
                self.path.extend(self.plan.geo.to_mm(self.x, self.y))
            else:
                self.x += dx * step / d
                self.y += dy * step / d
        if self.next_wp >= len(self.route):
            self.mode = "docked"
            self.route, self.next_wp, self.home_from = [], 0, 0
        elif self.path[-2:] != list(self.plan.geo.to_mm(self.x, self.y)):
            self.path.extend(self.plan.geo.to_mm(self.x, self.y))
        self.map_dirty = True

    # -- reports ------------------------------------------------------------

    def observation(self) -> Observation:
        activity = self.activity
        if activity == "docked":
            label = "Charging" if self.battery < 100 else "Fully charged"
        else:
            label = {
                "cleaning": "Cleaning" if self.whole_home else "Cleaning rooms",
                "returning": "Returning to the dock",
                "paused": "Paused",
                "idle": "Battery low" if self.battery < 10 else "Idle",
                "error": "Error",
            }[activity]
        in_job = self.clean_s > 0 or activity in ("cleaning", "paused", "error")
        return Observation(
            activity=activity,
            state_label=label,
            battery=math.ceil(self.battery),
            charging=activity == "docked" and self.battery < 100,
            error=self.error,
            link=self.link,
            fan=self.fan,
            fan_options=FAN_OPTIONS,
            water=self.water,
            water_options=self.water_options,
            clean_area_m2=round(self.clean_m2, 1) if in_job else None,
            clean_seconds=round(self.clean_s) if in_job else None,
        )

    def encoded_map(self) -> bytes:
        self.sequence += 1
        mx, my = self.plan.geo.to_mm(self.x, self.y)
        return rrmap.encode(
            self.plan.image_block,
            charger=self.plan.charger_mm,
            robot=(mx, my, self.angle),
            path=self.path,
            no_go=self.plan.no_go_mm,
            walls=self.plan.walls_mm,
            sequence=self.sequence,
        )


def _fast_forward(robot: _Robot, until_wp: int) -> None:
    """Run a job without reporting until it reaches a waypoint, to set the
    scene the app opens on."""
    while robot.next_wp < until_wp:
        robot.advance(1.0)


def _halfway_along(robot: _Robot) -> None:
    """Drive half of the segment ahead: out in the room, not at a lane end."""
    wp = robot.route[robot.next_wp]
    robot.advance(math.hypot(wp.x - robot.x, wp.y - robot.y) / 2 / SPEED_PX_PER_S)


def _make_robots() -> list[_Robot]:
    plans = [_build_plan(s) for s in _SPECS]
    models = (
        ("downstairs", "Downstairs", "roborock.vacuum.a65", "S7 Max Ultra", "local", WATER_OPTIONS),
        ("upstairs", "Upstairs", "roborock.vacuum.a70", "S8 Pro Ultra", "local", WATER_OPTIONS),
        ("garage", "Garage", "roborock.vacuum.a38", "Q7 Max", "cloud", WATER_OPTIONS),
        ("studio", "Studio", "roborock.vacuum.a34", "Q5", "local", ()),
    )
    robots = []
    for plan, (rid, name, model, model_name, link, water) in zip(plans, models):
        r = _Robot(
            info=RobotInfo(rid, name, model, model_name),
            plan=plan,
            link=link,
            water_options=water,
            water="standard" if water else None,
        )
        r.dock_at_rest()
        robots.append(r)
    one, two, three, four = robots
    every = lambda r: [room.id for room in r.plan.rooms]  # noqa: E731

    # 1: on the dock, charging.
    one.battery = 90.0

    # 2: part of the way through cleaning the whole home.
    two.battery = 96.0
    two.begin_job(every(two), 1, whole_home=True)
    _fast_forward(two, int(len(two.route) * 0.45))

    # 3: stuck in the middle of its second room with a jammed brush.
    three.begin_job(every(three), 1, whole_home=True)
    cleaning = [i for i, wp in enumerate(three.route) if wp.cleaning]
    rooms_in_order = list(dict.fromkeys(three.route[i].room for i in cleaning))
    lanes = [i for i in cleaning if three.route[i].room == rooms_in_order[1]]
    # Lane waypoints come in (start, end) pairs: stop just past a start.
    _fast_forward(three, lanes[(len(lanes) // 2) & ~1] + 1)
    _halfway_along(three)
    three.mode, three.error, three.battery = "error", ErrorInfo(5, "Main brush jammed"), 40.0

    # 4: gave up cleaning far from the dock, battery nearly flat.
    hops = _hops(four.plan, four.plan.dock_room)
    far = max(every(four), key=lambda rid: len(_chain(hops, rid)))
    four.begin_job([far], 1, whole_home=False)
    lanes = [i for i, wp in enumerate(four.route) if wp.cleaning]
    _fast_forward(four, lanes[(len(lanes) // 3) & ~1] + 1)
    _halfway_along(four)
    four.mode, four.battery = "idle", 4.0
    four.route, four.next_wp, four.home_from = [], 0, 0
    four.dead_at = DEAD_AFTER_S

    for r in robots:
        r.map_dirty = True
    return robots


# --------------------------------------------------------------------------
# The backend
# --------------------------------------------------------------------------


class DemoBackend(Backend):
    """Four simulated robots. `speedup` runs the clock faster (tests): a tick
    is still 1 or 5 simulated seconds, slept for that long divided by it."""

    def __init__(self, *, speedup: float = 1.0) -> None:
        self._speedup = speedup
        self._robots = {r.info.id: r for r in _make_robots()}
        self._sink: Sink | None = None
        self._task: asyncio.Task[None] | None = None
        self._watched = False
        self._wake = asyncio.Event()
        self._now = 0.0  # simulated seconds since start

    async def start(self, sink: Sink) -> None:
        self._sink = sink
        self._task = asyncio.create_task(self._run(), name="demo robots")

    async def stop(self) -> None:
        if self._task is None:
            return
        self._task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self._task
        self._task = None

    def set_watched(self, watched: bool) -> None:
        if watched != self._watched:
            self._watched = watched
            self._wake.set()

    async def command(self, robot_id: str, command: Command) -> None:
        robot = self._robots.get(robot_id)
        if robot is None:
            raise RobotError("not_found", f"No robot {robot_id!r}")
        if self._dead(robot):
            raise RobotError("robot_unreachable", f"{robot.info.name} did not answer")
        self._apply(robot, command)
        self._report(robot)
        self._wake.set()

    # -- commands -----------------------------------------------------------

    def _apply(self, robot: _Robot, cmd: Command) -> None:
        name = robot.info.name
        match cmd.name:
            case "start":
                if robot.mode in ("paused", "error") and robot.route:
                    robot.mode, robot.error = "running", None
                elif robot.activity != "cleaning":
                    robot.begin_job([r.id for r in robot.plan.rooms], 1, whole_home=True)
            case "clean_rooms":
                known = {r.id for r in robot.plan.rooms}
                if not cmd.rooms:
                    raise RobotError("bad_request", "clean_rooms needs at least one room")
                unknown = [r for r in cmd.rooms if r not in known]
                if unknown:
                    raise RobotError("bad_request", f"{name} has no room {unknown[0]}")
                if not 1 <= cmd.repeat <= 3:
                    raise RobotError("bad_request", f"repeat must be 1 to 3, not {cmd.repeat}")
                robot.begin_job(list(dict.fromkeys(cmd.rooms)), cmd.repeat, whole_home=False)
            case "pause":
                if robot.mode != "running":
                    raise RobotError("rejected", f"{name} is not cleaning or returning")
                robot.mode = "paused"
            case "resume":
                if robot.mode not in ("paused", "error") or not robot.route:
                    raise RobotError("rejected", f"{name} has nothing to resume")
                robot.mode, robot.error = "running", None
            case "stop":
                if robot.mode in ("running", "paused", "error"):
                    # Stopped in a doorway, the next route starts by finishing
                    # the crossing (see _start_point); otherwise nothing is kept.
                    lead, _room, _x, _y = robot._start_point()
                    robot.mode, robot.error = "idle", None
                    robot.route, robot.next_wp, robot.home_from = lead, 0, 0
            case "dock":
                if robot.mode != "docked":
                    robot.go_home()
            case "locate":
                pass
            case "set_fan":
                if cmd.value not in FAN_OPTIONS:
                    raise RobotError("bad_request", f"fan must be one of {', '.join(FAN_OPTIONS)}")
                robot.fan = cmd.value
            case "set_water":
                if not robot.water_options:
                    raise RobotError("unsupported", f"{name} cannot mop")
                if cmd.value not in robot.water_options:
                    raise RobotError("bad_request", f"water must be one of {', '.join(robot.water_options)}")
                robot.water = cmd.value
            case _:
                raise RobotError("bad_request", f"Unknown command {cmd.name!r}")

    # -- the clock ----------------------------------------------------------

    def _dead(self, robot: _Robot) -> bool:
        return robot.dead_at is not None and self._now >= robot.dead_at

    def _report(self, robot: _Robot) -> None:
        sink = self._sink
        assert sink is not None, "command before start()"
        if self._dead(robot):
            sink.robot_unreachable(robot.info.id, "The robot did not answer")
            return
        sink.robot_status(robot.info.id, robot.observation())
        if robot.map_dirty:
            robot.map_dirty = False
            sink.robot_map(robot.info.id, robot.encoded_map())

    def _tick(self, dt: float) -> None:
        self._now += dt
        for robot in self._robots.values():
            if not self._dead(robot):
                robot.advance(dt)
            self._report(robot)

    async def _run(self) -> None:
        sink = self._sink
        assert sink is not None
        for robot in self._robots.values():
            sink.robot_found(robot.info)
            sink.robot_rooms(robot.info.id, list(robot.plan.rooms))
            self._report(robot)
        loop = asyncio.get_running_loop()
        next_at = loop.time()
        while True:
            self._wake.clear()
            if not self._watched and not self._busy():
                await self._wake.wait()  # nothing to show and nobody looking
                next_at = loop.time() + UNWATCHED_TICK_S / self._speedup
                continue
            dt = WATCHED_TICK_S if self._watched else UNWATCHED_TICK_S
            # Someone starting to watch brings the next tick forward.
            next_at = min(next_at, loop.time() + dt / self._speedup)
            delay = next_at - loop.time()
            if delay > 0:
                with contextlib.suppress(TimeoutError):  # the timeout is the tick
                    async with asyncio.timeout(delay):
                        await self._wake.wait()
                continue
            self._tick(dt)
            next_at = loop.time() + dt / self._speedup

    def _busy(self) -> bool:
        """A robot is driving, or one still has to die (robot 4)."""
        return any(
            not self._dead(r) and (r.moving or r.dead_at is not None) for r in self._robots.values()
        )
