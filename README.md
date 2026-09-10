# CGen

CGen is a small YAML-driven CLI for generating C interface headers and concrete
module headers/sources. Generated files contain compact `/*@CGen(...)*/`
markers and named user regions, so implementation code survives regeneration.

## Build and run

Requires Java 17 and Maven.

```console
mvn clean package
java -jar target/cgen-1.0-SNAPSHOT.jar --help
```

The packaged JAR includes SnakeYAML and can be copied without a separate Maven
dependency directory.

### Install the `CGen` command on Windows

From the repository root, run:

```powershell
.\scripts\install.ps1
```

This builds CGen, installs a private copy in `%LOCALAPPDATA%\CGen`, and adds
that directory to your user `PATH`. Open a new terminal afterward:

```console
CGen --help
```

In VS Code, press `Ctrl+Shift+B` and run the default
`CGen: Package + Install` task to rebuild, test, package, and update the
installed command in one step.

The installed copy is independent from `target`, so `mvn clean` will not remove
it. Re-run the installer to update it. To uninstall safely:

```powershell
& "$env:LOCALAPPDATA\CGen\Uninstall-CGen.ps1"
```

## Workflow

Run commands from the project directory (or one of its descendants):

```console
CGen init
CGen create interface common_iic drivers/common/Interface
CGen create module ra_iic drivers/common/RA --implements common_iic
CGen generate
```

When no native `CGen` launcher is installed, replace `CGen` with
`java -jar path/to/cgen-1.0-SNAPSHOT.jar`.

`init [directory]` creates only `cgen.yaml` and never chooses a source layout
for you. `create interface|module <name> [directory]` writes the YAML into the
specified directory, creating it when needed. Without a directory it writes in
the current directory. `generate [directory]` scans the specified directory
tree; without one it scans the current directory. Interface references are
resolved across the whole project. `gen` is an alias for `generate`.

## Project configuration

```yaml
schema: 1
name: firmware
version: 0.1.0

documentation:
  style: doxygen # doxygen, none, or custom
  # file: documentation.yaml

format:
  indent: 4
  lineEnding: lf # lf or crlf
```

Project configuration contains generator-wide preferences only; it does not
emit additional C headers or sources.

For custom documentation, set `documentation.style` to `custom` and point
`documentation.file` at a YAML file with optional `file`, `function`, `type`,
and `variable` text templates. Supported placeholders include `${file}`,
`${name}`, `${brief}`, `${return}`, and `${params}`.

## Interface YAML

An interface YAML and its generated header live in the same directory.

```yaml
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
      - { type: uint32_t, name: speed }

functions:
  - name: write
    return: common_iic_status_t
    description: Write bytes
    parameters:
      - { type: const uint8_t *, name: data, description: Source bytes }
      - { type: uint32_t, name: length, description: Byte count }
```

The generated interface contains a context/function-pointer table and guarded
inline dispatch functions, following the pattern in the target examples.

## Module YAML

```yaml
kind: module
name: ra_iic
description: RA I2C implementation
header: ra_iic.h
source: ra_iic.c
implements: [common_iic]
includes: ['"vendor_i2c.h"']

context:
  - { type: void *, name: hardware }

variables:
  - { type: uint32_t, name: transfer_count, visibility: public, initial: 0U }
  - { type: bool, name: busy, visibility: private, initial: false }
```

Public variables receive an `extern` declaration in the module header and one
definition in the source. Private variables are `static` in the source.

## Safe regeneration and permanent detach

Edit only inside named user regions:

```c
/*@CGen(+function.common_iic.write.body)*/
/* Your code is retained here. */
/*@CGen(-function.common_iic.write.body)*/
```

CGen refuses to overwrite files without its generated-file marker. If a YAML
item is removed, its user region is retained as an orphan instead of being
discarded.

To permanently remove CGen metadata from the entire project:

```console
CGen detach
```

This destructive command requires typing the exact project name. It keeps all
generated C code and unrelated YAML files, removes CGen marker lines, then
deletes `cgen.yaml`, all `*.interface.yaml` and `*.module.yaml` files, and the
custom documentation YAML referenced by the project. The detached project
cannot be regenerated unless it is configured again with `CGen init`.
