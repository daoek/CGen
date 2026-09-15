# CGen

<div class="cgen-hero" markdown>

## Describe the shape of your C, write only the logic

CGen is a small, YAML-driven command line tool for embedded C projects. You describe an
interface, a module, a state machine or another well-known pattern in a short YAML file, and
CGen generates the headers and sources around it: the structs, the function-pointer tables,
the null checks, the dispatch, the Doxygen comments.

Your own code lives in named **user regions** inside those generated files. Regenerate as
often as you like — CGen writes the structure around your code and never touches what is
inside a region.

</div>

```yaml title="drivers/RA/ra_iic.module.yaml"
kind: module
name: ra_iic
description: RA-family I2C implementation
header: ra_iic.h
source: ra_iic.c

implements:
  - common_iic

context:
  - void *hardware
```

```c title="drivers/RA/ra_iic.c (generated — you fill in the region)"
/*@CGen(private-function:ra_iic_common_iic_write)*/
static common_iic_status_t ra_iic_common_iic_write(void *context, uint32_t slave_address, const uint8_t *data, uint32_t length)
{
    ra_iic_context_t *module = (ra_iic_context_t *)context;
    common_iic_status_t cgen_result = COMMON_IIC_INVALID_PARAM;

    /*@CGen usercode+ function.common_iic.write.body*/
    /* Your driver code goes here and survives every regeneration. */
    /*@CGen usercode-*/
    return cgen_result;
}
```

[Get started :material-arrow-right:](getting-started/installation.md){ .md-button .md-button--primary }
[See the generators](generators/index.md){ .md-button }

## Why CGen

<div class="grid cards" markdown>

-   :material-shield-check:{ .lg .middle } **Regeneration is safe**

    ---

    Hand-written code lives in `usercode+` / `usercode-` regions. Change the YAML, regenerate,
    and your logic is carried over. Remove a YAML item and its region is kept as an orphan
    rather than deleted.

    [:octicons-arrow-right-24: User regions](guide/user-regions.md)

-   :material-format-list-checks:{ .lg .middle } **Seven generators, one workflow**

    ---

    Interfaces, modules, state machines, observers, command tables, status codes and adapters.
    Every kind is one YAML file and the same `CGen generate` command.

    [:octicons-arrow-right-24: Generators](generators/index.md)

-   :material-certificate:{ .lg .middle } **MISRA-oriented C**

    ---

    Single return per function, null checks before every pointer dereference, explicit
    consumption of unused parameters — the generated skeleton is written for safety-critical
    review from the start.

    [:octicons-arrow-right-24: MISRA notes](guide/misra.md)

-   :material-package-variant-closed:{ .lg .middle } **No runtime, no dependency**

    ---

    CGen produces plain C with no library to link and nothing to allocate. A directory with its
    own `cgen.yaml` is a self-contained project, so vendored libraries stay untouched.

    [:octicons-arrow-right-24: Nested projects](guide/nested-projects.md)

</div>

## The workflow in four commands

```console
CGen init
CGen create interface common_iic drivers/Interface
CGen create module ra_iic drivers/RA --implements common_iic
CGen generate
```

`init` writes a `cgen.yaml` and nothing else — CGen never chooses a source layout for you.
`create` scaffolds a fully commented spec file. `generate` scans the project tree and writes
every header and source that the specs describe.

[Walk through it step by step :material-arrow-right:](getting-started/quickstart.md){ .md-button }

## What CGen is not

- **Not a build system.** CGen writes `.h` and `.c` files; your existing Make, CMake or IDE
  project compiles them.
- **Not a C parser.** It reads YAML, plus a light best-effort scan of your headers for
  [`@CGenSwitch`](guide/cgenswitch.md) enum lookups.
- **Not a lock-in.** [`CGen detach`](reference/cli.md#cgen-detach) strips every marker and leaves
  you with ordinary C source you can maintain by hand forever.
