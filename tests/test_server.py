from __future__ import annotations

import asyncio
import gzip
import json
import sys
import types
from dataclasses import replace
from pathlib import Path

import pytest
from aiohttp.test_utils import TestClient, TestServer
from test_hub import CLEANING, DOCKED, FRAMES, INFO, ROOMS, Clock, FakeBackend, fake_parse, frame

import roborock_at_home
from roborock_at_home import hub as hub_module
from roborock_at_home.config import SESSION_COOKIE, Config
from roborock_at_home.hub import Hub
from roborock_at_home.model import RobotError, RobotInfo
from roborock_at_home.scheduler import Scheduler
from roborock_at_home.server import SERVER_KEY, app_info, create_app
from roborock_at_home.tunnel import Tunnel

PASSWORD = "correct horse"
NOW = 1_800_000_000.0


class Env:
    client: TestClient
    config: Config
    hub: Hub
    backend: FakeBackend
    scheduler: Scheduler
    tunnel: Tunnel
    clock: Clock
    cookie: str
    read_token: str
    control_token: str

    async def call(self, method: str, path: str, auth: str = "none", body=None, headers=None, **kw):
        h = dict(headers or {})
        if auth == "session":
            h["Cookie"] = f"{SESSION_COOKIE}={self.cookie}"
            h.setdefault("X-RAH", "1")
        elif auth == "read":
            h["Authorization"] = f"Bearer {self.read_token}"
        elif auth == "control":
            h["Authorization"] = f"Bearer {self.control_token}"
        data = None if body is None else (body if isinstance(body, (bytes, str)) else json.dumps(body))
        resp = await self.client.request(method, path, data=data, headers=h, **kw)
        self.client.session.cookie_jar.clear()
        return resp


async def build(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, demo: bool = False) -> Env:
    monkeypatch.setattr(hub_module.rrmap, "parse", fake_parse)
    static = tmp_path / "static"
    static.mkdir()
    (static / "index.html").write_text("<!doctype html><title>Roborock at Home</title>" + "x" * 2000)
    (static / "icon.svg").write_text("<svg xmlns='http://www.w3.org/2000/svg'/>")
    (static / "manifest.webmanifest").write_text('{"name": "Roborock at Home"}')
    (static / "API.md").write_text("# API\n")

    env = Env()
    env.clock = Clock(NOW)
    data = tmp_path / "data"
    env.config = config = Config.create(data, "admin", PASSWORD, int(NOW))
    _, env.read_token = config.create_token("Reader", "read", int(NOW))
    _, env.control_token = config.create_token("Agent", "control", int(NOW))
    config.save()

    env.hub = hub = Hub(data, app_info=lambda: app_info(config, hub, env.tunnel, demo), clock=env.clock)

    script = tmp_path / "fake_cloudflared.py"
    script.write_text(
        "import time\n"
        "print('INF |  https://test-words.trycloudflare.com  |', flush=True)\n"
        "print('INF Registered tunnel connection connIndex=0', flush=True)\n"
        "time.sleep(60)\n"
    )

    async def binary() -> list[str]:
        return [sys.executable, str(script)]

    env.tunnel = Tunnel(config, 8770, hub.app_changed, binary=binary)
    env.scheduler = Scheduler(hub, config.schedules_file, clock=env.clock)
    env.scheduler.load(None)
    hub.app_changed()
    env.backend = FakeBackend()
    await hub.set_backend(env.backend)
    hub.robot_found(INFO)
    hub.robot_rooms("r1", ROOMS)
    hub.robot_status("r1", DOCKED)
    FRAMES[b"m"] = frame(path=(1.0, 1.0, 3.5, 2.5))
    hub.robot_map("r1", b"m")
    while hub._tasks:
        await asyncio.gather(*list(hub._tasks))

    app = create_app(config, hub, env.scheduler, env.tunnel, static_dir=static, demo=demo, clock=env.clock)
    env.client = TestClient(TestServer(app))
    await env.client.start_server()
    resp = await env.client.post("/api/login", json={"name": "admin", "password": PASSWORD})
    assert resp.status == 200
    env.cookie = resp.cookies[SESSION_COOKIE].value
    env.client.session.cookie_jar.clear()
    return env


