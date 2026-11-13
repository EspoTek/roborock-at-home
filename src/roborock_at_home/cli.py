"""The `roborock-at-home` command: set up, run, manage people and tokens.

`setup` is the second half of the installer (Linux with systemd): it makes
the data directory, installs a user service, starts it and waits until it
answers, so re-running the installer is also how to upgrade. Everything that
touches the system goes through an injectable `run` so tests can check the
exact commands without a systemd.
"""

from __future__ import annotations

import argparse
import asyncio
import getpass
import logging
import os
import shutil
import subprocess
import sys
import time
from collections.abc import Callable
from datetime import datetime
from pathlib import Path

from . import __version__
from .config import (
    Config,
    ConfigMissing,
    Invalid,
    NotFound,
    generate_password,
    resolve_data_dir,
)

SERVICE = "roborock-at-home"
FIRST_USER = "admin"
DEMO_USER = "demo"
HEALTH_WAIT = 30.0

Run = Callable[[list[str]], subprocess.CompletedProcess]


class Fail(Exception):
    """A problem the person at the terminal can fix; printed, exit 1."""


def _run(cmd: list[str]) -> subprocess.CompletedProcess:
    # Callers look at the return code: some failures are expected (is-active).
    return subprocess.run(cmd, capture_output=True, text=True, check=False)


def _must(run: Run, cmd: list[str]) -> subprocess.CompletedProcess:
    result = run(cmd)
    if result.returncode != 0:
        raise Fail(f"{' '.join(cmd)} failed: {(result.stderr or result.stdout).strip()}")
    return result


# --------------------------------------------------------------------------
# init, user, token
# --------------------------------------------------------------------------


def init(data_dir: Path) -> str | None:
    """Create the configuration with the first person. Returns their new
    password, or None when it already existed."""
    if Config.exists(data_dir):
        print(f"Already set up in {data_dir}.")
        return None
    password = generate_password()
    Config.create(data_dir, FIRST_USER, password, int(time.time()))
    print(f"Set up in {data_dir}.")
    print(f"Sign in as: {FIRST_USER}")
    print(f"Password: {password}")
    return password


def read_password(generate: bool) -> tuple[str, bool]:
    """A new password: generated, one line from stdin when it is not a
    terminal (so it never appears in argv or the shell history), else typed
    twice. Returns (password, whether it was generated)."""
    if generate:
        return generate_password(), True
    if not sys.stdin.isatty():
        password = sys.stdin.readline().rstrip("\r\n")
        if not password:
            raise Fail("Expected the password as one line on standard input.")
        return password, False
    first = getpass.getpass("New password: ")
    if getpass.getpass("Again: ") != first:
        raise Fail("The two passwords differ.")
    return first, False


def user_command(data_dir: Path, args: argparse.Namespace) -> None:
    config = Config.load(data_dir)
    if args.action == "list":
        for user in config.users:
            created = datetime.fromtimestamp(user.created).strftime("%Y-%m-%d")
            print(f"{user.name}  (added {created})")
        return
    if args.action == "add":
        password, generated = read_password(args.generate)
        user = config.add_user(args.name, password, int(time.time()))
        config.save()
        print(f"{user.name} can now sign in.")
    elif args.action == "password":
        password, generated = read_password(args.generate)
        user = config.set_password(args.name, password)
        config.save()
        print(f"Changed the password for {user.name}; they are signed out everywhere.")
    else:
        user = config.user(args.name)
        config.remove_user(user.name)
        config.save()
        print(f"Removed {user.name}.")
        return
    if generated:
        print(f"Password: {password}")


def token_command(data_dir: Path, name: str, scope: str) -> None:
    config = Config.load(data_dir)
    token, secret = config.create_token(name, scope, int(time.time()))
    config.save()
    print(f"Token {token.id} ({token.scope}). It is shown only this once:")
    print(secret)


# --------------------------------------------------------------------------
# serve
# --------------------------------------------------------------------------


def _primary_address() -> str:
    """This machine's address on the LAN (no packet is sent)."""
    import socket

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("192.0.2.1", 9))
        except OSError:  # no network at all: only this machine can connect
            return "localhost"
        return s.getsockname()[0]


