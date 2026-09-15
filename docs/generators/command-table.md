# Command table

A generic UART, CLI or wire-protocol opcode dispatcher: one enum, one `dispatch` function, and one
handler with its own user region per command.

```console
CGen create command-table uart_cmd
```

## Spec

```yaml title="uart_cmd.command-table.yaml"
kind: command-table
name: uart_cmd
description: UART command table
includes: []
context: []

commands:
  - { name: PING, opcode: 0 }
  - { name: RESET, opcode: 1 }
```

| Key | Meaning |
| --- | --- |
| `commands` | The command list. Each gets an enum member and a handler. |
| `context` | Fields of the generated `<name>_context_t`, passed to every handler. |

### Opcodes

Give **every** command an explicit `opcode`, or omit it on **all** of them to auto-number from 0.
Mixing the two is rejected — a table where some opcodes are pinned to the protocol and others drift
with list order is a wire-compatibility bug waiting to happen.

```yaml
commands:          # auto-numbered: PING = 0, RESET = 1
  - { name: PING }
  - { name: RESET }
```

!!! tip "Pin the opcodes once the protocol ships"

    Auto-numbering is convenient while you are designing. Once anything on the other end of the
    wire knows the numbers, write them out explicitly so reordering the YAML cannot renumber them.

## Generated API

```c title="uart_cmd.h"
typedef enum
{
    UART_CMD_CMD_PING = 0,
    UART_CMD_CMD_RESET = 1
} uart_cmd_command_t;

typedef struct
{
    unsigned char reserved;   /* replaced by your own context: fields */
} uart_cmd_context_t;

void uart_cmd_dispatch(uart_cmd_context_t *context, uart_cmd_command_t command, const uint8_t *payload, uint32_t length);
```

Enum members are `<NAME>_CMD_<COMMAND>`. An empty `context:` produces a single `reserved` field so
the struct stays valid C.

## Handlers

`dispatch` switches on the opcode into one `static` handler per command:

```c title="uart_cmd.c"
/*@CGen(private-function:uart_cmd_handle_PING)*/
static void uart_cmd_handle_PING(uart_cmd_context_t *context, const uint8_t *payload, uint32_t length)
{
    (void)context;
    (void)payload;
    (void)length;

    /*@CGen usercode+ command.PING.body*/
    /*@CGen usercode-*/
}

/*@CGen(function:uart_cmd_dispatch)*/
void uart_cmd_dispatch(uart_cmd_context_t *context, uart_cmd_command_t command, const uint8_t *payload, uint32_t length)
{
    switch (command)
    {
        case UART_CMD_CMD_PING:
            uart_cmd_handle_PING(context, payload, length);
            break;

        case UART_CMD_CMD_RESET:
            uart_cmd_handle_RESET(context, payload, length);
            break;

        default:
        {
            /*@CGen usercode+ command.unknown*/
            /*@CGen usercode-*/
            break;
        }
    }
}
```

Every handler receives the context plus the raw `payload` and `length`. An opcode with no matching
command falls through to `command.unknown` — where you send a NAK, count the error, or ignore it.

Adding a command to the YAML adds an enum member, a handler and a fresh empty region; every
existing handler body is untouched.

## Using it

```c title="main.c"
#include "uart_cmd.h"

static uart_cmd_context_t commands;

void on_frame_received(const uint8_t *frame, uint32_t length)
{
    if (length >= 1U)
    {
        uart_cmd_dispatch(&commands,
                          (uart_cmd_command_t)frame[0],
                          &frame[1],
                          length - 1U);
    }
}
```

Framing, checksums and transport stay yours — the command table only decides what an opcode means.

!!! note "Validate the payload in the handler"

    `dispatch` does not know how long a `PING` payload should be. Check `length` at the top of each
    handler body before reading `payload`.

## User regions

| Region | Use it for |
| --- | --- |
| `command.<NAME>.body` | What that command does |
| `command.unknown` | Unrecognised opcode handling |
| `command-table.header.preamble` / `.footer` | Header edges |
| `command-table.source.includes` / `.footer` | Source edges |

## See also

- [State machine](state-machine.md) — when the reaction depends on the current state too.
- [Status codes](status-codes.md) — a shared error vocabulary for handler results.
