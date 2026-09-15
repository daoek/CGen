# Editor snippets

Spec files are mostly repeated list items: fields, parameters, enum values, states, transitions.
The repository ships [`.vscode/cgen.code-snippets`](https://github.com/daoek/CGen/blob/main/.vscode/cgen.code-snippets)
so you can expand each of those instead of retyping the shape from the documentation.

## Using them

Open any `.yaml` file, type a prefix such as `cgen-function-module` or `cgen-variable`, and accept
the completion. Tab moves through the placeholders.

The snippets are scoped to YAML, so they are available in every kind of spec file — interface,
module, state machine, observer, command table, status codes and adapter — as well as in
`cgen.yaml` itself.

!!! info "Getting them into your own project"

    The snippets live in this repository's `.vscode/` folder. To use them in a project of your own,
    copy `cgen.code-snippets` into that project's `.vscode/` directory, or into your user snippets
    directory to have them everywhere.

## Available snippets

| Prefix | Inserts |
| --- | --- |
| `cgen-enum` | An `enums:` entry (interface or module) |
| `cgen-enum-value` | One enum value line |
| `cgen-struct` | A `structs:` entry (interface) |
| `cgen-field` | A compact `type name` field, context or parameter line |
| `cgen-field-full` | The same, in map form with a `description` |
| `cgen-function-interface` | An interface function entry |
| `cgen-function-module` | A module standalone function entry |
| `cgen-parameter` | A compact function parameter line |
| `cgen-variable` | A module variable, compact form — leave the third field blank for `private` |
| `cgen-variable-full` | A module variable with `visibility`, `initial` and `description` |
| `cgen-state` | A state-machine state entry |
| `cgen-event` | A state-machine event entry |
| `cgen-transition` | A state-machine transition entry |
| `cgen-command` | A command-table command entry |
| `cgen-code` | A status-codes code entry |
| `cgen-mapping` | An adapter function mapping entry |
| `cgen-include-system` | An `<system.h>` include entry |
| `cgen-include-local` | A `"local.h"` include entry, single-quoted so the double quotes survive YAML parsing |

## Build task

The same `.vscode` folder contains a default build task. Press ++ctrl+shift+b++ and run
**CGen: Package + Install** to rebuild, test, package and update the installed `CGen` command in
one step — useful when you are working on CGen itself rather than with it.

## YAML editing tips

- **Local includes need single quotes.** YAML would otherwise eat the double quotes:

  ```yaml
  includes: ['"vendor_i2c.h"', <stdint.h>]
  ```

- **Compact form first.** Struct fields, function and event `parameters`, and `context` entries all
  accept `"type name"` shorthand. Switch to the map form only when you need a `description`:

  ```yaml
  context:
    - void *hardware                                    # compact
    - { type: uint32_t, name: ticks, description: Uptime }  # needs a description
  ```

- **A schema-aware YAML extension helps.** Nothing CGen-specific is required, but generic YAML
  validation catches indentation slips before `generate` does.
