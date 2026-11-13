"""rrmap: round trips, agreement with the reference parser, speed."""

from __future__ import annotations

import io
import struct
import time
import zlib
from pathlib import Path

import pytest
from PIL import Image  # the reference parser's dependency, used to read our PNGs back

from roborock_at_home import rrmap
from roborock_at_home.model import PX_FLOOR, PX_OUTSIDE, PX_SEEN, PX_WALL, ROOM_BASE

REAL_MAP = Path(__file__).resolve().parent.parent / "private" / "map0.bin"


def room_px(rid: int) -> int:
    return (rid << 3) | 7


class Canvas:
    """Raw rr pixels addressed top-down (row 0 at the top, as people look at
    a map), written bottom row first by `pixels()` as the format wants."""

    def __init__(self, width: int, height: int) -> None:
        self.w, self.h = width, height
        self.rows = [bytearray(width) for _ in range(height)]

    def rect(self, x0: int, y0: int, x1: int, y1: int, v: int) -> None:
        for y in range(y0, y1 + 1):
            self.rows[y][x0 : x1 + 1] = bytes((v,)) * (x1 - x0 + 1)

    def room(self, x0: int, y0: int, x1: int, y1: int, rid: int) -> None:
        """A room with a one-pixel wall round it."""
        self.rect(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0x01)
        self.rect(x0, y0, x1, y1, room_px(rid))

    def pixels(self) -> bytes:
        return b"".join(bytes(r) for r in reversed(self.rows))


TOP, LEFT = 200, 380


def mm(px_x: float, px_y_top_down: float, height: int) -> tuple[int, int]:
    """mm of the centre of a pixel given top-down, for a map at TOP/LEFT."""
    raw_row = height - 1 - px_y_top_down
    return round((px_x + LEFT) * 50), round((raw_row + TOP) * 50)


def pose(px_x: float, px_y_top_down: float, height: int, angle: int) -> tuple[int, int, int]:
    x, y = mm(px_x, px_y_top_down, height)
    return x, y, angle


def rgba(im: Image.Image, xy: tuple[int, int]) -> tuple[int, ...]:
    v = im.getpixel(xy)
    assert isinstance(v, tuple)
    return v


def small_map() -> tuple[Canvas, bytes]:
    c = Canvas(60, 40)
    c.room(10, 8, 25, 20, 16)  # room 16
    c.room(27, 8, 45, 30, 17)  # room 17, shares the wall column 26
    c.rect(26, 12, 26, 14, room_px(17))  # a doorway into 17
    c.rect(10, 22, 20, 25, 0xFF)  # floor in no room
    c.rect(21, 22, 22, 22, 0x07)  # scanned floor
    c.rect(23, 22, 23, 22, 0x08)  # grey wall
    c.rect(24, 22, 24, 22, 0x0A)  # unknown kind: floor
    return c, rrmap.encode_image(c.pixels(), top=TOP, left=LEFT, width=c.w, height=c.h)