@pytest.fixture
async def env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    e = await build(tmp_path, monkeypatch)
    yield e
    await e.client.close()
    await e.tunnel.stop()
    await e.hub.stop()


async def error_code(resp) -> str:
    return (await resp.json())["error"]["code"]


# --------------------------------------------------------------------------
# Static and service
# --------------------------------------------------------------------------


async def test_page_is_served_from_memory_with_etag(env: Env) -> None:
    resp = await env.call("GET", "/", headers={"Accept-Encoding": "gzip"})
    assert resp.status == 200
    assert resp.headers["Content-Encoding"] == "gzip"
    assert resp.headers["Cache-Control"] == "max-age=0, stale-while-revalidate=604800"
    assert "frame-ancestors 'none'" in resp.headers["Content-Security-Policy"]
    assert resp.headers["X-Content-Type-Options"] == "nosniff"
    assert resp.headers["Referrer-Policy"] == "no-referrer"
    assert "Roborock at Home" in await resp.text()
    etag = resp.headers["ETag"]
    again = await env.call("GET", "/", headers={"If-None-Match": etag})
    assert again.status == 304 and await again.read() == b""
    plain = await env.call("GET", "/", headers={"Accept-Encoding": "identity"})
    assert "Content-Encoding" not in plain.headers and "Roborock" in await plain.text()
    for path, kind in (
        ("/icon.svg", "image/svg+xml"),
        ("/manifest.webmanifest", "application/manifest+json"),
        ("/api/docs", "text/markdown"),
    ):
        resp = await env.call("GET", path)
        assert resp.status == 200 and resp.headers["Content-Type"].startswith(kind) and "ETag" in resp.headers
    resp = await env.call("GET", "/healthz")
    assert await resp.text() == "ok"


async def test_unknown_api_path_is_json(env: Env) -> None:
    resp = await env.call("GET", "/api/nothing", "session")
    assert resp.status == 404 and await error_code(resp) == "not_found"


async def test_stats(env: Env) -> None:
    resp = await env.call("GET", "/api/stats", "read")
    stats = await resp.json()
    assert stats["robots"] == 1 and stats["clients"] == 0 and stats["rss_mb"] > 1 and stats["uptime_s"] >= 0


# --------------------------------------------------------------------------
# Signing in
# --------------------------------------------------------------------------


async def test_session_endpoint(env: Env) -> None:
    assert await (await env.call("GET", "/api/session")).json() == {"authenticated": False}
    assert await (await env.call("GET", "/api/session", "session")).json() == {
        "authenticated": True, "kind": "session", "scope": "all", "name": "admin",
    }
    assert await (await env.call("GET", "/api/session", "read")).json() == {
        "authenticated": True, "kind": "token", "scope": "read", "name": None,
    }
    bad = await env.call("GET", "/api/session", headers={"Authorization": "Bearer rah_nope"})
    assert await bad.json() == {"authenticated": False}


async def test_login(env: Env) -> None:
    resp = await env.call("POST", "/api/login", body={"name": "ADMIN", "password": PASSWORD})
    assert resp.status == 200 and await resp.json() == {"ok": True}
    morsel = resp.cookies[SESSION_COOKIE]
    assert morsel["httponly"] and morsel["samesite"] == "Lax" and not morsel["secure"]
    assert int(morsel["max-age"]) == 30 * 24 * 3600

    wrong_name = await env.call("POST", "/api/login", body={"name": "nobody", "password": PASSWORD})
    wrong_pass = await env.call("POST", "/api/login", body={"name": "admin", "password": "nope"})
    assert wrong_name.status == wrong_pass.status == 401
    assert await wrong_name.json() == await wrong_pass.json()

    behind_proxy = await env.call(
        "POST", "/api/login", body={"name": "admin", "password": PASSWORD},
        headers={"X-Forwarded-Proto": "https"},
    )
    assert behind_proxy.cookies[SESSION_COOKIE]["secure"]

    for body in ({"name": "admin"}, {"name": 1, "password": "x"}, {"name": "a", "password": "b", "x": 1}):
        resp = await env.call("POST", "/api/login", body=body)
        assert resp.status == 400 and await error_code(resp) == "bad_request"
    resp = await env.call("POST", "/api/login", body=b"{not json")
    assert resp.status == 400 and "not valid JSON" in (await resp.json())["error"]["message"]
    resp = await env.call("POST", "/api/login", body=b"[1]")
    assert resp.status == 400
    resp = await env.call("POST", "/api/login", "read", body={"name": "admin", "password": PASSWORD})
    assert resp.status == 403


