from __future__ import annotations

import io
import subprocess
import sys
from pathlib import Path

import pytest

from roborock_at_home import cli
from roborock_at_home.config import Config


class FakeSystem:
    """Records commands; answers like a machine where the service is not
    running yet and lingering is refused."""

    def __init__(self, active: bool = False, linger_ok: bool = False) -> None:
        self.calls: list[list[str]] = []
        self.active = active
        self.linger_ok = linger_ok

    def __call__(self, cmd: list[str]) -> subprocess.CompletedProcess:
        self.calls.append(cmd)
        code, out = 0, ""
        if cmd[:3] == ["systemctl", "--user", "is-active"]:
            code = 0 if self.active else 3
        elif cmd[0] == "loginctl":
            code = 0 if self.linger_ok else 1
        elif cmd[0] == "ip":
            out = (
                "2: eth0    inet 192.0.2.10/24 brd 192.0.2.255 scope global eth0\\       valid_lft forever\n"
                "3: wlan0    inet 198.51.100.7/24 brd 198.51.100.255 scope global dynamic wlan0\n"
            )
        return subprocess.CompletedProcess(cmd, code, out, "")


def run_setup(tmp_path: Path, system: FakeSystem, port: int = 8770) -> int:
    return cli.setup(
        tmp_path / "data",
        port,
        run=system,
        exe="/home/someone/.local/bin/roborock-at-home",
        unit=tmp_path / "unit" / "roborock-at-home.service",
        user="someone",
        healthy=lambda p: True,
        systemd=lambda: True,
    )


def test_unit_file() -> None:
    text = cli.unit_file("/home/someone/.local/bin/roborock-at-home", Path("/home/someone/data dir"), 8771)
    assert (
        'ExecStart=/home/someone/.local/bin/roborock-at-home --data-dir "/home/someone/data dir" serve --port 8771'
        in text
    )
    for line in ("Restart=on-failure", "Environment=MALLOC_ARENA_MAX=2", "Environment=PYTHONUNBUFFERED=1",
                 "Environment=MALLOC_MMAP_THRESHOLD_=131072",
                 "WantedBy=default.target"):
        assert line in text.splitlines()


