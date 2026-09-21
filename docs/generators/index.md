# Generators

Every spec file starts with a `kind:` that selects one of seven generators. All of them are
scaffolded by [`CGen create`](../reference/cli.md#cgen-create) and produced by the same
`CGen generate` run, and all of them write their output next to the YAML that describes them.

## Choosing one

<div class="grid cards" markdown>

-   :material-connection: **[Interface](interface.md)**

    ---

    A contract several modules can implement: a context plus function-pointer table, with guarded
    `static inline` dispatch. Header only.

    *Use when callers should not know which driver they are talking to.*

-   :material-cube-outline: **[Module](module.md)**

    ---

    A concrete unit: private state, variables, standalone functions, and an implementation of any
    number of interfaces. Header and source.

    *The workhorse. Most files in a project are modules.*

-   :material-state-machine: **[State machine](state-machine.md)**

    ---

    States, events, transitions and guards, with entry/exit/tick hooks and a `_tick()` for your
    main loop. `engine: statesmith` adds hierarchical (composite) states via
    [StateSmith](state-machine.md#the-statesmith-engine).

    *Use when behaviour depends on what happened before.*

-   :material-broadcast: **[Observer](observer.md)**

    ---

    Fan one call out to every subscriber implementing a `void`-returning interface. Fixed capacity,
    no allocation.

    *Use for event notification with an unknown number of listeners.*

-   :material-table-arrow-right: **[Command table](command-table.md)**

    ---

    An opcode dispatcher for UART, CLI or wire protocols, with one handler region per command.

    *Use when bytes arrive and something has to decide what they mean.*

-   :material-alert-circle-check-outline: **[Status codes](status-codes.md)**

    ---

    A shared status enum plus `SUCCEEDED` / `FAILED` / `CHECK` macros. Header only, no source.

    *Use for one error vocabulary across a whole project.*

-   :material-swap-horizontal: **[Adapter](adapter.md)**

    ---

    Glue between two incompatible interfaces — typically your own contract and a vendor HAL.
    Matching signatures become direct call-throughs.

    *Use when you cannot change either side.*

</div>

## At a glance

| Kind | `create` command | Output | Cross-references | User regions |
| --- | --- | --- | --- | --- |
| `interface` | `CGen create interface <name> [dir]` | header | — | 3 |
| `module` | `CGen create module <name> [dir] --implements <i>` | header + source | interfaces | one per function, plus file-level |
| `state-machine` | `CGen create state-machine <name> [dir] [--engine statesmith]` | header + source (+ `_hooks.h/.c` and `_sm/` with `engine: statesmith`) | — | per state, transition and event |
| `observer` | `CGen create observer <name> --interface <i> [dir]` | header + source | one interface | none |
| `command-table` | `CGen create command-table <name> [dir]` | header + source | — | one per command, plus `command.unknown` |
| `status-codes` | `CGen create status-codes <name> [dir]` | header | — | none |
| `adapter` | `CGen create adapter <name> --from <i> --to <i> [dir]` | header + source | two interfaces | one per unmapped function |

## Shared conventions

These apply to every kind.

### Common top-level keys

```yaml
kind: module          # selects the generator
name: ra_iic          # C identifier prefix for everything generated
description: ...      # becomes the file's @brief
header: ra_iic.h      # generated header name
source: ra_iic.c      # generated source name (kinds that emit one)
includes: ['"vendor_i2c.h"', <stdint.h>]
```

`includes` entries are copied verbatim into the generated header. Quote a local include in single
quotes so YAML keeps the double quotes.

### Compact `"type name"` shorthand

Struct fields, function and event `parameters`, and `context` entries all accept a plain string
instead of a map:

```yaml
context:
  - void *hardware
  - uint32_t timeout_ms
```

Use the map form when you need a `description`:

```yaml
context:
  - { type: void *, name: hardware, description: Vendor handle }
```

### Where files land

The spec and its generated files always share a directory. To sort generators into folders, move
the YAML — the output follows it on the next `generate`.

### Naming

Generated function names are `<name>_<something>` by default. The project-wide
[`format.functionNaming`](../guide/project-configuration.md#functionnaming) switch renders them in
`camelCase` instead if you prefer.
