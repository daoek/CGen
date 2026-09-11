# CGen

CGen is a small YAML-driven CLI for generating C interface headers and concrete
module headers/sources. Generated files contain compact `/*@CGen(...)*/`
markers and named user regions, so implementation code survives regeneration.

## Build and run

Requires Java 17 and Maven.

```console
mvn clean package
java -jar target/cgen-1.0-SNAPSHOT.jar --help
```

The packaged JAR includes SnakeYAML and can be copied without a separate Maven
dependency directory.

### Install the `CGen` command on Windows

From the repository root, run:

```powershell
.\scripts\install.ps1
```

This builds CGen, installs a private copy in `%LOCALAPPDATA%\CGen`, and adds
that directory to your user `PATH`. Open a new terminal afterward:

```console
CGen --help
```

In VS Code, press `Ctrl+Shift+B` and run the default
`CGen: Package + Install` task to rebuild, test, package, and update the
installed command in one step.

The installed copy is independent from `target`, so `mvn clean` will not remove
it. Re-run the installer to update it. To uninstall safely:

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
```

Project configuration contains generator-wide preferences only; it does not
emit additional C headers or sources.

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
      - { type: uint32_t, name: speed }

functions:
  - name: write
    return: common_iic_status_t
    description: Write bytes
    parameters:
      - { type: const uint8_t *, name: data, description: Source bytes }
      - { type: uint32_t, name: length, description: Byte count }
```

The generated interface contains a context/function-pointer table and guarded
inline dispatch functions, following the pattern in the target examples.
Every non-`void` function must define `invalidReturn` either at interface or
function level. This avoids silently generating an invalid `-1` for enum,
pointer, unsigned, or application-specific return types.

## Module YAML

```yaml
kind: module
name: ra_iic
description: RA I2C implementation
header: ra_iic.h
source: ra_iic.c
implements: [common_iic]
includes: ['"vendor_i2c.h"']

context:
  - { type: void *, name: hardware }

variables:
  - { type: uint32_t, name: transfer_count, visibility: public, initial: 0U }
  - { type: bool, name: busy, visibility: private, initial: false }
```

Public variables receive an `extern` declaration in the module header and one
definition in the source. Private variables are `static` in the source.

Set `singleton: true` to also generate a lazy-init accessor instead of relying
on an externally supplied context:

```yaml
singleton: true
```

This adds `<name>_context_t *<name>_instance(void)` to the header. The source
keeps the context as static storage and runs a `singleton.init` user region
the first time the accessor is called:

```c
/*@CGen(+singleton.init)*/
/* One-time setup for the singleton instance. */
/*@CGen(-singleton.init)*/
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
  - { type: uint32_t, name: open_count }

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
/*@CGen(+state.OPEN.entry)*/
/*@CGen(-state.OPEN.entry)*/
/*@CGen(+state.OPEN.exit)*/
/*@CGen(-state.OPEN.exit)*/
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
/*@CGen(+state.RUNNING.tick)*/
if (getMotorSpeed() > 100.0f)
{
    door_go_to_state(context, DOOR_STATE_FAULT);
}
/*@CGen(-state.RUNNING.tick)*/
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
/*@CGen(+command.PING.body)*/
/*@CGen(-command.PING.body)*/
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
/*@CGen(+function.bus.reset.body)*/
/*@CGen(-function.bus.reset.body)*/
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

Edit only inside named user regions:

```c
/*@CGen(+function.common_iic.write.body)*/
/* Your code is retained here. */
/*@CGen(-function.common_iic.write.body)*/
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