def test_round_trip_classes_crop_and_coordinates() -> None:
    c, img = small_map()
    rx, ry = mm(15, 12, c.h)
    raw = rrmap.encode(
        img,
        charger=pose(12, 19, c.h, 0),
        robot=(rx, ry, -160),
        path=[*mm(12, 19, c.h), rx, ry],
        no_go=[[*mm(30, 10, c.h), *mm(34, 10, c.h), *mm(34, 14, c.h), *mm(30, 14, c.h)]],
        walls=[[*mm(28, 20, c.h), *mm(40, 20, c.h)]],
    )
    f = rrmap.parse(raw)
    # Content spans x 9..46, y 7..31 (top-down); the crop adds 2 each side.
    assert (f.width, f.height) == (46 - 9 + 1 + 4, 31 - 7 + 1 + 4)
    xo, yo = 9 - 2, 7 - 2

    def at(x: int, y: int) -> int:
        return f.index[(y - yo) * f.width + (x - xo)]

    assert at(9, 7) == PX_WALL
    assert at(15, 12) == ROOM_BASE + 16
    assert at(26, 13) == ROOM_BASE + 17  # doorway
    assert at(26, 10) == PX_WALL
    assert at(15, 23) == PX_FLOOR
    assert at(21, 22) == PX_FLOOR
    assert at(23, 22) == PX_SEEN
    assert at(24, 22) == PX_FLOOR
    assert f.index[0] == PX_OUTSIDE and f.index[-1] == PX_OUTSIDE
    assert len(f.index) == f.width * f.height

    # A pixel centre lands at +0.5 in the cropped image.
    robot, charger = (15 - xo + 0.5, 12 - yo + 0.5), (12 - xo + 0.5, 19 - yo + 0.5)
    assert f.robot == robot
    assert f.robot_angle == -160
    assert f.robot_room == 16
    assert rrmap.room_at(f, *robot) == 16
    assert f.charger == charger
    assert f.path == (*charger, *robot)
    assert f.no_go == ((30 - xo + 0.5, 10 - yo + 0.5, 34 - xo + 0.5, 10 - yo + 0.5,
                        34 - xo + 0.5, 14 - yo + 0.5, 30 - xo + 0.5, 14 - yo + 0.5),)
    assert f.walls == ((28 - xo + 0.5, 20 - yo + 0.5, 40 - xo + 0.5, 20 - yo + 0.5),)

    assert set(f.rooms) == {16, 17}
    r16 = f.rooms[16]
    assert (r16.x0, r16.y0, r16.x1, r16.y1) == (10 - xo, 8 - yo, 25 - xo, 20 - yo)
    assert r16.area_px == 16 * 13
    assert (r16.cx, r16.cy) == ((10 + 26) / 2 - xo, 14 - yo + 0.5)
    r17 = f.rooms[17]
    assert r17.area_px == 19 * 23 + 3
    assert (r17.x0, r17.x1) == (26 - xo, 45 - xo)
    for rid, g in f.rooms.items():
        assert rrmap.room_at(f, g.cx, g.cy) == rid

    assert rrmap.room_at(f, -1, 3) is None
    assert rrmap.room_at(f, 0.5, 0.5) is None


def test_digest_follows_the_floor_plan_not_the_robot() -> None:
    c, img = small_map()
    a = rrmap.parse(rrmap.encode(img, robot=pose(15, 12, c.h, 0)))
    b = rrmap.parse(rrmap.encode(img, robot=pose(30, 20, c.h, 90), path=[1, 2, 3, 4], sequence=7))
    assert a.digest == b.digest and len(a.digest) == 8
    c.rect(40, 25, 41, 26, 0x01)
    moved = rrmap.parse(rrmap.encode(rrmap.encode_image(c.pixels(), top=TOP, left=LEFT, width=c.w, height=c.h)))
    assert moved.digest != a.digest


def test_room_label_sits_inside_an_l_shaped_room() -> None:
    c = Canvas(50, 50)
    c.rect(5, 5, 40, 12, room_px(3))
    c.rect(5, 13, 12, 40, room_px(3))
    f = rrmap.parse(rrmap.encode(rrmap.encode_image(c.pixels(), top=0, left=0, width=50, height=50)))
    g = f.rooms[3]
    assert rrmap.room_at(f, g.cx, g.cy) == 3


def test_crop_pads_when_content_touches_the_edge() -> None:
    c = Canvas(20, 10)
    c.rect(0, 0, 19, 9, room_px(5))
    f = rrmap.parse(rrmap.encode(rrmap.encode_image(c.pixels(), top=0, left=0, width=20, height=10)))
    assert (f.width, f.height) == (24, 14)
    assert f.index[: f.width * 2] == bytes(f.width * 2)
    assert f.rooms[5].area_px == 200


# --------------------------------------------------------------------------
# Against the reference parser
# --------------------------------------------------------------------------


def reference_parse(raw: bytes):  # noqa: ANN201 (the reference's MapData)
    from roborock.map.map_parser import MapParserConfig, _create_map_data_parser

    parser = _create_map_data_parser(MapParserConfig(drawables=[], show_background=False, map_scale=1))
    return parser.parse(raw)


