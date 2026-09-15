# Installation

CGen ships as a single self-contained JAR. You can install a published release without any
build tools, or build it from source.

## Install a release (recommended)

Every version tag is built and tested by
[CI](https://github.com/daoek/CGen/blob/main/.github/workflows/release.yml) and published as a
[GitHub Release](https://github.com/daoek/CGen/releases) with the JAR and a `SHA256SUMS`
checksum file attached. You do not need Java, Maven or a clone of the repository to install it.

Download [`scripts/install.ps1`](https://github.com/daoek/CGen/raw/main/scripts/install.ps1) and
run it with the version you want:

```powershell
.\install.ps1 -Version 1.2.0
```

The script downloads that release's JAR and `SHA256SUMS` over HTTPS from GitHub, verifies the
JAR's SHA-256 against the published checksum, and installs it **only** if the checksum matches.
Nothing is written to disk otherwise. At the end it prints the installed JAR's checksum so you
can cross-check it by hand against the `SHA256SUMS` file on the
[Releases page](https://github.com/daoek/CGen/releases).

!!! tip "If PowerShell blocks the script"

    When your machine's default execution policy refuses to run a local `.ps1`, invoke it
    through an explicitly scoped bypass instead:

    ```powershell
    powershell -ExecutionPolicy Bypass -File .\install.ps1 -Version 1.2.0
    ```

    That flag applies only to the single `powershell.exe` invocation it is passed to. It does
    not change the execution policy for your machine or your user account.

## Build and install from source

For contributors, or to install an unreleased build. Requires **Java 17** and **Maven**.

```console
git clone https://github.com/daoek/CGen.git
cd CGen
mvn clean package
```

That produces `target/cgen-1.0-SNAPSHOT.jar`, which already contains SnakeYAML and can be copied
anywhere without a separate dependency directory. You can run it directly:

```console
java -jar target/cgen-1.0-SNAPSHOT.jar --help
```

To install it as a `CGen` command instead, run the same installer from the repository root with
no `-Version`:

```powershell
.\scripts\install.ps1
```

In this mode the script builds CGen locally (`mvn clean package`) rather than downloading
anything, then installs the result.

!!! tip "VS Code task"

    Press ++ctrl+shift+b++ and run the default **CGen: Package + Install** task to rebuild, test,
    package and update the installed command in one step.

## What the installer touches

Both modes behave the same way:

- Runs entirely as your user — no administrator rights needed.
- Writes only inside `%LOCALAPPDATA%\CGen`, or wherever you point `-InstallDirectory`, and adds
  that one directory to your **user** `PATH`.
- Is marker-gated: it refuses to overwrite a directory it did not create itself, and
  `Uninstall-CGen.ps1` refuses to remove anything without that same marker present.

The installed copy is independent from `target/`, so `mvn clean` will not remove it. Re-run the
installer in either mode to update it.

## Verify

Open a **new** terminal so the updated `PATH` is picked up:

```console
CGen --help
```

```title="Expected output"
CGen - YAML-driven C interface and module generator

Usage:
  CGen init [directory] [-f|--force]
  CGen create interface <name> [directory]
  ...
```

See the [CLI reference](../reference/cli.md) for the full command list.

## Uninstall

```powershell
& "$env:LOCALAPPDATA\CGen\Uninstall-CGen.ps1"
```

This removes the install directory and its `PATH` entry. It does not touch any project: your
`cgen.yaml` files and generated C code stay exactly as they are. To remove CGen from a *project*,
use [`CGen detach`](../reference/cli.md#cgen-detach).

## Running without installing

Every command in these docs starts with `CGen`. When no native launcher is installed, substitute
the JAR:

```console
java -jar path/to/cgen-1.0-SNAPSHOT.jar generate
```
