"""Roborock's "rr" map format: parse, render to PNG, and write.

A robot sends its map as an "rr" blob every few seconds while it cleans, and
this runs on small machines, so nothing here loops over pixels in Python and
nothing imports Pillow: classes come from one `bytes.translate`, the crop from
`strip`, rooms from `find` over runs, the PNG from slice assignment and zlib.

Coordinates. Every position in a MapFrame is in pixels of the cropped index
image with pixel (i, j) covering [i, i+1) x [j, j+1), so a canvas can draw the
index at scale s and multiply positions by s. That is the reference parser's
`Point.to_img` plus half a pixel (it puts pixel centres on integers) minus the
crop offset; `room_at` takes the pixel under a point the way the reference
looks up the room under the robot.
"""

from __future__ import annotations

import math
import struct
import zlib
from collections.abc import Sequence

from .model import (
    MM_PER_PX,
    PX_FLOOR,
    PX_OUTSIDE,
    PX_SEEN,
    PX_WALL,
    ROOM_BASE,
    MapFrame,
    RoomGeom,
)

# Block types this parser reads or writes; every other type is skipped by its
# lengths.
CHARGER = 1
IMAGE = 2
PATH = 3
ROBOT_POSITION = 8
NO_GO_AREAS = 9
VIRTUAL_WALLS = 10

_FILE_HEADER = struct.Struct("<2sHIHHII")  # magic, header len, data len, major, minor, index, sequence
_BLOCK_HEADER = struct.Struct("<HHI")  # type, header len, data len
_IMAGE_GEOMETRY = struct.Struct("<iiii")  # top, left, height, width: the last 16 header bytes
_MARGIN = 2  # outside pixels kept round the crop


def _class_of(v: int) -> int:
    """The index class of one raw pixel value, as the reference image parser
    colours it (0x00 outside, 0x01 wall, 0xFF floor, 0x07 scanned floor, then
    by the low three bits: 0 grey wall, 1 wall, 7 room `v >> 3`). Values the
    reference marks "unknown" (low bits 2-6, never seen on a real map) are
    floor in no room.

    The reference paints "grey wall" like a wall. On a real map only 8% of
    those pixels touch floor (against 92% of true wall pixels): they are the
    space seen beyond the walls, not walls, so they get their own class."""
    if v == 0x00:
        return PX_OUTSIDE
    if v == 0x01:
        return PX_WALL
    if v in (0xFF, 0x07):
        return PX_FLOOR
    low = v & 0x07
    if low == 0:
        return PX_SEEN
    if low == 1:
        return PX_WALL
    if low == 7:
        return ROOM_BASE + (v >> 3)
    return PX_FLOOR


_CLASS = bytes(_class_of(v) for v in range(256))
# Room segment ids that a raw pixel can carry (0x07 and 0xFF are floor).
_ROOM_IDS = tuple(sorted({c - ROOM_BASE for c in _CLASS if c >= ROOM_BASE}))
# Per room class: a table turning the index into 1 on that room, 0 elsewhere.
_ROOM_MASK = {rid: bytes(1 if c == ROOM_BASE + rid else 0 for c in range(256)) for rid in _ROOM_IDS}


# --------------------------------------------------------------------------
# Parsing
# --------------------------------------------------------------------------


def _blocks(raw: bytes) -> dict[int, tuple[int, int, int]]:
    """Block type -> (block start, header length, data length). A type seen
    twice keeps the last one, as the reference parser does."""
    if len(raw) < _FILE_HEADER.size:
        raise ValueError(f"not an rr map: {len(raw)} bytes is shorter than the file header")
    magic, header_len, data_len, _major, _minor, _index, _seq = _FILE_HEADER.unpack_from(raw, 0)
    if magic != b"rr":
        raise ValueError(f"not an rr map: starts with {magic!r}, not b'rr'")
    if header_len < _FILE_HEADER.size or header_len + data_len != len(raw):
        raise ValueError(
            f"not an rr map: header says {header_len} + {data_len} bytes, the blob has {len(raw)}"
        )
    blocks: dict[int, tuple[int, int, int]] = {}
    pos = header_len
    end = len(raw)
    while pos < end:
        if pos + _BLOCK_HEADER.size > end:
            raise ValueError(f"rr map truncated: block header at {pos} runs past the end")
        btype, bhl, bdl = _BLOCK_HEADER.unpack_from(raw, pos)
        if bhl < _BLOCK_HEADER.size or pos + bhl + bdl > end:
            raise ValueError(
                f"rr map truncated: block {btype} at {pos} (header {bhl}, data {bdl}) runs past the end"
            )
        blocks[btype] = (pos, bhl, bdl)
        pos += bhl + bdl
    return blocks


