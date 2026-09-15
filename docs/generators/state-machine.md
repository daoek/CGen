# State machine

States, events, transitions and guards, generated as a header and a source. Use it when behaviour
depends on what happened before — a door, a protocol handshake, a motor controller, a boot
sequence.

```console
CGen create state-machine door
```

## Spec

```yaml title="door.state-machine.yaml"
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

Spec and generated files share a directory, the same as a [module](module.md). To sort generators
into folders, move the YAML.

## Keys

| Key | Meaning |
| --- | --- |
| `context` | Extra fields on `<name>_context_t`. The current `state` is always the first field and is added for you. |
| `initial` | The state `<name>_init()` enters. |
| `states` | The state list. Each becomes `<NAME>_STATE_<STATE>` in the generated enum. |
| `events` | Things that can happen. Each becomes a `<name>_on_<EVENT>()` function; `parameters` uses the usual compact shorthand. |
| `transitions` | `{ from, event, to, guard }`. `guard: true` adds an overridable guard region. |

!!! warning "One transition per (from, event)"

    Each `(from, event)` pair must be unique, so the generated dispatch is never ambiguous. Two
    transitions out of the same state on the same event is a configuration error — express the
    choice with a `guard` instead.

## Generated API

```c title="door.h"
typedef enum
{
    DOOR_STATE_CLOSED,
    DOOR_STATE_OPEN
} door_state_t;

typedef struct
{
    door_state_t state;
    uint32_t open_count;
} door_context_t;

void door_init(door_context_t *context);
void door_tick(door_context_t *context);
void door_go_to_state(door_context_t *context, door_state_t state);
void door_on_OPEN_REQUEST(door_context_t *context);
```

`door_init()` sets the initial state and runs its entry hook. One `door_on_<EVENT>()` exists per
event, taking the context plus whatever `parameters` you declared.

## Entry and exit hooks

Each state gets a pair of generated `static` hook functions, each with its own user region:

```c
/*@CGen usercode+ state.OPEN.entry*/
/*@CGen usercode-*/

/*@CGen usercode+ state.OPEN.exit*/
/*@CGen usercode-*/
```

These run on every transition into or out of the state, however it was reached — through an event
or through `door_go_to_state()`.

## Events, guards and actions

```c title="door.c"
void door_on_OPEN_REQUEST(door_context_t *context)
{
    bool cgen_transitioned = false;

    switch (context->state)
    {
        case DOOR_STATE_CLOSED:
        {
            bool cgen_guard = true;

            /*@CGen usercode+ transition.CLOSED.OPEN_REQUEST.guard*/
            /*@CGen usercode-*/
            if (cgen_guard)
            {
                door_exit_CLOSED(context);
                /*@CGen usercode+ transition.CLOSED.OPEN_REQUEST.action*/
                /*@CGen usercode-*/
                context->state = DOOR_STATE_OPEN;
                door_enter_OPEN(context);
                cgen_transitioned = true;
            }
            break;
        }
        default:
            break;
    }

    if (!cgen_transitioned)
    {
        /*@CGen usercode+ event.OPEN_REQUEST.unhandled*/
        /*@CGen usercode-*/
    }
}
```

- **`guard`** — with `guard: true`, `cgen_guard` starts as `true` and you may overwrite it in
  `transition.<from>.<event>.guard`:

  ```c
  /*@CGen usercode+ transition.CLOSED.OPEN_REQUEST.guard*/
  cgen_guard = (context->open_count < MAX_CYCLES);
  /*@CGen usercode-*/
  ```

- **`action`** — `transition.<from>.<event>.action` runs between the exit hook and the state
  assignment. Put transition-specific work here rather than in an entry hook that would also run
  on other paths into the state.

- **Unhandled** — a `false` guard, or an event fired in a state with no matching transition, both
  fall through to `event.<EVENT>.unhandled`. That is the place to log, count, or set an error flag.

## `_tick()` — the main-loop hook

```c
void door_tick(door_context_t *context);
```

Call `door_tick()` on every iteration of your main loop. It switches on the current state into a
per-state region — `state.<STATE>.tick` — where you write whatever runs continuously while in that
state: polling, timers, sensor reads, and conditional moves elsewhere, in plain C:

```c
/*@CGen usercode+ state.RUNNING.tick*/
if (getMotorSpeed() > 100.0f)
{
    door_go_to_state(context, DOOR_STATE_FAULT);
}
/*@CGen usercode-*/
```

## `_go_to_state()` — the transition primitive

```c
void door_go_to_state(door_context_t *context, door_state_t state);
```

It runs the current state's exit hook, assigns the new state, then runs the target state's entry
hook — for **any** state, not only ones with a declared `transitions` entry. It is generated once,
mechanically, with no user region of its own.

Use it for moves that are not a reaction to a declared event: a fault detected in a tick, a
watchdog, a shutdown request. Keep `events` and `transitions` for the reactions that should be part
of the machine's documented contract.

## Using it

```c title="main.c"
#include "door.h"

static door_context_t door;

int main(void)
{
    door_init(&door);

    for (;;)
    {
        if (button_pressed())
        {
            door_on_OPEN_REQUEST(&door);
        }
        door_tick(&door);
    }
}
```

## User regions

| Region | Runs |
| --- | --- |
| `state-machine.header.preamble` / `.footer` | — (header edges) |
| `state-machine.source.includes` / `.footer` | — (source edges) |
| `state.<STATE>.entry` | On every entry into the state |
| `state.<STATE>.exit` | On every exit from the state |
| `state.<STATE>.tick` | On every `_tick()` while in the state |
| `transition.<from>.<event>.guard` | Before the transition, to set `cgen_guard` |
| `transition.<from>.<event>.action` | Between exit hook and state assignment |
| `event.<EVENT>.unhandled` | When no transition fired |

## See also

- [Command table](command-table.md) — dispatch on an opcode rather than on a state.
- [`@CGenSwitch`](../guide/cgenswitch.md) — exhaustive switches inside a tick region.
