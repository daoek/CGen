#!/usr/bin/env sh
#
# Installs Pinfit into a per-user directory (default $HOME/.local/share/Pinfit) on Linux or macOS.
# No root/sudo is used, and - unlike a PATH change on Windows, which install.ps1 makes through
# the registry - this script never edits your shell profile: it prints the one line to add
# yourself, so nothing outside the install directory is ever touched.
#
# Two modes:
#   - Pass --version <tag> (e.g. "0.1.0-beta.3") to download that published release's jar and
#     SHA256SUMS from GitHub over HTTPS, verify the checksum, and install only if it matches.
#     Nothing is written to the install directory if verification fails.
#   - Omit --version to build from the local checkout instead (`mvn clean package`), which is
#     what contributors use.
set -eu

REPO_SLUG="daoek/Pinfit"
INSTALL_DIR="${PINFIT_INSTALL_DIR:-$HOME/.local/share/Pinfit}"
VERSION=""
SKIP_BUILD=0
MARKER_NAME=".pinfit-install-marker"

usage() {
    echo "Usage: $0 [--version <tag>] [--install-dir <dir>] [--skip-build]" >&2
    exit 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --version)
            [ $# -ge 2 ] || usage
            VERSION="$2"
            shift 2
            ;;
        --install-dir)
            [ $# -ge 2 ] || usage
            INSTALL_DIR="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=1
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            usage
            ;;
    esac
done

if [ -n "$VERSION" ] && [ "$SKIP_BUILD" -eq 1 ]; then
    echo "--version and --skip-build are mutually exclusive: --version installs a downloaded release and never builds locally." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Refuse the handful of directories that would make "rm -rf $INSTALL_DIR" (in uninstall.sh)
# catastrophic if a user pointed --install-dir somewhere careless.
case "$INSTALL_DIR" in
    "$HOME"|"$HOME"/|/|"")
        echo "Refusing unsafe installation directory: $INSTALL_DIR" >&2
        exit 1
        ;;
esac

if [ -d "$INSTALL_DIR" ] && [ -n "$(ls -A "$INSTALL_DIR" 2>/dev/null)" ] && [ ! -f "$INSTALL_DIR/$MARKER_NAME" ]; then
    echo "Refusing to overwrite non-Pinfit directory: $INSTALL_DIR" >&2
    exit 1
fi

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        echo "Neither sha256sum nor shasum is available - cannot verify the download." >&2
        exit 1
    fi
}

# Verifies $1's SHA-256 against the entry for $2 in the SHA256SUMS file at $3 (the standard
# "<hash>  <filename>" or "<hash> *<filename>" format). Exits - installing nothing - on any
# mismatch or missing entry.
assert_sha256_match() {
    file_path=$1
    file_name=$2
    sums_path=$3
    expected=$(awk -v name="$file_name" '$2 == name || $2 == "*" name {print $1; exit}' "$sums_path")
    if [ -z "$expected" ]; then
        echo "No checksum entry for '$file_name' found in $sums_path" >&2
        exit 1
    fi
    actual=$(sha256_of "$file_path")
    if [ "$actual" != "$expected" ]; then
        echo "Checksum mismatch for '$file_name': expected $expected, got $actual. Refusing to install." >&2
        exit 1
    fi
    echo "Checksum verified for $file_name ($actual)"
}

if [ -n "$VERSION" ]; then
    case "$VERSION" in
        v*) tag="$VERSION" ;;
        *) tag="v$VERSION" ;;
    esac
    bare_version=${tag#v}
    jar_name="pinfit-$bare_version.jar"
    release_base_url="https://github.com/$REPO_SLUG/releases/download/$tag"
    raw_base_url="https://raw.githubusercontent.com/$REPO_SLUG/$tag/scripts"

    download_dir=$(mktemp -d "${TMPDIR:-/tmp}/pinfit-install.XXXXXX")
    trap 'rm -rf "$download_dir"' EXIT

    echo "Downloading $jar_name from release $tag..."
    if ! curl -fsSL -o "$download_dir/$jar_name" "$release_base_url/$jar_name" \
            || ! curl -fsSL -o "$download_dir/SHA256SUMS" "$release_base_url/SHA256SUMS"; then
        echo "Could not download release '$tag' from https://github.com/$REPO_SLUG/releases" >&2
        exit 1
    fi
    assert_sha256_match "$download_dir/$jar_name" "$jar_name" "$download_dir/SHA256SUMS"
    source_jar="$download_dir/$jar_name"

    if ! curl -fsSL -o "$download_dir/pinfit.sh" "$raw_base_url/pinfit.sh" \
            || ! curl -fsSL -o "$download_dir/uninstall.sh" "$raw_base_url/uninstall.sh"; then
        echo "Could not download install scripts for release '$tag'" >&2
        exit 1
    fi
    launcher_source="$download_dir/pinfit.sh"
    uninstall_source="$download_dir/uninstall.sh"
else
    if [ "$SKIP_BUILD" -eq 0 ]; then
        if ! command -v mvn >/dev/null 2>&1; then
            echo "Maven (mvn) was not found on PATH." >&2
            exit 1
        fi
        # Clean first so Shade never consumes a JAR that was already shaded by a prior build.
        (cd "$REPO_ROOT" && mvn clean package)
    fi
    source_jar=$(find "$REPO_ROOT/target" -maxdepth 1 -name 'pinfit-*.jar' \
        ! -name 'original-*' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print \
        | sort | tail -n 1)
    if [ -z "$source_jar" ]; then
        echo "No packaged Pinfit JAR found. Run without --skip-build first." >&2
        exit 1
    fi
    launcher_source="$SCRIPT_DIR/pinfit.sh"
    uninstall_source="$SCRIPT_DIR/uninstall.sh"
fi

mkdir -p "$INSTALL_DIR"
cp "$source_jar" "$INSTALL_DIR/pinfit.jar.new"
mv -f "$INSTALL_DIR/pinfit.jar.new" "$INSTALL_DIR/pinfit.jar"
cp "$launcher_source" "$INSTALL_DIR/pinfit"
chmod +x "$INSTALL_DIR/pinfit"
cp "$uninstall_source" "$INSTALL_DIR/uninstall.sh"
chmod +x "$INSTALL_DIR/uninstall.sh"
echo "Pinfit managed installation. Safe removal requires this marker." > "$INSTALL_DIR/$MARKER_NAME"

installed_hash=$(sha256_of "$INSTALL_DIR/pinfit.jar")
echo "Pinfit installed in $INSTALL_DIR"
echo "Installed jar SHA256: $installed_hash"
echo "To uninstall later: $INSTALL_DIR/uninstall.sh"

if ! command -v java >/dev/null 2>&1; then
    echo >&2
    echo "Warning: no 'java' found on PATH. Pinfit needs a Java 17+ runtime to RUN (installing it" >&2
    echo "needed no Java at all) - install one, e.g. from https://adoptium.net, before using Pinfit." >&2
fi

case ":$PATH:" in
    *":$INSTALL_DIR:"*) ;;
    *)
        echo
        echo "Add $INSTALL_DIR to your PATH - add this line to your shell profile (~/.bashrc,"
        echo "~/.zshrc, ~/.profile, ...) and open a new terminal:"
        echo
        echo "    export PATH=\"$INSTALL_DIR:\$PATH\""
        echo
        echo "This script does not edit that file for you - nothing outside $INSTALL_DIR is touched."
        ;;
esac
echo "Then run: pinfit --help"