class _Geometry:
    """mm -> cropped index pixels: the reference `to_img`, half a pixel, crop."""

    __slots__ = ("bx", "by")

    def __init__(self, top: int, left: int, height: int, x_off: int, y_off: int) -> None:
        self.bx = 0.5 - left - x_off
        self.by = (height - 1) + top + 0.5 - y_off

    def point(self, mm_x: float, mm_y: float) -> tuple[float, float]:
        return (round(mm_x / MM_PER_PX + self.bx, 1), round(self.by - mm_y / MM_PER_PX, 1))

    def flat(self, mm: Sequence[int]) -> tuple[float, ...]:
        """x0, y0, x1, y1, ... in mm to the same in pixels."""
        bx, by = self.bx, self.by
        out = [0.0] * len(mm)
        out[0::2] = [round(x / MM_PER_PX + bx, 1) for x in mm[0::2]]
        out[1::2] = [round(by - y / MM_PER_PX, 1) for y in mm[1::2]]
        return tuple(out)


def _crop(cls: bytes, width: int, height: int) -> tuple[bytes, int, int, int, int]:
    """Flip `cls` (raw rows, bottom first) to top-first and crop it to what is
    not outside plus the margin. Returns (index, width, height, x_off, y_off)
    where the offsets place the crop in the flipped full image (they can be
    negative: the margin is padded with outside where the picture ends)."""
    first = len(cls) - len(cls.lstrip(b"\x00"))
    if first == len(cls):
        raise ValueError("rr map image holds nothing but outside")
    last = len(cls.rstrip(b"\x00")) - 1
    r_min, r_max = first // width, last // width
    c_min, c_max = width, -1
    for r in range(r_min, r_max + 1):
        row = cls[r * width : (r + 1) * width]
        right = len(row.rstrip(b"\x00"))
        if right:
            c_min = min(c_min, width - len(row.lstrip(b"\x00")))
            c_max = max(c_max, right - 1)
    x_off = c_min - _MARGIN
    out_w = c_max - c_min + 1 + 2 * _MARGIN
    y_off = (height - 1 - r_max) - _MARGIN
    out_h = r_max - r_min + 1 + 2 * _MARGIN
    lo, hi = max(x_off, 0), min(x_off + out_w, width)
    pad_l = b"\x00" * (lo - x_off)
    pad_r = b"\x00" * (x_off + out_w - hi)
    blank = b"\x00" * out_w
    rows = []
    for r in range(r_max + _MARGIN, r_min - _MARGIN - 1, -1):
        if 0 <= r < height:
            rows.append(pad_l + cls[r * width + lo : r * width + hi] + pad_r)
        else:
            rows.append(blank)
    return b"".join(rows), out_w, out_h, x_off, y_off


def _rooms(index: bytes, width: int) -> dict[int, RoomGeom]:
    rooms: dict[int, RoomGeom] = {}
    for rid in _ROOM_IDS:
        vb = bytes((ROOM_BASE + rid,))
        first = index.find(vb)
        if first < 0:
            continue
        y0 = first // width
        y1 = index.rfind(vb) // width
        band = index[y0 * width : (y1 + 1) * width].translate(_ROOM_MASK[rid])
        area = 0
        sum_x2 = 0  # twice the sum of pixel-centre x, kept integral
        sum_y = 0
        x0, x1 = width, -1
        widest: list[tuple[int, int, int]] = []  # (row, run start, run end) per row that has the room
        for k in range((y1 - y0) + 1):
            row = band[k * width : (k + 1) * width]
            pos = row.find(1)
            if pos < 0:
                continue
            n_row = 0
            best = (0, 0)
            x0 = min(x0, pos)
            while pos >= 0:
                end = row.find(0, pos)
                if end < 0:
                    end = width
                n = end - pos
                n_row += n
                sum_x2 += (pos + end) * n  # each pixel's centre is x + 0.5
                if n > best[1] - best[0]:
                    best = (pos, end)
                last_end = end
                pos = row.find(1, end)
            x1 = max(x1, last_end - 1)
            area += n_row
            sum_y += (y0 + k) * n_row
            widest.append((y0 + k, best[0], best[1]))
        my = sum_y / area + 0.5
        row_y, a, b = min(widest, key=lambda w: abs(w[0] + 0.5 - my))
        rooms[rid] = RoomGeom(
            cx=(a + b) / 2,
            cy=row_y + 0.5,
            x0=x0,
            y0=y0,
            x1=x1,
            y1=y1,
            area_px=area,
        )
    return rooms