def test_setup_first_time(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    system = FakeSystem()
    assert run_setup(tmp_path, system, port=8771) == 0
    unit = (tmp_path / "unit" / "roborock-at-home.service").read_text()
    assert f"--data-dir {tmp_path / 'data'} serve --port 8771" in unit
    assert system.calls[:4] == [
        ["systemctl", "--user", "daemon-reload"],
        ["systemctl", "--user", "is-active", "--quiet", "roborock-at-home"],
        ["systemctl", "--user", "enable", "--now", "roborock-at-home"],
        ["loginctl", "enable-linger", "someone"],
    ]
    out = capsys.readouterr().out
    assert "http://192.0.2.10:8771/" in out and "http://198.51.100.7:8771/" in out
    assert "only run while you are logged in" in out and "sudo loginctl enable-linger someone" in out
    assert "Sign in as: admin" in out
    password = out.split("Password: ")[-1].split()[0]
    config = Config.load(tmp_path / "data")
    assert config.check_login("admin", password) is not None and config.port == 8771


def test_setup_again_upgrades(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    run_setup(tmp_path, FakeSystem())
    capsys.readouterr()
    system = FakeSystem(active=True, linger_ok=True)
    assert run_setup(tmp_path, system) == 0
    assert ["systemctl", "--user", "restart", "roborock-at-home"] in system.calls
    out = capsys.readouterr().out
    assert "Password:" not in out and "Already set up" in out and "logged in" not in out


def test_setup_needs_systemd(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    code = cli.setup(tmp_path / "data", 8770, run=FakeSystem(), systemd=lambda: False)
    assert code == 1 and "serve --port 8770" in capsys.readouterr().out
    assert not (tmp_path / "data").exists()


def test_setup_reports_a_service_that_does_not_answer(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    code = cli.setup(
        tmp_path / "data", 8770, run=FakeSystem(), exe="/x", unit=tmp_path / "u.service", user="someone",
        healthy=lambda p: False, wait=0.0, systemd=lambda: True,
    )
    assert code == 1 and "journalctl --user -u roborock-at-home" in capsys.readouterr().out


def test_uninstall(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    run_setup(tmp_path, FakeSystem())
    system = FakeSystem()
    unit = tmp_path / "unit" / "roborock-at-home.service"
    cli.uninstall(tmp_path / "data", purge=True, run=system, unit=unit)
    assert system.calls == [
        ["systemctl", "--user", "disable", "--now", "roborock-at-home"],
        ["systemctl", "--user", "daemon-reload"],
    ]
    assert not unit.exists() and not (tmp_path / "data").exists()
    cli.uninstall(tmp_path / "data", purge=False, run=system, unit=unit)
    assert "No service was installed." in capsys.readouterr().out


def main(tmp_path: Path, *args: str) -> None:
    cli.main(["--data-dir", str(tmp_path / "data"), *args])


def test_init_and_users(tmp_path: Path, capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch) -> None:
    main(tmp_path, "init")
    out = capsys.readouterr().out
    assert "Sign in as: admin" in out and "Password: " in out
    main(tmp_path, "init")
    assert "Password" not in capsys.readouterr().out

    monkeypatch.setattr(sys, "stdin", io.StringIO("piped password\n"))
    main(tmp_path, "user", "add", "Ann@Example.com")
    assert "Ann@Example.com can now sign in." in capsys.readouterr().out
    assert Config.load(tmp_path / "data").check_login("ann@example.com", "piped password")

    main(tmp_path, "user", "password", "ann@example.com", "--generate")
    new = capsys.readouterr().out.split("Password: ")[-1].strip()
    assert Config.load(tmp_path / "data").check_login("ann@example.com", new)

    cli.main(["user", "list", "--data-dir", str(tmp_path / "data")])  # the flag after the command too
    listed = capsys.readouterr().out
    assert "admin" in listed and "Ann@Example.com" in listed

    main(tmp_path, "user", "remove", "admin")
    with pytest.raises(SystemExit) as e:
        main(tmp_path, "user", "remove", "ann@example.com")
    assert e.value.code == 1 and "only person" in capsys.readouterr().err

    monkeypatch.setattr(sys, "stdin", io.StringIO("short\n"))
    with pytest.raises(SystemExit):
        main(tmp_path, "user", "add", "bob")
    assert "at least 8" in capsys.readouterr().err


def test_token(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    main(tmp_path, "init")
    capsys.readouterr()
    main(tmp_path, "token", "--name", "Agent", "--scope", "control")
    secret = capsys.readouterr().out.strip().splitlines()[-1]
    token = Config.load(tmp_path / "data").token_or_none(secret)
    assert token is not None and token.scope == "control" and token.name == "Agent"


def test_serve_without_config(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    with pytest.raises(SystemExit) as e:
        main(tmp_path, "serve")
    assert e.value.code == 1 and "roborock-at-home init" in capsys.readouterr().err


def test_version(capsys: pytest.CaptureFixture[str]) -> None:
    with pytest.raises(SystemExit):
        cli.main(["--version"])
    assert capsys.readouterr().out.strip() == "roborock-at-home 0.1.0"


def test_demo_serves_and_stops_cleanly(tmp_path: Path) -> None:
    """The real command, end to end: the demo answers, and SIGTERM exits 0
    without python-roborock ever being imported."""
    import os
    import signal
    import socket
    import time
    import urllib.request

    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    code = (
        "import sys, runpy\n"
        "from roborock_at_home import cli\n"
        "try:\n"
        f"    cli.main(['serve', '--demo', '--host', '127.0.0.1', '--port', '{port}'])\n"
        "finally:\n"
        "    print('ROBOROCK LOADED' if 'roborock' in sys.modules else 'ROBOROCK NOT LOADED', flush=True)\n"
    )
    proc = subprocess.Popen(
        [sys.executable, "-c", code],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        env={**os.environ, "RAH_DEMO_PASSWORD": "demo-pass-1"},
    )
    try:
        deadline = time.monotonic() + 20
        while True:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/healthz", timeout=1) as r:
                    assert r.read() == b"ok"
                break
            except OSError:
                assert time.monotonic() < deadline, "the demo did not start"
                time.sleep(0.1)
        proc.send_signal(signal.SIGTERM)
        out, _ = proc.communicate(timeout=20)
    finally:
        if proc.poll() is None:
            proc.kill()
    assert proc.returncode == 0, out
    assert "Sign in as: demo" in out and "Password: demo-pass-1" in out
    assert f":{port}/" in out and "ROBOROCK NOT LOADED" in out
