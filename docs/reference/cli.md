# CLI reference

Run `CGen` from the project directory or any of its descendants. When no native launcher is
installed, substitute `java -jar path/to/cgen-1.0-SNAPSHOT.jar` for `CGen` everywhere below.

```console
CGen --help
```

```title="Usage"
CGen - YAML-driven C interface and module generator

Usage: CGen <command> [options]

Commands:
  init             Create a new cgen.yaml project
  create           Scaffold a new interface, module, or other spec
  gen, generate    Generate C source from YAML specs
  rename           Rename a module and update every reference to it
  fix-prototypes   Add missing prototypes for hand-written functions
  detach           Remove CGen tags and generated-file tracking (destructive)

Run 'CGen help <command>' or 'CGen <command> --help' for details on a command.
```

`CGen`, `CGen help` and `CGen --help`/`-h` all print the command list above, with no flag detail.
For a given command, either `CGen <command> --help`/`-h` or `CGen help <command>` prints that
command's full usage and flags — the sections below. Errors print a red `CGen error` block, usually
followed by a cyan hint explaining how to fix the spec.

The same reference is also shipped as a Unix `man` page, [`man/man1/cgen.1`](https://github.com/daoek/CGen/blob/main/man/man1/cgen.1)
in the repository. On a system with `man`, view it directly or install it:

```console
man ./man/man1/cgen.1
# or, to make `man cgen` work from anywhere:
mkdir -p ~/.local/share/man/man1 && cp man/man1/cgen.1 ~/.local/share/man/man1/ && mandb
```

---

## `CGen init`

```console
CGen init [directory] [-f|--force]
```

Creates `cgen.yaml` — and nothing else. CGen never chooses a source layout for you.

Without a directory it writes in the current one. It refuses to overwrite an existing `cgen.yaml`
unless you pass `-f`.

See [Project configuration](../guide/project-configuration.md) for what the file contains.

---

## `CGen create`

```console
CGen create <kind> <name> [directory] [kind-specific options]
```

Writes a spec file into `directory`, creating that directory when needed. Without a directory it
writes in the current one. The scaffold arrives pre-filled with commented examples of every field.

| Kind | Command |
| --- | --- |
| [Interface](../generators/interface.md) | `CGen create interface common_iic drivers/Interface` |
| [Module](../generators/module.md) | `CGen create module ra_iic drivers/RA --implements common_iic` |
| [State machine](../generators/state-machine.md) | `CGen create state-machine door` |
| [Observer](../generators/observer.md) | `CGen create observer button_events --interface button_listener --capacity 8` |
| [Command table](../generators/command-table.md) | `CGen create command-table uart_cmd` |
| [Status codes](../generators/status-codes.md) | `CGen create status-codes cgen_status` |
| [Adapter](../generators/adapter.md) | `CGen create adapter bus_adapter --from bus --to bus_hal` |

### Options

| Option | Applies to | Meaning |
| --- | --- | --- |
| `--implements <name>[,<name>...]` | module | Interfaces the module implements. Comma-separated, no spaces. |
| `--interface <name>` | observer | The listener interface. Required. |
| `--capacity <n>` | observer | Maximum subscribers. |
| `--from <interface>` | adapter | The interface the adapter exposes. Required. |
| `--to <interface>` | adapter | The interface it calls into. Required. |
| `--dir <directory>` | all | The target directory, as an explicit flag instead of a positional argument. |

!!! note "Creating does not generate"

    `create` writes YAML only. Run `CGen generate` to produce the C.

!!! warning "Not into a nested project"

    `CGen create ... Lib/importedlib` is refused when that directory has its own `cgen.yaml` — you
    would be writing a spec into another project, to be generated under the wrong rules. See
    [Nested projects](../guide/nested-projects.md).

---

## `CGen generate`

```console
CGen generate [directory] [-f|--force] [-v|--verbose] [--also-nested]
CGen gen [directory] ...
```

Scans the given directory tree (the current one by default), resolves every spec, and writes the
headers and sources. Interface references are resolved across the whole project, so specs can be
laid out however you like. `gen` is an alias.

```console
[##############################] 3/3  drivers\RA\ra_iic.c
3 file(s) generated
```

Generation is idempotent: running it twice produces identical files the second time. User regions
are carried across every run — see [User regions](../guide/user-regions.md).

### `-f`, `--force`

Overwrite files on disk that are **not** CGen-generated, instead of refusing.

!!! danger "This destroys hand-written content"

    CGen normally refuses to write over a file lacking its generated-file marker, which is what
    protects a hand-written `ra_iic.c` that predates the spec. `--force` removes that protection.
    Commit first.

### `-v`, `--verbose`

Replaces the progress bar with a per-file report of the project root, the scope, which spec produced
each output, whether it is new or regenerated, and how many user regions were carried over:

```console
Project root: D:\firmware
Scope: D:\firmware
[1/3] drivers\Interface\common_iic.interface.yaml -> drivers\Interface\common_iic_I.h (new file)
[2/3] drivers\RA\ra_iic.module.yaml -> drivers\RA\ra_iic.h (regenerated, no user regions found)
[3/3] drivers\RA\ra_iic.module.yaml -> drivers\RA\ra_iic.c (regenerated, 2 user region(s) carried over)
```

Use it when you want to confirm that your code was picked up, or to find which spec owns a file.

### `--also-nested`

Also generate every nested project found under the scanned directory, each with its own
`cgen.yaml`. Asks for confirmation first, after a fast directory count.
[Full explanation](../guide/nested-projects.md#generating-everything-at-once-also-nested).

---

## `CGen rename module`

```console
CGen rename module <old-name> <new-name>
```

Renames a module: its spec file and its generated header and source are moved, the references are
updated, and the project is regenerated in the same run.

```console
Moved drivers\RA\ra_iic.module.yaml -> drivers\RA\ra_iic_master.module.yaml
Moved drivers\RA\ra_iic.h -> drivers\RA\ra_iic_master.h
Moved drivers\RA\ra_iic.c -> drivers\RA\ra_iic_master.c
Renamed module 'ra_iic' to 'ra_iic_master'
3 file(s) generated
```

Use this rather than renaming files by hand: edited region content moves with the files, and
generated identifiers built from the module name are rebuilt consistently.

!!! note "Your own call sites are not rewritten"

    Code inside user regions, and code elsewhere in your project that calls the old
    `ra_iic_*` functions, is yours to update. The compiler will find them.

---

## `CGen fix-prototypes`

```console
CGen fix-prototypes [directory]
```

Scans every CGen-generated module source (`.c`) file in scope for functions you wrote directly
inside a usercode region that have no prototype anywhere in the file. This only catches plain
hand-written functions — a helper you added yourself, not part of any `.module.yaml` — since
YAML-spec'd module functions already get a prototype from CGen.

For each file with findings, it prints the function names and a unified diff (3 lines of context)
of the prototype(s) it would add to the `module.source.prototypes` usercode region at the top of
the file, then asks before writing anything:

```console
drivers\RA\ra_iic.c - 1 function(s) without a prototype:
  checksum

--- a/drivers\RA\ra_iic.c
+++ b/drivers\RA\ra_iic.c
@@ -8,6 +8,7 @@
 /*@CGen usercode+ module.source.variables*/
 /*@CGen usercode-*/
 /*@CGen usercode+ module.source.prototypes*/
+static uint8_t checksum(const uint8_t *data, size_t length);
 /*@CGen usercode-*/

 /*@CGen usercode+ module.source.includes*/
Add these prototypes to the module.source.prototypes usercode region? [y/N]:
```

Answering anything but `y`/`yes` leaves that file untouched and moves on to the next one; the
command itself always exits `0` and reports how many prototypes were added, across how many files.

!!! note "Single-line signatures only"

    This is a line-based scan, not a C parser. A function signature split across multiple lines
    won't be recognized — keep the return type, name and parameter list on one line, brace on the
    next (or the same) line, as CGen's own generated functions do.

---

## `CGen detach`

```console
CGen detach
```

**Permanently** removes CGen from the project. It keeps all generated C code and unrelated YAML,
removes the CGen marker lines, then deletes `cgen.yaml`, every `*.interface.yaml` and
`*.module.yaml`, and the custom documentation YAML the project referenced.

```console
DESTRUCTIVE: detach CGen from this project
This removes all CGen tags and CGen-owned YAML configuration.
Generated C code and unrelated YAML files are kept.

Type the project name 'firmware' to continue:
```

You must type the exact project `name` from `cgen.yaml`. Anything else cancels with nothing
changed.

!!! danger "There is no undo"

    A detached project cannot be regenerated unless it is configured again from scratch with
    `CGen init`. Commit before running it.

Like `generate`, `detach` stops at nested projects: a vendored library with its own `cgen.yaml` is
left fully intact.

---

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Success. |
| `1` | A `CGen error` was printed — an invalid spec, a missing reference, a refused overwrite, an unreadable file — **or** a confirmation prompt was declined (`detach`, `--also-nested`). |

A declined confirmation is a non-zero exit deliberately, so a script that pipes `n` into
`CGen generate --also-nested` does not report success for a run that generated nothing.