def assert_agrees(raw: bytes) -> None:
    f = rrmap.parse(raw)
    ref = reference_parse(raw)
    dims = ref.image.dimensions
    left, top, right, bottom = ref.image.data.getbbox()
    assert (f.width, f.height) == (right - left + 4, bottom - top + 4)
    xo, yo = left - 2, top - 2

    def near(mine: tuple[float, float], point) -> None:  # noqa: ANN001
        p = point.to_img(dims)
        assert mine[0] == pytest.approx(p.x + 0.5 - xo, abs=0.051)
        assert mine[1] == pytest.approx(p.y + 0.5 - yo, abs=0.051)

    assert (f.robot is None) == (ref.vacuum_position is None)
    if f.robot is not None:
        near(f.robot, ref.vacuum_position)
        assert f.robot_room == ref.vacuum_room
    assert (f.charger is None) == (ref.charger is None)
    if f.charger is not None:
        near(f.charger, ref.charger)
    assert set(f.rooms) == set(ref.rooms or {})
    for rid, g in f.rooms.items():
        r = ref.rooms[rid]
        # The reference keeps room bounds in mm of raw (bottom-up) rows.
        assert g.x0 == r.x0 / 50 - dims.left - xo
        assert g.x1 == r.x1 / 50 - dims.left - xo
        assert g.y1 == dims.height - 1 - (r.y0 / 50 - dims.top) - yo
        assert g.y0 == dims.height - 1 - (r.y1 / 50 - dims.top) - yo
    ref_path = ref.path.path[0] if ref.path is not None else []
    assert len(f.path) == 2 * len(ref_path)
    for i, p in enumerate(ref_path[:50]):
        near((f.path[2 * i], f.path[2 * i + 1]), p)
    assert len(f.walls) == len(ref.walls or [])
    assert len(f.no_go) == len(ref.no_go_areas or [])


def synthetic_house(width: int, height: int, seed: int) -> Canvas:
    c = Canvas(width, height)
    cols, rows = 3, 3
    cw, rh = (width - 20) // cols, (height - 20) // rows
    rid = 1 + seed
    for j in range(rows):
        for i in range(cols):
            x0, y0 = 10 + i * cw + 1, 10 + j * rh + 1
            c.room(x0, y0, x0 + cw - 3, y0 + rh - 3, rid)
            rid += 1
    for j in range(rows):
        for i in range(cols - 1):
            x = 10 + (i + 1) * cw - 1
            y = 10 + j * rh + rh // 2
            c.rect(x - 1, y, x, y + 8, 0xFF)  # doorways
    return c


@pytest.mark.parametrize("seed", [0, 5])
def test_agrees_with_reference_on_synthetic_maps(seed: int) -> None:
    c = synthetic_house(120, 90, seed)
    img = rrmap.encode_image(c.pixels(), top=TOP, left=LEFT, width=c.w, height=c.h)
    rx, ry = mm(30.3, 33.7, c.h)
    path = []
    for k in range(40):
        path += mm(20 + k, 20 + (k % 7), c.h)
    raw = rrmap.encode(
        img,
        charger=pose(15, 15, c.h, 1),
        robot=(rx, ry, 45),
        path=path,
        no_go=[[*mm(40, 40, c.h), *mm(50, 40, c.h), *mm(50, 50, c.h), *mm(40, 50, c.h)]],
        walls=[[*mm(60, 20, c.h), *mm(60, 40, c.h)], [*mm(70, 20, c.h), *mm(80, 20, c.h)]],
    )
    assert_agrees(raw)


def test_agrees_with_reference_on_the_real_map() -> None:
    if not REAL_MAP.exists():
        pytest.skip("private/map0.bin is not here")
    raw = REAL_MAP.read_bytes()
    assert_agrees(raw)
    f = rrmap.parse(raw)
    for rid, g in f.rooms.items():
        assert rrmap.room_at(f, g.cx, g.cy) == rid
    mine = best_ms(lambda: rrmap.parse(raw), 5)
    ref = best_ms(lambda: reference_parse(raw), 1)
    print(f"\nreal map: {f.width} x {f.height} (cropped), {len(f.rooms)} rooms, "
          f"{len(f.path) // 2} path points; parse {mine:.2f} ms, reference {ref:.0f} ms")


# --------------------------------------------------------------------------
# Malformed input
# --------------------------------------------------------------------------


