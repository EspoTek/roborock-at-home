"""Remote access through a Cloudflare tunnel, run as a child process.

`quick` asks Cloudflare for a throwaway `https://<words>.trycloudflare.com`
address (read from cloudflared's output); `token` runs a named tunnel the
owner set up in their Cloudflare account. The binary is `cloudflared` on PATH,
or downloaded once into `<data dir>/bin`. If the process exits it is started
again with backoff, and its last lines are shown as the error.

The token is a credential: it is never returned by the API, never logged, and
blanked out of any cloudflared output kept for the error message.
"""

from __future__ import annotations

import asyncio
import logging
import os
import platform
import re
import shutil
from collections import deque
from collections.abc import Awaitable, Callable, Mapping
from pathlib import Path

from .config import Config

log = logging.getLogger(__name__)

RELEASES = "https://github.com/cloudflare/cloudflared/releases/latest/download/"
FIRST_BACKOFF = 5.0
MAX_BACKOFF = 300.0
STOP_GRACE = 5.0

_QUICK_URL = re.compile(r"https://[-a-z0-9]+\.trycloudflare\.com")
_REGISTERED = "Registered tunnel connection"

MODES = ("off", "quick", "token")


def tunnel_args(mode: str, port: int) -> list[str]:
    """cloudflared's arguments, after the binary. `--url` on a token tunnel
    makes one created from the command line (with no dashboard config)
    forward to the app too; a dashboard-managed tunnel uses its own."""
    url = f"http://127.0.0.1:{port}"
    if mode == "quick":
        return ["tunnel", "--no-autoupdate", "--url", url]
    if mode == "token":
        return ["tunnel", "--no-autoupdate", "run", "--url", url]
    raise ValueError(f"no process for tunnel mode {mode!r}")


def tunnel_env(mode: str, token: str | None, environ: Mapping[str, str]) -> dict[str, str]:
    """The child's environment: ours, plus the token for a token tunnel.
    cloudflared reads it from TUNNEL_TOKEN, which keeps it out of `ps`."""
    env = dict(environ)
    if mode == "token":
        if not token:
            raise ValueError("token mode needs a token")
        env["TUNNEL_TOKEN"] = token
    return env


def parse_line(line: str) -> tuple[str | None, bool]:
    """(the quick tunnel's public URL if this line names it, whether this line
    says a connection to Cloudflare is up)."""
    match = _QUICK_URL.search(line)
    return (match.group(0) if match else None), _REGISTERED in line


def error_summary(lines: list[str], returncode: int | None) -> str:
    """What to show when cloudflared exits: its last error lines, else its
    last lines."""
    errors = [line for line in lines if " ERR " in line]
    tail = (errors or lines)[-3:]
    head = f"cloudflared stopped (exit code {returncode})."
    return head if not tail else head + " " + " / ".join(tail)


def download_name(system: str, machine: str) -> str:
    """The release asset for this machine."""
    arch = {
        "x86_64": "amd64",
        "amd64": "amd64",
        "aarch64": "arm64",
        "arm64": "arm64",
        "armv7l": "arm",
        "armv6l": "arm",
    }.get(machine.lower())  # absent: no build for it, raised below
    if arch is None:
        raise RuntimeError(f"cloudflared has no build for {machine}; install it on PATH.")
    if system == "Linux":
        return f"cloudflared-linux-{arch}"
    if system == "Darwin" and arch in ("amd64", "arm64"):
        return f"cloudflared-darwin-{arch}.tgz"
    raise RuntimeError(f"cloudflared has no download for {system}; install it on PATH.")


def download_cloudflared(dest: Path) -> None:
    """Blocking; run in a thread. Streams to disk, never holding the 40 MB
    binary in memory."""
    import tarfile
    import urllib.request

    name = download_name(platform.system(), platform.machine())
    dest.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".download")
    log.warning("Downloading %s", RELEASES + name)
    with urllib.request.urlopen(RELEASES + name, timeout=60) as response:
        if name.endswith(".tgz"):
            with tarfile.open(fileobj=response, mode="r|gz") as archive:
                for member in archive:
                    if member.isfile() and Path(member.name).name == "cloudflared":
                        source = archive.extractfile(member)
                        assert source is not None  # a regular file always has data
                        with open(tmp, "wb") as out:
                            shutil.copyfileobj(source, out)
                        break
                else:
                    raise RuntimeError(f"{name} has no cloudflared in it")
        else:
            with open(tmp, "wb") as out:
                shutil.copyfileobj(response, out)
    os.chmod(tmp, 0o755)
    os.replace(tmp, dest)


