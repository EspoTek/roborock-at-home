"""The HTTP side: every route in static/API.md, on aiohttp.

No middleware stack. Each handler is wrapped by `api()`, which checks who is
asking (one function call) and turns RobotError / Invalid / NotFound into the
JSON errors API.md documents; anything else is a bug and stays a 500.

Nothing is formatted per request that could have been formatted when the data
changed: the page, icon, manifest and API.md are read and gzipped once at
start and served with an ETag; `/api/state` is the Hub's ready-made bytes
(and gzipped copy); `map.bin` is the stored gzip, served as is.
"""

from __future__ import annotations

import asyncio
import functools
import gzip
import hashlib
import json
import os
import secrets
import sys
import time
import zlib
from collections import OrderedDict
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from zoneinfo import available_timezones

from aiohttp import web

from . import __version__, rrmap
from .config import (
    SESSION_COOKIE,
    SESSION_SECONDS,
    Config,
    Invalid,
    LoginThrottle,
    NotFound,
    Token,
    User,
    arrived_over_https,
    client_address,
    mask_email,
)
from .hub import Hub, dumps
from .model import COMMANDS, Command, RobotError
from .scheduler import Scheduler
from .tunnel import MODES, Tunnel

STATIC_DIR = Path(__file__).parent / "static"
SERVER_KEY: web.AppKey[Server] = web.AppKey("server")
MAX_BODY = 16 * 1024
HEARTBEAT = 25.0
PNG_CACHE = 6
# last_used is a hint for the Settings page, not an audit log: writing
# config.json on every request would cost far more than it tells.
TOKEN_TOUCH_EVERY = 600
# Each password check holds 16 MB for scrypt; a burst of sign-ins from many
# addresses must not add that up on a 512 MB machine.
PASSWORD_CHECKS_AT_ONCE = 2

PAGE_HEADERS = {
    "Content-Security-Policy": (
        "default-src 'self'; script-src 'self' 'unsafe-inline'; "
        "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
        "connect-src 'self'; frame-ancestors 'none'"
    ),
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
}

_JSON = "application/json"
_OK = b'{"ok":true}'


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, headers: dict | None = None) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.headers = headers


def error_response(status: int, code: str, message: str, headers: dict | None = None) -> web.Response:
    body = dumps({"error": {"code": code, "message": message}})
    return web.Response(
        status=status, body=body, headers={"Content-Type": _JSON, **(headers or {})}
    )


def json_response(body: bytes, status: int = 200) -> web.Response:
    return web.Response(status=status, body=body, headers={"Content-Type": _JSON})


def bad(message: str) -> ApiError:
    return ApiError(400, "bad_request", message)


@dataclass(slots=True)
class Who:
    kind: str  # "session" or "token"
    scope: str  # "all", "read" or "control"
    user: User | None
    token: Token | None


@dataclass(slots=True)
class Static:
    body: bytes
    gz: bytes
    etag: str
    content_type: str


def _static(path: Path, content_type: str) -> Static:
    body = path.read_bytes()
    return Static(
        body,
        gzip.compress(body, 9, mtime=0),
        f'"{hashlib.sha256(body).hexdigest()[:20]}"',
        content_type,
    )


def _wants_gzip(request: web.Request) -> bool:
    return "gzip" in request.headers.get("Accept-Encoding", "")  # absent: plain


def rss_mb() -> float:
    if sys.platform.startswith("linux"):
        with open("/proc/self/statm") as f:
            pages = int(f.read().split()[1])
        return round(pages * os.sysconf("SC_PAGE_SIZE") / 2**20, 1)
    import resource

    # Elsewhere only the peak is available; macOS reports it in bytes.
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return round(peak / 2**20 if sys.platform == "darwin" else peak / 2**10, 1)


def app_info(config: Config, hub: Hub, tunnel: Tunnel, demo: bool) -> dict:
    """The `app` object of /api/state."""
    if demo:
        account = {"linked": True, "email": None, "problem": None}
    elif config.account is None:
        account = {"linked": False, "email": None, "problem": None}
    else:
        account = {
            "linked": True,
            "email": mask_email(config.account["email"]),
            "problem": hub.account_problem_message,
        }
    return {
        "version": __version__,
        "demo": demo,
        "tz": config.tz,
        "account": account,
        "tunnel": tunnel.info(),
    }


