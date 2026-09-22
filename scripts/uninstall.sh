#!/usr/bin/env sh
#
# Removes a Pinfit installation made by install.sh. Refuses unless the install directory carries
# install.sh's own marker file, so this can never be pointed at an unrelated directory by mistake.
#
# install.sh copies this script into the install directory itself, so with no argument this
# defaults to removing *that* directory (wherever this copy of the script actually lives) -
# not a hardcoded default location, which would be wrong for anyone who installed with a custom
# --install-dir and then ran the copy that ended up inside it.
set -eu

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="${1:-${PINFIT_INSTALL_DIR:-$SELF_DIR}}"
MARKER_PATH="$INSTALL_DIR/.pinfit-install-marker"

case "$INSTALL_DIR" in
    "$HOME"|"$HOME"/|/|"")
        echo "Refusing unsafe uninstall directory: $INSTALL_DIR" >&2
        exit 1
        ;;
esac

if [ ! -f "$MARKER_PATH" ]; then
    echo "Refusing to remove an unrecognized directory: $INSTALL_DIR" >&2
    exit 1
fi

rm -rf "$INSTALL_DIR"
echo "Pinfit uninstalled from $INSTALL_DIR."
echo "If you added a PATH line for it to your shell profile, remove that line yourself too."