def _room_in(index: bytes, width: int, height: int, x: float, y: float) -> int | None:
    i, j = math.floor(x), math.floor(y)
    if not (0 <= i < width and 0 <= j < height):
        return None
    c = index[j * width + i]
    return c - ROOM_BASE if c >= ROOM_BASE else None


def _position(raw: bytes, start: int, length: int) -> tuple[int, int, int | None]:
    if length < 8:
        raise ValueError(f"rr map position block holds {length} bytes, needs 8")
    x, y = struct.unpack_from("<II", raw, start)
    angle = struct.unpack_from("<i", raw, start + 8)[0] if length >= 12 else None
    return x, y, angle


def _u16s(raw: bytes, start: int, count: int) -> tuple[int, ...]:
    return struct.unpack_from(f"<{count}H", raw, start)


def parse(raw: bytes) -> MapFrame:
    """Parse an rr blob (already gunzipped). Raises ValueError on a blob that
    is not an rr map, has no image, or whose image is all outside."""
    blocks = _blocks(raw)
    if IMAGE not in blocks:
        raise ValueError("rr map has no image block")
    start, hl, dl = blocks[IMAGE]
    if hl < _BLOCK_HEADER.size + _IMAGE_GEOMETRY.size:
        raise ValueError(f"rr map image block header is {hl} bytes, too short for its geometry")
    top, left, height, width = _IMAGE_GEOMETRY.unpack_from(raw, start + hl - _IMAGE_GEOMETRY.size)
    if width <= 0 or height <= 0 or width * height != dl:
        raise ValueError(f"rr map image is {width} x {height} but holds {dl} bytes")
    cls = raw[start + hl : start + hl + dl].translate(_CLASS)
    index, w, h, x_off, y_off = _crop(cls, width, height)
    geo = _Geometry(top, left, height, x_off, y_off)

    robot = robot_angle = robot_room = charger = None
    if ROBOT_POSITION in blocks:
        s, bhl, bdl = blocks[ROBOT_POSITION]
        x, y, robot_angle = _position(raw, s + bhl, bdl)
        robot = geo.point(x, y)
        robot_room = _room_in(index, w, h, *robot)
    if CHARGER in blocks:
        s, bhl, bdl = blocks[CHARGER]
        x, y, _ = _position(raw, s + bhl, bdl)
        charger = geo.point(x, y)

    path: tuple[float, ...] = ()
    if PATH in blocks:
        s, bhl, bdl = blocks[PATH]
        if bdl % 4:
            raise ValueError(f"rr map path holds {bdl} bytes, not whole points")
        path = geo.flat(_u16s(raw, s + bhl, bdl // 2))

    no_go: tuple[tuple[float, ...], ...] = ()
    if NO_GO_AREAS in blocks:
        no_go = tuple(geo.flat(a) for a in _counted(raw, blocks[NO_GO_AREAS], 8))
    walls: tuple[tuple[float, float, float, float], ...] = ()
    if VIRTUAL_WALLS in blocks:
        walls = tuple(
            (p[0], p[1], p[2], p[3]) for p in (geo.flat(a) for a in _counted(raw, blocks[VIRTUAL_WALLS], 4))
        )

    digest = zlib.crc32(index, zlib.crc32(struct.pack("<II", w, h)))
    return MapFrame(
        width=w,
        height=h,
        index=index,
        digest=f"{digest:08x}",
        rooms=_rooms(index, w),
        robot=robot,
        robot_angle=robot_angle,
        robot_room=robot_room,
        charger=charger,
        path=path,
        no_go=no_go,
        walls=walls,
    )


def _counted(raw: bytes, block: tuple[int, int, int], per: int) -> list[tuple[int, ...]]:
    """Records of `per` u16 values, counted by the u16 at header +8 (how the
    reference reads no-go areas and virtual walls)."""
    s, bhl, bdl = block
    if bhl < 10:
        raise ValueError(f"rr map block at {s} has a {bhl}-byte header, no record count")
    (count,) = struct.unpack_from("<H", raw, s + 8)
    if count * per * 2 > bdl:
        raise ValueError(f"rr map block at {s} counts {count} records in {bdl} bytes")
    flat = _u16s(raw, s + bhl, count * per)
    return [flat[i : i + per] for i in range(0, len(flat), per)]


def room_at(frame: MapFrame, x: float, y: float) -> int | None:
    """The segment id of the room under a point, None when the pixel there is
    not a room (wall, floor in no room, outside, or off the image)."""
    return _room_in(frame.index, frame.width, frame.height, x, y)


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------

_P_OUTSIDE, _P_WALL, _P_FLOOR = 0, 1, 2
_P_ROOM0 = 3
_ROOM_RGB = (
    (0xA8, 0xC8, 0xF0),
    (0xF4, 0xC4, 0x9C),
    (0xB4, 0xE0, 0xB0),
    (0xF0, 0xB4, 0xC4),
    (0xD0, 0xC0, 0xF0),
    (0xF4, 0xE4, 0xA0),
    (0xA4, 0xDC, 0xD8),
    (0xE8, 0xC8, 0xB0),
    (0xC4, 0xD8, 0x9C),
    (0xC8, 0xB8, 0xD8),
    (0x9C, 0xD0, 0xEC),
    (0xEC, 0xB8, 0xA0),
)
_P_PATH = _P_ROOM0 + len(_ROOM_RGB)
_P_DOCK = _P_PATH + 1
_P_ROBOT = _P_DOCK + 1
_P_ROBOT_EDGE = _P_ROBOT + 1
_P_BARRIER = _P_ROBOT_EDGE + 1
_P_SEEN = _P_BARRIER + 1
_PALETTE = b"".join(
    bytes(rgb)
    for rgb in (
        (0, 0, 0),  # outside, transparent through tRNS
        (0x4A, 0x4E, 0x58),  # wall
        (0xE4, 0xE6, 0xEA),  # floor in no room
        *_ROOM_RGB,
        (0x5C, 0x6A, 0x80),  # path: reads on every room colour
        (0x2E, 0x9E, 0x5A),  # dock
        (0x1E, 0x6F, 0xE0),  # robot
        (0xFF, 0xFF, 0xFF),  # robot outline
        (0xD8, 0x3A, 0x3A),  # no-go areas and virtual walls
        (0xEE, 0xEF, 0xF1),  # seen but never driven: barely there
    )
)
_TRNS = b"\x00"  # only entry 0 is transparent; the rest default to opaque


def _render_table(frame: MapFrame) -> bytes:
    table = bytearray(256)
    table[PX_WALL] = _P_WALL
    table[PX_FLOOR] = _P_FLOOR
    table[PX_SEEN] = _P_SEEN
    # Colours by rank among the rooms present, so up to twelve rooms never
    # share one whatever their segment ids.
    for rank, rid in enumerate(sorted(frame.rooms)):
        table[ROOM_BASE + rid] = _P_ROOM0 + rank % len(_ROOM_RGB)
    return bytes(table)


def _line(img: bytearray, w: int, h: int, x0: int, y0: int, x1: int, y1: int, colour: int) -> None:
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        if 0 <= x0 < w and 0 <= y0 < h:
            img[y0 * w + x0] = colour
        if x0 == x1 and y0 == y1:
            return
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def _polyline(img: bytearray, w: int, h: int, flat: Sequence[float], s: int, colour: int, closed: bool) -> None:
    pts = [(int(flat[i] * s), int(flat[i + 1] * s)) for i in range(0, len(flat) - 1, 2)]
    if closed and pts:
        pts.append(pts[0])
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        _line(img, w, h, ax, ay, bx, by, colour)


def _disc(img: bytearray, w: int, h: int, cx: float, cy: float, r: float, colour: int) -> None:
    for y in range(max(0, math.floor(cy - r)), min(h, math.ceil(cy + r) + 1)):
        dy = y + 0.5 - cy
        if dy * dy > r * r:
            continue
        half = math.sqrt(r * r - dy * dy)
        a, b = max(0, round(cx - half)), min(w, round(cx + half))
        if a < b:
            img[y * w + a : y * w + b] = bytes((colour,)) * (b - a)


def _square(img: bytearray, w: int, h: int, cx: float, cy: float, half: float, colour: int) -> None:
    a, b = max(0, round(cx - half)), min(w, round(cx + half))
    if a >= b:
        return
    run = bytes((colour,)) * (b - a)
    for y in range(max(0, round(cy - half)), min(h, round(cy + half))):
        img[y * w + a : y * w + b] = run


def _chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(data, zlib.crc32(kind)))


def render_png(frame: MapFrame, scale: int = 2) -> bytes:
    """The map as a paletted PNG, `scale` (1-8) screen pixels per map pixel:
    outside transparent, walls dark, rooms in soft colours, the path, the dock
    and the robot on top."""
    if not 1 <= scale <= 8:
        raise ValueError(f"render scale {scale} is not between 1 and 8")
    s = scale
    w, h = frame.width * s, frame.height * s
    small = frame.index.translate(_render_table(frame))
    wide = bytearray(len(small) * s)
    for k in range(s):
        wide[k::s] = small
    img = bytearray(b"".join(bytes(wide[y * w : (y + 1) * w]) * s for y in range(frame.height)))

    for area in frame.no_go:
        _polyline(img, w, h, area, s, _P_BARRIER, closed=True)
    for wall in frame.walls:
        _polyline(img, w, h, wall, s, _P_BARRIER, closed=False)
    if frame.path:
        _polyline(img, w, h, frame.path, s, _P_PATH, closed=False)
    if frame.charger is not None:
        _square(img, w, h, frame.charger[0] * s, frame.charger[1] * s, 1.5 * s, _P_DOCK)
    if frame.robot is not None:
        rx, ry = frame.robot[0] * s, frame.robot[1] * s
        _disc(img, w, h, rx, ry, 2.5 * s, _P_ROBOT_EDGE)
        _disc(img, w, h, rx, ry, 2.0 * s, _P_ROBOT)

    rows = b"".join(b"\x00" + img[y * w : (y + 1) * w] for y in range(h))
    return b"".join(
        (
            b"\x89PNG\r\n\x1a\n",
            _chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 3, 0, 0, 0)),
            _chunk(b"PLTE", _PALETTE),
            _chunk(b"tRNS", _TRNS),
            _chunk(b"IDAT", zlib.compress(rows, 6)),
            _chunk(b"IEND", b""),
        )
    )


