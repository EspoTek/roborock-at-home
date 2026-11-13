from __future__ import annotations

import json
import os
import stat
from pathlib import Path

import pytest

from roborock_at_home.config import (
    SESSION_SECONDS,
    Config,
    ConfigMissing,
    Invalid,
    LoginThrottle,
    NotFound,
    arrived_over_https,
    check_robot_id,
    client_address,
    generate_password,
    hash_password,
    mask_email,
    resolve_data_dir,
    verify_password,
)

NOW = 1_800_000_000


def make(tmp_path: Path) -> Config:
    return Config.create(tmp_path / "data", "admin", "correct horse", NOW)


def test_scrypt_round_trip() -> None:
    stored = hash_password("s3cret-pass")
    assert verify_password(stored, "s3cret-pass")
    assert not verify_password(stored, "s3cret-pasS")
    assert hash_password("s3cret-pass")["salt"] != stored["salt"]
    assert {"n", "r", "p", "salt", "hash"} <= stored.keys()


def test_generated_password_shape() -> None:
    pw = generate_password()
    groups = pw.split("-")
    assert len(groups) == 4 and all(len(g) == 4 for g in groups)
    assert not set(pw) & set("01ilo")


def test_create_load_and_permissions(tmp_path: Path) -> None:
    config = make(tmp_path)
    data = tmp_path / "data"
    assert stat.S_IMODE(os.stat(data).st_mode) == 0o700
    assert stat.S_IMODE(os.stat(config.config_file).st_mode) == 0o600
    again = Config.load(data)
    assert [u.name for u in again.users] == ["admin"]
    assert again.tz is None and again.port == 8770 and again.host == "0.0.0.0"
    assert again.tunnel_mode == "off" and again.account is None
    with pytest.raises(FileExistsError):
        Config.create(data, "x", "y" * 8, NOW)


def test_load_missing(tmp_path: Path) -> None:
    with pytest.raises(ConfigMissing):
        Config.load(tmp_path / "nothing")


def test_missing_field_is_corruption(tmp_path: Path) -> None:
    config = make(tmp_path)
    data = json.loads(config.config_file.read_text())
    del data["tz"]
    config.config_file.write_text(json.dumps(data))
    with pytest.raises(KeyError):
        Config.load(config.data_dir)


def test_reload_picks_up_cli_edits(tmp_path: Path) -> None:
    server_side = make(tmp_path)
    cli_side = Config.load(server_side.data_dir)
    cli_side.add_user("Someone@Example.com", "another pass", NOW)
    cli_side.save()
    assert server_side.user_or_none("someone@example.com") is None
    server_side.reload_if_changed()
    assert server_side.user_or_none("someone@example.com") is not None


def test_users(tmp_path: Path) -> None:
    config = make(tmp_path)
    user = config.add_user("  Ann@Example.com ", "password1", NOW)
    assert user.name == "Ann@Example.com"
    assert config.user("ann@example.COM") is user
    with pytest.raises(Invalid):
        config.add_user("ANN@example.com", "password1", NOW)
    with pytest.raises(Invalid):
        config.add_user("bob", "short", NOW)
    with pytest.raises(Invalid):
        config.add_user("", "password1", NOW)
    with pytest.raises(Invalid):
        config.add_user("a/b", "password1", NOW)
    with pytest.raises(Invalid):
        config.add_user("x" * 101, "password1", NOW)
    assert config.check_login("ANN@example.com", "password1") is user
    assert config.check_login("ann@example.com", "wrong") is None
    assert config.check_login("nobody", "password1") is None
    config.remove_user("admin")
    with pytest.raises(Invalid):
        config.remove_user("ann@example.com")  # the last one
    with pytest.raises(NotFound):
        config.user("admin")


def test_sessions(tmp_path: Path) -> None:
    config = make(tmp_path)
    ann = config.add_user("ann", "password1", NOW)
    cookie = config.make_session(ann, NOW)
    assert config.session_user_or_none(cookie, NOW + 10) is ann
    assert config.session_user_or_none(cookie, NOW + SESSION_SECONDS) is None  # expired
    assert config.session_user_or_none(cookie + "x", NOW) is None
    assert config.session_user_or_none("a.b.c", NOW) is None
    name, gen, exp, sig = cookie.split(".")
    assert config.session_user_or_none(f"{name}.{gen}.{int(exp) + 999}.{sig}", NOW) is None

    admin_cookie = config.make_session(config.user("admin"), NOW)
    config.set_password("ann", "a new password")  # signs ann out, nobody else
    assert config.session_user_or_none(cookie, NOW) is None
    assert config.session_user_or_none(admin_cookie, NOW) is not None
    cookie2 = config.make_session(config.user("ann"), NOW)
    assert config.session_user_or_none(cookie2, NOW) is not None

    config.remove_user("ann")
    assert config.session_user_or_none(cookie2, NOW) is None
    config.add_user("ann", "password1", NOW)  # the same name again: old cookies stay dead
    assert config.session_user_or_none(cookie2, NOW) is None