async def serve(data_dir: Path, host: str | None, port: int | None, demo: bool) -> None:
    import gc
    import signal
    import tempfile

    from aiohttp import web

    from .hub import Hub
    from .scheduler import Scheduler
    from .server import app_info, create_app
    from .tunnel import Tunnel

    if demo:
        data_dir = Path(tempfile.mkdtemp(prefix="roborock-at-home-demo-"))
        password = os.environ.get("RAH_DEMO_PASSWORD") or generate_password()
        Config.create(data_dir, DEMO_USER, password, int(time.time()))
        print("Demo with simulated robots.")
        print(f"Sign in as: {DEMO_USER}")
        print(f"Password: {password}")
    try:
        config = Config.load(data_dir)
        host = host or config.host
        port = port or config.port

        hub = Hub(data_dir, app_info=lambda: app_info(config, hub, tunnel, demo))
        tunnel = Tunnel(config, port, hub.app_changed)
        hub.load()
        hub.set_tz(config.tz)
        scheduler = Scheduler(hub, config.schedules_file)
        scheduler.load(config.tz)
        hub.app_changed()

        app = create_app(config, hub, scheduler, tunnel, demo=demo)
        runner = web.AppRunner(app, access_log=None, handle_signals=False)
        await runner.setup()
        await web.TCPSite(runner, host, port).start()
        shown = _primary_address() if host in ("0.0.0.0", "::", "") else host
        print(f"Roborock at Home is running at http://{shown}:{port}/", flush=True)

        if demo:
            from .demo import DemoBackend

            await hub.set_backend(DemoBackend())
        elif config.account is not None:
            from .roborock_backend import RoborockBackend

            await hub.set_backend(RoborockBackend(config.account, config.cache_file))
        scheduler.start()
        tunnel.start()

        # Everything loaded so far lives as long as the process: keep the
        # collector from walking it again and again.
        gc.collect()
        gc.freeze()

        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, stop.set)
        await stop.wait()
        print("Stopping.", flush=True)
        await tunnel.stop()
        await scheduler.stop()
        await hub.stop()
        await runner.cleanup()
    finally:
        if demo:
            shutil.rmtree(data_dir)


# --------------------------------------------------------------------------
# setup, uninstall
# --------------------------------------------------------------------------


def _quote(arg: str) -> str:
    if arg and all(c.isalnum() or c in "/._-:=@+," for c in arg):
        return arg
    return '"' + arg.replace("\\", "\\\\").replace('"', '\\"') + '"'


def unit_file(exe: str, data_dir: Path, port: int) -> str:
    command = " ".join(_quote(a) for a in (exe, "--data-dir", str(data_dir), "serve", "--port", str(port)))
    return f"""[Unit]
Description=Roborock at Home

[Service]
ExecStart={command}
Restart=on-failure
RestartSec=5
# Fewer malloc arenas: less memory held for threads that barely allocate.
Environment=MALLOC_ARENA_MAX=2
# A fixed mmap threshold: scrypt's 16 MB block goes back to the system after
# each sign-in instead of staying in the heap.
Environment=MALLOC_MMAP_THRESHOLD_=131072
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=default.target
"""


def this_executable() -> str:
    exe = sys.argv[0]
    if os.sep not in exe:
        found = shutil.which(exe)
        if found is None:
            raise Fail(f"Cannot find {exe} on PATH to put in the service.")
        exe = found
    return os.path.abspath(exe)


def has_systemd() -> bool:
    return (
        sys.platform.startswith("linux")
        and shutil.which("systemctl") is not None
        and Path("/run/systemd/system").is_dir()
    )


def unit_path() -> Path:
    config_home = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(config_home) / "systemd" / "user" / f"{SERVICE}.service"


def lan_addresses(run: Run) -> list[str]:
    result = _must(run, ["ip", "-4", "-o", "addr", "show", "scope", "global"])
    addresses = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if "inet" in fields:
            addresses.append(fields[fields.index("inet") + 1].split("/")[0])
    return addresses


def _healthy(port: int) -> bool:
    import urllib.error
    import urllib.request

    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/healthz", timeout=1) as response:
            return response.read() == b"ok"
    except (urllib.error.URLError, ConnectionError, TimeoutError):
        return False  # not listening yet


