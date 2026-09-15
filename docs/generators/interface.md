# Interface

An interface is a contract that any number of [modules](module.md) can implement. It generates a
**header only**: a context plus function-pointer table, and one guarded `static inline` dispatch
function per entry. Callers include the interface header and never the driver.

```console
CGen create interface common_iic drivers/Interface
```

## Spec

```yaml title="drivers/Interface/common_iic.interface.yaml"
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

The interface YAML and its generated header live in the same directory.

## Keys

| Key | Required | Meaning |
| --- | --- | --- |
| `kind` | yes | `interface` |
| `name` | yes | Prefix for every generated identifier |
| `description` | no | File `@brief` |
| `header` | yes | Generated header file name |
| `invalidReturn` | see below | Returned when the caller passes a null interface |
| `uninitializedReturn` | see below | Returned when nothing has been bound yet |
| `includes` | no | Verbatim include lines |
| `enums` | no | `typedef enum` types emitted before the table |
| `structs` | no | `typedef struct` types emitted before the table |
| `functions` | yes | The contract itself |

### `invalidReturn` and `uninitializedReturn`

Every non-`void` function **must** have an `invalidReturn`, either at interface level (a default
for all of them) or on the individual function. CGen refuses to invent one, because a silent `-1`
is not a valid value for an enum, a pointer, an unsigned type, or an application-specific status.

`uninitializedReturn` is the same idea for a call made before anything is bound. Both can be
overridden per function:

```yaml
functions:
  - name: probe
    return: bool
    parameters: []
    invalidReturn: false
    uninitializedReturn: false
```

### `functions`

```yaml
functions:
  - name: write
    return: common_iic_status_t
    description: Write bytes
    parameters:
      - const uint8_t *data      # compact "type name"
      - { type: uint32_t, name: length, description: Byte count }
```

A `void *context` first parameter is added implicitly to every function-pointer entry — do not
declare it yourself.

### `enums` and `structs`

Types declared here are emitted in the header ahead of the interface table, so the table and your
function signatures can use them. Both accept a `description`, and struct `fields` use the same
compact shorthand as parameters.

## Generated output

```c title="drivers/Interface/common_iic_I.h"
/*@CGen(file:interface:common_iic.interface.yaml)*/
/**
 * @file common_iic_I.h
 * @brief Portable I2C master interface
 */

#ifndef COMMON_IIC_I_H_
#define COMMON_IIC_I_H_

#include <stddef.h>
#include <stdint.h>

/*@CGen usercode+ interface.preamble*/
/*@CGen usercode-*/

/*@CGen(enum:common_iic_status_t)*/
/** @brief Transfer result */
typedef enum
{
    COMMON_IIC_SUCCESS = 0,
    COMMON_IIC_INVALID_PARAM = 1,
    COMMON_IIC_NOT_INITIALIZED = 2
} common_iic_status_t;

/*@CGen usercode+ interface.declarations*/
/*@CGen usercode-*/

/*@CGen(interface-table:common_iic)*/
typedef struct
{
    void *context;
    common_iic_status_t (*write)(void *context, uint32_t slave_address, const uint8_t *data, uint32_t length);
} common_iic_interface_t;

/*@CGen(function:write)*/
/**
 * @brief Write bytes to a slave
 * @return common_iic_status_t result.
 */
static inline common_iic_status_t common_iic_write(const common_iic_interface_t * const interface, uint32_t slave_address, const uint8_t *data, uint32_t length)
{
    common_iic_status_t cgen_result = COMMON_IIC_INVALID_PARAM;

    if (interface != NULL)
    {
        if ((interface->context != NULL) && (interface->write != NULL))
        {
            cgen_result = interface->write(interface->context, slave_address, data, length);
        }
        else
        {
            cgen_result = COMMON_IIC_NOT_INITIALIZED;
        }
    }

    return cgen_result;
}

/*@CGen usercode+ interface.footer*/
/*@CGen usercode-*/

#endif /* COMMON_IIC_I_H_ */
```

What you get per interface:

- `<name>_interface_t` — the context and function-pointer table.
- `<name>_<function>(interface, ...)` — a guarded `static inline` wrapper per function. Null
  interface returns `invalidReturn`; missing context or function pointer returns
  `uninitializedReturn`; otherwise it calls through.

## User regions

| Region | Use it for |
| --- | --- |
| `interface.preamble` | Extra includes or macros needed before the generated types |
| `interface.declarations` | Hand-written types or declarations the interface exposes |
| `interface.footer` | Convenience macros or inline helpers built on the dispatch functions |

## Using it

```c
#include "common_iic_I.h"

void transfer(common_iic_interface_t *bus)
{
    const uint8_t payload[2] = { 0x10U, 0x2AU };

    if (common_iic_write(bus, 0x42U, payload, sizeof(payload)) != COMMON_IIC_SUCCESS)
    {
        /* handle it */
    }
}
```

The caller never names a driver. A [module](module.md) that `implements: [common_iic]` supplies a
`ra_iic_bind_common_iic(&bus, &context)` function to populate the table; swapping in a simulator or
a test double is a different bind call and no change at the call site.

## See also

- [Module](module.md) — implement the contract.
- [Observer](observer.md) — fan a `void` interface out to many subscribers.
- [Adapter](adapter.md) — bridge two interfaces you cannot change.
