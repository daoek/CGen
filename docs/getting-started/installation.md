# Installation

Pinfit ships as a single self-contained JAR, so it runs anywhere a **Java 17 or newer runtime** is
installed — Windows, Linux or macOS. You can install a published release without Maven or a clone
of the repository, or build it from source.

!!! info "Java is a runtime requirement, not an install-time one"

    The install scripts below need no Java themselves — they only download or build a JAR. But
    **running** `pinfit` afterwards does need Java 17+ on `PATH`. If you don't already have it,
    install one first, e.g. from [Adoptium](https://adoptium.net). Both launchers check for it
    and print a clear message rather than a raw JVM error if it's missing.

## Install a release (recommended)

Every version tag is built and tested by
[CI](https://github.com/daoek/Pinfit/blob/main/.github/workflows/release.yml) and published as a
[GitHub Release](https://github.com/daoek/Pinfit/releases) with the JAR and a `SHA256SUMS`
checksum file attached.

=== "Windows"

    Download [`scripts/install.ps1`](https://github.com/daoek/Pinfit/raw/main/scripts/install.ps1)
    and run it with the version you want:

    ```powershell
    .\install.ps1 -Version 0.1.0-beta.3
    ```

    !!! tip "If PowerShell blocks the script"

        When your machine's default execution policy refuses to run a local `.ps1`, invoke it
        through an explicitly scoped bypass instead:

        ```powershell
        powershell -ExecutionPolicy Bypass -File .\install.ps1 -Version 0.1.0-beta.3
        ```

        That flag applies only to the single `powershell.exe` invocation it is passed to. It does
        not change the execution policy for your machine or your user account.

=== "Linux / macOS"

    Download [`scripts/install.sh`](https://github.com/daoek/Pinfit/raw/main/scripts/install.sh)
    and run it with the version you want:

    ```console
    chmod +x install.sh
    ./install.sh --version 0.1.0-beta.3
    ```

The script downloads that release's JAR and `SHA256SUMS` over HTTPS from GitHub, verifies the
JAR's SHA-256 against the published checksum, and installs it **only** if the checksum matches.
Nothing is written to disk otherwise. At the end it prints the installed JAR's checksum so you
can cross-check it by hand against the `SHA256SUMS` file on the
[Releases page](https://github.com/daoek/Pinfit/releases).

## Build and install from source

For contributors, or to install an unreleased build. Requires **Java 17** and **Maven** (this is
the one place that's true at install time too, since it actually compiles Pinfit).

```console
git clone https://github.com/daoek/Pinfit.git
cd Pinfit
mvn clean package
```

That produces `target/pinfit-1.0-SNAPSHOT.jar`, which already contains SnakeYAML and can be copied
anywhere without a separate dependency directory. You can run it directly:

```console
java -jar target/pinfit-1.0-SNAPSHOT.jar --help
```

To install it as a `pinfit` command instead, run the same installer from the repository root with
no version:

=== "Windows"

    ```powershell
    .\scripts\install.ps1
    ```

=== "Linux / macOS"

    ```console
    ./scripts/install.sh
    ```

In this mode the script builds Pinfit locally (`mvn clean package`) rather than downloading
anything, then installs the result.

!!! tip "VS Code task"

    Press ++ctrl+shift+b++ and run the default **Pinfit: Package + Install** task to rebuild, test,
    package and update the installed command in one step (Windows).

## What the installer touches

Both modes, on both platforms, behave the same way:

- Runs entirely as your user — no administrator/`sudo` rights needed.
- Is marker-gated: it refuses to overwrite a directory it did not create itself, and the matching
  uninstall script refuses to remove anything without that same marker present.
- Writes only inside the install directory itself:

=== "Windows"

    `%LOCALAPPDATA%\Pinfit` by default, or wherever `-InstallDirectory` points — and adds that one
    directory to your **user** `PATH` (via the registry, so no file is edited for this).

=== "Linux / macOS"

    `$HOME/.local/share/Pinfit` by default, or wherever `--install-dir` points. Unlike the Windows
    registry, editing a shell profile to add to `PATH` means picking a file among several
    (`~/.bashrc`, `~/.zshrc`, `~/.profile`, ...) and guessing which one you actually use — this
    script does not do that for you. If the install directory isn't already on `PATH`, it prints
    the one line to add yourself:

    ```console
    export PATH="$HOME/.local/share/Pinfit:$PATH"
    ```

The installed copy is independent from `target/`, so `mvn clean` will not remove it. Re-run the
installer in either mode to update it.

## Verify

Open a **new** terminal (or, on Linux/macOS, source your profile) so the updated `PATH` is picked up:

```console
pinfit --help
```

```title="Expected output"
Pinfit - YAML-driven C interface and module generator

Usage:
  pinfit init [directory] [-f|--force]
  pinfit create interface <name> [directory]
  ...
```

See the [CLI reference](../reference/cli.md) for the full command list.

## Uninstall

=== "Windows"

    ```powershell
    & "$env:LOCALAPPDATA\Pinfit\Uninstall-Pinfit.ps1"
    ```

=== "Linux / macOS"

    `install.sh` copies its own `uninstall.sh` into the install directory, so:

    ```console
    ~/.local/share/Pinfit/uninstall.sh
    ```

This removes the install directory (and, on Windows, its `PATH` entry). It does not touch any
project: your `pinfit.yaml` files and generated C code stay exactly as they are. To remove Pinfit from
a *project*, use [`pinfit detach`](../reference/cli.md#pinfit-detach).

## Why a separate Java runtime, not a single native binary

Pinfit could in principle ship as a `jlink` custom runtime or a GraalVM native-image binary, so
nobody needs Java installed at all. Not done today: SnakeYAML's YAML parsing leans on reflection
in places `native-image` needs explicit reachability metadata for, so that path needs a real
compatibility pass and testing before it could be trusted, and either approach adds a second
platform-specific artifact per release to build, sign and maintain (`jlink` is the lower-risk of
the two - a bundled JRE next to the jar, not a fully separate compiler - but still a second
artifact). Tracked as a possible future improvement, not started.

## Running without installing

Every command in these docs starts with `pinfit`. When no native launcher is installed, substitute
the JAR:

```console
java -jar path/to/pinfit-1.0-SNAPSHOT.jar generate
```
