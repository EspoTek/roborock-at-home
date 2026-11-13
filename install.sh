#!/bin/sh
# Roborock at Home installer.
#
#   curl -fsSL https://raw.githubusercontent.com/EspoTek/roborock-at-home/master/install.sh | sh
#
# Installs for the current user only, without root: the app into uv's tool
# directory, its data into ~/.local/share/roborock-at-home, and a systemd user
# service that starts it at boot. Run it again to upgrade.
#
#   ... | sh -s -- --uninstall           remove the service and the app (keeps your data)
#   ... | sh -s -- --uninstall --purge   and delete the data too
#
# Environment: RAH_PORT (default 8770), RAH_REF (branch or tag, default master).

set -eu

REPO="${RAH_REPO:-https://github.com/EspoTek/roborock-at-home}"
REF="${RAH_REF:-master}"
PORT="${RAH_PORT:-8770}"

say() { printf '%s\n' "$*"; }
die() { printf 'roborock-at-home: %s\n' "$*" >&2; exit 1; }

find_uv() {
    if command -v uv >/dev/null 2>&1; then
        command -v uv
    elif [ -x "$HOME/.local/bin/uv" ]; then
        printf '%s\n' "$HOME/.local/bin/uv"
    elif [ -x "$HOME/.cargo/bin/uv" ]; then
        printf '%s\n' "$HOME/.cargo/bin/uv"
    else
        return 1
    fi
}

# Everything runs from main(), called on the last line, so a download that was
# cut off half way runs nothing at all.
main() {
    [ "$(id -u)" != 0 ] || die "run this as your normal user, not root (it installs a per-user service)."

    if [ "${1:-}" = "--uninstall" ]; then
        shift
        UV=$(find_uv) || die "uv is not installed, so there is nothing of ours to remove."
        APP="$("$UV" tool dir --bin)/roborock-at-home"
        [ -x "$APP" ] || die "Roborock at Home is not installed for this user."
        "$APP" uninstall "$@"
        "$UV" tool uninstall roborock-at-home
        say "Roborock at Home is removed."
        exit 0
    fi
    [ $# -eq 0 ] || die "unknown option: $1"

    [ "$(uname -s)" = Linux ] || die "the installer sets up a systemd service, so it needs Linux.
On this system, run it by hand instead:
    uvx --from '${REPO}/archive/${REF}.tar.gz' roborock-at-home init
    uvx --from '${REPO}/archive/${REF}.tar.gz' roborock-at-home serve"
    systemctl --user show-environment >/dev/null 2>&1 || die "no systemd user session here.
Log in as this user directly (ssh or a terminal, not 'su') and run the installer again."

    say "[1/3] uv (the Python tool installer)"
    if UV=$(find_uv); then
        say "      found $UV"
    else
        say "      not found, installing it for this user"
        curl -LsSf https://astral.sh/uv/install.sh | sh >/dev/null
        UV=$(find_uv) || die "uv did not install. See https://docs.astral.sh/uv/getting-started/installation/"
    fi

    say "[2/3] Roborock at Home ($REF)"
    # A source archive, not git+https, so git is not needed. --reinstall and
    # --refresh make a second run pick up the newest code.
    "$UV" tool install --quiet --force --reinstall --refresh --python 3.12 \
        "roborock-at-home @ ${REPO}/archive/${REF}.tar.gz"
    APP="$("$UV" tool dir --bin)/roborock-at-home"
    [ -x "$APP" ] || die "the app did not install to $APP."
    say "      $("$APP" --version)"

    say "[3/3] service"
    "$APP" setup --port "$PORT"
}

main "$@"
