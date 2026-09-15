# `@CGenSwitch`

Writing one `case` per enum member by hand is tedious and goes stale the moment the enum grows.
Inside any user region you can tag a `switch` with a `@CGenSwitch` comment and let CGen keep the
cases in sync instead.

## Tag an empty switch

Write the comment directly above your own `switch`, naming the enum type:

```c
/*@CGen usercode+ function.dispatch_opcode.body*/
/*@CGenSwitch flash_opcodes_t*/
switch (opcode)
{
}
/*@CGen usercode-*/
```

Run `CGen generate`. CGen resolves `flash_opcodes_t` (see [Resolving the enum](#resolving-the-enum)
below) and rewrites the switch with one `case` per member plus a `default`, each carrying its own
nested user region:

```c
/*@CGenSwitch flash_opcodes_t*/
switch (opcode)
{
    case FLASH_OP_READ:
    {
        /*@CGen usercode+ switchcase.flash_opcodes_t.FLASH_OP_READ*/
        /*@CGen usercode-*/
        break;
    }
    case FLASH_OP_WRITE:
    {
        /*@CGen usercode+ switchcase.flash_opcodes_t.FLASH_OP_WRITE*/
        /*@CGen usercode-*/
        break;
    }
    default:
    {
        /*@CGen usercode+ switchcase.flash_opcodes_t.default*/
        /*@CGen usercode-*/
        break;
    }
}
```

From then on, edit only inside the `switchcase.<enum>.<case>` regions. They survive regeneration
like any other user region, and **a new enum member automatically gets its own empty case** on the
next run.

!!! warning "The skeleton is CGen's now"

    Anything you write inside the switch body but *outside* a case's own region — a stray
    statement, or a case you added by hand without regenerating — is not preserved. From the moment
    you tag it, the structure around the case regions belongs to CGen, exactly as everywhere else.

## Tagging a switch you already wrote

You do not have to start from an empty switch. The first time you tag an existing hand-written one:

- code already inside a `case` whose label **matches** an enum member is migrated into that
  member's new region rather than discarded;
- a `case` whose label **does not match** any member is left alone, and generation fails with a
  clear error instead of silently dropping it — fix or remove that case, then generate again.

## Resolving the enum

CGen looks for the named type in two places, in order:

1. **Every `enums:` block declared anywhere in the project's `.module.yaml` and `.interface.yaml`
   files.** If it is there, the case list is taken straight from the spec and nothing else happens.
2. **The project's `.h` and `.c` files**, scanned for a matching
   `typedef enum { ... } flash_opcodes_t;`. Because this is a guess about your source, CGen shows
   what it found and asks you to confirm before using it:

```console
@CGenSwitch flash_opcodes_t is not declared in any YAML enums: block.
Found a matching 'typedef enum' in drivers/flash_regs.h:
  FLASH_OP_READ, FLASH_OP_WRITE, FLASH_OP_ERASE
Use this enum? [y/N]:
```

!!! info "The fallback scan is deliberately simple"

    It matches a single `typedef enum { ... } name;` and splits members on commas — it is not a C
    preprocessor. Two files defining the same enum name differently, or a body that builds values
    from macros, are not handled. Declare the enum in a YAML `enums:` block if you hit that.

## What gets remembered

Once you confirm an enum found by the file scan, CGen records **where it is declared** — never a
copy of it. It adds an entry to the `externalEnums:` block of the `.module.yaml` that owns the file
containing the `@CGenSwitch`, with `file` relative to that YAML:

```yaml title="drivers/flash.module.yaml"
externalEnums:
  - { name: flash_opcodes_t, file: ../drivers/flash_regs.h }
```

This is a **link, not a declaration**. Unlike `enums:`, CGen never emits its own `typedef` for an
`externalEnums:` entry — the type already exists in `file`, and redeclaring it would conflict with
or duplicate the real definition. Later runs read `file` directly to get the case list, with no
project scan and no repeated question.

The entry is written as a plain text insertion, the same way `enums:` entries are: the rest of the
file, comments included, is untouched, and the result is verified by re-parsing and rolled back if
that fails.

!!! note "Only modules can remember"

    `externalEnums:` exists on `.module.yaml` only. A `@CGenSwitch` inside a file owned by a
    state machine, observer, command table or adapter spec will ask for confirmation **every**
    time. Declare the enum in YAML `enums:` if that becomes tiresome.

## When to use it

| Situation | Better tool |
| --- | --- |
| Reacting to opcodes from a wire protocol you own | [Command table](../generators/command-table.md) — it generates the whole dispatcher. |
| Reacting to events that change a mode or state | [State machine](../generators/state-machine.md). |
| Exhaustively handling a vendor enum, a register map, or an error code list inside one function | `@CGenSwitch`. |
