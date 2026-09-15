# YAML cheat sheet

Every spec shape on one page. Each block is complete and valid — copy it and edit. Follow the link
in each heading for the explanation.

## Shared shorthand

Struct fields, function and event `parameters`, and `context` entries accept a compact string or a
full map:

```yaml
context:
  - void *hardware                                          # compact "type name"
  - uint8_t buffer[16]                                      # array declarator is kept verbatim
  - { type: uint32_t, name: ticks, description: Uptime }    # map form, for a description
```

Includes are copied verbatim. Single-quote a local include so YAML keeps the double quotes:

```yaml
includes: [<stdint.h>, '"vendor_i2c.h"']
```

---

## [`cgen.yaml`](../guide/project-configuration.md)

```yaml
schema: 1
name: firmware
version: 0.1.0

documentation:
  style: doxygen        # doxygen | none | custom
  # file: documentation.yaml   # required when style is custom

format:
  indent: 4
  lineEnding: lf                 # lf | crlf
  publicVariables: extern        # extern | accessors
  suppressUnusedWarnings: true   # emit (void)param; in stubs
  functionNaming: snake_case     # snake_case | camelCase
```

---

## [`*.interface.yaml`](../generators/interface.md)

```yaml
kind: interface
name: common_iic
description: Portable I2C interface
header: common_iic_I.h
invalidReturn: COMMON_IIC_INVALID_PARAM        # required for non-void functions
uninitializedReturn: COMMON_IIC_NOT_INITIALIZED
includes: [<stdint.h>]

enums:
  - name: common_iic_status_t
    description: Transfer result
    values:
      - { name: COMMON_IIC_SUCCESS, value: 0 }
      - { name: COMMON_IIC_INVALID_PARAM, value: 1 }
      - { name: COMMON_IIC_NOT_INITIALIZED }    # value omitted: continues from the previous

structs:
  - name: common_iic_options_t
    description: Interface configuration
    fields:
      - uint32_t speed

functions:
  - name: write
    return: common_iic_status_t
    description: Write bytes
    parameters:
      - const uint8_t *data
      - uint32_t length
    # invalidReturn: ...        # optional per-function override
    # uninitializedReturn: ...
```

---

## [`*.module.yaml`](../generators/module.md)

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
    values:
      - { name: RA_IIC_MODE_OFF, value: 0 }
      - { name: RA_IIC_MODE_ON }

context:
  - void *hardware

variables:
  - bool busy                          # private (static) by default
  - uint32_t transfer_count public     # public | get | set
  - uint8_t command_buffer[6]
  - { type: uint32_t, name: ticks, visibility: public, initial: '0U', description: Uptime }

functions:
  - name: initialize
    return: bool
    description: One-time module initialization
    parameters: []
    invalidReturn: false        # required for non-void
    visibility: public          # private (default) | public

singleton: false
# instance: ra_iic_handle       # rename the singleton accessor
# singletonElse: true           # add an else branch to it

# Written automatically by @CGenSwitch; a link, never a declaration:
# externalEnums:
#   - { name: flash_opcodes_t, file: ../drivers/flash_regs.h }
```

!!! warning "Visibility rules"

    `get` / `set` require `format.publicVariables: accessors`. Array variables cannot be `public`,
    `get` or `set` under `accessors`.

---

## [`*.state-machine.yaml`](../generators/state-machine.md)

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

Each `(from, event)` pair must be unique.

---

## [`*.observer.yaml`](../generators/observer.md)

```yaml
kind: observer
name: button_events
description: Button event fan-out
includes: []
interface: button_listener     # every function on it must return void
capacity: 8
context: []
```

---

## [`*.command-table.yaml`](../generators/command-table.md)

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

Give every command an explicit `opcode`, or omit it on all of them to auto-number from 0. Mixing is
rejected.

---

## [`*.status-codes.yaml`](../generators/status-codes.md)

```yaml
kind: status-codes
name: cgen_status
description: Shared status codes
includes: []

codes:
  - { name: OK, value: 0, description: Success }     # exactly one code must be 0
  - { name: INVALID_PARAM, value: -1 }
  - { name: NOT_READY, value: -2 }
```

---

## [`*.adapter.yaml`](../generators/adapter.md)

```yaml
kind: adapter
name: bus_adapter
description: Adapts bus to bus_hal
includes: []
from: bus          # the interface this adapter exposes
to: bus_hal        # the interface it calls into
context: []

mappings:
  - { from: write, to: send }     # accepted only when the signatures match exactly
```

---

## User region names

| Region | Generator |
| --- | --- |
| `interface.preamble` / `.declarations` / `.footer` | interface |
| `module.header.preamble` / `.footer` | module |
| `module.source.includes` / `.variables` / `.prototypes` / `.footer` | module |
| `function.<interface>.<function>.body` | module, adapter |
| `function.<name>.body` | module |
| `variable.<name>.get` / `.set` | module (`accessors`) |
| `singleton.init` / `singleton.else` | module |
| `state.<STATE>.entry` / `.exit` / `.tick` | state machine |
| `transition.<from>.<event>.guard` / `.action` | state machine |
| `event.<EVENT>.unhandled` | state machine |
| `command.<NAME>.body` / `command.unknown` | command table |
| `switchcase.<enum>.<case>` | [`@CGenSwitch`](../guide/cgenswitch.md) |
| `<kind>.header.preamble` / `.footer`, `<kind>.source.includes` / `.footer` | observer, command table, adapter, state machine |
| `status-codes.preamble` / `.footer` | status codes |
