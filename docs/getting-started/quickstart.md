# Quickstart

In this walkthrough you build a portable I2C interface and one RA-family implementation of it,
then fill in the driver code and regenerate to prove that it survives. Every output on this page
is real output from the commands above it.

You need Pinfit [installed](installation.md) and an empty directory to work in.

## 1. Create the project

```console
pinfit init
```

```title="Output"
Created D:\firmware\pinfit.yaml
```

That is the only file `init` writes. Pinfit never invents a source layout for you — you choose the
directories, and generated files follow the YAML that describes them.

The generated `pinfit.yaml` holds generator-wide preferences, fully commented:

```yaml title="pinfit.yaml"
# Pinfit project configuration
schema: 1
name: 'firmware'
version: 0.1.0

documentation:
  style: doxygen # doxygen, none, or custom
  # file: documentation.yaml

format:
  indent: 4
  lineEnding: lf
  # suppressUnusedWarnings: false # emit (void)param; lines in generated stub bodies (default true)
  # publicVariables: accessors # extern (default) or accessors (getter/setter functions)
  # functionNaming: camelCase # snake_case (default) or camelCase for generated function names
```

The defaults are fine for now. See [Project configuration](../guide/project-configuration.md)
when you want to change them.

## 2. Scaffold an interface and a module

```console
pinfit create interface common_iic drivers/Interface
pinfit create module ra_iic drivers/RA --implements common_iic
```

```title="Output"
Created D:\firmware\drivers\Interface\common_iic.interface.yaml
Created D:\firmware\drivers\RA\ra_iic.module.yaml
```

Both directories are created as needed. The spec files arrive pre-filled with commented examples
of every field, so you can usually edit rather than look anything up.

!!! info "Where generated files land"

    A spec file and the C files it produces always live in the **same directory**. To reorganise
    your tree, move the YAML — the generated files follow it there on the next `generate`.

## 3. Describe the interface

Replace the scaffolded content of the interface with a real contract:

```yaml title="drivers/Interface/common_iic.interface.yaml"
kind: interface
name: common_iic
description: Portable I2C master interface
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

functions:
  - name: write
    return: common_iic_status_t
    description: Write bytes to a slave
    parameters:
      - uint32_t slave_address
      - const uint8_t *data
      - uint32_t length
```

