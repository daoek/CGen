# Project configuration

`pinfit.yaml` marks the root of a project and holds generator-wide preferences. It does not emit any
C of its own.

```yaml title="pinfit.yaml"
schema: 1
name: firmware
version: 0.1.0

documentation:
  style: doxygen # doxygen, none, or custom
  # file: documentation.yaml

format:
  indent: 4
  lineEnding: lf # lf or crlf
  publicVariables: extern # extern or accessors
  suppressUnusedWarnings: true # emit (void)param; lines in generated stubs
  functionNaming: snake_case # snake_case or camelCase

# Only needed by a state machine with engine: statesmith - see
# State machine > The statesmith engine.
stateSmith:
  command: ss.cli
  version: 0.22.2

strict: false
```

Create it with [`pinfit init`](../reference/cli.md#pinfit-init). Its location defines the project:
every `Pinfit` command you run from this directory or any descendant uses this file, and the scan
stops at any subdirectory that has a `pinfit.yaml` of its own
(see [Nested projects](nested-projects.md)).

## `documentation`

| Key | Values | Meaning |
| --- | --- | --- |
| `style` | `doxygen` (default), `none`, `custom` | Comment style on generated files, functions, types and variables. |
| `file` | path | Only with `style: custom`. Points at the YAML holding your templates. |

### Custom documentation templates

Set `style: custom` and point `file` at a YAML with optional `file`, `function`, `type` and
`variable` text templates:

```yaml title="documentation.yaml"
file: |
  /* ${file} - ${brief} */
function: |
  /* ${name}: ${brief}
     params: ${params}
     returns: ${return} */
```

Supported placeholders are `${file}`, `${name}`, `${brief}`, `${return}` and `${params}`.

`style: none` suppresses generated comments entirely — useful when a project's own header
template is applied by a separate tool.

## `format`

### `indent`

Number of spaces per level in generated C. Default `4`.

### `lineEnding`

`lf` (default) or `crlf`. Set this deliberately on mixed Windows/Linux teams so regeneration does
not produce whole-file diffs.

### `publicVariables`

Decides how a module's non-`private` variables are exposed, project-wide.

=== "`extern` (default)"

    Each `public` variable gets a plain `extern` declaration in the header and a definition in the
    source.

    ```yaml
    variables:
      - uint32_t transfer_count public
    ```

    ```c title="ra_iic.h"
    extern uint32_t transfer_count;
    ```

    ```c title="ra_iic.c"
    uint32_t transfer_count;
    ```

=== "`accessors`"

    The variable stays `static` (private storage) and Pinfit generates `get_<name>` / `set_<name>`
    functions instead — no module prefix — each with its own user region, so you can add
    validation or side effects on read and write.

    ```c title="ra_iic.c"
    uint32_t get_transfer_count(void)
    {
    /*@Pinfit usercode+ variable.transfer_count.get*/
        return transfer_count;
    /*@Pinfit usercode-*/
    }
    ```

`get` or `set` visibility on an individual variable narrows this to only that one accessor — a
read-only counter, or a write-only latch. Both require `publicVariables: accessors`; using them
under `extern` is a configuration error, because `extern` only understands `public` and `private`.

!!! warning "Arrays cannot use accessors"

    An array variable marked `public`, `get` or `set` is a configuration error under `accessors`,
    since C cannot return or take an array by value the way `get_<name>` / `set_<name>` would
    need to. Keep array variables `private`, or expose them as `public` under `extern`.

See [Module](../generators/module.md#variables) for the variable syntax itself.

### `suppressUnusedWarnings`

Default `true`. Generated stub bodies begin with `(void)parameter;` lines so that an untouched
stub compiles warning-free under `-Wunused-parameter`:

```c
static common_iic_status_t ra_iic_common_iic_write(void *context, uint32_t length)
{
    ra_iic_context_t *module = (ra_iic_context_t *)context;
    common_iic_status_t pinfit_result = COMMON_IIC_INVALID_PARAM;
    (void)module;
    (void)length;
    ...
```

Set it to `false` if your coding standard forbids those casts, and accept the warnings on stubs
you have not filled in yet.

### `functionNaming`

`snake_case` (default) or `camelCase`, applied to generated function names:

| `functionNaming` | Generated |
| --- | --- |
| `snake_case` | `ra_iic_bind_common_iic`, `door_go_to_state`, `get_transfer_count` |
| `camelCase` | `raIicBindCommonIic`, `doorGoToState`, `getTransferCount` |

This is a project-wide switch, so a project stays internally consistent. Changing it renames every
generated function on the next `generate`; call sites in your own user regions are **not** rewritten,
so update those in the same commit.

## `stateSmith`

Only required when the project has at least one [`engine: statesmith`](../generators/state-machine.md#the-statesmith-engine)
state machine; `generate` never invokes or requires `ss.cli` otherwise.

| Key | Default | Meaning |
| --- | --- | --- |
| `command` | `ss.cli` | Executable name or path. Resolved on PATH; a bare name also tries `<name>.exe`, since that's how StateSmith's own installer names it on Windows. |
| `version` | none, required if used | Exact version `ss.cli --version` must report. `generate` fails with install/version instructions on a missing tool or a mismatch. |

## `strict`

Default `false`. When `true`, `generate` fails instead of warning on a non-`void` function that
has no [`invalidReturn`/`uninitializedReturn`](../generators/interface.md#invalidreturn-and-uninitializedreturn)
anywhere and falls back to a zero initializer. `pinfit generate --strict` does the same for one run
without changing `pinfit.yaml`.

## `schema`, `name`, `version`

- `schema: 1` is the config format version Pinfit validates against.
- `name` is what [`pinfit detach`](../reference/cli.md#pinfit-detach) requires you to type to confirm
  that destructive command, so keep it recognisable.
- `version` is yours to use for your own release tracking; Pinfit only carries it.
