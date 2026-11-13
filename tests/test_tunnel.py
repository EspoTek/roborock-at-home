from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

import pytest

from roborock_at_home import tunnel as tunnel_module
from roborock_at_home.config import Config
from roborock_at_home.tunnel import (
    Tunnel,
    download_name,
    error_summary,
    parse_line,
    tunnel_args,
    tunnel_env,
)

TOKEN = "eyJhIjoiZmFrZS10b2tlbi1mb3ItdGVzdHMtb25seSJ9"

# Lines in the shape cloudflared prints them.
BANNER = [
    "2026-11-13T10:00:00Z INF Thank you for trying Cloudflare Tunnel. Doing so, without a Cloudflare account, is a quick way to experiment",
    "2026-11-13T10:00:00Z INF Requesting new quick Tunnel on trycloudflare.com...",
    "2026-11-13T10:00:01Z INF +--------------------------------------------------------------------------------------------+",
    "2026-11-13T10:00:01Z INF |  Your quick Tunnel has been created! Visit it at (it may take some time to be reachable):  |",
    "2026-11-13T10:00:01Z INF |  https://quiet-river-bright-lamp.trycloudflare.com                                         |",
    "2026-11-13T10:00:01Z INF +--------------------------------------------------------------------------------------------+",
]
REGISTERED = (
    "2026-11-13T10:00:02Z INF Registered tunnel connection connIndex=0 "
    "connection=00000000-0000-0000-0000-000000000000 event=0 ip=198.41.200.13 location=syd07 protocol=quic"
)


def test_args() -> None:
    assert tunnel_args("quick", 8770) == [
        "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:8770",
    ]
    assert tunnel_args("token", 8771) == [
        "tunnel", "--no-autoupdate", "run", "--url", "http://127.0.0.1:8771",
    ]
    with pytest.raises(ValueError):
        tunnel_args("off", 8770)
    base = {"PATH": "/usr/bin", "HOME": "/home/someone"}
    assert tunnel_env("token", TOKEN, base) == {**base, "TUNNEL_TOKEN": TOKEN}
    assert tunnel_env("quick", None, base) == base
    assert "TUNNEL_TOKEN" not in base  # the app's own environment is untouched
    with pytest.raises(ValueError):
        tunnel_env("token", None, base)


def test_parse_lines() -> None:
    urls = [parse_line(line)[0] for line in BANNER]
    assert [u for u in urls if u] == ["https://quiet-river-bright-lamp.trycloudflare.com"]
    assert parse_line(BANNER[1]) == (None, False)  # "trycloudflare.com" alone is not a URL
    assert parse_line(REGISTERED) == (None, True)


def test_error_summary() -> None:
    lines = ["INF starting", "2026-11-13T10:00:00Z ERR failed to dial edge", "INF retrying"]
    assert error_summary(lines, 1) == "cloudflared stopped (exit code 1). 2026-11-13T10:00:00Z ERR failed to dial edge"
    assert error_summary(["a", "b", "c", "d"], 2) == "cloudflared stopped (exit code 2). b / c / d"
    assert error_summary([], -9) == "cloudflared stopped (exit code -9)."


def test_download_names() -> None:
    assert download_name("Linux", "x86_64") == "cloudflared-linux-amd64"
    assert download_name("Linux", "aarch64") == "cloudflared-linux-arm64"
    assert download_name("Linux", "armv7l") == "cloudflared-linux-arm"
    assert download_name("Darwin", "arm64") == "cloudflared-darwin-arm64.tgz"
    with pytest.raises(RuntimeError):
        download_name("Linux", "mips")


def fake(tmp_path: Path, body: str) -> callable:
    script = tmp_path / "fake_cloudflared.py"
    script.write_text("import sys, time, signal, os\n" + body)

    async def binary() -> list[str]:
        return [sys.executable, str(script)]

    return binary


async def until(predicate, timeout: float = 5.0) -> None:
    deadline = asyncio.get_running_loop().time() + timeout
    while not predicate():
        if asyncio.get_running_loop().time() > deadline:
            raise AssertionError("timed out")
        await asyncio.sleep(0.01)


def config(tmp_path: Path, mode: str, token: str | None = None) -> Config:
    c = Config.create(tmp_path / "data", "admin", "password1", 0)
    c.tunnel_mode, c.tunnel_token = mode, token
    return c


async def test_quick_tunnel_runs_and_stops(tmp_path: Path) -> None:
    pid_file = tmp_path / "pid"
    lines = "\n".join(BANNER + [REGISTERED])
    binary = fake(
        tmp_path,
        f"open({str(pid_file)!r}, 'w').write(str(os.getpid()))\n"
        f"print({lines!r}, flush=True)\n"
        "time.sleep(60)\n",
    )
    changes: list[dict] = []
    t = Tunnel(config(tmp_path, "quick"), 8770, lambda: changes.append(t.info()), binary=binary)
    t.start()
    await until(lambda: t.status == "running")
    assert t.info() == {
        "mode": "quick",
        "status": "running",
        "url": "https://quiet-river-bright-lamp.trycloudflare.com",
        "error": None,
    }
    statuses = [c["status"] for c in changes]
    assert statuses.index("starting") < statuses.index("running")
    pid = int(pid_file.read_text())
    await t.stop()
    assert t.info()["status"] == "stopped" and t.url is None
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


async def test_crash_restarts_with_backoff_and_hides_the_token(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tunnel_module, "FIRST_BACKOFF", 0.05)
    starts = tmp_path / "starts"
    binary = fake(
        tmp_path,
        f"open({str(starts)!r}, 'a').write('x')\n"
        "assert '--token' not in sys.argv\n"
        "print('INF args:', ' '.join(sys.argv[1:]), flush=True)\n"
        "print('2026-11-13T10:00:00Z ERR Unauthorized: token', os.environ['TUNNEL_TOKEN'], flush=True)\n"
        "sys.exit(3)\n",
    )
    t = Tunnel(config(tmp_path, "token", TOKEN), 8770, lambda: None, binary=binary)
    t.start()
    await until(lambda: starts.exists() and len(starts.read_text()) >= 2)
    await until(lambda: t.status == "error")
    assert t.mode == "token" and t.url is None
    assert "exit code 3" in t.error and "Unauthorized" in t.error
    assert TOKEN not in t.error and "[token]" in t.error
    await t.stop()


async def test_stubborn_process_is_killed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tunnel_module, "STOP_GRACE", 0.2)
    pid_file = tmp_path / "pid"
    binary = fake(
        tmp_path,
        "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
        f"open({str(pid_file)!r}, 'w').write(str(os.getpid()))\n"
        f"print({REGISTERED!r}, flush=True)\n"
        "time.sleep(60)\n",
    )
    t = Tunnel(config(tmp_path, "quick"), 8770, lambda: None, binary=binary)
    t.start()
    await until(lambda: t.status == "running")
    pid = int(pid_file.read_text())
    await t.stop()
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


async def test_off_runs_nothing(tmp_path: Path) -> None:
    async def never() -> list[str]:
        raise AssertionError("no binary is needed when off")

    t = Tunnel(config(tmp_path, "off"), 8770, lambda: None, binary=never)
    t.start()
    assert t.info() == {"mode": "off", "status": "stopped", "url": None, "error": None}
    await t.restart()
    assert t.status == "stopped"