def setup(
    data_dir: Path,
    port: int,
    run: Run = _run,
    exe: str | None = None,
    unit: Path | None = None,
    user: str | None = None,
    healthy: Callable[[int], bool] = _healthy,
    wait: float = HEALTH_WAIT,
    systemd: Callable[[], bool] = has_systemd,
) -> int:
    if not systemd():
        print(
            "Automatic setup needs Linux with systemd. Run the app by hand instead:\n"
            f"  roborock-at-home init\n  roborock-at-home serve --port {port}"
        )
        return 1
    password = init(data_dir)
    config = Config.load(data_dir)
    if config.port != port:
        config.port = port
        config.save()

    unit = unit or unit_path()
    unit.parent.mkdir(parents=True, exist_ok=True)
    unit.write_text(unit_file(exe or this_executable(), data_dir, port))
    print(f"Wrote {unit}.")
    _must(run, ["systemctl", "--user", "daemon-reload"])
    if run(["systemctl", "--user", "is-active", "--quiet", SERVICE]).returncode == 0:
        _must(run, ["systemctl", "--user", "enable", SERVICE])
        _must(run, ["systemctl", "--user", "restart", SERVICE])
        print("Restarted the service.")
    else:
        _must(run, ["systemctl", "--user", "enable", "--now", SERVICE])
        print("Started the service.")

    user = user or getpass.getuser()
    if run(["loginctl", "enable-linger", user]).returncode != 0:
        print(
            "Could not let the service run while you are logged out, so it will only run "
            f"while you are logged in. To fix it: sudo loginctl enable-linger {user}"
        )

    print("Waiting for it to answer", end="", flush=True)
    deadline = time.monotonic() + wait
    while not healthy(port):
        if time.monotonic() >= deadline:
            print()
            print(f"It did not answer within {wait:.0f} s. See: journalctl --user -u {SERVICE}")
            return 1
        print(".", end="", flush=True)
        time.sleep(1)
    print(" ready.")
    print("Open one of these:")
    for address in lan_addresses(run):
        print(f"  http://{address}:{port}/")
    if password is not None:
        print(f"Sign in as: {FIRST_USER}")
        print(f"Password: {password}")
    return 0


def uninstall(data_dir: Path, purge: bool, run: Run = _run, unit: Path | None = None) -> None:
    unit = unit or unit_path()
    if unit.exists():
        run(["systemctl", "--user", "disable", "--now", SERVICE])
        unit.unlink()
        _must(run, ["systemctl", "--user", "daemon-reload"])
        print(f"Stopped the service and removed {unit}.")
    else:
        print("No service was installed.")
    if purge:
        if data_dir.exists():
            shutil.rmtree(data_dir)
            print(f"Deleted {data_dir}.")
        else:
            print(f"There was no data in {data_dir}.")


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def _parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument(
        "--data-dir",
        default=argparse.SUPPRESS,
        help="where the app keeps its files (default: $RAH_DATA_DIR, else "
        "$XDG_DATA_HOME/roborock-at-home, else ~/.local/share/roborock-at-home)",
    )
    parser = argparse.ArgumentParser(
        prog="roborock-at-home",
        description="A small, fast, self-hosted control panel and API for Roborock vacuums.",
        parents=[common],
    )
    parser.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("init", parents=[common], help="create the configuration and the first person")

    serve_p = sub.add_parser("serve", parents=[common], help="run the app")
    serve_p.add_argument("--host", help="address to listen on (default: from the configuration, 0.0.0.0)")
    serve_p.add_argument("--port", type=int, help="port (default: from the configuration, 8770)")
    serve_p.add_argument("--demo", action="store_true", help="simulated robots, nothing saved")
    serve_p.add_argument("--verbose", action="store_true", help="log more")

    user_p = sub.add_parser("user", parents=[common], help="people who can sign in")
    user_sub = user_p.add_subparsers(dest="action", required=True)
    for action, text in (("add", "add a person"), ("password", "change a person's password")):
        p = user_sub.add_parser(action, parents=[common], help=text)
        p.add_argument("name")
        p.add_argument("--generate", action="store_true", help="make up a password and print it")
    p = user_sub.add_parser("remove", parents=[common], help="remove a person")
    p.add_argument("name")
    user_sub.add_parser("list", parents=[common], help="list the people")

    token_p = sub.add_parser("token", parents=[common], help="create an API token")
    token_p.add_argument("--name", required=True)
    token_p.add_argument("--scope", required=True, choices=("read", "control"))

    setup_p = sub.add_parser("setup", parents=[common], help="install as a service (Linux, systemd)")
    setup_p.add_argument("--port", type=int, default=8770)

    uninstall_p = sub.add_parser("uninstall", parents=[common], help="remove the service")
    uninstall_p.add_argument("--purge", action="store_true", help="also delete all data")
    return parser


def main(argv: list[str] | None = None) -> None:
    args = _parser().parse_args(argv)
    data_dir = resolve_data_dir(getattr(args, "data_dir", None))  # optional flag
    verbose = args.command == "serve" and args.verbose
    logging.basicConfig(
        level=logging.INFO if verbose else logging.WARNING,
        format="%(levelname)s %(name)s: %(message)s",
    )
    try:
        if args.command == "init":
            init(data_dir)
        elif args.command == "serve":
            asyncio.run(serve(data_dir, args.host, args.port, args.demo))
        elif args.command == "user":
            user_command(data_dir, args)
        elif args.command == "token":
            token_command(data_dir, args.name, args.scope)
        elif args.command == "setup":
            sys.exit(setup(data_dir, args.port))
        elif args.command == "uninstall":
            uninstall(data_dir, args.purge)
    except (Fail, ConfigMissing, Invalid, NotFound) as exc:
        print(exc, file=sys.stderr)
        sys.exit(1)