async def test_login_throttle(env: Env) -> None:
    cf = {"CF-Connecting-IP": "203.0.113.7"}  # the test client connects from loopback
    for _ in range(5):
        resp = await env.call("POST", "/api/login", body={"name": "admin", "password": "x"}, headers=cf)
        assert resp.status == 401
    resp = await env.call("POST", "/api/login", body={"name": "admin", "password": PASSWORD}, headers=cf)
    assert resp.status == 429 and await error_code(resp) == "too_many_attempts"
    assert resp.headers["Retry-After"] == "30"
    # Another address is not locked out.
    other = await env.call(
        "POST", "/api/login", body={"name": "admin", "password": PASSWORD},
        headers={"CF-Connecting-IP": "203.0.113.8"},
    )
    assert other.status == 200
    env.clock.t += 31
    resp = await env.call("POST", "/api/login", body={"name": "admin", "password": PASSWORD}, headers=cf)
    assert resp.status == 200


async def test_logout(env: Env) -> None:
    resp = await env.call("POST", "/api/logout", "session")
    assert resp.status == 200
    assert resp.cookies[SESSION_COOKIE].value == ""


# --------------------------------------------------------------------------
# Who may do what
# --------------------------------------------------------------------------

ROUTES = [
    # method, path, body, needs
    ("GET", "/api/state", None, "read"),
    ("GET", "/api/robots", None, "read"),
    ("GET", "/api/robots/r1", None, "read"),
    ("GET", "/api/robots/r1/map.png", None, "read"),
    ("GET", "/api/robots/r1/map.bin?rev=aaaa1111", None, "read"),
    ("GET", "/api/robots/r1/path", None, "read"),
    ("GET", "/api/schedules", None, "read"),
    ("GET", "/api/history", None, "read"),
    ("GET", "/api/stats", None, "read"),
    ("POST", "/api/robots/r1/command", {"command": "locate"}, "control"),
    ("POST", "/api/schedules", {}, "control"),
    ("PUT", "/api/schedules/s_none", {}, "control"),
    ("DELETE", "/api/schedules/s_none", None, "control"),
    ("POST", "/api/schedules/s_none/run", None, "control"),
    ("POST", "/api/logout", None, "session"),
    ("PUT", "/api/settings", {"tz": "UTC"}, "session"),
    ("POST", "/api/password", {}, "session"),
    ("GET", "/api/users", None, "session"),
    ("POST", "/api/users", {}, "session"),
    ("DELETE", "/api/users/nobody", None, "session"),
    ("GET", "/api/tokens", None, "session"),
    ("POST", "/api/tokens", {}, "session"),
    ("DELETE", "/api/tokens/t_none", None, "session"),
    ("POST", "/api/account/code", {}, "session"),
    ("POST", "/api/account/login", {}, "session"),
    ("DELETE", "/api/account", None, "session"),
    ("PUT", "/api/tunnel", {}, "session"),
]
RANK = {"read": 1, "control": 2, "session": 3}


@pytest.mark.parametrize("method,path,body,needs", ROUTES)
async def test_auth_matrix(env: Env, method: str, path: str, body, needs: str) -> None:
    resp = await env.call(method, path, "none", body)
    assert resp.status == 401 and await error_code(resp) == "unauthenticated"
    bad = await env.call(method, path, body=body, headers={"Authorization": "Bearer rah_wrong"})
    assert bad.status == 401
    for auth, rank in (("read", 1), ("control", 2)):
        resp = await env.call(method, path, auth, body)
        if RANK[needs] > rank:
            assert resp.status == 403, (auth, resp.status)
            assert await error_code(resp) == "forbidden"
        else:
            assert resp.status not in (401, 403), (auth, resp.status)
    if method != "GET":
        resp = await env.call(method, path, "session", body, headers={"X-RAH": "0"})
        assert resp.status == 403 and "X-RAH" in (await resp.json())["error"]["message"]
    resp = await env.call(method, path, "session", body)
    assert resp.status not in (401, 403), resp.status