Handler = Callable[["Server", web.Request, "Who | None"], Awaitable[web.StreamResponse]]


def api(scope: str | None, session_only: bool = False) -> Callable[[Handler], Callable]:
    """Wrap a handler: check who is asking (scope None: anyone) and turn
    the errors API.md documents into responses."""

    def wrap(fn: Handler) -> Callable:
        async def handler(self: Server, request: web.Request) -> web.StreamResponse:
            try:
                who = None if scope is None else self.authorise(request, scope, session_only)
                return await fn(self, request, who)
            except ApiError as exc:
                return error_response(exc.status, exc.code, exc.message, exc.headers)
            except RobotError as exc:
                return error_response(exc.http_status, exc.code, exc.message)
            except Invalid as exc:
                return error_response(400, "bad_request", str(exc))
            except NotFound as exc:
                return error_response(404, "not_found", str(exc))

        return functools.wraps(fn)(handler)

    return wrap


async def read_json(request: web.Request) -> dict:
    if request.content_length is not None and request.content_length > MAX_BODY:
        raise bad("The request body is larger than 16 kB.")
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await request.content.read(MAX_BODY + 1 - total)
        if not chunk:
            break
        chunks.append(chunk)
        total += len(chunk)
        if total > MAX_BODY:
            raise bad("The request body is larger than 16 kB.")
    raw = b"".join(chunks)
    if not raw:
        raise bad("This request needs a JSON body.")
    try:
        data = json.loads(raw)
    except ValueError as exc:
        raise bad(f"The body is not valid JSON: {exc}.") from exc
    if not isinstance(data, dict):
        raise bad("The body must be a JSON object.")
    return data


def _text(body: dict, key: str) -> str:
    if key not in body:
        raise bad(f"{key!r} is required.")
    value = body[key]
    if not isinstance(value, str):
        raise bad(f"{key!r} must be text.")
    return value


def _only(body: dict, allowed: set[str]) -> None:
    for key in body:
        if key not in allowed:
            raise bad(f"Unknown field {key!r}.")


