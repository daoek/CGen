# Module

A module is a concrete unit of code: its own state, its own variables and functions, and an
implementation of any number of [interfaces](interface.md). It generates a **header and a source**,
and it is where most of your hand-written code lives.

```console
CGen create module ra_iic drivers/RA --implements common_iic
```

## Spec

```yaml title="drivers/RA/ra_iic.module.yaml"
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

## Keys

| Key | Meaning |
| --- | --- |
| `implements` | Interface names this module provides. Resolved across the whole project, not by path. |
| `enums` | Module-private `typedef enum` types, emitted before the context struct so `context` and `variables` can use them. Same shape as [interface enums](interface.md#enums-and-structs). |
| `context` | Fields of the generated `<name>_context_t` — the module's per-instance state. |
| `variables` | Module-level variables. See [below](#variables). |
| `functions` | Standalone functions, independent of any interface. See [below](#standalone-functions). |
| `singleton` | `true` generates a lazy-init instance accessor. See [below](#singleton). |
| `singletonElse` | `true` adds an `else` branch to that accessor. |
| `instance` | Renames the generated singleton accessor (default `<name>_instance`). |
| `externalEnums` | Links to enums declared in your own headers, written automatically by [`@CGenSwitch`](../guide/cgenswitch.md#what-gets-remembered). |

## Implementing an interface

Each entry in `implements` produces one `static` function per interface function, plus a bind
function that populates the interface table:

```c title="ra_iic.c"
/*@CGen(private-function:ra_iic_common_iic_write)*/
static common_iic_status_t ra_iic_common_iic_write(void *context, uint32_t slave_address, const uint8_t *data, uint32_t length)
{
    ra_iic_context_t *module = (ra_iic_context_t *)context;
    common_iic_status_t cgen_result = COMMON_IIC_INVALID_PARAM;
    (void)module;
    (void)slave_address;

    /*@CGen usercode+ function.common_iic.write.body*/
    /*@CGen usercode-*/
    return cgen_result;
}

/*@CGen(bind-function:ra_iic_bind_common_iic)*/
void ra_iic_bind_common_iic(common_iic_interface_t *interface, ra_iic_context_t *context)
{
    if (interface != NULL)
    {
        interface->context = context;
        interface->write = ra_iic_common_iic_write;
    }
}
```

Your code goes in `function.<interface>.<function>.body`. The context is pre-cast for you as
`module`, and `cgen_result` starts at the interface's `invalidReturn` — assign to it rather than
returning early (see [MISRA](../guide/misra.md)).

The interface header is included from the module header with a relative path CGen works out itself.

## `variables`

Variables default to `private` — `static` storage, source only. The compact form is just
`type name`:

```yaml
variables:
  - bool busy                          # private
  - uint32_t transfer_count public     # exposed
  - uint8_t command_buffer[6]          # array declarator comes through unchanged
  - uint8_t status_flags[]
  - { type: uint32_t, name: ticks, visibility: public, initial: '0U', description: Uptime }