def test_malformed_input_raises() -> None:
    c, img = small_map()
    good = rrmap.encode(img, robot=(1, 2, 3))
    bad = [
        b"",
        b"rr",
        b"xx" + good[2:],
        good[:-3],  # truncated
        good + b"\x00",  # data length disagrees
        good[:4] + struct.pack("<I", len(good)) + good[8:],
    ]
    for blob in bad:
        with pytest.raises(ValueError):
            rrmap.parse(blob)
    with pytest.raises(ValueError, match="no image"):
        rrmap.parse(rrmap.encode(b"", robot=(1, 2, 3)))
    empty = rrmap.encode_image(bytes(100), top=0, left=0, width=10, height=10)
    with pytest.raises(ValueError, match="nothing but outside"):
        rrmap.parse(rrmap.encode(empty))
    # An image block whose size disagrees with its own geometry.
    lying = bytearray(rrmap.encode(img))
    struct.pack_into("<i", lying, 20 + 24, c.w + 1)
    with pytest.raises(ValueError, match="holds"):
        rrmap.parse(bytes(lying))
    with pytest.raises(ValueError):
        rrmap.encode_image(b"\x01" * 5, top=0, left=0, width=2, height=2)


# --------------------------------------------------------------------------
# PNG
# --------------------------------------------------------------------------


def test_render_png_is_a_paletted_png_with_transparent_outside() -> None:
    c, img = small_map()
    f = rrmap.parse(rrmap.encode(img, charger=pose(12, 19, c.h, 0), robot=pose(15, 12, c.h, 0),
                                 path=[*mm(12, 19, c.h), *mm(20, 12, c.h)]))
    png = rrmap.render_png(f, scale=3)
    assert png[:8] == b"\x89PNG\r\n\x1a\n"
    w, h, depth, colour_type = struct.unpack(">IIBB", png[16:26])
    assert (w, h, depth, colour_type) == (f.width * 3, f.height * 3, 8, 3)
    im = Image.open(io.BytesIO(png)).convert("RGBA")
    assert im.size == (w, h)
    assert rgba(im, (0, 0))[3] == 0  # outside
    xo, yo = 7, 5
    wall = rgba(im, ((9 - xo) * 3 + 1, (7 - yo) * 3 + 1))
    room16 = rgba(im, ((24 - xo) * 3 + 1, (9 - yo) * 3 + 1))
    room17 = rgba(im, ((44 - xo) * 3 + 1, (29 - yo) * 3 + 1))
    assert wall[3] == room16[3] == room17[3] == 255
    assert len({wall, room16, room17}) == 3
    assert f.robot is not None
    robot = rgba(im, (round(f.robot[0] * 3), round(f.robot[1] * 3)))
    assert robot not in (wall, room16, room17)
    for bad in (0, 9):
        with pytest.raises(ValueError):
            rrmap.render_png(f, scale=bad)
    # The IDAT inflates to one filter byte plus a row, per row.
    idat_len = struct.unpack(">I", png[png.index(b"IDAT") - 4 : png.index(b"IDAT")])[0]
    data = png[png.index(b"IDAT") + 4 : png.index(b"IDAT") + 4 + idat_len]
    assert len(zlib.decompress(data)) == h * (w + 1)


# --------------------------------------------------------------------------
# Speed
# --------------------------------------------------------------------------


def best_ms(fn, n: int) -> float:  # noqa: ANN001
    times = []
    for _ in range(n):
        t = time.perf_counter()
        fn()
        times.append(time.perf_counter() - t)
    return min(times) * 1000


def test_speed_on_a_400_by_600_map() -> None:
    c = synthetic_house(400, 600, 0)
    img = rrmap.encode_image(c.pixels(), top=TOP, left=LEFT, width=c.w, height=c.h)
    path = []
    for k in range(2000):
        path += mm(20 + (k % 360), 20 + k // 8, c.h)
    raw = rrmap.encode(img, charger=pose(15, 15, c.h, 0), robot=pose(200, 300, c.h, 0), path=path)
    parse_ms = best_ms(lambda: rrmap.parse(raw), 5)
    f = rrmap.parse(raw)
    png_ms = best_ms(lambda: rrmap.render_png(f, 2), 5)
    print(f"\n400 x 600: parse {parse_ms:.2f} ms, render_png(scale 2) {png_ms:.2f} ms, "
          f"blob {len(raw)} bytes, png {len(rrmap.render_png(f, 2))} bytes")
    assert parse_ms < 20
    assert png_ms < 60
