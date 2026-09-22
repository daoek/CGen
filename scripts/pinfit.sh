#!/usr/bin/env sh
# Launcher installed as "$INSTALL_DIR/pinfit" by install.sh. Mirrors pinfit.cmd's own Java check.
if ! command -v java >/dev/null 2>&1; then
    echo "Pinfit requires Java 17 or newer on PATH." >&2
    exit 1
fi

DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -jar "$DIR/pinfit.jar" "$@"
