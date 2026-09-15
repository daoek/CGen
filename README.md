# CGen

CGen is a small YAML-driven CLI for generating C interface headers and concrete
module headers/sources. Generated files contain compact `/*@CGen(...)*/`
structural markers and distinctive `/*@CGen usercode+ name*/` ... `/*@CGen
usercode-*/` user regions, so implementation code survives regeneration.

## Build and run

Requires Java 17 and Maven.

```console
mvn clean package
java -jar target/cgen-1.0-SNAPSHOT.jar --help
```

The packaged JAR includes SnakeYAML and can be copied without a separate Maven
dependency directory.

### Install the `CGen` command on Windows

Two ways to install, depending on whether you want a published build or your
own local build.

**Install a release (recommended for most people).** Every version tag is
built and tested by [CI](.github/workflows/release.yml) and published as a
[GitHub Release](https://github.com/daoek/CGen/releases) with the jar and a
`SHA256SUMS` checksum file attached — you don't need Java, Maven, or a clone
of this repo to install it. Grab `scripts/install.ps1` (e.g. from the
[repo's raw source](https://github.com/daoek/CGen/raw/main/scripts/install.ps1))
and run:

```powershell
.\install.ps1 -Version 1.2.0
```

This downloads that release's jar and `SHA256SUMS` over HTTPS from GitHub,
verifies the jar's SHA-256 against the published checksum, and only installs
it if the checksum matches — nothing is written to disk otherwise. It prints
the installed jar's checksum at the end so you can cross-check it by hand
against the `SHA256SUMS` file on the [Releases page](https://github.com/daoek/CGen/releases)
if you'd rather not take the script's word for it.

**Build and install from source (for contributors).** From the repository
root, run:

```powershell
.\scripts\install.ps1
```

This builds CGen locally (`mvn clean package`) instead of downloading
anything, then installs it the same way. In VS Code, press `Ctrl+Shift+B` and
run the default `CGen: Package + Install` task to rebuild, test, package, and
update the installed command in one step.

**Either way**, the install:
- Runs entirely as your user — no admin rights needed.
- Only ever writes inside `%LOCALAPPDATA%\CGen` (or wherever you pass to
  `-InstallDirectory`) and adds that one directory to your user `PATH`.
- Is marker-gated: it refuses to overwrite a directory it didn't create
  itself, and `Uninstall-CGen.ps1` refuses to remove anything without that
  same marker present.
- If your machine's default execution policy blocks running a local `.ps1`
  directly, run it as `powershell -ExecutionPolicy Bypass -File .\install.ps1 -Version 1.2.0`
  instead (this is also what the VS Code task does). That flag scopes to the
  one `powershell.exe` invocation it's passed to — it doesn't change your
  machine's or user's execution policy for anything else.

Open a new terminal after installing:

```console
CGen --help
```

The installed copy is independent from `target`, so `mvn clean` will not
remove it. Re-run the installer (either mode) to update it. To uninstall
safely:

```powershell
& "$env:LOCALAPPDATA\CGen\Uninstall-CGen.ps1"
```

## Workflow

Run commands from the project directory (or one of its descendants):

```console
CGen init
CGen create interface common_iic drivers/common/Interface
CGen create module ra_iic drivers/common/RA --implements common_iic
CGen generate
```

When no native `CGen` launcher is installed, replace `CGen` with
`java -jar path/to/cgen-1.0-SNAPSHOT.jar`.

`init [directory]` creates only `cgen.yaml` and never chooses a source layout
for you. `create interface|module <name> [directory]` writes the YAML into the
specified directory, creating it when needed. Without a directory it writes in
the current directory. `generate [directory]` scans the specified directory
tree; without one it scans the current directory. Interface references are
resolved across the whole project. `gen` is an alias for `generate`.

`create` also scaffolds the other generator kinds (see their sections below
for the full YAML shape each one produces):

```console
CGen create state-machine door
CGen create observer button_events --interface button_listener [--capacity 8]
CGen create command-table uart_cmd
CGen create status-codes cgen_status
CGen create adapter bus_adapter --from bus --to bus_hal
```

### Nested projects (importing a library)

A directory tree with its own `cgen.yaml` is a separate, self-contained
project, even when it lives inside another project's directory tree. This is
how you vendor or import another CGen-based library without it being pulled
into (and regenerated or overwritten by) the enclosing project:

```
firmware/
  cgen.yaml                        # outer project
  drivers/common/ra_iic.module.yaml
  Lib/importedlib/
    cgen.yaml                      # its own project - a boundary starts here
    sensor.interface.yaml
```

Running `CGen generate` (or `detach`) from `firmware/` scans its own tree but
stops at `Lib/importedlib/` the moment it sees that directory's `cgen.yaml`;
nothing underneath it (however deep) is scanned, generated, or deleted.
`CGen create ... Lib/importedlib` from the outer project is refused for the
same reason - you'd be writing into someone else's project with the wrong
`cgen.yaml` rules. To work on the nested project, run `CGen` from inside it;
it resolves its own `cgen.yaml` and generates with its own settings.

### Editor snippets

`.vscode/cgen.code-snippets` has VS Code snippets for the repeated list items
below (enum/struct entries, fields, parameters, functions, module variables,
state-machine states/events/transitions, command-table commands, status
codes, adapter mappings, `includes` entries) - open any `.yaml` file, type a
prefix like `cgen-function-module` or `cgen-variable`, and expand it instead
of retyping the shape from the docs each time. They work anywhere VS Code
sees the file as YAML, so they apply to every kind of spec file in this repo.

## Project configuration

```yaml
schema: 1
name: firmware
version: 0.1.0

documentation:
  style: doxygen # doxygen, none, or custom
  # file: documentation.yaml

format:
  indent: 4
  lineEnding: lf # lf or crlf
  publicVariables: extern # extern or accessors
```

Project configuration contains generator-wide preferences only; it does not
emit additional C headers or sources.

`format.publicVariables` picks how a module's non-`private` variables are
exposed project-wide. `extern` (default) gives each `public` one a plain
`extern` declaration in the header and a matching definition in the source.
`accessors` keeps the variable `static` (private storage) and instead
generates `get_<name>` / `set_<name>` functions (no module prefix), each with
its own `variable.<name>.get` / `variable.<name>.set` user region so you can
add validation or side effects on read/write. `get`/`set` visibility on a
variable narrows this to only that one accessor; both require
`format.publicVariables: accessors`. See [Module YAML](#module-yaml) below.

For custom documentation, set `documentation.style` to `custom` and point
`documentation.file` at a YAML file with optional `file`, `function`, `type`,
and `variable` text templates. Supported placeholders include `${file}`,
`${name}`, `${brief}`, `${return}`, and `${params}`.

## Interface YAML

An interface YAML and its generated header live in the same directory.

```yaml
kind: interface
name: common_iic
description: Portable I2C interface
header: common_iic_I.h
invalidReturn: COMMON_IIC_INVALID_PARAM
uninitializedReturn: COMMON_IIC_NOT_INITIALIZED
includes: [<stdint.h>]

enums:
  - name: common_iic_status_t
    description: Transfer result
    values:
      - { name: COMMON_IIC_SUCCESS, value: 0 }
      - { name: COMMON_IIC_INVALID_PARAM, value: 1 }
      - { name: COMMON_IIC_NOT_INITIALIZED, value: 2 }

structs:
  - name: common_iic_options_t
    fields:
      - uint32_t speed

functions:
  - name: write
    return: common_iic_status_t
    description: Write bytes
    parameters:
      - const uint8_t *data
      - uint32_t length
```

The generated interface contains a context/function-pointer table and guarded
inline dispatch functions, following the pattern in the target examples.
Every non-`void` function must define `invalidReturn` either at interface or
function level. This avoids silently generating an invalid `-1` for enum,
pointer, unsigned, or application-specific return types.

Struct fields, function/event `parameters`, and `context` entries all accept
this compact `"type name"` shorthand in addition to the full
`{ type: ..., name: ..., description: ... }` map form. Use the map form when
you need a `description`.

## Module YAML

```yaml
kind: module
name: ra_iic
description: RA I2C implementation
header: ra_iic.h
source: ra_iic.c
implements: [common_iic]
includes: ['"vendor_i2c.h"']

enums:
  - name: ra_iic_mode_t
    description: Operating mode
    values:
      - { name: RA_IIC_MODE_OFF, value: 0 }
      - { name: RA_IIC_MODE_ON }

context:
  - void *hardware

variables:
  - uint32_t transfer_count public
  - bool busy
```

`enums` declares module-private `typedef enum` types in the header, ahead of
the context struct and variables, so `context`/`variables` entries can use
them as a field type - same shape as [interface enums](#interface-yaml).

Variables default to `private` (`static` storage) — write just `type name`.
Append a trailing `public`, `get`, or `set` to expose one, or use the map form
(`{ type: ..., name: ..., visibility: public, initial: ... }`) when you need
`initial`/`description` alongside it. How a non-`private` variable is exposed
is controlled project-wide by `format.publicVariables` (see
[Project configuration](#project-configuration)): `extern` gives a `public`
variable a plain `extern` declaration in the header and a definition in the
source; `accessors` keeps it `static` and generates a getter/setter pair
instead, each with its own user region:

```c
uint32_t get_transfer_count(void)
{
/*@CGen usercode+ variable.transfer_count.get*/
    return transfer_count;
/*@CGen usercode-*/
}
```

`get` and `set` visibility generate only that one accessor (the variable stays
`static`, with no counterpart function) — useful for a read-only counter or a
write-only latch. `get`/`set` require `format.publicVariables: accessors`;
using them under `extern` is a config error, since `extern` only knows
`public`/`private`.

A module can also declare its own standalone functions, independent of any
implemented interface:

```yaml
functions:
  - name: initialize
    return: bool
    description: One-time module initialization
    parameters: []
    invalidReturn: false
    visibility: public
```

This generates `initialize(void)` - unlike bind/accessor functions, the
module name is not prefixed, so the C identifier is exactly `name` (or with
parameters, same `"type name"`/map shorthand as elsewhere) - with a
`function.initialize.body` user region seeded with `cgen_result = false;`
before it and `return cgen_result;` after - same shape as an interface
function, but not part of an interface's function-pointer table, so no
`void *context` first parameter is added implicitly; take one yourself as an
explicit parameter if the function needs one. `invalidReturn` is required
for any non-`void` return type (there's no interface-level default to fall
back on here). Since the name isn't module-prefixed, keep it unique
yourself across a module's functions and its implemented interfaces.

`visibility` is `private` (default) or `public`, same meaning as on
`variables`: `private` emits a `static` function defined only in the source
(no header declaration); `public` declares it in the header too.

Set `singleton: true` to also generate a lazy-init accessor instead of relying
on an externally supplied context:

```yaml
singleton: true
```

This adds `<name>_context_t *<name>_instance(void)` to the header. The source
keeps the context as static storage and runs a `singleton.init` user region
the first time the accessor is called:

```c
/*@CGen usercode+ singleton.init*/
/* One-time setup for the singleton instance. */
/*@CGen usercode-*/
```

Set `singletonElse: true` to also emit an `else` branch that runs on every
call after the first, with its own `singleton.else` user region:

```c
/*@CGen usercode+ singleton.else*/
/* Runs on every call after the singleton is already initialized. */
/*@CGen usercode-*/
```

## State machine YAML

A state machine YAML and its generated header/source live in the same
directory (e.g. `door.state-machine.yaml` -> `door.h`, `door.c`), same as
module. To lay a project out with generators sorted into folders, move the
YAML itself into that folder — CGen scans by directory, so the generated
files simply follow it there.

```yaml
kind: state-machine
name: door
description: Door state machine
header: door.h
source: door.c
includes: []
context:
  - uint32_t open_count

initial: CLOSED

states:
  - { name: CLOSED, description: Door is closed }
  - { name: OPEN, description: Door is open }

events:
  - name: OPEN_REQUEST
    description: Request to open
    parameters: []

transitions:
  - { from: CLOSED, event: OPEN_REQUEST, to: OPEN, guard: true }
```

Each `(from, event)` pair must be unique, so the generated dispatch is never
ambiguous. Generated API: `door_init(context)`, one `door_on_<EVENT>(context,
...)` function per event, and a `door_state_t`/`door_context_t` pair (context
always carries `state` plus your `context` fields). Per state, entry/exit
hooks are user regions:

```c
/*@CGen usercode+ state.OPEN.entry*/
/*@CGen usercode-*/
/*@CGen usercode+ state.OPEN.exit*/
/*@CGen usercode-*/
```

When `guard: true`, the transition gets a `bool cgen_guard = true;` default
you can override in `transition.<from>.<event>.guard`; a `false` guard or a
state/event combination with no matching transition both fall through to
`event.<EVENT>.unhandled`.

Every state machine also gets a `<name>_tick(context)` and a
`<name>_go_to_state(context, state)`:

```c
void door_tick(door_context_t *context);
void door_go_to_state(door_context_t *context, door_state_t state);
```

Call `door_tick()` on every iteration of your main loop. It switches on the
current state into a per-state user region — `state.<STATE>.tick` — where you
write whatever runs on every tick while in that state: polling, timers,
sensor reads, and conditional moves to another state, all in plain C:

```c
/*@CGen usercode+ state.RUNNING.tick*/
if (getMotorSpeed() > 100.0f)
{
    door_go_to_state(context, DOOR_STATE_FAULT);
}
/*@CGen usercode-*/
```

`door_go_to_state()` is the generic transition primitive underneath: it runs
the current state's exit hook, assigns the new state, then runs the target
state's entry hook — for any state, not just ones with a declared
`transitions` entry. It's generated once, mechanically, with no user region
of its own; `transitions`/`events` stay exactly as above for transitions that
should only happen in reaction to a specific event.

## Observer YAML

Fans a single call out to every subscriber implementing an existing
`interface` — that interface's functions must all return `void` (there is no
sensible way to aggregate N subscriber return values). Header/source live
next to the YAML, same as state machine and module.

```yaml
kind: observer
name: button_events
description: Button event fan-out
includes: []
interface: button_listener
capacity: 8
context: []
```

Generated API: `button_events_init`, `button_events_subscribe`/
`_unsubscribe` (fixed-capacity array, no allocation), and one
`button_events_publish_<function>(context, ...)` per function on
`button_listener`, which loops subscribers and calls straight through the
listener interface's own generated dispatch wrapper. No user regions —
fan-out is fully mechanical; put your logic in the modules that implement
`button_listener`.

## Command table YAML

A generic UART/CLI-style opcode dispatcher. Header/source live next to the
YAML.

```yaml
kind: command-table
name: uart_cmd
description: UART command table
includes: []
context: []

commands:
  - { name: PING, opcode: 0 }
  - { name: RESET, opcode: 1 }
```

Give every command an explicit `opcode`, or omit it on all of them to
auto-number starting at 0 — mixing the two is rejected. Generated API:
`void uart_cmd_dispatch(context, uart_cmd_command_t command, const uint8_t
*payload, uint32_t length)`, which switches on the opcode enum into one
static handler per command:

```c
/*@CGen usercode+ command.PING.body*/
/*@CGen usercode-*/
```

An opcode with no matching command falls through to `command.unknown`.

## Status codes YAML

A standalone, header-only (no source file) shared status enum plus checking
macros. Purely additive — it does not change how existing
`interface.yaml` files declare their own `invalidReturn`/`uninitializedReturn`.

```yaml
kind: status-codes
name: cgen_status
description: Shared status codes
includes: []

codes:
  - { name: OK, value: 0, description: Success }
  - { name: INVALID_PARAM, value: -1 }
  - { name: NOT_READY, value: -2 }
```

Exactly one code must have `value: 0`; it becomes the success value.
Generates `cgen_status_t` plus:

```c
#define CGEN_STATUS_SUCCEEDED(status) ((status) == CGEN_STATUS_OK)
#define CGEN_STATUS_FAILED(status) (!CGEN_STATUS_SUCCEEDED(status))
#define CGEN_STATUS_CHECK(status_expression) \
    do { cgen_status_t cgen_status = (status_expression); \
        if (CGEN_STATUS_FAILED(cgen_status)) { return cgen_status; } \
    } while (0)
```

## Adapter YAML

Glue between two existing, incompatible interfaces — typically a project's
own contract (`from`) and a vendor HAL (`to`). Header/source live next to
the YAML.

```yaml
kind: adapter
name: bus_adapter
description: Adapts bus to bus_hal
includes: []
from: bus
to: bus_hal
context: []

mappings:
  - { from: write, to: send }
```

`from` is the interface this adapter exposes (bound via the usual
`bus_adapter_bind_bus(interface, context)`); `to` is the interface it calls
into, supplied at runtime via the generated `bus_adapter_set_target(context,
target)`. A mapped function is only accepted when both functions have the
exact same parameter types (in order) and return type — CGen then generates
a direct call-through with no user code needed. Any `from` function left out
of `mappings`, or rejected for a signature mismatch, falls back to a plain
stub body exactly like an unmapped `module` function:

```c
/*@CGen usercode+ function.bus.reset.body*/
/*@CGen usercode-*/
```

## MISRA-oriented generated C

CGen emits MISRA C:2012-friendly control flow: generated functions use a
single final return, pointer members are accessed only after null checks, and
generated stubs explicitly consume unused parameters. In a non-`void` module
user region, assign the final value to `cgen_result` instead of returning early.

MISRA compliance applies to the complete translation unit, including configured
types, expressions, includes, and user regions. It must therefore be confirmed
with the project's MISRA checker and deviation policy. The generic interface
context intentionally converts `void *` to the concrete module context type;
projects enforcing advisory Rule 11.5 need to record that design deviation.

## Safe regeneration and permanent detach

Edit only inside named user regions — marked distinctively with `usercode+`/
`usercode-` so they stand out from CGen's other `/*@CGen(...)*/` structural
markers:

```c
/*@CGen usercode+ function.common_iic.write.body*/
/* Your code is retained here. */
/*@CGen usercode-*/
```

CGen refuses to overwrite files without its generated-file marker. If a YAML
item is removed, its user region is retained as an orphan instead of being
discarded.

To permanently remove CGen metadata from the entire project:

```console
CGen detach
```

This destructive command requires typing the exact project name. It keeps all
generated C code and unrelated YAML files, removes CGen marker lines, then
deletes `cgen.yaml`, all `*.interface.yaml` and `*.module.yaml` files, and the
custom documentation YAML referenced by the project. The detached project
cannot be regenerated unless it is configured again with `CGen init`.