```

Append `public`, `get` or `set` to expose one, or switch to the map form when you also need
`initial` or `description`.

An array declarator on `name` is copied onto the generated declaration verbatim.

### How `public` is exposed

That is a project-wide decision, made by
[`format.publicVariables`](../guide/project-configuration.md#publicvariables):

=== "`extern` (default)"

    ```c title="ra_iic.h"
    /*@CGen(public-variable:transfer_count)*/
    extern uint32_t transfer_count;
    ```

    ```c title="ra_iic.c"
    /*@CGen(variable-definition:transfer_count)*/
    uint32_t transfer_count;
    ```

=== "`accessors`"

    The variable stays `static` and gets a getter/setter pair instead — no module prefix — each
    with its own user region:

    ```c title="led.h"
    /*@CGen(public-accessor:blink_count)*/
    uint32_t get_blink_count(void);
    ```

    ```c title="led.c"
    /*@CGen(private-variable:blink_count)*/
    static uint32_t blink_count;

    /*@CGen(public-accessor:blink_count)*/
    uint32_t get_blink_count(void)
    {
        /*@CGen usercode+ variable.blink_count.get*/
        return blink_count;
        /*@CGen usercode-*/
    }
    ```

    That region is where validation, clamping, logging or a lock belongs.

`get` or `set` visibility generates only that one accessor — the variable stays `static` with no
counterpart function, which is how you express a read-only counter or a write-only latch.

!!! warning "Restrictions"

    - `get` and `set` require `format.publicVariables: accessors`. Using them under `extern` is a
      configuration error, because `extern` only understands `public` and `private`.
    - An array variable marked `public`, `get` or `set` is a configuration error under `accessors`:
      C cannot return or take an array by value the way `get_<name>` / `set_<name>` would need to.
      Keep array variables `private`, or expose them as `public` under `extern`.

## Standalone functions

A module can declare functions of its own, unrelated to any interface:

```yaml
functions:
  - name: initialize
    return: bool
    description: One-time module initialization
    parameters: []
    invalidReturn: false
    visibility: public
```

```c title="led.c"
/*@CGen(function:initialize)*/
bool initialize(void)
{
    bool cgen_result = false;

    /*@CGen usercode+ function.initialize.body*/
    /*@CGen usercode-*/
    return cgen_result;
}
```

Points worth knowing:

- **The name is not module-prefixed.** The C identifier is exactly `name`, so keep it unique
  yourself across the module's own functions and its implemented interfaces.
- **No implicit context.** Unlike an interface function, no `void *context` first parameter is
  added. Take one as an explicit parameter if the function needs it.
- **`invalidReturn` is required** for any non-`void` return type. There is no interface-level
  default to fall back on here.
- **`visibility`** is `private` (default — `static`, source only) or `public` (also declared in the
  header).

## Singleton

Set `singleton: true` when a module has exactly one instance and you would rather not thread a
context through every call site:

```yaml
singleton: true
singletonElse: true   # optional
# instance: led_handle  # optional: rename the accessor
```

```c title="led.h"
led_context_t *led_instance(void);
```

```c title="led.c"
/*@CGen(function:led_instance)*/
static led_context_t led_singleton_context;
static bool led_singleton_initialized = false;

led_context_t *led_instance(void)
{
    if (!led_singleton_initialized)
    {
        led_singleton_initialized = true;
        /*@CGen usercode+ singleton.init*/
        /* One-time setup for the singleton instance. */
        /*@CGen usercode-*/
    }
    else
    {
        /*@CGen usercode+ singleton.else*/
        /* Runs on every call after the first. */
        /*@CGen usercode-*/
    }
    return &led_singleton_context;
}
```

The context lives in static storage; `singleton.init` runs on the first call only. Add
`singletonElse: true` for the `else` branch — useful for a refresh, a liveness check, or a counter.

!!! note "Not thread-safe by itself"

    The initialised flag is a plain `bool`. If the accessor can be reached from more than one
    thread or from an interrupt, add the guard you need inside `singleton.init`.

## User regions

| Region | Use it for |
| --- | --- |
| `module.header.preamble` | Declarations needed before the generated types |
| `module.header.footer` | Macros or inline helpers exposed to users of the module |
| `module.source.includes` | Extra `#include` lines |
| `module.source.variables` | File-scope state CGen does not know about |
| `module.source.prototypes` | Forward declarations for your own helpers |
| `module.source.footer` | Definitions of those helpers |
| `function.<interface>.<function>.body` | An implemented interface function |
| `function.<name>.body` | A standalone function |
| `variable.<name>.get` / `.set` | Accessor bodies |
| `singleton.init` / `singleton.else` | Singleton accessor branches |

## See also

- [Interface](interface.md) — the contract a module implements.
- [`@CGenSwitch`](../guide/cgenswitch.md) — generate switch cases inside a module function body.
- [`CGen rename module`](../reference/cli.md#cgen-rename-module) — rename a module and its files.