def test_session_secret_is_per_install(tmp_path: Path) -> None:
    a = Config.create(tmp_path / "a", "admin", "password1", NOW)
    b = Config.create(tmp_path / "b", "admin", "password1", NOW)
    cookie = a.make_session(a.user("admin"), NOW)
    assert b.session_user_or_none(cookie, NOW) is None


def test_tokens(tmp_path: Path) -> None:
    config = make(tmp_path)
    token, secret = config.create_token("Agent", "control", NOW)
    assert secret.startswith("rah_") and len(secret) > 40
    assert token.id.startswith("t_")
    assert config.token_or_none(secret) is token
    assert config.token_or_none(secret + "x") is None
    saved = config.config_file.read_text()
    config.save()
    assert secret not in config.config_file.read_text()
    assert secret not in saved
    with pytest.raises(Invalid):
        config.create_token("x", "admin", NOW)
    with pytest.raises(Invalid):
        config.create_token("  ", "read", NOW)
    config.delete_token(token.id)
    assert config.token_or_none(secret) is None
    with pytest.raises(NotFound):
        config.delete_token(token.id)


def test_account_file(tmp_path: Path) -> None:
    config = make(tmp_path)
    config.set_account({"email": "a@b.c", "user_data": {}, "base_url": None})
    assert stat.S_IMODE(os.stat(config.account_file).st_mode) == 0o600
    assert Config.load(config.data_dir).account["email"] == "a@b.c"
    config.set_account(None)
    assert not config.account_file.exists()
    assert mask_email("yolanda@example.com") == "y***@example.com"


def test_throttle() -> None:
    t = LoginThrottle()
    now = 1000.0
    for _ in range(4):
        t.failed("1.2.3.4", now)
        assert t.retry_after("1.2.3.4", now) == 0
    t.failed("1.2.3.4", now)  # the fifth
    assert t.retry_after("1.2.3.4", now) == 30
    assert t.retry_after("5.6.7.8", now) == 0
    assert t.retry_after("1.2.3.4", now + 30) == 0
    t.failed("1.2.3.4", now + 30)
    assert t.retry_after("1.2.3.4", now + 30) == 60
    for _ in range(20):
        t.failed("1.2.3.4", now)
    assert t.retry_after("1.2.3.4", now) == 900  # capped at 15 minutes
    t.succeeded("1.2.3.4")
    assert t.retry_after("1.2.3.4", now) == 0


def test_throttle_is_bounded() -> None:
    t = LoginThrottle()
    for i in range(1500):
        t.failed(f"10.0.{i // 256}.{i % 256}", 0.0)
    assert len(t._table) == LoginThrottle.MAX_ADDRESSES


def test_client_address_and_https() -> None:
    assert client_address("192.168.1.9", None) == "192.168.1.9"
    assert client_address("192.168.1.9", "203.0.113.5") == "192.168.1.9"  # not believed
    assert client_address("127.0.0.1", "203.0.113.5") == "203.0.113.5"
    assert client_address("::1", "203.0.113.5") == "203.0.113.5"
    assert arrived_over_https("127.0.0.1", False, "https")
    assert not arrived_over_https("192.168.1.9", False, "https")
    assert arrived_over_https("192.168.1.9", True, None)


def test_robot_ids() -> None:
    assert check_robot_id("abc_DEF-123") == "abc_DEF-123"
    for bad in ("", "../x", "a b", "x" * 65, "a/b"):
        with pytest.raises(ValueError):
            check_robot_id(bad)


def test_data_dir_resolution(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.delenv("RAH_DATA_DIR", raising=False)
    monkeypatch.delenv("XDG_DATA_HOME", raising=False)
    monkeypatch.setenv("HOME", str(tmp_path))
    assert resolve_data_dir(None) == tmp_path / ".local/share/roborock-at-home"
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "xdg"))
    assert resolve_data_dir(None) == tmp_path / "xdg/roborock-at-home"
    monkeypatch.setenv("RAH_DATA_DIR", str(tmp_path / "env"))
    assert resolve_data_dir(None) == tmp_path / "env"
    assert resolve_data_dir(str(tmp_path / "arg")) == tmp_path / "arg"