class Server:
    def __init__(
        self,
        config: Config,
        hub: Hub,
        scheduler: Scheduler,
        tunnel: Tunnel,
        static_dir: Path,
        demo: bool,
        clock: Callable[[], float],
    ) -> None:
        self.config = config
        self.hub = hub
        self.scheduler = scheduler
        self.tunnel = tunnel
        self.demo = demo
        self.clock = clock
        self.started = time.monotonic()
        self.throttle = LoginThrottle()
        # A restart starts the revision over; the boot id keeps an old ETag
        # from matching a new state.
        self.boot = secrets.token_hex(4)
        self.page = _static(static_dir / "index.html", "text/html; charset=utf-8")
        self.icon = _static(static_dir / "icon.svg", "image/svg+xml")
        self.manifest = _static(static_dir / "manifest.webmanifest", "application/manifest+json")
        self.docs = _static(static_dir / "API.md", "text/markdown; charset=utf-8")
        self.pngs: OrderedDict[tuple, bytes] = OrderedDict()
        self.save_lock = asyncio.Lock()
        self.password_checks = asyncio.Semaphore(PASSWORD_CHECKS_AT_ONCE)
        self.tasks: set[asyncio.Task[None]] = set()

    # -- who is asking -----------------------------------------------------

    def identify(self, request: web.Request) -> Who | None:
        """Who sent this request, or None for nobody (or a bad credential)."""
        self.config.reload_if_changed()
        auth = request.headers.get("Authorization")  # absent: try the cookie
        if auth is not None:
            scheme, _, secret = auth.partition(" ")
            if scheme.lower() != "bearer":
                return None
            token = self.config.token_or_none(secret.strip())
            if token is None:
                return None
            now = int(self.clock())
            if token.last_used is None or now - token.last_used >= TOKEN_TOUCH_EVERY:
                token.last_used = now
                self.spawn(self.save_config())
            return Who("token", token.scope, None, token)
        cookie = request.cookies.get(SESSION_COOKIE)  # absent: not signed in
        if cookie is None:
            return None
        user = self.config.session_user_or_none(cookie, int(self.clock()))
        return None if user is None else Who("session", "all", user, None)

    def authorise(self, request: web.Request, scope: str, session_only: bool) -> Who:
        who = self.identify(request)
        if who is None:
            if "Authorization" in request.headers:
                raise ApiError(401, "unauthenticated", "That token is not valid.")
            raise ApiError(401, "unauthenticated", "Sign in first.")
        if who.kind == "token":
            if session_only:
                raise ApiError(403, "forbidden", "Only a signed-in person can do this, not a token.")
            if scope == "control" and who.scope != "control":
                raise ApiError(403, "forbidden", "This token can only read.")
        elif request.method != "GET" and request.headers.get("X-RAH") != "1":
            raise ApiError(403, "forbidden", "Send the header X-RAH: 1 with this request.")
        return who

    def spawn(self, coro: Awaitable[None]) -> None:
        task = asyncio.ensure_future(coro)
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    async def save_config(self) -> None:
        async with self.save_lock:
            data = self.config.snapshot()
            await asyncio.to_thread(self.config.write_snapshot, data)

    def set_session(self, request: web.Request, response: web.Response, user: User) -> None:
        peer = request.remote
        response.set_cookie(
            SESSION_COOKIE,
            self.config.make_session(user, int(self.clock())),
            max_age=SESSION_SECONDS,
            path="/",
            httponly=True,
            samesite="Lax",
            secure=arrived_over_https(
                peer, request.secure, request.headers.get("X-Forwarded-Proto")
            ),
        )

    def address(self, request: web.Request) -> str:
        return client_address(request.remote, request.headers.get("CF-Connecting-IP"))

    # -- static ------------------------------------------------------------

    def serve_static(self, request: web.Request, static: Static, page: bool = False) -> web.Response:
        headers = {"ETag": static.etag, "Cache-Control": "no-cache", "Vary": "Accept-Encoding"}
        if page:
            # A browser that knows stale-while-revalidate paints its cached
            # copy at once and checks for a new one behind it.
            headers.update(PAGE_HEADERS)
            headers["Cache-Control"] = "max-age=0, stale-while-revalidate=604800"
        if request.headers.get("If-None-Match") == static.etag:
            return web.Response(status=304, headers=headers)
        headers["Content-Type"] = static.content_type
        if _wants_gzip(request):
            headers["Content-Encoding"] = "gzip"
            return web.Response(body=static.gz, headers=headers)
        return web.Response(body=static.body, headers=headers)

    async def index(self, request: web.Request) -> web.Response:
        return self.serve_static(request, self.page, page=True)

    async def icon_svg(self, request: web.Request) -> web.Response:
        return self.serve_static(request, self.icon)

    async def manifest_file(self, request: web.Request) -> web.Response:
        return self.serve_static(request, self.manifest)

    async def healthz(self, request: web.Request) -> web.Response:
        return web.Response(body=b"ok", headers={"Content-Type": "text/plain"})

    async def api_docs(self, request: web.Request) -> web.Response:
        return self.serve_static(request, self.docs)

    @api(None)
    async def not_found(self, request: web.Request, who: Who | None) -> web.Response:
        raise ApiError(404, "not_found", f"There is no {request.method} {request.path}.")

    # -- session -----------------------------------------------------------

    @api(None)
    async def session(self, request: web.Request, who: Who | None) -> web.Response:
        who = self.identify(request)
        if who is None:
            return json_response(b'{"authenticated":false}')
        return json_response(
            dumps(
                {
                    "authenticated": True,
                    "kind": who.kind,
                    "scope": who.scope,
                    "name": None if who.user is None else who.user.name,
                    "theme": None if who.user is None else who.user.theme,
                }
            )
        )

    @api(None)
    async def login(self, request: web.Request, who: Who | None) -> web.Response:
        if "Authorization" in request.headers:
            raise ApiError(403, "forbidden", "Only a person can sign in, not a token.")
        body = await read_json(request)
        _only(body, {"name", "password"})
        name, password = _text(body, "name"), _text(body, "password")
        address = self.address(request)
        wait = self.throttle.retry_after(address, self.clock())
        if wait:
            raise ApiError(
                429,
                "too_many_attempts",
                f"Too many wrong passwords. Try again in {wait} seconds.",
                {"Retry-After": str(wait)},
            )
        self.config.reload_if_changed()
        async with self.password_checks:
            user = await asyncio.to_thread(self.config.check_login, name, password)
        if user is None:
            self.throttle.failed(address, self.clock())
            raise ApiError(401, "unauthenticated", "Wrong name or password.")
        self.throttle.succeeded(address)
        response = json_response(_OK)
        self.set_session(request, response, user)
        return response

    @api("read", session_only=True)
    async def logout(self, request: web.Request, who: Who | None) -> web.Response:
        response = json_response(_OK)
        response.del_cookie(SESSION_COOKIE, path="/")
        return response

    # -- state -------------------------------------------------------------

    @api("read")
    async def state(self, request: web.Request, who: Who | None) -> web.Response:
        hub = self.hub
        body = hub.state()
        # Weak: `seen` may move inside one revision (it causes no revision).
        etag = f'W/"{self.boot}-{hub.rev}"'
        headers = {"ETag": etag, "Cache-Control": "no-cache", "Vary": "Accept-Encoding"}
        if request.headers.get("If-None-Match") == etag:
            return web.Response(status=304, headers=headers)
        headers["Content-Type"] = _JSON
        if _wants_gzip(request):
            headers["Content-Encoding"] = "gzip"
            return web.Response(body=hub.state_gz(), headers=headers)
        return web.Response(body=body, headers=headers)

    @api("read")
    async def robots(self, request: web.Request, who: Who | None) -> web.Response:
        return json_response(self.hub.robots_body())

    @api("read")
    async def robot(self, request: web.Request, who: Who | None) -> web.Response:
        return json_response(self.hub.robot(request.match_info["id"]).fragment)

    @api("read")
    async def events(self, request: web.Request, who: Who | None) -> web.StreamResponse:
        response = web.StreamResponse(
            headers={
                "Content-Type": "text/event-stream",
                "Cache-Control": "no-cache, no-transform",
                "X-Accel-Buffering": "no",
            }
        )
        await response.prepare(request)
        hub = self.hub
        queue = hub.subscribe()  # before the state, so nothing in between is lost
        try:
            await response.write(hub.event(b"state", hub.state()))
            while True:
                try:
                    message = await asyncio.wait_for(queue.get(), HEARTBEAT)
                except TimeoutError:
                    message = b": keep-alive\n\n"
                if message is None:  # dropped as too slow, or shutting down
                    break
                await response.write(message)
        except ConnectionResetError:
            pass  # the client went away: the normal end of a stream
        finally:
            hub.unsubscribe(queue)
        return response

    @api("read")
    async def history(self, request: web.Request, who: Who | None) -> web.Response:
        raw = request.query.get("limit", "50")  # optional, documented default
        if not raw.isdigit() or not 1 <= int(raw) <= 200:
            raise bad("'limit' is a number from 1 to 200.")
        return json_response(dumps(self.hub.history(int(raw))))

    @api("read")
    async def stats(self, request: web.Request, who: Who | None) -> web.Response:
        return json_response(
            dumps(
                {
                    "rss_mb": rss_mb(),
                    "uptime_s": int(time.monotonic() - self.started),
                    "clients": self.hub.clients,
                    "robots": len(self.hub.robots),
                }
            )
        )

    # -- maps --------------------------------------------------------------

    @api("read")
    async def map_png(self, request: web.Request, who: Who | None) -> web.Response:
        raw = request.query.get("scale", "2")  # optional, documented default
        if not raw.isdigit() or not 1 <= int(raw) <= 8:
            raise bad("'scale' is a number from 1 to 8.")
        scale = int(raw)
        robot_id = request.match_info["id"]
        key = (*self.hub.render_key(robot_id), scale)
        png = self.pngs.get(key)  # absent: not drawn yet
        if png is None:
            frame = self.hub.frame_for_render(robot_id)
            png = await asyncio.to_thread(rrmap.render_png, frame, scale)
            self.pngs[key] = png
            while len(self.pngs) > PNG_CACHE:
                self.pngs.popitem(last=False)
        else:
            self.pngs.move_to_end(key)
        return web.Response(body=png, headers={"Content-Type": "image/png", "Cache-Control": "no-cache"})

    @api("read")
    async def map_bin(self, request: web.Request, who: Who | None) -> web.Response:
        robot = self.hub.robot(request.match_info["id"])
        rev = request.query.get("rev")  # absent: cannot be the current map
        if robot.geom is None or robot.map_gz is None or rev != robot.geom.digest:
            raise ApiError(404, "not_found", "That is not the current map; read map.rev from /api/state.")
        headers = {
            "Content-Type": "application/octet-stream",
            "Cache-Control": "private, max-age=31536000, immutable",
            "Vary": "Accept-Encoding",
        }
        if _wants_gzip(request):
            headers["Content-Encoding"] = "gzip"
            return web.Response(body=robot.map_gz, headers=headers)
        return web.Response(body=zlib.decompress(robot.map_gz, 31), headers=headers)

    @api("read")
    async def path(self, request: web.Request, who: Who | None) -> web.Response:
        robot_id = request.match_info["id"]
        body = self.hub.path_body(robot_id)
        etag = f'"{self.boot}-{robot_id}-{self.hub.robot(robot_id).path_rev}"'
        headers = {"ETag": etag, "Cache-Control": "no-cache"}
        if request.headers.get("If-None-Match") == etag:
            return web.Response(status=304, headers=headers)
        headers["Content-Type"] = _JSON
        return web.Response(body=body, headers=headers)

    # -- commands ----------------------------------------------------------

    @api("control")
    async def command(self, request: web.Request, who: Who | None) -> web.Response:
        assert who is not None
        robot_id = request.match_info["id"]
        self.hub.robot(robot_id)  # 404 before 400
        body = await read_json(request)
        name = body.get("command")  # validated next
        if not isinstance(name, str) or name not in COMMANDS:
            raise bad(f"'command' is one of {', '.join(COMMANDS)}.")
        fields = COMMANDS[name]
        _only(body, {"command", *fields})
        rooms: tuple[int, ...] = ()
        repeat = 1
        value = None
        if "rooms" in fields:
            raw_rooms = body.get("rooms")  # checked just below
            if not isinstance(raw_rooms, list) or not all(
                isinstance(r, int) and not isinstance(r, bool) for r in raw_rooms
            ):
                raise bad("'rooms' is a list of room ids.")
            rooms = tuple(raw_rooms)
            repeat = body.get("repeat", 1)  # optional, one pass by default
            if not isinstance(repeat, int) or isinstance(repeat, bool):
                raise bad("'repeat' is 1, 2 or 3.")
        if "value" in fields:
            value = _text(body, "value")
        source = None if who.token is None else who.token.name
        await self.hub.command(robot_id, Command(name, rooms, repeat, value), source)
        return json_response(_OK)

    # -- schedules ---------------------------------------------------------

    @api("read")
    async def schedules(self, request: web.Request, who: Who | None) -> web.Response:
        return json_response(self.hub.schedules_body)

    @api("control")
    async def schedule_create(self, request: web.Request, who: Who | None) -> web.Response:
        body = await read_json(request)
        return json_response(dumps(await self.scheduler.create(body)))

    @api("control")
    async def schedule_update(self, request: web.Request, who: Who | None) -> web.Response:
        schedule_id = request.match_info["id"]
        self.scheduler.get(schedule_id)
        body = await read_json(request)
        return json_response(dumps(await self.scheduler.update(schedule_id, body)))

    @api("control")
    async def schedule_delete(self, request: web.Request, who: Who | None) -> web.Response:
        await self.scheduler.delete(request.match_info["id"])
        return json_response(_OK)

    @api("control")
    async def schedule_run(self, request: web.Request, who: Who | None) -> web.Response:
        await self.scheduler.run_now(request.match_info["id"])
        return json_response(_OK)

    # -- settings (session only) -------------------------------------------

    @api("all", session_only=True)
    async def settings(self, request: web.Request, who: Who | None) -> web.Response:
        body = await read_json(request)
        _only(body, {"tz"})
        tz = _text(body, "tz")
        if tz not in available_timezones():
            raise bad(f"{tz!r} is not a time zone name like Europe/London.")
        self.config.tz = tz
        await self.save_config()
        self.hub.set_tz(tz)
        await self.scheduler.set_tz(tz)
        self.hub.app_changed()
        return json_response(_OK)

    @api("all", session_only=True)
    async def me(self, request: web.Request, who: Who | None) -> web.Response:
        assert who is not None and who.user is not None
        body = await read_json(request)
        _only(body, {"theme"})
        self.config.set_theme(who.user.name, _text(body, "theme"))
        await self.save_config()
        return json_response(_OK)

    @api("all", session_only=True)
    async def password(self, request: web.Request, who: Who | None) -> web.Response:
        assert who is not None and who.user is not None
        body = await read_json(request)
        _only(body, {"current", "new"})
        current, new = _text(body, "current"), _text(body, "new")
        address = self.address(request)
        wait = self.throttle.retry_after(address, self.clock())
        if wait:
            raise ApiError(
                429,
                "too_many_attempts",
                f"Too many wrong passwords. Try again in {wait} seconds.",
                {"Retry-After": str(wait)},
            )
        async with self.password_checks:
            right = await asyncio.to_thread(self.config.check_login, who.user.name, current)
        if not right:
            self.throttle.failed(address, self.clock())
            raise bad("The current password is wrong.")
        self.throttle.succeeded(address)
        user = self.config.set_password(who.user.name, new)
        await self.save_config()
        response = json_response(_OK)
        self.set_session(request, response, user)  # this session stays signed in
        return response

    @api("all", session_only=True)
    async def users(self, request: web.Request, who: Who | None) -> web.Response:
        assert who is not None and who.user is not None
        me = who.user.key
        return json_response(
            dumps([{"name": u.name, "created": u.created, "you": u.key == me} for u in self.config.users])
        )

    @api("all", session_only=True)
    async def user_add(self, request: web.Request, who: Who | None) -> web.Response:
        body = await read_json(request)
        _only(body, {"name", "password"})
        user = self.config.add_user(_text(body, "name"), _text(body, "password"), int(self.clock()))
        await self.save_config()
        return json_response(dumps({"name": user.name, "created": user.created, "you": False}))

    @api("all", session_only=True)
    async def user_remove(self, request: web.Request, who: Who | None) -> web.Response:
        assert who is not None and who.user is not None
        user = self.config.user(request.match_info["name"])
        if user.key == who.user.key:
            raise bad("You cannot remove yourself.")
        self.config.remove_user(user.name)
        await self.save_config()
        return json_response(_OK)

    @api("all", session_only=True)
    async def tokens(self, request: web.Request, who: Who | None) -> web.Response:
        return json_response(dumps([t.public() for t in self.config.tokens]))

    @api("all", session_only=True)
    async def token_create(self, request: web.Request, who: Who | None) -> web.Response:
        body = await read_json(request)
        _only(body, {"name", "scope"})
        token, secret = self.config.create_token(
            _text(body, "name"), _text(body, "scope"), int(self.clock())
        )
        await self.save_config()
        return json_response(dumps({"id": token.id, "token": secret}))

    @api("all", session_only=True)
    async def token_delete(self, request: web.Request, who: Who | None) -> web.Response:
        self.config.delete_token(request.match_info["id"])
        await self.save_config()
        return json_response(_OK)

    # -- Roborock account (session only) -----------------------------------

    def _not_in_demo(self) -> None:
        if self.demo:
            raise ApiError(409, "demo", "The demo has simulated robots and no Roborock account.")

    @api("all", session_only=True)
    async def account_code(self, request: web.Request, who: Who | None) -> web.Response:
        self._not_in_demo()
        body = await read_json(request)
        _only(body, {"email"})
        email = _text(body, "email").strip()
        if "@" not in email:
            raise bad("Enter the email address of your Roborock account.")
        from . import roborock_backend  # only ever loaded for a real account

        await roborock_backend.request_code(email)
        return json_response(_OK)

    @api("all", session_only=True)
    async def account_login(self, request: web.Request, who: Who | None) -> web.Response:
        self._not_in_demo()
        body = await read_json(request)
        _only(body, {"email", "code"})
        email, code = _text(body, "email").strip(), _text(body, "code").strip()
        if not code:
            raise bad("Enter the code Roborock emailed you.")
        from . import roborock_backend  # only ever loaded for a real account

        account = await roborock_backend.login_with_code(email, code)
        old = self.config.account
        if old is not None and old["email"].casefold() != account["email"].casefold():
            # A different account: the old one's robots are not this one's.
            await self.hub.set_backend(None)
            await self.scheduler.remove_all()
        await asyncio.to_thread(self.config.set_account, account)
        self.hub.account_problem_message = None
        await self.hub.set_backend(roborock_backend.RoborockBackend(account, self.config.cache_file))
        self.hub.app_changed()
        return json_response(_OK)

    @api("all", session_only=True)
    async def account_unlink(self, request: web.Request, who: Who | None) -> web.Response:
        self._not_in_demo()
        if self.config.account is None:
            raise ApiError(409, "not_linked", "No Roborock account is linked.")
        await self.hub.set_backend(None)
        await self.scheduler.remove_all()
        await asyncio.to_thread(self.config.set_account, None)
        self.hub.account_problem_message = None
        self.hub.app_changed()
        return json_response(_OK)

    # -- remote access (session only) --------------------------------------

    @api("all", session_only=True)
    async def tunnel_mode(self, request: web.Request, who: Who | None) -> web.Response:
        body = await read_json(request)
        _only(body, {"mode", "token"})
        mode = _text(body, "mode")
        if mode not in MODES:
            raise bad(f"'mode' is one of {', '.join(MODES)}.")
        token = None
        if mode == "token":
            token = _text(body, "token").strip()
            if not 20 <= len(token) <= 4096 or any(c.isspace() for c in token):
                raise bad("Paste the tunnel token from the Cloudflare dashboard.")
        elif "token" in body:
            raise bad("Only token mode takes a token.")
        self.config.tunnel_mode = mode
        self.config.tunnel_token = token
        await self.save_config()
        await self.tunnel.restart()
        return json_response(_OK)

    async def on_shutdown(self, app: web.Application) -> None:
        # Ends every event stream so the runner is not kept waiting on them.
        self.hub.close_subscribers()


