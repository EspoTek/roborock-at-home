"""The data directory, its files, and who may use the app.

Everything the app keeps lives in one directory (created 0700) as small JSON
files, each written atomically (temp file, fsync, rename) so a power cut
leaves the old or the new version, never half of one. `config.json` is the
app's own: the people who can sign in (scrypt hashes), the session secret,
API tokens (only their SHA-256), the time zone, the tunnel and the listening
address. Every field is required; a missing one is corruption and raises.

The server and the command line both edit `config.json` (`roborock-at-home
user add` while the app runs), so the server re-reads the file whenever it
changed on disk. That costs one `stat` per authenticated request.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import threading
from collections import OrderedDict
from dataclasses import dataclass
from ipaddress import ip_address
from pathlib import Path

DEFAULT_PORT = 8770
DEFAULT_HOST = "0.0.0.0"

SESSION_COOKIE = "rah_session"
SESSION_SECONDS = 30 * 24 * 3600

MIN_PASSWORD = 8
MAX_NAME = 100

# scrypt at n=2**14, r=8 needs 16 MB for about 40 ms on a Raspberry Pi 4:
# slow enough to make guessing expensive, small enough for a 512 MB machine.
SCRYPT_N = 2**14
SCRYPT_R = 8
SCRYPT_P = 1
SCRYPT_LEN = 32

# A password generated for a person: four groups of four from an alphabet
# without the characters people misread (0/o, 1/l/i).
_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"

_ROBOT_ID = re.compile(r"[A-Za-z0-9_-]{1,64}")
_TOKEN_SCOPES = ("read", "control")
_TUNNEL_MODES = ("off", "quick", "token")


class Invalid(ValueError):
    """Input a person can correct (a short password, a taken name). The
    message is a sentence to show them."""


class NotFound(LookupError):
    """No such user or token."""


class ConfigMissing(RuntimeError):
    """`config.json` does not exist yet."""


def check_robot_id(robot_id: str) -> str:
    """Robot ids come from the backend and end up in file names."""
    if not _ROBOT_ID.fullmatch(robot_id):
        raise ValueError(f"robot id {robot_id!r} is not [A-Za-z0-9_-]{{1,64}}")
    return robot_id


def resolve_data_dir(arg: str | None) -> Path:
    if arg:
        path = Path(arg)
    elif os.environ.get("RAH_DATA_DIR"):
        path = Path(os.environ["RAH_DATA_DIR"])
    elif os.environ.get("XDG_DATA_HOME"):
        path = Path(os.environ["XDG_DATA_HOME"]) / "roborock-at-home"
    else:
        path = Path.home() / ".local" / "share" / "roborock-at-home"
    return path.expanduser().absolute()


def ensure_dir(path: Path) -> None:
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path, 0o700)


def atomic_write(path: Path, data: bytes, mode: int = 0o600) -> None:
    # The thread id keeps two writers of one file (a flush in a worker thread
    # and one on the loop) off each other's temp file.
    tmp = path.with_name(f".{path.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    try:
        view = memoryview(data)
        while view:
            view = view[os.write(fd, view) :]
        os.fsync(fd)
    finally:
        os.close(fd)
    os.replace(tmp, path)


def write_json(path: Path, obj: object, mode: int = 0o600) -> None:
    atomic_write(path, json.dumps(obj, indent=1).encode(), mode)


def generate_password() -> str:
    return "-".join("".join(secrets.choice(_ALPHABET) for _ in range(4)) for _ in range(4))


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def hash_password(password: str) -> dict:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode(), salt=salt, n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P, dklen=SCRYPT_LEN
    )
    return {"n": SCRYPT_N, "r": SCRYPT_R, "p": SCRYPT_P, "salt": _b64(salt), "hash": _b64(digest)}


def verify_password(stored: dict, password: str) -> bool:
    expected = _unb64(stored["hash"])
    digest = hashlib.scrypt(
        password.encode(),
        salt=_unb64(stored["salt"]),
        n=stored["n"],
        r=stored["r"],
        p=stored["p"],
        dklen=len(expected),
    )
    return hmac.compare_digest(digest, expected)


# Checked against when the name is unknown, so a wrong name costs the same
# scrypt as a wrong password and the answer time gives nothing away.
_DUMMY_HASH = {
    "n": SCRYPT_N,
    "r": SCRYPT_R,
    "p": SCRYPT_P,
    "salt": _b64(b"\0" * 16),
    "hash": _b64(b"\0" * SCRYPT_LEN),
}


def clean_name(name: str) -> str:
    """A person's name as stored: stripped, 1-100 characters, no control
    characters and no "/" (it appears in `/api/users/{name}`)."""
    name = name.strip()
    if not 1 <= len(name) <= MAX_NAME:
        raise Invalid(f"A name is 1 to {MAX_NAME} characters.")
    if "/" in name or any(ord(c) < 0x20 or ord(c) == 0x7F for c in name):
        raise Invalid("A name cannot contain control characters or a slash.")
    return name


def check_new_password(password: str) -> str:
    if len(password) < MIN_PASSWORD:
        raise Invalid(f"A password is at least {MIN_PASSWORD} characters.")
    return password


def mask_email(email: str) -> str:
    local, sep, domain = email.partition("@")
    return f"{local[:1]}***{sep}{domain}"


@dataclass(slots=True)
class User:
    name: str
    password: dict
    created: int
    # Signed into every session cookie: bumping it signs that person out
    # everywhere. Starts random, so a person removed and added again under the
    # same name does not revive the old cookies.
    generation: int

    @property
    def key(self) -> str:
        return self.name.casefold()


@dataclass(slots=True)
class Token:
    id: str
    name: str
    scope: str  # "read" or "control"
    created: int
    last_used: int | None
    sha256: str

    def public(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "scope": self.scope,
            "created": self.created,
            "last_used": self.last_used,
        }


class Config:
    """`config.json` and `account.json`, in memory."""

    def __init__(self, data_dir: Path) -> None:
        self.data_dir = data_dir
        self.users: list[User] = []
        self.session_secret = b""
        self.tokens: list[Token] = []
        self.tz: str | None = None
        self.tunnel_mode = "off"
        self.tunnel_token: str | None = None
        self.port = DEFAULT_PORT
        self.host = DEFAULT_HOST
        self.account: dict | None = None
        self._stamp: tuple[int, int] | None = None
        self._lock = threading.Lock()

    # -- files -------------------------------------------------------------

    @property
    def config_file(self) -> Path:
        return self.data_dir / "config.json"

    @property
    def account_file(self) -> Path:
        return self.data_dir / "account.json"

    @property
    def schedules_file(self) -> Path:
        return self.data_dir / "schedules.json"

    @property
    def robots_file(self) -> Path:
        return self.data_dir / "robots.json"

    @property
    def history_file(self) -> Path:
        return self.data_dir / "history.json"

    @property
    def maps_dir(self) -> Path:
        return self.data_dir / "maps"

    @property
    def cache_file(self) -> Path:
        return self.data_dir / "roborock-cache.bin"

    @property
    def bin_dir(self) -> Path:
        return self.data_dir / "bin"

    @classmethod
    def exists(cls, data_dir: Path) -> bool:
        return (data_dir / "config.json").is_file()

    @classmethod
    def create(cls, data_dir: Path, first_user: str, password: str, now: int) -> Config:
        """A fresh `config.json` with one person in it. Refuses to overwrite."""
        ensure_dir(data_dir)
        config = cls(data_dir)
        if config.config_file.exists():
            raise FileExistsError(f"{config.config_file} already exists")
        config.session_secret = secrets.token_bytes(32)
        config.users.append(
            User(clean_name(first_user), hash_password(password), now, secrets.randbelow(2**31))
        )
        config.save()
        return config

    @classmethod
    def load(cls, data_dir: Path) -> Config:
        config = cls(data_dir)
        if not config.config_file.is_file():
            raise ConfigMissing(
                f"No configuration in {data_dir}. Run `roborock-at-home init` first."
            )
        config._read()
        return config

    def _read(self) -> None:
        stat = self.config_file.stat()
        data = json.loads(self.config_file.read_bytes())
        self.users = [
            User(u["name"], u["password"], u["created"], u["generation"]) for u in data["users"]
        ]
        if not self.users:
            raise ValueError(f"{self.config_file} has no users")
        self.session_secret = bytes.fromhex(data["session_secret"])
        self.tokens = [
            Token(t["id"], t["name"], t["scope"], t["created"], t["last_used"], t["sha256"])
            for t in data["tokens"]
        ]
        self.tz = data["tz"]
        self.tunnel_mode = data["tunnel"]["mode"]
        self.tunnel_token = data["tunnel"]["token"]
        if self.tunnel_mode not in _TUNNEL_MODES:
            raise ValueError(f"{self.config_file}: unknown tunnel mode {self.tunnel_mode!r}")
        self.port = data["port"]
        self.host = data["host"]
        if self.account_file.is_file():
            account = json.loads(self.account_file.read_bytes())
            account["email"]  # the one field this app reads; absent means corruption
            self.account = account
        else:
            self.account = None
        self._stamp = (stat.st_mtime_ns, stat.st_size)

    def reload_if_changed(self) -> None:
        """Pick up edits the command line made while the app runs."""
        stat = self.config_file.stat()
        if (stat.st_mtime_ns, stat.st_size) != self._stamp:
            self._read()

    def _to_json(self) -> dict:
        return {
            "users": [
                {"name": u.name, "password": u.password, "created": u.created, "generation": u.generation}
                for u in self.users
            ],
            "session_secret": self.session_secret.hex(),
            "tokens": [
                {
                    "id": t.id,
                    "name": t.name,
                    "scope": t.scope,
                    "created": t.created,
                    "last_used": t.last_used,
                    "sha256": t.sha256,
                }
                for t in self.tokens
            ],
            "tz": self.tz,
            "tunnel": {"mode": self.tunnel_mode, "token": self.tunnel_token},
            "port": self.port,
            "host": self.host,
        }

    def save(self) -> None:
        """Write `config.json`. Blocking (fsync): the server calls it through
        `asyncio.to_thread` with a snapshot taken on the loop (`snapshot`)."""
        self.write_snapshot(self.snapshot())

    def snapshot(self) -> bytes:
        return json.dumps(self._to_json(), indent=1).encode()

    def write_snapshot(self, data: bytes) -> None:
        with self._lock:
            atomic_write(self.config_file, data)
            stat = self.config_file.stat()
            self._stamp = (stat.st_mtime_ns, stat.st_size)

    def set_account(self, account: dict | None) -> None:
        if account is None:
            self.account_file.unlink(missing_ok=True)
            self.cache_file.unlink(missing_ok=True)
        else:
            account["email"]
            write_json(self.account_file, account)
        self.account = account

    # -- people ------------------------------------------------------------

    def user_or_none(self, name: str) -> User | None:
        """The person with this name, or None: an unknown name is an ordinary
        outcome at sign-in."""
        key = name.strip().casefold()
        for user in self.users:
            if user.key == key:
                return user
        return None

    def user(self, name: str) -> User:
        user = self.user_or_none(name)
        if user is None:
            raise NotFound(f"There is nobody called {name.strip()}.")
        return user

    def add_user(self, name: str, password: str, now: int) -> User:
        name = clean_name(name)
        check_new_password(password)
        if self.user_or_none(name) is not None:
            raise Invalid(f"{name} can already sign in.")
        user = User(name, hash_password(password), now, secrets.randbelow(2**31))
        self.users.append(user)
        return user

    def set_password(self, name: str, password: str) -> User:
        check_new_password(password)
        user = self.user(name)
        user.password = hash_password(password)
        user.generation += 1
        return user

    def remove_user(self, name: str) -> None:
        user = self.user(name)
        if len(self.users) == 1:
            raise Invalid("That is the only person who can sign in. Add someone else first.")
        self.users.remove(user)

    def check_login(self, name: str, password: str) -> User | None:
        """The person if the name and password match, else None. Costs one
        scrypt either way."""
        user = self.user_or_none(name)
        if user is None:
            verify_password(_DUMMY_HASH, password)
            return None
        return user if verify_password(user.password, password) else None

    # -- sessions ----------------------------------------------------------

    def _sign(self, payload: str) -> str:
        return _b64(hmac.new(self.session_secret, payload.encode(), hashlib.sha256).digest())

    def make_session(self, user: User, now: int) -> str:
        payload = f"{_b64(user.key.encode())}.{user.generation}.{now + SESSION_SECONDS}"
        return f"{payload}.{self._sign(payload)}"

    def session_user_or_none(self, cookie: str, now: int) -> User | None:
        """Whoever the cookie belongs to, or None when it is not valid (forged,
        expired, the person's password changed, the person was removed)."""
        parts = cookie.split(".")
        if len(parts) != 4:
            return None
        name_b64, generation, expiry, signature = parts
        payload = f"{name_b64}.{generation}.{expiry}"
        if not hmac.compare_digest(signature, self._sign(payload)):
            return None
        if int(expiry) <= now:
            return None
        user = self.user_or_none(_unb64(name_b64).decode())
        if user is None or user.generation != int(generation):
            return None
        return user

    # -- tokens ------------------------------------------------------------

    def create_token(self, name: str, scope: str, now: int) -> tuple[Token, str]:
        name = name.strip()
        if not 1 <= len(name) <= MAX_NAME:
            raise Invalid(f"A token's name is 1 to {MAX_NAME} characters.")
        if scope not in _TOKEN_SCOPES:
            raise Invalid('A token\'s scope is "read" or "control".')
        taken = {t.id for t in self.tokens}
        token_id = f"t_{secrets.token_hex(2)}"
        while token_id in taken:
            token_id = f"t_{secrets.token_hex(2)}"
        secret = "rah_" + secrets.token_urlsafe(32)
        token = Token(token_id, name, scope, now, None, hashlib.sha256(secret.encode()).hexdigest())
        self.tokens.append(token)
        return token, secret

    def token_or_none(self, secret: str) -> Token | None:
        """The token this secret belongs to, or None when it is not one."""
        digest = hashlib.sha256(secret.encode()).hexdigest()
        found = None
        for token in self.tokens:
            if hmac.compare_digest(digest, token.sha256):
                found = token
        return found

    def delete_token(self, token_id: str) -> None:
        for token in self.tokens:
            if token.id == token_id:
                self.tokens.remove(token)
                return
        raise NotFound(f"There is no token {token_id}.")


# --------------------------------------------------------------------------
# Who is asking
# --------------------------------------------------------------------------


def _is_loopback(host: str | None) -> bool:
    if host is None:
        return False
    return ip_address(host.split("%", 1)[0]).is_loopback


def client_address(peer: str | None, cf_connecting_ip: str | None) -> str:
    """The socket peer, except behind our own cloudflared: it connects from
    loopback and names the real client in `CF-Connecting-IP`."""
    if cf_connecting_ip and _is_loopback(peer):
        return cf_connecting_ip.strip()
    return peer or "unknown"


def arrived_over_https(peer: str | None, secure: bool, forwarded_proto: str | None) -> bool:
    """`X-Forwarded-Proto` is believed only from a loopback peer (a proxy on
    this machine); anyone else could send it."""
    if secure:
        return True
    return forwarded_proto == "https" and _is_loopback(peer)


class LoginThrottle:
    """Failed sign-ins per client address: after 5, locked out for 30 s,
    doubling with each further failure up to 15 minutes."""

    FREE_TRIES = 5
    FIRST_LOCK = 30.0
    MAX_LOCK = 900.0
    MAX_ADDRESSES = 1000

    def __init__(self) -> None:
        # address -> (failures, locked until)
        self._table: OrderedDict[str, tuple[int, float]] = OrderedDict()

    def retry_after(self, address: str, now: float) -> int:
        """Seconds until this address may try again; 0 when it may now."""
        entry = self._table.get(address)  # absent: never failed
        if entry is None or entry[1] <= now:
            return 0
        return max(1, int(entry[1] - now + 0.999))

    def failed(self, address: str, now: float) -> None:
        failures = self._table.pop(address, (0, 0.0))[0] + 1
        locked_until = 0.0
        if failures >= self.FREE_TRIES:
            lock = min(self.FIRST_LOCK * 2 ** (failures - self.FREE_TRIES), self.MAX_LOCK)
            locked_until = now + lock
        self._table[address] = (failures, locked_until)
        while len(self._table) > self.MAX_ADDRESSES:
            self._table.popitem(last=False)

    def succeeded(self, address: str) -> None:
        self._table.pop(address, None)
