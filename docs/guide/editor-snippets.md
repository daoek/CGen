# Editor snippets

Spec files are mostly repeated list items: fields, parameters, enum values, states, transitions.
The repository ships [`.vscode/pinfit.code-snippets`](https://github.com/daoek/Pinfit/blob/main/.vscode/pinfit.code-snippets)
so you can expand each of those instead of retyping the shape from the documentation.

## Using them

Open any `.yaml` file, type a prefix such as `pinfit-function-module` or `pinfit-variable`, and accept
the completion. Tab moves through the placeholders.

The snippets are scoped to YAML, so they are available in every kind of spec file — interface,
module, state machine, observer, command table, status codes and adapter — as well as in
`pinfit.yaml` itself.

!!! info "Getting them into your own project"

    The snippets live in this repository's `.vscode/` folder. To use them in a project of your own,
    copy `pinfit.code-snippets` into that project's `.vscode/` directory, or into your user snippets
    directory to have them everywhere.

## Available snippets

| Prefix | Inserts |
| --- | --- |
| `pinfit-enum` | An `enums:` entry (interface or module) |
| `pinfit-enum-value` | One enum value line |
| `pinfit-struct` | A `structs:` entry (interface) |
| `pinfit-field` | A compact `type name` field, context or parameter line |
| `pinfit-field-full` | The same, in map form with a `description` |
| `pinfit-function-interface` | An interface function entry |
| `pinfit-function-module` | A module standalone function entry |
| `pinfit-parameter` | A compact function parameter line |
| `pinfit-variable` | A module variable, compact form — leave the third field blank for `private` |
| `pinfit-variable-full` | A module variable with `visibility`, `initial` and `description` |
| `pinfit-state` | A state-machine state entry |
| `pinfit-event` | A state-machine event entry |
| `pinfit-transition` | A state-machine transition entry |
| `pinfit-command` | A command-table command entry |
| `pinfit-code` | A status-codes code entry |
| `pinfit-mapping` | An adapter function mapping entry |
| `pinfit-include-system` | An `<system.h>` include entry |
| `pinfit-include-local` | A `"local.h"` include entry, single-quoted so the double quotes survive YAML parsing |

## Build task

The same `.vscode` folder contains a default build task. Press ++ctrl+shift+b++ and run
**Pinfit: Package + Install** to rebuild, test, package and update the installed `Pinfit` command in
one step — useful when you are working on Pinfit itself rather than with it.

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

- **A schema-aware YAML extension helps.** Nothing Pinfit-specific is required, but generic YAML
  validation catches indentation slips before `generate` does.