async def test_public_routes_need_nothing(env: Env) -> None:
    for path in ("/", "/icon.svg", "/manifest.webmanifest", "/healthz", "/api/docs", "/api/session"):
        assert (await env.call("GET", path)).status == 200


# --------------------------------------------------------------------------
# State and maps
# --------------------------------------------------------------------------


async def test_state_etag_and_gzip(env: Env) -> None:
    resp = await env.call("GET", "/api/state", "read", headers={"Accept-Encoding": "gzip"})
    assert resp.status == 200 and resp.headers["Content-Encoding"] == "gzip"
    state = await resp.json()
    assert state["rev"] == env.hub.rev
    assert state["app"]["account"] == {"linked": False, "email": None, "problem": None}
    assert state["app"]["tunnel"]["mode"] == "off" and state["app"]["demo"] is False
    assert state["robots"][0]["id"] == "r1" and state["schedules"] == []
    etag = resp.headers["ETag"]
    same = await env.call("GET", "/api/state", "read", headers={"If-None-Match": etag})
    assert same.status == 304 and await same.read() == b""
    env.hub.robot_status("r1", replace(DOCKED, battery=50))
    changed = await env.call("GET", "/api/state", "read", headers={"If-None-Match": etag})
    assert changed.status == 200 and (await changed.json())["robots"][0]["battery"] == 50

    robots = await (await env.call("GET", "/api/robots", "read")).json()
    one = await (await env.call("GET", "/api/robots/r1", "read")).json()
    assert robots == [one] and one["battery"] == 50
    missing = await env.call("GET", "/api/robots/nope", "read")
    assert missing.status == 404 and await error_code(missing) == "not_found"


async def test_map_bin(env: Env) -> None:
    rev = env.hub.robot("r1").geom.digest
    resp = await env.call("GET", f"/api/robots/r1/map.bin?rev={rev}", "read", headers={"Accept-Encoding": "gzip"},
                          auto_decompress=False)
    assert resp.status == 200 and resp.headers["Content-Encoding"] == "gzip"
    assert resp.headers["Cache-Control"] == "private, max-age=31536000, immutable"
    assert gzip.decompress(await resp.read()) == frame().index
    plain = await env.call("GET", f"/api/robots/r1/map.bin?rev={rev}", "read", headers={"Accept-Encoding": "identity"})
    assert "Content-Encoding" not in plain.headers and await plain.read() == frame().index
    for query in ("?rev=00000000", ""):
        resp = await env.call("GET", f"/api/robots/r1/map.bin{query}", "read")
        assert resp.status == 404 and await error_code(resp) == "not_found"


async def test_map_png_and_path(env: Env) -> None:
    resp = await env.call("GET", "/api/robots/r1/map.png?scale=3", "read")
    assert resp.status == 200 and resp.headers["Content-Type"] == "image/png"
    png = await resp.read()
    assert png.startswith(b"\x89PNG\r\n\x1a\n")
    again = await env.call("GET", "/api/robots/r1/map.png?scale=3", "read")
    assert await again.read() == png
    assert len(env.client.server.app[SERVER_KEY].pngs) == 1  # drawn once
    for scale in ("0", "9", "x"):
        resp = await env.call("GET", f"/api/robots/r1/map.png?scale={scale}", "read")
        assert resp.status == 400
    resp = await env.call("GET", "/api/robots/r1/path", "read")
    assert await resp.json() == {"rev": 1, "points": [1.0, 1.0, 3.5, 2.5]}
    same = await env.call("GET", "/api/robots/r1/path", "read", headers={"If-None-Match": resp.headers["ETag"]})
    assert same.status == 304
    env.hub.robot_found(RobotInfo("r2", "Upstairs", "m", "M"))
    resp = await env.call("GET", "/api/robots/r2/map.png", "read")
    assert resp.status == 404