`invalidReturn` and `uninitializedReturn` are what the generated guards return when a caller
passes a null interface, or calls before anything was bound. Give them per function, per return
type through `invalidReturns`, or once per interface as above - Pinfit never spreads one scalar
across return types it does not fit. See
[`invalidReturn` and `uninitializedReturn`](../generators/interface.md#invalidreturn-and-uninitializedreturn).

## 4. Describe the module

```yaml title="drivers/RA/ra_iic.module.yaml"
kind: module
name: ra_iic
description: RA-family I2C implementation
header: ra_iic.h
source: ra_iic.c

implements:
  - common_iic
includes: []

context:
  - void *hardware

variables:
  - uint32_t transfer_count public
  - bool busy
```

`context` becomes the module's own state struct. `variables` are module-level variables; they are
`static` unless you mark them `public`.

## 5. Generate

```console
pinfit generate
```

```title="Output"
[##############################] 3/3  drivers\RA\ra_iic.c
3 file(s) generated
```

Interface references resolve across the whole project, so `ra_iic` finds `common_iic` even though
they live in different directories.

!!! tip

    `gen` is an alias for `generate`, and `-v` swaps the progress bar for a per-file report of
    which spec produced what and how many user regions were carried over.

### The generated interface

```c title="drivers/Interface/common_iic_I.h"
/*@Pinfit(file:interface:common_iic.interface.yaml)*/
/**
 * @file common_iic_I.h
 * @brief Portable I2C master interface
 */

#ifndef COMMON_IIC_I_H_
#define COMMON_IIC_I_H_

#include <stddef.h>
#include <stdint.h>

/*@Pinfit usercode+ interface.preamble*/
/*@Pinfit usercode-*/

/*@Pinfit(enum:common_iic_status_t)*/
/** @brief Transfer result */
typedef enum
{
    COMMON_IIC_SUCCESS = 0,
    COMMON_IIC_INVALID_PARAM = 1,
    COMMON_IIC_NOT_INITIALIZED = 2
} common_iic_status_t;

/*@Pinfit usercode+ interface.declarations*/
/*@Pinfit usercode-*/

/*@Pinfit(interface-table:common_iic)*/
typedef struct
{
    void *context;
    common_iic_status_t (*write)(void *context, uint32_t slave_address, const uint8_t *data, uint32_t length);
} common_iic_interface_t;

/*@Pinfit(function:write)*/
/**
 * @brief Write bytes to a slave
 * @param slave_address slave_address
 * @param data data
 * @param length length
 * @return common_iic_status_t result.
 */
static inline common_iic_status_t common_iic_write(const common_iic_interface_t * const interface, uint32_t slave_address, const uint8_t *data, uint32_t length)
{
    common_iic_status_t pinfit_result = COMMON_IIC_INVALID_PARAM;

    if (interface != NULL)
    {
        if ((interface->context != NULL) && (interface->write != NULL))
        {
            pinfit_result = interface->write(interface->context, slave_address, data, length);
        }
        else
        {
            pinfit_result = COMMON_IIC_NOT_INITIALIZED;
        }
    }

    return pinfit_result;
}

/*@Pinfit usercode+ interface.footer*/
/*@Pinfit usercode-*/

#endif /* COMMON_IIC_I_H_ */
```

The interface is header-only: a context plus function-pointer table, and one guarded `static
inline` dispatch function per entry. Callers depend on `common_iic_I.h` alone and never on the
driver.

### The generated module header

```c title="drivers/RA/ra_iic.h"
/*@Pinfit(file:module-header:ra_iic.module.yaml)*/
/**
 * @file ra_iic.h
 * @brief RA-family I2C implementation
 */

#ifndef RA_IIC_H_
#define RA_IIC_H_

#include "../Interface/common_iic_I.h"

/*@Pinfit usercode+ module.header.preamble*/
/*@Pinfit usercode-*/

/*@Pinfit(public-variable:transfer_count)*/
/** @brief transfer_count */
extern uint32_t transfer_count;

/*@Pinfit(context:ra_iic)*/
typedef struct
{
    void *hardware;
} ra_iic_context_t;

/*@Pinfit(bind-function:ra_iic_bind_common_iic)*/
void ra_iic_bind_common_iic(common_iic_interface_t *interface, ra_iic_context_t *context);

/*@Pinfit usercode+ module.header.footer*/
/*@Pinfit usercode-*/

#endif /* RA_IIC_H_ */
```

The `#include` of the interface header is worked out and written relative to the module. Each
implemented interface gets one `<module>_bind_<interface>` function.

### The generated module source

```c title="drivers/RA/ra_iic.c"
/*@Pinfit(file:module-source:ra_iic.module.yaml)*/
#include "ra_iic.h"

/*@Pinfit usercode+ module.source.includes*/
/*@Pinfit usercode-*/

/*@Pinfit usercode+ module.source.variables*/
/*@Pinfit usercode-*/

/*@Pinfit usercode+ module.source.prototypes*/
/*@Pinfit usercode-*/

/*@Pinfit(variable-definition:transfer_count)*/
uint32_t transfer_count;

/*@Pinfit(private-variable:busy)*/
static bool busy;

/*@Pinfit(private-function:ra_iic_common_iic_write)*/
static common_iic_status_t ra_iic_common_iic_write(void *context, uint32_t slave_address, const uint8_t *data, uint32_t length)
{
    ra_iic_context_t *module = (ra_iic_context_t *)context;
    common_iic_status_t pinfit_result = COMMON_IIC_INVALID_PARAM;
    (void)module;
    (void)slave_address;
    (void)data;
    (void)length;

    /*@Pinfit usercode+ function.common_iic.write.body*/
    /*@Pinfit usercode-*/
    return pinfit_result;
}

/*@Pinfit(bind-function:ra_iic_bind_common_iic)*/
void ra_iic_bind_common_iic(common_iic_interface_t *interface, ra_iic_context_t *context)
{
    if (interface != NULL)
    {
        interface->context = context;
        interface->write = ra_iic_common_iic_write;
    }
}

/*@Pinfit usercode+ module.source.footer*/
/*@Pinfit usercode-*/
```

The stub compiles as-is. `pinfit_result` is pre-set to the interface's `invalidReturn`, and the
`(void)parameter;` lines keep an untouched stub warning-free.

## 6. Write your code, then regenerate

Fill in the region — and only the region:

```c title="drivers/RA/ra_iic.c"
    /*@Pinfit usercode+ function.common_iic.write.body*/
    if ((data != NULL) && (length > 0U))
    {
        busy = true;
        pinfit_result = vendor_i2c_write(module->hardware, slave_address, data, length)
            ? COMMON_IIC_SUCCESS
            : COMMON_IIC_INVALID_PARAM;
        transfer_count++;
        busy = false;
    }
    /*@Pinfit usercode-*/
```

Now add a second function to the interface YAML:

```yaml title="drivers/Interface/common_iic.interface.yaml"
functions:
  - name: write
    # ... unchanged ...
  - name: read
    return: common_iic_status_t
    description: Read bytes from a slave
    parameters:
      - uint32_t slave_address
      - uint8_t *data
      - uint32_t length
```

and regenerate:

```console
pinfit generate
```

`ra_iic.c` now has a fresh `function.common_iic.read.body` stub, the dispatch table and bind
function have grown a `read` entry — and your `write` body is exactly where you left it.

!!! warning "Stay inside the regions"

    Anything you write **outside** a `usercode+` / `usercode-` pair is Pinfit's to rewrite. Put
    extra includes in `module.source.includes`, file-scope helpers in `module.source.variables`
    or `module.source.prototypes`, and everything else in the region that belongs to it.

    Pinfit refuses to overwrite any file that does not carry its generated-file marker, so it will
    never clobber a hand-written file you happened to name the same.

## 7. Use it

```c title="main.c"
#include "drivers/RA/ra_iic.h"

static ra_iic_context_t hardware_context;
static common_iic_interface_t bus;

int main(void)
{
    ra_iic_bind_common_iic(&bus, &hardware_context);

    const uint8_t payload[2] = { 0x10U, 0x2AU };
    if (common_iic_write(&bus, 0x42U, payload, sizeof(payload)) != COMMON_IIC_SUCCESS)
    {
        /* handle the error */
    }
    return 0;
}
```

Application code talks to `common_iic_*` only. Swapping the RA driver for a simulator, or a test
double, means binding a different module to the same `bus` — no call site changes.

## Where to next

<div class="grid cards" markdown>

-   :material-shape-outline: **More than interfaces**

    State machines, observers, command tables, status codes and adapters are scaffolded the same
    way.

    [:octicons-arrow-right-24: Generators](../generators/index.md)

-   :material-content-save-cog: **Understand regeneration**

    Which regions exist, what happens to removed items, and how to leave Pinfit for good.

    [:octicons-arrow-right-24: User regions](../guide/user-regions.md)

-   :material-code-braces: **Stop writing switch cases**

    Tag a switch with `@PinfitSwitch` and let Pinfit keep one case per enum member in sync.

    [:octicons-arrow-right-24: @PinfitSwitch](../guide/pinfitswitch.md)

-   :material-console: **Full command list**

    Every command and flag, including `--also-nested`, `rename` and `detach`.

    [:octicons-arrow-right-24: CLI reference](../reference/cli.md)

</div>
