# MISRA-oriented generated C

CGen emits MISRA C:2012-friendly control flow by default. This page explains what that means
concretely, and — just as importantly — what it does not mean.

## What the generator does

### Single point of exit

Generated functions compute into `cgen_result` and return once, at the end:

```c
static inline common_iic_status_t common_iic_write(const common_iic_interface_t * const interface, uint32_t length)
{
    common_iic_status_t cgen_result = COMMON_IIC_INVALID_PARAM;

    if (interface != NULL)
    {
        if ((interface->context != NULL) && (interface->write != NULL))
        {
            cgen_result = interface->write(interface->context, length);
        }
        else
        {
            cgen_result = COMMON_IIC_NOT_INITIALIZED;
        }
    }

    return cgen_result;
}
```

In a non-`void` user region, **assign to `cgen_result`** rather than returning early, so the
function keeps its single return.

### Pointers are checked before use

No generated code dereferences a pointer it has not tested. The dispatch wrapper above checks the
interface, its context and the function pointer before calling through; a bind function checks its
`interface` argument before writing to it.

### No silent invalid return values

Every non-`void` interface function should supply `invalidReturn` - on the function, on
`invalidReturns` for its return type, or as the interface-level default. A fabricated `-1` is not a
valid value of an enum, a pointer, or an unsigned type, so CGen never spreads one scalar across
unrelated return types. Name nothing at all and the guard falls back to a zero initializer,
`(flash_command_t){0}`: it compiles for any type, but for an enum whose `0` value means success it
reports success from a failed guard. Name a real sentinel for those.
`uninitializedReturn` covers the "nothing bound yet" case in the same way.

### Unused parameters are consumed explicitly

Stub bodies you have not filled in yet still compile cleanly:

```c
    (void)module;
    (void)slave_address;
```

Set [`format.suppressUnusedWarnings: false`](project-configuration.md#suppressunusedwarnings) if
your standard forbids those casts.

### Braces and blocks everywhere

Every `if`, `else`, `case` and loop body that CGen generates is braced, including single-statement
bodies and each `@CGenSwitch` case, which gets its own `{ ... }` block with an explicit `break;`.

## What it does not mean

!!! warning "Compliance is a property of the translation unit, not of the generator"

    MISRA compliance applies to the **complete** translation unit — your configured types, your
    expressions, your includes, and everything inside your user regions. CGen generating
    conforming skeletons does not make the resulting file conforming.

    Confirm it with your project's MISRA checker and deviation policy, the same as any other
    source file.

### The known deviation: Rule 11.5

The generic interface pattern converts the `void *context` of the function-pointer table to the
concrete module context type:

```c
static common_iic_status_t ra_iic_common_iic_write(void *context, uint32_t length)
{
    ra_iic_context_t *module = (ra_iic_context_t *)context;   /* conversion from void* */
```

That is the mechanism by which one interface serves any number of implementations without the
interface knowing any of them. Projects enforcing advisory **Rule 11.5** (a conversion from a
pointer to object to a pointer to a different object type) need to record this as a **design
deviation**, once, for the generated dispatch pattern.

It is the only deviation inherent to the generated structure. Everything else a checker flags is
either in your user regions or in the types you chose.

## The StateSmith engine is out of scope

None of the above applies to [`engine: statesmith`](../generators/state-machine.md#the-statesmith-engine)
state machines' `door_sm.h`/`door_sm.c` - that code comes from StateSmith, a separate tool with its
own generation style, and needs its own review and deviations if your project requires MISRA
compliance. CGen's MISRA-oriented claims cover only what CGen itself generates: for a statesmith
machine, that's `door.h`/`door.c` and `door_hooks.h`/`door_hooks.c`, generated the same
single-return, null-checked way as everything else on this page.

## Practical review advice

- **Keep the YAML in review.** Reviewers can read one spec file instead of three generated ones,
  and the generated diff shows exactly what the change produced.
- **Run the checker on generated output, not on the YAML.** CGen has no view of your checker's
  configuration or deviations.
- **Re-run after changing `format`.** `indent`, `functionNaming` and `publicVariables` change every
  generated file, so re-baseline any checker report that records line numbers.