# --------------------------------------------------------------------------
# Writing
# --------------------------------------------------------------------------


def encode_image(pixels: bytes, *, top: int, left: int, width: int, height: int) -> bytes:
    """An IMAGE block from raw rr pixel values, bottom row first. Encode it
    once and hand it to `encode` for every frame: only the small blocks
    change while a robot moves."""
    if width <= 0 or height <= 0 or len(pixels) != width * height:
        raise ValueError(f"image is {width} x {height} but {len(pixels)} pixels were given")
    header = _BLOCK_HEADER.pack(IMAGE, 28, len(pixels)) + struct.pack("<I", 0)
    return header + _IMAGE_GEOMETRY.pack(top, left, height, width) + pixels


def encode(
    image_block: bytes,
    *,
    charger: tuple[int, int, int] | None = None,
    robot: tuple[int, int, int] | None = None,
    path: Sequence[int] = (),
    no_go: Sequence[Sequence[int]] = (),
    walls: Sequence[Sequence[int]] = (),
    map_index: int = 0,
    sequence: int = 0,
) -> bytes:
    """An rr blob. Positions are in mm, as a robot reports them: `charger` and
    `robot` are (x, y, angle), `path` is flat x0, y0, x1, y1, ..., each no-go
    area 8 values (four corners) and each wall 4 (two ends)."""
    parts = [image_block]
    if charger is not None:
        parts.append(_BLOCK_HEADER.pack(CHARGER, 8, 12) + struct.pack("<IIi", *charger))
    if robot is not None:
        parts.append(_BLOCK_HEADER.pack(ROBOT_POSITION, 8, 12) + struct.pack("<IIi", *robot))
    if path:
        if len(path) % 2:
            raise ValueError(f"path holds {len(path)} values, not whole points")
        n = len(path) // 2
        parts.append(_BLOCK_HEADER.pack(PATH, 20, 4 * n) + struct.pack("<iii", n, 4, 0))
        parts.append(struct.pack(f"<{len(path)}H", *path))
    for btype, records, per in ((NO_GO_AREAS, no_go, 8), (VIRTUAL_WALLS, walls, 4)):
        if not records:
            continue
        flat = [v for rec in records for v in rec]
        if len(flat) != per * len(records):
            raise ValueError(f"block {btype} records need {per} values each")
        parts.append(_BLOCK_HEADER.pack(btype, 12, 2 * len(flat)) + struct.pack("<I", len(records)))
        parts.append(struct.pack(f"<{len(flat)}H", *flat))
    body = b"".join(parts)
    return _FILE_HEADER.pack(b"rr", _FILE_HEADER.size, len(body), 1, 0, map_index, sequence) + body
