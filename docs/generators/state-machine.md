# State machine

States, events, transitions and guards, generated as a header and a source. Use it when behaviour
depends on what happened before — a door, a protocol handshake, a motor controller, a boot
sequence.

```console
pinfit create state-machine door
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
/*@Pinfit usercode+ state.OPEN.entry*/
/*@Pinfit usercode-*/

/*@Pinfit usercode+ state.OPEN.exit*/
/*@Pinfit usercode-*/
```

These run on every transition into or out of the state, however it was reached — through an event
or through `door_go_to_state()`.

## Events, guards and actions

```c title="door.c"
void door_on_OPEN_REQUEST(door_context_t *context)
{
    bool pinfit_transitioned = false;

    switch (context->state)
    {
        case DOOR_STATE_CLOSED:
        {
            bool pinfit_guard = true;

            /*@Pinfit usercode+ transition.CLOSED.OPEN_REQUEST.guard*/
            /*@Pinfit usercode-*/
            if (pinfit_guard)
            {
                door_exit_CLOSED(context);
                /*@Pinfit usercode+ transition.CLOSED.OPEN_REQUEST.action*/
                /*@Pinfit usercode-*/
                context->state = DOOR_STATE_OPEN;
                door_enter_OPEN(context);
                pinfit_transitioned = true;
            }
            break;
        }
        default:
            break;
    }

    if (!pinfit_transitioned)
    {
        /*@Pinfit usercode+ event.OPEN_REQUEST.unhandled*/
        /*@Pinfit usercode-*/
    }
}
```