async def test_history(env: Env) -> None:
    env.hub.robot_status("r1", CLEANING)
    entries = await (await env.call("GET", "/api/history?limit=1", "read")).json()
    assert entries == [{"ts": int(NOW), "robot": "r1", "kind": "started", "text": "Started cleaning"}]
    for limit in ("0", "201", "x"):
        assert (await env.call("GET", f"/api/history?limit={limit}", "read")).status == 400


# --------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------


async def test_commands(env: Env) -> None:
    resp = await env.call("POST", "/api/robots/r1/command", "control", {"command": "clean_rooms", "rooms": [16], "repeat": 2})
    assert resp.status == 200 and await resp.json() == {"ok": True}
    name, command = env.backend.commands[-1]
    assert name == "r1" and command.name == "clean_rooms" and command.rooms == (16,) and command.repeat == 2
    assert (await (await env.call("GET", "/api/history?limit=1", "read")).json())[0]["text"] == (
        "Clean Kitchen, 2 passes, sent by Agent"
    )
    resp = await env.call("POST", "/api/robots/r1/command", "session", {"command": "set_fan", "value": "turbo"})
    assert resp.status == 200 and env.backend.commands[-1][1].value == "turbo"

    cases = [
        ({"command": "fly"}, 400, "bad_request"),
        ({"command": "start", "rooms": [16]}, 400, "bad_request"),
        ({"command": "clean_rooms", "rooms": [99]}, 400, "bad_request"),
        ({"command": "clean_rooms", "rooms": "16"}, 400, "bad_request"),
        ({"command": "clean_rooms", "rooms": [16], "repeat": 5}, 400, "bad_request"),
        ({"command": "set_fan"}, 400, "bad_request"),
        ({"command": "set_fan", "value": "ludicrous"}, 400, "bad_request"),
    ]
    for body, status, code in cases:
        resp = await env.call("POST", "/api/robots/r1/command", "control", body)
        assert (resp.status, await error_code(resp)) == (status, code), body
    resp = await env.call("POST", "/api/robots/nope/command", "control", {"command": "start"})
    assert resp.status == 404
    resp = await env.call("POST", "/api/robots/r1/command", "control", b'{"command": "start", "x": "' + b"a" * 17000 + b'"}')
    assert resp.status == 400 and "16 kB" in (await resp.json())["error"]["message"]
    resp = await env.call("POST", "/api/robots/r1/command", "control", b"")
    assert resp.status == 400

    env.backend.fail_with = RobotError("robot_unreachable", "Downstairs did not answer.")
    resp = await env.call("POST", "/api/robots/r1/command", "control", {"command": "start"})
    assert resp.status == 503
    assert await resp.json() == {"error": {"code": "robot_unreachable", "message": "Downstairs did not answer."}}
    env.backend.fail_with = RobotError("rejected", "It said no.")
    resp = await env.call("POST", "/api/robots/r1/command", "control", {"command": "start"})
    assert resp.status == 409 and await error_code(resp) == "rejected"


# --------------------------------------------------------------------------
# Schedules and settings
# --------------------------------------------------------------------------

SCHEDULE = {
    "robot": "r1",
    "days": [0, 1, 2, 3, 4],
    "time": "09:30",
    "action": {"type": "clean_rooms", "rooms": [16], "repeat": 1, "fan": None, "water": None},
}