class Tunnel:
    def __init__(
        self,
        config: Config,
        port: int,
        on_change: Callable[[], None],
        binary: Callable[[], Awaitable[list[str]]] | None = None,
    ) -> None:
        self._config = config
        self._port = port
        self._on_change = on_change
        # The command that runs cloudflared (tests inject a fake one).
        self._binary = binary or self._find_binary
        self.mode = "off"
        self.status = "stopped"
        self.url: str | None = None
        self.error: str | None = None
        self._task: asyncio.Task[None] | None = None
        self._proc: asyncio.subprocess.Process | None = None

    def info(self) -> dict:
        return {"mode": self.mode, "status": self.status, "url": self.url, "error": self.error}

    def start(self) -> None:
        """Run the mode saved in the configuration."""
        self._launch(self._config.tunnel_mode, self._config.tunnel_token)

    async def restart(self) -> None:
        """The configuration's mode changed: stop and run the new one."""
        await self.stop()
        self.start()

    async def stop(self) -> None:
        task, self._task = self._task, None
        if task is not None:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        self._set(status="stopped", url=None, error=None)

    def _launch(self, mode: str, token: str | None) -> None:
        self._set(mode=mode)
        if mode == "off":
            return
        self._task = asyncio.get_running_loop().create_task(self._supervise(mode, token))
        self._task.add_done_callback(self._ended)

    def _ended(self, task: asyncio.Task[None]) -> None:
        # Only a cancel or "no build for this machine" ends the supervisor;
        # anything else is a bug, shown on the page and in the log.
        if not task.cancelled() and task.exception() is not None:
            log.critical("The tunnel supervisor stopped", exc_info=task.exception())
            self._set(status="error", url=None, error=f"Remote access stopped: {task.exception()}")

    def _set(self, **changes: object) -> None:
        before = self.info()
        for key, value in changes.items():
            setattr(self, key, value)
        if self.info() != before:
            self._on_change()

    async def _find_binary(self) -> list[str]:
        found = shutil.which("cloudflared")
        if found:
            return [found]
        local = self._config.bin_dir / "cloudflared"
        if not os.access(local, os.X_OK):
            self._set(status="installing")
            await asyncio.to_thread(download_cloudflared, local)
        return [str(local)]

    async def _supervise(self, mode: str, token: str | None) -> None:
        delay = FIRST_BACKOFF
        while True:
            try:
                argv = await self._binary() + tunnel_args(mode, self._port)
                env = tunnel_env(mode, token, os.environ)
            except OSError as exc:  # the download failed: retry like a crash
                self._set(status="error", url=None, error=f"Could not get cloudflared: {exc}")
            except RuntimeError as exc:  # no build for this machine: retrying cannot help
                self._set(status="error", url=None, error=str(exc))
                return
            else:
                self._set(status="starting", url=None, error=None)
                lines, returncode = await self._run(argv, env, token)
                if self.status == "running":
                    delay = FIRST_BACKOFF  # it worked for a while: start the backoff over
                self._set(status="error", url=None, error=error_summary(lines, returncode))
                log.warning("Tunnel: %s", self.error)
            await asyncio.sleep(delay)
            delay = min(delay * 2, MAX_BACKOFF)

    async def _run(
        self, argv: list[str], env: dict[str, str], token: str | None
    ) -> tuple[list[str], int | None]:
        proc = await asyncio.create_subprocess_exec(
            *argv,
            env=env,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
        )
        self._proc = proc
        lines: deque[str] = deque(maxlen=20)
        try:
            assert proc.stdout is not None
            async for raw in proc.stdout:
                line = raw.decode(errors="replace").rstrip()
                if token:
                    line = line.replace(token, "[token]")
                lines.append(line)
                url, registered = parse_line(line)
                if url is not None and self.mode == "quick":
                    self._set(url=url)
                if registered:
                    self._set(status="running", error=None)
            return list(lines), await proc.wait()
        finally:
            if proc.returncode is None:
                await _terminate(proc)
            self._proc = None


async def _terminate(proc: asyncio.subprocess.Process) -> None:
    proc.terminate()
    try:
        await asyncio.wait_for(asyncio.shield(proc.wait()), STOP_GRACE)
    except TimeoutError:
        proc.kill()
        await proc.wait()