- **`guard`** — with `guard: true`, `pinfit_guard` starts as `true` and you may overwrite it in
  `transition.<from>.<event>.guard`:

  ```c
  /*@Pinfit usercode+ transition.CLOSED.OPEN_REQUEST.guard*/
  pinfit_guard = (context->open_count < MAX_CYCLES);
  /*@Pinfit usercode-*/
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
/*@Pinfit usercode+ state.RUNNING.tick*/
if (getMotorSpeed() > 100.0f)
{
    door_go_to_state(context, DOOR_STATE_FAULT);
}
/*@Pinfit usercode-*/
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
| `transition.<from>.<event>.guard` | Before the transition, to set `pinfit_guard` |
| `transition.<from>.<event>.action` | Between exit hook and state assignment |
| `event.<EVENT>.unhandled` | When no transition fired |

## The statesmith engine

The builtin engine above is flat: one state, one level. For **hierarchical** states - a composite
state with its own sub-states, where a parent transition covers every child - Pinfit can drive
[StateSmith](https://github.com/StateSmith/StateSmith) (Apache-2.0) instead of implementing a
state-machine compiler itself. Choose it when a state naturally decomposes into modes with shared
behaviour (an `OPERATING` mode with `OPENING`/`OPEN`/`CLOSING` sub-states that all react the same
way to a fault, say); stay with the builtin engine for anything flat - it has no external tool to
install and no extra generated files.

### File ownership

```
door.state-machine.yaml   you author this - the single source of truth
door.h  door.c            Pinfit-owned public API (same shape as the builtin engine's, minus go_to_state)
door_hooks.h  door_hooks.c Pinfit-owned - every line of your code lives here, in usercode regions
door_sm/                  entirely StateSmith's - never hand-edited
  door_sm.plantuml          Pinfit-generated input (kept by `detach`, as documentation)
  door_sm.h  door_sm.c       StateSmith-generated state-machine logic
  door_sm.sim.html           StateSmith's browser simulator for this diagram
```

`generate` writes the Pinfit-owned files, then runs `ss.cli` on the `.plantuml` it just wrote.
`ss.cli` never runs, and is never required, in a project with no `engine: statesmith` machine.

### Install and version pinning

Install StateSmith's CLI (`ss.cli`) yourself - Pinfit never downloads or bundles it - then pin the
version in `pinfit.yaml`:

```yaml title="pinfit.yaml"
stateSmith:
  command: ss.cli  # or a full path
  version: 0.22.2  # generate fails with a clear message on a mismatch
```

See [Project configuration](../guide/project-configuration.md#statesmith) for both keys, and
StateSmith's own [CLI install guide](https://github.com/StateSmith/StateSmith/wiki/CLI:-download-or-install).

### Spec

```yaml title="door.state-machine.yaml"
kind: state-machine
engine: statesmith
name: door
description: Door controller
header: door.h
source: door.c
includes: []
context:
  - uint32_t open_count

initial: CLOSED

states:
  - { name: CLOSED }
  - { name: LOCKED }
  - { name: FAULT }
  - name: OPERATING              # composite state
    initial: OPENING              # required - OPERATING is a transition target below
    states:
      - { name: OPENING }
      - { name: OPEN }
      - { name: CLOSING }

events:
  - { name: OPEN_REQUEST, parameters: [] }
  - { name: CLOSE_REQUEST }
  - { name: END_STOP }
  - { name: MOTOR_FAULT, parameters: [uint32_t code] }

transitions:
  - { from: CLOSED, event: OPEN_REQUEST, to: OPENING, guard: true }
  - { from: OPERATING, event: MOTOR_FAULT, to: FAULT }   # covers every OPERATING child
  - { from: OPENING, event: END_STOP, to: OPEN }
  - { from: OPEN, event: tick, to: CLOSING, guard: true } # polled transition - see below
```

`states` nests to any depth: an item with its own `states:` is a composite. `initial` is required
on a composite exactly when something enters it directly - the machine's own `initial`, or a
transition's `to` - naming one of that composite's *direct* children (nested composites resolve
their own initial the same way, recursively). A transition's `from` may name a composite too: it
then applies to every one of its children, StateSmith's native behaviour - no Pinfit-specific syntax
needed.

State and event names must be unique across the *whole* hierarchy, same as `(from, event)` pairs
across the whole machine.

### `event: tick` — the polled transition

There is no `go_to_state()` with this engine (see below), so a conditional move that isn't a
reaction to a declared event - a polled condition checked every main-loop tick - is written as an
ordinary transition whose `event` is the reserved word `tick`, almost always paired with a guard:

```yaml
- { from: OPEN, event: tick, to: CLOSING, guard: true }
```

This maps to StateSmith's `do` event. `tick` is reserved: declaring a real event literally named
`tick` under `events:` is a configuration error.

### Generated API

Same shape as the builtin engine's, so callers don't need to know which engine generated a given
machine:

```c title="door.h (excerpt)"
typedef enum
{
    DOOR_STATE_CLOSED,
    DOOR_STATE_LOCKED,
    DOOR_STATE_FAULT,
    DOOR_STATE_OPENING,
    DOOR_STATE_OPEN,
    DOOR_STATE_CLOSING
} door_state_t;   // leaf states only - OPERATING itself never appears here

void door_init(door_context_t *context);
void door_tick(door_context_t *context);           // dispatches the do event
door_state_t door_get_state(const door_context_t *context);
void door_on_OPEN_REQUEST(door_context_t *context);
void door_on_MOTOR_FAULT(door_context_t *context, uint32_t code);
```

`door_state_t` lists leaf states only - a composite is never itself the "current state" StateSmith
reports, so it stays out of the public enum, exactly like the builtin engine's flat enum. Composite
names are still used for the composite's own entry/exit/tick hooks (below) and for PlantUML
structure.

### The parameter-passing model

StateSmith's events carry no data, so an event with `parameters` stores them on the context
*before* dispatching, and every hook reads them back from there:

```c title="door.c"
void door_on_MOTOR_FAULT(door_context_t *context, uint32_t code)
{
    if (context != NULL)
    {
        context->event_args.MOTOR_FAULT.code = code;
        door_sm_dispatch_event(&context->sm, door_sm_EventId_MOTOR_FAULT);
    }
}
```

```c title="door_hooks.c"
void door_hook_transition_OPERATING_MOTOR_FAULT_action(door_context_t *context)
{
    /*@Pinfit usercode+ transition.OPERATING.MOTOR_FAULT.action*/
    log_fault_code(context->event_args.MOTOR_FAULT.code);
    /*@Pinfit usercode-*/
}
```

### Hooks - where your code lives

One function per state (leaf **and** composite) for `entry`/`exit`/`tick`, plus one per transition
for `guard`/`action` - all in `door_hooks.h/.c`, all non-`static` (StateSmith's generated
`door_sm.c` calls them from a separate translation unit):

| Region | Runs |
| --- | --- |
| `state.<STATE>.entry` | On every entry into the state (leaf or composite) |
| `state.<STATE>.exit` | On every exit from the state |
| `state.<STATE>.tick` | On every `do` dispatch while the state is active |
| `transition.<from>.<event>.guard` | Before the transition, to set `pinfit_guard` |
| `transition.<from>.<event>.action` | Between the exit hook and the state assignment |

**Region names are identical to the builtin engine's.** Switching an existing state machine from
`engine: builtin` to `engine: statesmith` (same state names) keeps every user region: Pinfit carries
region content across regeneration by name, regardless of which engine wrote the file it came from.

### No `go_to_state()`

Arbitrary jumps bypass StateSmith's hierarchical entry/exit semantics (which parent do you exit
through?), so this engine does not generate one. Express a conditional move as an
[`event: tick`](#event-tick-the-polled-transition) transition with a guard instead.

### Using it

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

### MISRA

StateSmith-generated code (`door_sm.h/.c`) is outside Pinfit's [MISRA](../guide/misra.md) claims -
it comes from a separate tool with its own coding style, and needs its own review and deviations
if your project requires one.

## See also

- [Command table](command-table.md) — dispatch on an opcode rather than on a state.
- [`@PinfitSwitch`](../guide/pinfitswitch.md) — exhaustive switches inside a tick region.