async def test_schedules_need_a_time_zone_then_work(env: Env) -> None:
    resp = await env.call("POST", "/api/schedules", "control", {**SCHEDULE, "enabled": True})
    assert resp.status == 400 and (await resp.json())["error"]["message"] == "Set the time zone first."

    resp = await env.call("PUT", "/api/settings", "session", {"tz": "Mars/Olympus"})
    assert resp.status == 400
    resp = await env.call("PUT", "/api/settings", "session", {"tz": "Australia/Melbourne"})
    assert resp.status == 200
    assert env.config.tz == "Australia/Melbourne"
    assert Config.load(env.config.data_dir).tz == "Australia/Melbourne"
    state = await (await env.call("GET", "/api/state", "read")).json()
    assert state["app"]["tz"] == "Australia/Melbourne"

    resp = await env.call("POST", "/api/schedules", "control", SCHEDULE)
    assert resp.status == 200
    created = await resp.json()
    assert created["name"] == "Kitchen, weekdays 09:30" and created["enabled"] is True
    assert created["next_run"] > NOW and created["last_run"] is None
    listed = await (await env.call("GET", "/api/schedules", "read")).json()
    assert listed == [created]

    updated = await env.call("PUT", f"/api/schedules/{created['id']}", "control", {**created, "name": "Mornings"})
    assert (await updated.json())["name"] == "Mornings"

    run = await env.call("POST", f"/api/schedules/{created['id']}/run", "control")
    assert run.status == 200 and env.backend.commands[-1][1].name == "clean_rooms"
    env.backend.fail_with = RobotError("robot_unreachable", "Downstairs did not answer.")
    run = await env.call("POST", f"/api/schedules/{created['id']}/run", "control")
    assert run.status == 503
    listed = await (await env.call("GET", "/api/schedules", "read")).json()
    assert listed[0]["last_run"]["ok"] is False

    gone = await env.call("DELETE", f"/api/schedules/{created['id']}", "control")
    assert await gone.json() == {"ok": True}
    again = await env.call("DELETE", f"/api/schedules/{created['id']}", "control")
    assert again.status == 404 and await error_code(again) == "not_found"
    assert (await env.call("PUT", "/api/schedules/s_nope", "control", SCHEDULE)).status == 404


# --------------------------------------------------------------------------
# People and tokens
# --------------------------------------------------------------------------


async def test_password_change_signs_out_only_that_person(env: Env) -> None:
    other_session = env.cookie
    resp = await env.call("POST", "/api/password", "session", {"current": "wrong", "new": "a new password"})
    assert resp.status == 400
    resp = await env.call("POST", "/api/password", "session", {"current": PASSWORD, "new": "short"})
    assert resp.status == 400
    resp = await env.call("POST", "/api/password", "session", {"current": PASSWORD, "new": "a new password"})
    assert resp.status == 200
    env.cookie = resp.cookies[SESSION_COOKIE].value
    assert (await env.call("GET", "/api/users", "session")).status == 200  # this session lives on
    env.cookie, new = other_session, env.cookie
    assert (await env.call("GET", "/api/users", "session")).status == 401  # the old one does not
    env.cookie = new
    login = await env.call("POST", "/api/login", body={"name": "admin", "password": "a new password"})
    assert login.status == 200


async def test_users(env: Env) -> None:
    users = await (await env.call("GET", "/api/users", "session")).json()
    assert users == [{"name": "admin", "created": int(NOW), "you": True}]
    resp = await env.call("POST", "/api/users", "session", {"name": "Ann@Example.com", "password": "password1"})
    assert await resp.json() == {"name": "Ann@Example.com", "created": int(NOW), "you": False}
    resp = await env.call("POST", "/api/users", "session", {"name": "ann@example.com", "password": "password1"})
    assert resp.status == 400
    resp = await env.call("POST", "/api/users", "session", {"name": "bob", "password": "short"})
    assert resp.status == 400

    login = await env.call("POST", "/api/login", body={"name": "ann@EXAMPLE.com", "password": "password1"})
    ann_cookie = login.cookies[SESSION_COOKIE].value

    resp = await env.call("DELETE", "/api/users/admin", "session")
    assert resp.status == 400 and "yourself" in (await resp.json())["error"]["message"]
    resp = await env.call("DELETE", "/api/users/nobody", "session")
    assert resp.status == 404
    resp = await env.call("DELETE", "/api/users/ann%40example.com", "session")
    assert await resp.json() == {"ok": True}
    admin_cookie, env.cookie = env.cookie, ann_cookie
    assert (await env.call("GET", "/api/users", "session")).status == 401  # ann is signed out
    env.cookie = admin_cookie


