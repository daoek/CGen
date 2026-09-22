# Adapter

Glue between two existing, incompatible [interfaces](interface.md) — typically your project's own
contract (`from`) and a vendor HAL (`to`). Functions whose signatures match exactly become direct
call-throughs with no code to write.

```console
pinfit create adapter bus_adapter --from bus --to bus_hal
```

## Spec

```yaml title="bus_adapter.adapter.yaml"
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

| Key | Meaning |
| --- | --- |
| `from` | The interface this adapter **exposes**. Callers bind to it. |
| `to` | The interface this adapter **calls into**, supplied at runtime. |
| `mappings` | `{ from: <function on from>, to: <function on to> }` pairs. |
| `context` | Extra fields on the adapter context, alongside the target pointer. |

Both interfaces are resolved by name across the project, and both headers are included for you.

## Generated API

```c title="bus_adapter.h"
typedef struct
{
    const bus_hal_interface_t *target;
} bus_adapter_context_t;

void bus_adapter_set_target(bus_adapter_context_t *context, const bus_hal_interface_t *target);
void bus_adapter_bind_bus(bus_interface_t *interface, bus_adapter_context_t *context);
```

- `bus_adapter_bind_bus()` populates the `from` interface, exactly like a
  [module's](module.md) bind function — callers see a plain `bus`.
- `bus_adapter_set_target()` supplies the `to` implementation at runtime, so the adapter does not
  hard-code which HAL instance it talks to.

## Mapped functions

A mapping is accepted **only** when both functions have the same return type and the exact same
parameter types in the same order. Pinfit then generates a direct call-through with no user region:

```c title="bus_adapter.c"
/*@Pinfit(private-function:bus_adapter_bus_write)*/
static int bus_adapter_bus_write(void *context, const uint8_t *data, uint32_t length)
{
    bus_adapter_context_t *adapter = (bus_adapter_context_t *)context;
    int pinfit_result = -1;

    pinfit_result = bus_hal_send(adapter->target, data, length);
    return pinfit_result;
}
```

The call goes through `bus_hal_send()` — the target interface's own generated dispatch wrapper — so
a null target or an unbound HAL still returns that interface's `uninitializedReturn` rather than
crashing.

## Unmapped functions

Any `from` function left out of `mappings`, or rejected for a signature mismatch, falls back to a
plain stub body — exactly like an unmapped module function:

```c
/*@Pinfit(private-function:bus_adapter_bus_reset)*/
static int bus_adapter_bus_reset(void *context)
{
    bus_adapter_context_t *adapter = (bus_adapter_context_t *)context;
    int pinfit_result = -1;
    (void)adapter;

    /*@Pinfit usercode+ function.bus.reset.body*/
    /*@Pinfit usercode-*/
    return pinfit_result;
}
```

That is where translation work belongs: reordering parameters, converting units, mapping one status
enum onto another, or emulating a call the HAL does not offer.

!!! tip "A rejected mapping is not an error"

    If the signatures do not line up, you get the stub instead of the call-through — write the
    conversion in the region and call the target yourself:

    ```c
    /*@Pinfit usercode+ function.bus.reset.body*/
    pinfit_result = (bus_hal_power_cycle(adapter->target, 0U) == 0) ? 0 : -1;
    /*@Pinfit usercode-*/
    ```

## Using it

```c title="main.c"
#include "bus_adapter.h"
#include "vendor_hal.h"   /* a module implementing bus_hal */

static bus_adapter_context_t adapter;
static vendor_hal_context_t hal_context;
static bus_hal_interface_t hal;
static bus_interface_t bus;

int main(void)
{
    vendor_hal_bind_bus_hal(&hal, &hal_context);   /* (1)! */
    bus_adapter_set_target(&adapter, &hal);        /* (2)! */
    bus_adapter_bind_bus(&bus, &adapter);          /* (3)! */

    const uint8_t payload[1] = { 0x01U };
    (void)bus_write(&bus, payload, sizeof(payload));   /* (4)! */
}
```

1.  Bind the vendor module to the HAL interface, as usual.
2.  Point the adapter at that HAL instance.
3.  Expose the adapter as an ordinary `bus`.
4.  Application code calls `bus_*` and knows nothing about the vendor.

## When to use which

| Situation | Use |
| --- | --- |
| You own the implementation and are writing it now | [Module](module.md) with `implements:` |
| An implementation already exists behind a different interface you cannot change | **Adapter** |
| Two contracts differ only in naming, with identical signatures | Adapter — every function maps, nothing to write |

## User regions

| Region | Use it for |
| --- | --- |
| `function.<from-interface>.<function>.body` | An unmapped or mismatched function |
| `adapter.header.preamble` / `.footer` | Header edges |
| `adapter.source.includes` / `.footer` | Source edges |

Mapped functions have no region — there is nothing to decide.
