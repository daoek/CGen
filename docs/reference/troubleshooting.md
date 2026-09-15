# Troubleshooting

Errors print a red `CGen error` block with the offending file's path, often followed by a cyan hint
about the spec. Nothing is written when generation fails — a run either completes or changes
nothing.

## Generation refuses to run

### `implements unknown interface '<name>'`

```console
CGen error
D:\firmware\drivers\RA\ra_iic.module.yaml implements unknown interface 'nope'
```

The interface name is matched against the `name:` field of every `.interface.yaml` in the project,
not against a file name or a path. Check for a typo, and check the interface is inside this
project's tree — an interface in a [nested project](../guide/nested-projects.md) is invisible from
the outer one, and vice versa.

The same applies to `references unknown ...` from an observer's `interface:` or an adapter's
`from:` / `to:`.

### `maps unknown function '<name>'`

An [adapter](../generators/adapter.md) mapping names a function that does not exist on the `from` or
`to` interface. Names are the YAML `name:` of the function, not the generated C identifier.

### `Refusing to overwrite non-CGen file <path>`

```console
CGen error
Refusing to overwrite non-CGen file D:\firmware\drivers\RA\ra_iic.c (use -f/--force to overwrite)
```

A file CGen was about to write already exists without its generated-file marker. This is the
protection for a hand-written file that predates the spec.

Either point the spec at a different `header:` / `source:` name, or — once you are sure the file's
content is expendable and committed — pass `--force`.

### `Multiple YAML specifications generate <file>`

Two specs claim the same output path. Rename one, or move its YAML to another directory; generated
files always land next to their spec.

### `Duplicate interface name` / `Multiple modules named '<name>'`

Names are project-wide identifiers, so two specs cannot share one. This most often appears after
copy-pasting a spec file without changing `name:`.

## Configuration errors

| Message | Fix |
| --- | --- |
| `format.indent must be between 2 and 8` | Pick a value in that range. |
| `format.lineEnding must be lf or crlf` | Exactly those two spellings, lowercase. |
| `format.publicVariables must be extern or accessors` | Likewise. |
| `format.functionNaming must be snake_case or camelCase` | Note the mixed spelling: `snake_case`, `camelCase`. |
| `documentation.style must be doxygen, none, or custom` | Likewise. |
| `documentation.file is required when documentation.style is custom` | Add the path to your template YAML. |

See [Project configuration](../guide/project-configuration.md).

## Variable visibility errors

- **`get` / `set` under `extern`** — those two visibilities only exist under
  `format.publicVariables: accessors`; `extern` knows only `public` and `private`. Switch the
  project setting, or use `public`.
- **An array marked `public`, `get` or `set` under `accessors`** — C cannot return or take an array
  by value, so `get_<name>` / `set_<name>` cannot be generated. Keep array variables `private`, or
  expose them as `public` under `extern`.

## `@CGenSwitch` problems

### It asks about the same enum every time

Confirmation is only remembered in an `externalEnums:` link on a `.module.yaml`. A `@CGenSwitch`
inside a file owned by a state machine, observer, command table or adapter spec has nowhere to
record it. Declare the enum in a YAML `enums:` block instead.

### `Matched 'typedef enum' has no members`

The fallback scan found the typedef but could not parse members out of it — usually a body built
from macros. Declare the enum in YAML `enums:`.

### `Linked enum file ... cannot be read` / `no longer exists`

An `externalEnums:` entry points at a file that has moved or been deleted. The `file` path is
relative to the YAML holding the entry. Fix the path, or delete the entry to be asked again.

### Generation fails on a `case` label

A hand-written `case` in a tagged switch does not match any member of the enum. CGen fails rather
than silently dropping your code. Remove or correct that case, then generate again. See
[`@CGenSwitch`](../guide/cgenswitch.md#tagging-a-switch-you-already-wrote).

## My code disappeared

It did not — CGen never deletes user region content. Check these in order:

1. **Was it inside a region?** Only text between `usercode+` and `usercode-` is preserved. Anything
   else in a generated file is rewritten every run.
2. **Did the YAML item go away?** A removed function or state leaves its region in the file as an
   *orphan*, no longer wrapped in anything. Search the file for the region name.
3. **Was the item renamed?** That is a removal plus an addition: the old body is in the orphan
   region, and the new one is empty. Move the code across.
4. **Did you edit a marker line?** The `usercode+` line carries the name the next run matches on.
   If it was changed or deleted, the code is still in the file but no longer attached to anything —
   recover it from version control or by reading the file.

Run `CGen generate -v` to see how many regions were carried over per file.

## Nothing was generated

```console
0 file(s) generated
```

- **Wrong directory.** `generate` scans from the directory you gave it, defaulting to the current
  one. The project root is found by walking *up* for a `cgen.yaml`, but the *scan* starts where you
  are.
- **The specs are in a nested project.** A subdirectory with its own `cgen.yaml` is skipped
  entirely. Run CGen from inside it, or pass
  [`--also-nested`](../guide/nested-projects.md#generating-everything-at-once-also-nested).
- **File names.** A spec has to be named `<name>.<kind>.yaml` — `door.state-machine.yaml`, not
  `door.statemachine.yaml` or `door.yaml`.

Use `CGen generate -v` to print the project root and scope it actually used.

## The generated code will not compile

- **A changed signature.** Editing `parameters` or `return` rewrites the wrapper around your
  unchanged body. Fix the body.
- **Missing types.** A type used in `context`, `variables` or `parameters` must be reachable: add
  its header to `includes`, or declare the type in `enums` / `structs`.
- **Unprefixed standalone functions.** A module's own `functions:` are **not** module-prefixed, so
  two modules both declaring `initialize` collide at link time. Keep those names unique yourself.
- **`cgen_result` unused, or an early `return`.** In a non-`void` region, assign to `cgen_result`
  rather than returning. See [MISRA](../guide/misra.md).

## Line endings churn in every diff

Set [`format.lineEnding`](../guide/project-configuration.md#lineending) explicitly and agree on one
value across the team; add a `.gitattributes` rule to match. Otherwise each person's `generate`
rewrites every file.

## Still stuck

Run with `-v` for the project root, scope and per-file detail, and check the spec against the
[YAML cheat sheet](yaml-cheatsheet.md). If the behaviour still looks wrong, open an issue at
[github.com/daoek/CGen/issues](https://github.com/daoek/CGen/issues) with the spec file and the
exact error text.
