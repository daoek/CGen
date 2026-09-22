# User regions and regeneration

User regions are the reason Pinfit can regenerate a file you have already edited. This page covers
what they look like, which ones exist, what happens when a spec changes, and how to leave Pinfit
behind for good.

## The rule

Edit **only** inside a named user region:

```c
/*@Pinfit usercode+ function.common_iic.write.body*/
/* Your code is retained here, exactly as written. */
/*@Pinfit usercode-*/
```

Everything outside such a pair belongs to Pinfit and is rewritten on every run. The markers are
deliberately distinct from Pinfit's other `/*@Pinfit(...)*/` structural markers, so the two are easy to
tell apart when scanning a file.

!!! danger "Do not edit or delete the marker lines"

    The `usercode+` line carries the region's name, which is how the next run knows where your code
    belongs. Change or lose it and the code inside is no longer attached to anything.

## How a region survives

On each `generate`, Pinfit reads the existing output file, takes the text between every
`usercode+` / `usercode-` pair, renders the structure fresh from the YAML, and pastes each saved
block back into the region of the same name. The region's *content* is never inspected, parsed or
reformatted — it comes back byte for byte.

This means a region moves with its item. Reorder functions in the YAML, change a parameter type,
switch `format.indent` from 4 to 2: the surrounding code is re-rendered, your body is not.

## Which regions exist

Regions are created by the generator, not by you — you cannot add one by inventing a name. Every
generator page lists its own, but the naming is consistent:

| Region | Where it appears |
| --- | --- |
| `interface.preamble`, `interface.declarations`, `interface.footer` | [Interface](../generators/interface.md) headers |
| `module.header.preamble`, `module.header.footer` | [Module](../generators/module.md) headers |
| `module.source.includes`, `module.source.variables`, `module.source.prototypes`, `module.source.footer` | Module sources |
| `function.<interface>.<function>.body` | A module's implementation of an interface function |
| `function.<name>.body` | A module's own standalone function |
| `variable.<name>.get`, `variable.<name>.set` | Generated accessors, under `publicVariables: accessors` |
| `singleton.init`, `singleton.else` | A `singleton: true` module |
| `state.<STATE>.entry`, `state.<STATE>.exit`, `state.<STATE>.tick` | [State machine](../generators/state-machine.md) states |
| `transition.<from>.<event>.guard`, `event.<EVENT>.unhandled` | State machine transitions |
| `command.<NAME>.body`, `command.unknown` | [Command table](../generators/command-table.md) handlers |
| `switchcase.<enum>.<case>` | A [`@PinfitSwitch`](pinfitswitch.md) case |

[Observers](../generators/observer.md) and [status codes](../generators/status-codes.md) have no
user regions at all — their output is entirely mechanical.

## Where to put things that are not function bodies

A generated module source has four regions specifically for the things that do not belong in any
one function:

```c
#include "ra_iic.h"

/*@Pinfit usercode+ module.source.includes*/
#include "vendor_i2c.h"          /* extra includes go here */
/*@Pinfit usercode-*/

/*@Pinfit usercode+ module.source.variables*/
static uint8_t scratch[32];      /* file-scope state Pinfit does not know about */
/*@Pinfit usercode-*/

/*@Pinfit usercode+ module.source.prototypes*/
static void reset_bus(void);     /* forward declarations for your own helpers */
/*@Pinfit usercode-*/
```

Helper *definitions* go in `module.source.footer` at the bottom of the file.

!!! tip "Prefer the YAML when it can express it"

    Anything Pinfit can generate — a variable, a standalone function, an enum — is better declared in
    the YAML than hand-written into a region. You get the declaration, the documentation comment and
    the header entry for free, and the next reader sees it in the spec.

## Returning a value: `pinfit_result`

Generated non-`void` bodies are wrapped in a single-return shape, for
[MISRA](misra.md) reasons:

```c
static common_iic_status_t ra_iic_common_iic_write(void *context, uint32_t length)
{
    common_iic_status_t pinfit_result = COMMON_IIC_INVALID_PARAM;

    /*@Pinfit usercode+ function.common_iic.write.body*/
    pinfit_result = COMMON_IIC_SUCCESS;   /* assign, do not return */
    /*@Pinfit usercode-*/
    return pinfit_result;
}
```

