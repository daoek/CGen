# Status codes

A standalone, **header-only** shared status enum plus checking macros. One error vocabulary for a
whole project, with no source file and nothing to link.

```console
pinfit create status-codes pinfit_status
```

## Spec

```yaml title="pinfit_status.status-codes.yaml"
kind: status-codes
name: pinfit_status
description: Shared status codes
includes: []

codes:
  - { name: OK, value: 0, description: Success }
  - { name: INVALID_PARAM, value: -1 }
  - { name: NOT_READY, value: -2 }
```

!!! warning "Exactly one code must have `value: 0`"

    That code becomes the success value the macros test against. Zero codes with `value: 0`, or more
    than one, is a configuration error.

## Generated output

```c title="pinfit_status.h"
/*@Pinfit(enum:pinfit_status)*/
/** @brief Shared status codes */
typedef enum
{
    PINFIT_STATUS_OK = 0,
    PINFIT_STATUS_INVALID_PARAM = -1,
    PINFIT_STATUS_NOT_READY = -2
} pinfit_status_t;

/*@Pinfit(macro:PINFIT_STATUS_SUCCEEDED)*/
#define PINFIT_STATUS_SUCCEEDED(status) ((status) == PINFIT_STATUS_OK)

/*@Pinfit(macro:PINFIT_STATUS_FAILED)*/
#define PINFIT_STATUS_FAILED(status) (!PINFIT_STATUS_SUCCEEDED(status))

/*@Pinfit(macro:PINFIT_STATUS_CHECK)*/
#define PINFIT_STATUS_CHECK(status_expression) \
    do { pinfit_status_t pinfit_status = (status_expression); \
        if (PINFIT_STATUS_FAILED(pinfit_status)) { return pinfit_status; } \
    } while (0)
```

Enum members are `<NAME>_<CODE>`, and the macros are named from the same prefix, so a second status
set (`driver_status`, say) generates `DRIVER_STATUS_SUCCEEDED` and friends without colliding.

## Using it

```c
#include "pinfit_status.h"

static pinfit_status_t configure_sensor(sensor_t *sensor)
{
    PINFIT_STATUS_CHECK(sensor_reset(sensor));       /* (1)! */
    PINFIT_STATUS_CHECK(sensor_set_rate(sensor, 100U));

    return PINFIT_STATUS_OK;
}

void caller(void)
{
    if (PINFIT_STATUS_FAILED(configure_sensor(&sensor)))
    {
        /* report it */
    }
}
```

1.  `PINFIT_STATUS_CHECK` returns the failing status straight to the caller, so the happy path stays
    readable. Note that it *does* return early — see the note below.

!!! note "`PINFIT_STATUS_CHECK` and single-exit rules"

    The macro contains a `return`, which conflicts with the strict single-point-of-exit style Pinfit's
    own [generated code follows](../guide/misra.md). It is a convenience for your code, not
    something Pinfit emits into a generated function body. In a project that enforces single exit,
    use `PINFIT_STATUS_FAILED` with an explicit `pinfit_result` assignment instead:

    ```c
    pinfit_status_t pinfit_result = sensor_reset(sensor);

    if (PINFIT_STATUS_SUCCEEDED(pinfit_result))
    {
        pinfit_result = sensor_set_rate(sensor, 100U);
    }

    return pinfit_result;
    ```

## Relationship to `invalidReturn`

This generator is **purely additive**. It does not change how an [interface](interface.md) declares
its own `invalidReturn` and `uninitializedReturn` — those are still resolved per function, per
return type, or per interface.

What it does give you is something sensible to point them at:

```yaml title="bus.interface.yaml"
kind: interface
name: bus
includes: ['"pinfit_status.h"']
invalidReturn: PINFIT_STATUS_INVALID_PARAM
uninitializedReturn: PINFIT_STATUS_NOT_READY

functions:
  - name: write
    return: pinfit_status_t
    parameters:
      - const uint8_t *data
      - uint32_t length
```

Now every interface in the project fails with the same vocabulary, and a caller can check any of
them with the same macro.

## User regions

None for the codes themselves — the enum and macros are fully mechanical. Only
`status-codes.preamble` and `status-codes.footer` are editable, for anything you want before or
after the generated block.