async def test_tokens(env: Env) -> None:
    assert (await env.call("GET", "/api/state", "read")).status == 200
    listed = await (await env.call("GET", "/api/tokens", "session")).json()
    assert [t["name"] for t in listed] == ["Reader", "Agent"]
    assert set(listed[0]) == {"id", "name", "scope", "created", "last_used"}
    resp = await env.call("POST", "/api/tokens", "session", {"name": "Script", "scope": "read"})
    made = await resp.json()
    assert made["id"].startswith("t_") and made["token"].startswith("rah_")
    use = await env.call("GET", "/api/state", headers={"Authorization": f"Bearer {made['token']}"})
    assert use.status == 200
    resp = await env.call("POST", "/api/tokens", "session", {"name": "Bad", "scope": "everything"})
    assert resp.status == 400
    assert (await env.call("DELETE", f"/api/tokens/{made['id']}", "session")).status == 200
    use = await env.call("GET", "/api/state", headers={"Authorization": f"Bearer {made['token']}"})
    assert use.status == 401
    assert (await env.call("DELETE", f"/api/tokens/{made['id']}", "session")).status == 404
    await asyncio.sleep(0.05)  # last_used is written in the background
    stored = json.loads(env.config.config_file.read_text())
    assert any(t["last_used"] == int(NOW) for t in stored["tokens"])
    assert made["token"] not in env.config.config_file.read_text()


async def test_user_added_from_the_command_line_can_sign_in(env: Env) -> None:
    cli_side = Config.load(env.config.data_dir)
    cli_side.add_user("cli-person", "password1", int(NOW))
    cli_side.save()
    resp = await env.call("POST", "/api/login", body={"name": "cli-person", "password": "password1"})
    assert resp.status == 200


# --------------------------------------------------------------------------
# Roborock account and remote access
# --------------------------------------------------------------------------


async def test_account_link_and_unlink(env: Env, monkeypatch: pytest.MonkeyPatch) -> None:
    resp = await env.call("DELETE", "/api/account", "session")
    assert resp.status == 409 and await error_code(resp) == "not_linked"

    linked_backend = FakeBackend()
    fake = types.ModuleType("roborock_at_home.roborock_backend")
    codes: list[str] = []

    async def request_code(email: str) -> None:
        codes.append(email)

    async def login_with_code(email: str, code: str) -> dict:
        if code != "123456":
            raise RobotError("bad_request", "That code is wrong or has expired.")
        return {"email": email, "user_data": {"token": "x"}, "base_url": None}

    fake.request_code = request_code
    fake.login_with_code = login_with_code
    fake.RoborockBackend = lambda account, cache_file: linked_backend
    monkeypatch.setitem(sys.modules, "roborock_at_home.roborock_backend", fake)
    monkeypatch.setattr(roborock_at_home, "roborock_backend", fake, raising=False)

    assert (await env.call("POST", "/api/account/code", "session", {"email": "yolanda@example.com"})).status == 200
    assert codes == ["yolanda@example.com"]
    resp = await env.call("POST", "/api/account/login", "session", {"email": "yolanda@example.com", "code": "999"})
    assert resp.status == 400 and "expired" in (await resp.json())["error"]["message"]
    resp = await env.call("POST", "/api/account/login", "session", {"email": "yolanda@example.com", "code": "123456"})
    assert resp.status == 200
    assert linked_backend.sink is env.hub and env.backend.stopped
    state = await (await env.call("GET", "/api/state", "read")).json()
    assert state["app"]["account"] == {"linked": True, "email": "y***@example.com", "problem": None}
    assert env.config.account_file.exists()

    env.hub.account_problem("Sign in to Roborock again.")
    state = await (await env.call("GET", "/api/state", "read")).json()
    assert state["app"]["account"]["problem"] == "Sign in to Roborock again."

    resp = await env.call("DELETE", "/api/account", "session")
    assert resp.status == 200 and linked_backend.stopped
    state = await (await env.call("GET", "/api/state", "read")).json()
    assert state["robots"] == [] and state["app"]["account"]["linked"] is False
    assert not env.config.account_file.exists()
    assert not (env.config.maps_dir / "r1.bin.gz").exists()


