# Common I2C register-map driver

This module is a small helper for conventional register-addressed I2C devices.
It sits between a device-specific driver and `drivers-common-I2C`:

```text
Device-specific driver
        |
        v
drivers-common-I2C-regmap
        |
        v
drivers-common-I2C
        |
        v
Platform-specific I2C implementation
```

It uses only the public `common_iic_interface_t`: `common_iic_write()` for
writes and repeated-start `common_iic_write_read()` for reads. It does not
implement an I2C controller or duplicate its transport interface.

## Contents

| Path | Purpose |
| --- | --- |
| `Interface/common_iic_regmap_I.h` | Portable interface and inline dispatch helpers. |
| `Implementations/common_iic_regmap.h` | Register-table and reusable-map declarations. |
| `Implementations/common_iic_regmap.c` | Portable register-map implementation. |
| `example/example_iic_regmap.c` | Example device register table. |

## Map and interface

A `common_iic_regmap_t` is the complete reusable register-layout declaration.
It contains the register table, widths, and byte orders. It intentionally does
not contain an I2C interface or slave address: pass both to each operation so
one map can be shared by multiple instances of the same device type and by
multiple I2C controllers.

```c
typedef enum
{
    EXAMPLE_REG_STATUS,
    EXAMPLE_REG_CONFIG,
    EXAMPLE_REG_RESULT,
    EXAMPLE_REG_COUNT
} example_register_id_t;

static const common_iic_regmap_register_t example_registers[EXAMPLE_REG_COUNT] =
{
    [EXAMPLE_REG_STATUS] =
    {
        .address = 0x00U,
        .mask = 0x00FFU,
        .access = COMMON_IIC_REGMAP_ACCESS_READ_ONLY
    },
    [EXAMPLE_REG_CONFIG] =
    {
        .address = 0x01U,
        .mask = 0x007FU,
        .access = COMMON_IIC_REGMAP_ACCESS_READ_WRITE
    },
    [EXAMPLE_REG_RESULT] =
    {
        .address = 0x10U,
        .mask = 0xFFFFU,
        .access = COMMON_IIC_REGMAP_ACCESS_READ_ONLY
    }
};

static common_iic_regmap_t example_regmap =
{
    .registers = example_registers,
    .register_count = EXAMPLE_REG_COUNT,
    .register_address_width = 1U,
    .register_data_width = 2U,
    .register_address_byte_order = COMMON_IIC_REGMAP_BYTE_ORDER_BIG_ENDIAN,
    .register_data_byte_order = COMMON_IIC_REGMAP_BYTE_ORDER_BIG_ENDIAN
};

static common_iic_regmap_interface_t example_regmap_interface;
```

Call `common_iic_regmap_create(&example_regmap_interface, &example_regmap)`
once. It validates the map and installs the map as the interface context. The
public interface follows the same context-and-operation-pointer pattern as
`common_iic_interface_t`.

## Read, write, and update bits

```c
uint32_t value;

status = common_iic_regmap_read(&example_regmap_interface,
                                &i2c,
                                0x50U,
                                EXAMPLE_REG_STATUS,
                                &value);
status = common_iic_regmap_write(&example_regmap_interface,
                                 &i2c,
                                 0x50U,
                                 EXAMPLE_REG_CONFIG,
                                 0x12U);
status = common_iic_regmap_update_bits(&example_regmap_interface,
                                       &i2c,
                                       0x50U,
                                       EXAMPLE_REG_CONFIG,
                                       0x03U,
                                       0x01U);
```

Reads require read access and writes require write access. `update_bits`
requires read-write access and performs:

```text
effective_mask = caller_mask & register.mask
new_value = (old_value & ~effective_mask) | (value & effective_mask)
```

There is no register-value cache. The read-modify-write sequence is not atomic
relative to another user of the same device, so applications needing that
guarantee must synchronize at a higher level.

## Width, byte order, and masks

Register addresses and data values each use one to four bytes. Their byte order
is independent: big-endian sends or receives the most-significant byte first,
and little-endian sends or receives the least-significant byte first.

`create()` checks that every table address and mask fits its configured width.
Plain writes reject values that do not fit the data width or set bits outside
the register mask; no transfer is made. Reads clear reserved bits using the
register mask. `update_bits` limits the caller mask to the register mask.

## Supported and unsupported devices

The module supports ordinary, individually addressed registers. Addresses need
not be contiguous, and no auto-increment is assumed. It deliberately does not
support paging or bank selection, CRC/PEC, command-only devices, delays or
state machines, mixed protocols, SPI, or per-register transfer callbacks. Use
`drivers-common-I2C` directly, or a device-specific protocol layer, for those
devices.

## Status and errors

The first eight `common_iic_regmap_status_t` values mirror
`common_iic_status_t`, so low-level errors such as timeout and busy are passed
through unchanged. The module adds `COMMON_IIC_REGMAP_INVALID_REGISTER` for an
out-of-range table index and `COMMON_IIC_REGMAP_ACCESS_DENIED` for a disallowed
operation. Invalid map definitions, pointers, or plain-write values return
`COMMON_IIC_REGMAP_INVALID_PARAM`.