def create_app(
    config: Config,
    hub: Hub,
    scheduler: Scheduler,
    tunnel: Tunnel,
    static_dir: Path = STATIC_DIR,
    demo: bool = False,
    clock: Callable[[], float] = time.time,
) -> web.Application:
    server = Server(config, hub, scheduler, tunnel, static_dir, demo, clock)
    app = web.Application(client_max_size=MAX_BODY)
    app[SERVER_KEY] = server
    app.on_shutdown.append(server.on_shutdown)
    r = app.router
    r.add_get("/", server.index)
    r.add_get("/icon.svg", server.icon_svg)
    r.add_get("/manifest.webmanifest", server.manifest_file)
    r.add_get("/healthz", server.healthz)
    r.add_get("/api/docs", server.api_docs)
    r.add_get("/api/session", server.session)
    r.add_post("/api/login", server.login)
    r.add_post("/api/logout", server.logout)
    r.add_get("/api/state", server.state)
    r.add_get("/api/robots", server.robots)
    r.add_get("/api/robots/{id}", server.robot)
    r.add_get("/api/robots/{id}/map.png", server.map_png)
    r.add_get("/api/robots/{id}/map.bin", server.map_bin)
    r.add_get("/api/robots/{id}/path", server.path)
    r.add_post("/api/robots/{id}/command", server.command)
    r.add_get("/api/events", server.events)
    r.add_get("/api/schedules", server.schedules)
    r.add_post("/api/schedules", server.schedule_create)
    r.add_put("/api/schedules/{id}", server.schedule_update)
    r.add_delete("/api/schedules/{id}", server.schedule_delete)
    r.add_post("/api/schedules/{id}/run", server.schedule_run)
    r.add_get("/api/history", server.history)
    r.add_get("/api/stats", server.stats)
    r.add_put("/api/settings", server.settings)
    r.add_put("/api/me", server.me)
    r.add_post("/api/password", server.password)
    r.add_get("/api/users", server.users)
    r.add_post("/api/users", server.user_add)
    r.add_delete("/api/users/{name}", server.user_remove)
    r.add_get("/api/tokens", server.tokens)
    r.add_post("/api/tokens", server.token_create)
    r.add_delete("/api/tokens/{id}", server.token_delete)
    r.add_post("/api/account/code", server.account_code)
    r.add_post("/api/account/login", server.account_login)
    r.add_delete("/api/account", server.account_unlink)
    r.add_put("/api/tunnel", server.tunnel_mode)
    r.add_route("*", "/api/{tail:.*}", server.not_found)
    return app