async def test_demo_has_no_account(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    env = await build(tmp_path, monkeypatch, demo=True)
    try:
        for method, path, body in (
            ("POST", "/api/account/code", {"email": "a@b.c"}),
            ("POST", "/api/account/login", {"email": "a@b.c", "code": "1"}),
            ("DELETE", "/api/account", None),
        ):
            resp = await env.call(method, path, "session", body)
            assert resp.status == 409 and await error_code(resp) == "demo"
        state = await (await env.call("GET", "/api/state", "read")).json()
        assert state["app"]["demo"] is True and state["app"]["account"]["linked"] is True
    finally:
        await env.client.close()
        await env.hub.stop()


async def test_tunnel(env: Env) -> None:
    for body in ({"mode": "fast"}, {"mode": "token"}, {"mode": "token", "token": "short"}, {"mode": "quick", "token": "x" * 30}):
        resp = await env.call("PUT", "/api/tunnel", "session", body)
        assert resp.status == 400, body
    resp = await env.call("PUT", "/api/tunnel", "session", {"mode": "quick"})
    assert resp.status == 200
    for _ in range(300):
        state = await (await env.call("GET", "/api/state", "read")).json()
        if state["app"]["tunnel"]["status"] == "running":
            break
        await asyncio.sleep(0.01)
    assert state["app"]["tunnel"] == {
        "mode": "quick", "status": "running", "url": "https://test-words.trycloudflare.com", "error": None,
    }
    assert Config.load(env.config.data_dir).tunnel_mode == "quick"
    secret = "eyJ" + "a" * 60
    resp = await env.call("PUT", "/api/tunnel", "session", {"mode": "token", "token": secret})
    assert resp.status == 200
    state_text = await (await env.call("GET", "/api/state", "read")).text()
    assert secret not in state_text
    resp = await env.call("PUT", "/api/tunnel", "session", {"mode": "off"})
    state = await (await env.call("GET", "/api/state", "read")).json()
    assert state["app"]["tunnel"] == {"mode": "off", "status": "stopped", "url": None, "error": None}
    assert Config.load(env.config.data_dir).tunnel_token is None


# --------------------------------------------------------------------------
# Events
# --------------------------------------------------------------------------


async def read_event(resp) -> tuple[str, str, dict]:
    fields: dict[str, str] = {}
    while True:
        line = (await resp.content.readline()).decode().rstrip("\n")
        if line == "" and fields:
            return fields["event"], fields["id"], json.loads(fields["data"])
        if line.startswith(":") or not line:
            continue
        key, _, value = line.partition(": ")
        fields[key] = value


async def test_events_stream(env: Env) -> None:
    resp = await env.call("GET", "/api/events", "read")
    assert resp.status == 200 and resp.headers["Content-Type"] == "text/event-stream"
    assert resp.headers["Cache-Control"] == "no-cache, no-transform"
    assert resp.headers["X-Accel-Buffering"] == "no" and "Content-Encoding" not in resp.headers
    name, rev, data = await read_event(resp)
    assert name == "state" and int(rev) == env.hub.rev and data["robots"][0]["id"] == "r1"
    assert env.hub.clients == 1
    env.hub.robot_status("r1", replace(DOCKED, battery=33))
    name, rev, data = await asyncio.wait_for(read_event(resp), 2)
    assert name == "robot" and int(rev) == env.hub.rev
    assert data == {"id": "r1", "battery": 33}  # a patch: only what changed
    FRAMES[b"m2"] = frame(robot=(4.5, 2.5), path=(1.0, 1.0, 3.5, 2.5, 4.5, 2.5))
    env.hub.robot_map("r1", b"m2")
    name, _, data = await asyncio.wait_for(read_event(resp), 2)
    assert name == "robot" and set(data) == {"id", "pos"}
    name, _, data = await asyncio.wait_for(read_event(resp), 2)
    assert (name, data) == ("path", {"id": "r1", "rev": 2, "start": 4, "points": [4.5, 2.5]})
    env.hub.robot_status("r1", CLEANING)
    seen = {(await asyncio.wait_for(read_event(resp), 2))[0] for _ in range(2)}
    assert seen == {"robot", "history"}
    resp.close()
    for _ in range(100):
        await asyncio.sleep(0.01)
        env.hub.robot_status("r1", replace(CLEANING, battery=_ % 50))  # writing notices the client left
        if env.hub.clients == 0:
            break
    assert env.hub.clients == 0
