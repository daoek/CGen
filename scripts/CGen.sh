#!/usr/bin/env sh
# Launcher installed as "$INSTALL_DIR/CGen" by install.sh. Mirrors CGen.cmd's own Java check.
if ! command -v java >/dev/null 2>&1; then
    echo "CGen requires Java 17 or newer on PATH." >&2
    exit 1
fi

DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -jar "$DIR/cgen.jar" "$@"
