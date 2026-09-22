#!/usr/bin/env sh
# Launcher installed as "$INSTALL_DIR/pinfit" by install.sh. Mirrors pinfit.cmd's own Java check.
PINFIT_JAVA="java"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    PINFIT_JAVA="$JAVA_HOME/bin/java"
fi

if ! command -v "$PINFIT_JAVA" >/dev/null 2>&1; then
    echo "Pinfit requires Java 17 or newer on PATH." >&2
    exit 1
fi

DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$PINFIT_JAVA" -jar "$DIR/pinfit.jar" "$@"
