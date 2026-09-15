# Status codes

A standalone, **header-only** shared status enum plus checking macros. One error vocabulary for a
whole project, with no source file and nothing to link.

```console
CGen create status-codes cgen_status
```

## Spec

```yaml title="cgen_status.status-codes.yaml"
kind: status-codes
name: cgen_status
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

```c title="cgen_status.h"
/*@CGen(enum:cgen_status)*/
/** @brief Shared status codes */
typedef enum
{
    CGEN_STATUS_OK = 0,
    CGEN_STATUS_INVALID_PARAM = -1,
    CGEN_STATUS_NOT_READY = -2
} cgen_status_t;

/*@CGen(macro:CGEN_STATUS_SUCCEEDED)*/
#define CGEN_STATUS_SUCCEEDED(status) ((status) == CGEN_STATUS_OK)

/*@CGen(macro:CGEN_STATUS_FAILED)*/
#define CGEN_STATUS_FAILED(status) (!CGEN_STATUS_SUCCEEDED(status))

/*@CGen(macro:CGEN_STATUS_CHECK)*/
#define CGEN_STATUS_CHECK(status_expression) \
    do { cgen_status_t cgen_status = (status_expression); \
        if (CGEN_STATUS_FAILED(cgen_status)) { return cgen_status; } \
    } while (0)
```

Enum members are `<NAME>_<CODE>`, and the macros are named from the same prefix, so a second status
set (`driver_status`, say) generates `DRIVER_STATUS_SUCCEEDED` and friends without colliding.

## Using it

```c
#include "cgen_status.h"

static cgen_status_t configure_sensor(sensor_t *sensor)
{
    CGEN_STATUS_CHECK(sensor_reset(sensor));       /* (1)! */
    CGEN_STATUS_CHECK(sensor_set_rate(sensor, 100U));

    return CGEN_STATUS_OK;
}

void caller(void)
{
    if (CGEN_STATUS_FAILED(configure_sensor(&sensor)))
    {
        /* report it */
    }
}
```

1.  `CGEN_STATUS_CHECK` returns the failing status straight to the caller, so the happy path stays
    readable. Note that it *does* return early — see the note below.

!!! note "`CGEN_STATUS_CHECK` and single-exit rules"

    The macro contains a `return`, which conflicts with the strict single-point-of-exit style CGen's
    own [generated code follows](../guide/misra.md). It is a convenience for your code, not
    something CGen emits into a generated function body. In a project that enforces single exit,
    use `CGEN_STATUS_FAILED` with an explicit `cgen_result` assignment instead:

    ```c
    cgen_status_t cgen_result = sensor_reset(sensor);

    if (CGEN_STATUS_SUCCEEDED(cgen_result))
    {
        cgen_result = sensor_set_rate(sensor, 100U);
    }

    return cgen_result;
    ```

## Relationship to `invalidReturn`

This generator is **purely additive**. It does not change how an [interface](interface.md) declares
its own `invalidReturn` and `uninitializedReturn` — those still have to be given per interface or
per function.

What it does give you is something sensible to point them at:

```yaml title="bus.interface.yaml"
kind: interface
name: bus
includes: ['"cgen_status.h"']
invalidReturn: CGEN_STATUS_INVALID_PARAM
uninitializedReturn: CGEN_STATUS_NOT_READY

functions:
  - name: write
    return: cgen_status_t
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
