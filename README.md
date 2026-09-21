# CGen

**YAML-driven C interface and module generator for embedded projects.**

Describe an interface, a module or a state machine in a short YAML file. CGen generates the
headers and sources around it — structs, function-pointer tables, null checks, dispatch, Doxygen
comments. Your own code lives in named user regions inside those files and survives every
regeneration.

📖 **[Full documentation → daoek.github.io/CGen](https://daoek.github.io/CGen/)**

## Install

Windows: download [`install.ps1`](https://github.com/daoek/CGen/raw/main/scripts/install.ps1);
Linux/macOS: download [`install.sh`](https://github.com/daoek/CGen/raw/main/scripts/install.sh).
Run it with the version you want:

```powershell
.\install.ps1 -Version 0.1.0-beta.3
```

```console
./install.sh --version 0.1.0-beta.3
```

It downloads that [release](https://github.com/daoek/CGen/releases), verifies the jar against the
published SHA-256 checksum, and installs it per-user — no admin/`sudo` rights, no Maven or a clone
of the repository needed **to install it**. Running the installed `CGen` does need a **Java 17+
runtime** on `PATH`; both launchers check for it and say so clearly if it's missing. Open a new
terminal afterwards:

```console
CGen --help
```

[Installation guide](https://daoek.github.io/CGen/getting-started/installation/) covers
uninstalling, building from source, and running the jar directly.

## Use

```console
CGen init
CGen create interface common_iic drivers/Interface
CGen create module ra_iic drivers/RA --implements common_iic
CGen generate
```

```yaml
# drivers/RA/ra_iic.module.yaml
kind: module
name: ra_iic
description: RA-family I2C implementation
header: ra_iic.h
source: ra_iic.c
implements: [common_iic]
context:
  - void *hardware
```

```c
/* drivers/RA/ra_iic.c — generated; you fill in the region */
static common_iic_status_t ra_iic_common_iic_write(void *context, uint32_t slave_address, const uint8_t *data, uint32_t length)
{
    ra_iic_context_t *module = (ra_iic_context_t *)context;
    common_iic_status_t cgen_result = COMMON_IIC_INVALID_PARAM;

    /*@CGen usercode+ function.common_iic.write.body*/
    /* Your driver code goes here and survives regeneration. */
    /*@CGen usercode-*/
    return cgen_result;
}
```

Run `CGen generate` again after any YAML change. The structure is rewritten, your regions are
carried over.

[Quickstart →](https://daoek.github.io/CGen/getting-started/quickstart/)

## What you can generate

| Kind | For |
| --- | --- |
| [Interface](https://daoek.github.io/CGen/generators/interface/) | A contract several modules implement, with guarded dispatch |
| [Module](https://daoek.github.io/CGen/generators/module/) | A concrete unit: state, variables, functions, implementations |
| [State machine](https://daoek.github.io/CGen/generators/state-machine/) | States, events, transitions, guards, tick hooks — optionally hierarchical, via StateSmith |
| [Observer](https://daoek.github.io/CGen/generators/observer/) | Fan one call out to many subscribers, no allocation |
| [Command table](https://daoek.github.io/CGen/generators/command-table/) | UART/CLI opcode dispatch |
| [Status codes](https://daoek.github.io/CGen/generators/status-codes/) | A shared status enum plus checking macros |
| [Adapter](https://daoek.github.io/CGen/generators/adapter/) | Glue between two interfaces you cannot change |

Generated C is MISRA C:2012-oriented: single point of exit, null checks before every dereference,
explicit handling of unused parameters.

## No lock-in

`CGen detach` strips every marker, deletes the spec files, and leaves ordinary C behind.

## License

[MIT](LICENSE)
