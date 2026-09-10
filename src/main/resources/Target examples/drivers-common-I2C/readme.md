# Common I²C driver

This module defines a portable I²C-master interface and provides a Renesas RA
adapter. Device drivers use `common_iic_interface_t` instead of a vendor I²C
API; platform code creates and starts the adapter, then supplies the interface
to those drivers.

## Contents

| Path | Purpose |
| --- | --- |
| `Interface/common_iic_I.h` | Platform-independent status codes, interface type, and inline dispatch helpers. |
| `Implementations/RA/ra_iic_master.h` | Renesas RA adapter declaration and context type. |
| `Implementations/RA/ra_iic_master.c` | Renesas Flexible Software Package (FSP) I²C-master implementation. |

The interface header is header-only. The RA adapter must be compiled and linked
with the application, together with the OSAL and the Renesas FSP I²C driver.

## Common interface

Include `common_iic_I.h` to use the portable API. A
`common_iic_interface_t` contains a private context and operations for starting
and stopping the controller, writing, reading, and combined write/read
transfers:

```c
common_iic_status_t common_iic_start(common_iic_interface_t *interface);
common_iic_status_t common_iic_stop(common_iic_interface_t *interface);

common_iic_status_t common_iic_write(common_iic_interface_t *interface,
                                     uint32_t slave_address,
                                     const uint8_t *data,
                                     uint32_t data_length);
common_iic_status_t common_iic_read(common_iic_interface_t *interface,
                                    uint32_t slave_address,
                                    uint8_t *data,
                                    uint32_t data_length);
common_iic_status_t common_iic_write_read(common_iic_interface_t *interface,
                                          uint32_t slave_address,
                                          const uint8_t *tx_data,
                                          uint32_t tx_data_length,
                                          uint8_t *rx_data,
                                          uint32_t rx_data_length);
```

The helpers validate the interface pointer. They return
`COMMON_IIC_INVALID_PARAM` for a null interface and
`COMMON_IIC_NOT_INITIALIZED` when the interface has no context or matching
operation. Validation of transfer buffers, lengths, addresses, and controller
state is performed by the selected implementation.

| Status | Meaning |
| --- | --- |
| `COMMON_IIC_SUCCESS` | Operation completed successfully. |
| `COMMON_IIC_ERROR` | Generic driver or bus error. |
| `COMMON_IIC_BUSY` | Interface-defined busy status. Not currently returned by the RA adapter. |
| `COMMON_IIC_TIMEOUT` | The mutex or a transfer timed out. |
| `COMMON_IIC_INVALID_PARAM` | An argument is invalid. |
| `COMMON_IIC_NOT_INITIALIZED` | The interface has not been populated. |
| `COMMON_IIC_NOT_SUPPORTED` | Interface-defined unsupported-operation status. Not currently returned by the RA adapter. |
| `COMMON_IIC_UNKNOWN_ERROR` | Interface-defined fallback status. Not currently returned by the RA adapter. |

## Renesas RA implementation

`ra_iic_master_create()` binds an FSP `i2c_master_instance_t` to the common
interface. The FSP instance must have been generated/configured for the I²C
peripheral, but it must not already be open: the adapter opens it in
`common_iic_start()` and closes it in `common_iic_stop()`.

```c
#include "common_iic_I.h"
#include "ra_iic_master.h"
#include "hal_data.h"              /* Declares g_i2c_master0. */

static common_iic_interface_t i2c;
static ra_iic_master_context_t i2c_context;

common_iic_status_t i2c_init(void)
{
    common_iic_status_t status = ra_iic_master_create(&i2c,
                                                       &i2c_context,
                                                       (i2c_master_instance_t *)&g_i2c_master0,
                                                       I2C_MASTER_ADDR_MODE_7BIT);
    if (status != COMMON_IIC_SUCCESS)
    {
        return status;
    }

    return common_iic_start(&i2c);
}

void i2c_deinit(void)
{
    (void) common_iic_stop(&i2c);
}
```

Use the address in its native form: `0x50U` for a 7-bit device address, not an
8-bit address byte such as `0xA0`. The RA adapter accepts addresses from
`0x00` through `0x7F` in 7-bit mode and `0x000` through `0x3FF` in 10-bit
mode.

### Transfers

`common_iic_write()` and `common_iic_read()` issue a normal FSP transfer.
`common_iic_write_read()` issues the write with restart enabled, then starts
the read, producing the usual register-read sequence with a repeated START
between phases. All transfer buffers must be non-null and all lengths must be
non-zero.

```c
uint8_t const register_address = 0x0FU;
uint8_t value;

common_iic_status_t status = common_iic_write_read(&i2c,
                                                    0x50U,
                                                    &register_address,
                                                    1U,
                                                    &value,
                                                    1U);
```

The adapter registers its own FSP callback during `common_iic_start()` and
waits for it to report transfer completion. Do not replace that callback while
the common adapter is in use.

## Concurrency and timeouts

The RA context contains one OSAL mutex, so calls made through the same
interface are serialized. Each caller waits up to 100 ms to acquire that
mutex, and each transfer phase waits up to 100 ms for the FSP callback. On a
transfer timeout, the adapter aborts the FSP transfer before returning
`COMMON_IIC_TIMEOUT`.

Override either compile-time default before compiling `ra_iic_master.c` when
the application needs different limits:

```c
#define RA_IIC_MUTEX_TIMEOUT_MS    (250U)
#define RA_IIC_TRANSFER_TIMEOUT_MS (250U)
```

The adapter depends on the OSAL for mutexes, delays, and elapsed time. Its
current usable OSAL target is Azure RTOS ThreadX; see
[`drivers-common-osal`](https://git.optilinkserver.org/HenF/drivers-common-osal) for target setup and
OSAL timing behavior. Calls can block, so make them from thread context rather
than an interrupt handler.