Assign your result to `pinfit_result` instead of returning early. The initial value is the
function's `invalidReturn`, so a stub you have not filled in yet fails safely rather than returning
garbage.

The module context is available as `module`, already cast from the generic `void *context`.

## When you remove something from the YAML

**Nothing you wrote is deleted.** If a YAML item disappears, its generated structure goes with it,
but the user region is retained as an *orphan* — kept in the file, still named, no longer wrapped
in any function. Move the code where it now belongs, then delete the empty region.

A rename is a removal plus an addition, so you get an orphan holding the old body and a fresh
empty region under the new name. (For the specific case of renaming a whole module, use
[`pinfit rename module`](../reference/cli.md#pinfit-rename-module), which moves the files and updates
the references for you.)

## Files Pinfit will not touch

Every generated file starts with a marker line naming the spec that produced it:

```c
/*@Pinfit(file:module-source:ra_iic.module.yaml)*/
```

If a file Pinfit is about to write already exists **without** that marker, generation fails rather
than overwriting it. That is what protects a hand-written `ra_iic.c` that predates the spec.

When you genuinely want that file replaced, pass
[`--force`](../reference/cli.md#pinfit-generate) — and check the file into version control first,
because its content is gone afterwards.

!!! danger "A file using the old `/*@Pinfit(+name)*/` region syntax"

    Versions before the `usercode+`/`usercode-` syntax above wrote regions as
    `/*@Pinfit(+name)*/ ... /*@Pinfit(-name)*/`. The current parser does not recognize that shape as a
    region at all, so `generate` **refuses** a file that still has it, naming the file and line,
    rather than silently discarding what's inside — `--force` does not bypass this refusal either,
    since the file is a recognized Pinfit file, just an outdated one. Fix it by hand: change that
    region's two marker lines to the current syntax, keeping the code between them exactly as it
    is, then run `generate` again.

## Files Pinfit will not overwrite either: an edit outside any region

Every generated file's second line is a hash of everything in it **outside** its usercode regions:

```c
/*@Pinfit(file:module-source:ra_iic.module.yaml)*/
/*@Pinfit(skeleton-hash:afa5049c3118ea5c)*/
```

If that hash no longer matches when `generate` runs again, something outside every region changed
since Pinfit last wrote the file — by hand, by another tool, whatever the cause. `generate` refuses,
naming the file, rather than silently overwriting whatever that change was. Move it into a
usercode region or into the YAML spec, or pass `--force` to overwrite it anyway. A file from before
this existed has no hash yet and is not flagged; it gets one on its next regeneration.

This is a second, independent line of defense from the marker check above — either one refusing is
enough to keep the file untouched.

## Orphaned regions are reported on every run

An [orphaned region](#when-you-remove-something-from-the-yaml) is kept in the file, but you would
otherwise only find it by opening that file. `generate` also prints every orphan it wrote, with
file and line, so a non-interactive run cannot finish without it showing up somewhere:

```console
Warning: ra_iic.c:42: orphaned user region 'function.common_iic.write.body' - its YAML item is
gone; move the code where it belongs, then delete the region
```

## Detaching permanently

To remove Pinfit from a project for good:

```console
pinfit detach
```

This destructive command requires typing the exact project `name` from `pinfit.yaml` to confirm. It:

- keeps **all** generated C code and every unrelated YAML file,
- removes the Pinfit marker lines from the C files, then
- deletes `pinfit.yaml` and every Pinfit spec YAML - `*.interface.yaml`, `*.module.yaml`,
  `*.state-machine.yaml`, `*.status-codes.yaml`, `*.observer.yaml`, `*.command-table.yaml`,
  `*.adapter.yaml` - and the custom documentation YAML the project referenced.

An `engine: statesmith` state machine's generated `.plantuml` is the one exception: it is kept,
with only its marker stripped, as documentation.

The result is ordinary C with no trace of the generator. A detached project cannot be regenerated
unless you configure it again from scratch with `pinfit init`.
