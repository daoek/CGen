# Nested projects

A directory tree with its own `pinfit.yaml` is a separate, self-contained project — even when it
sits inside another project's tree. This is how you vendor or import a Pinfit-based library without
the enclosing project regenerating or overwriting it.

## The boundary

```title="A project boundary starts at every pinfit.yaml"
firmware/
  pinfit.yaml                        # (1)! outer project
  drivers/common/ra_iic.module.yaml
  Lib/importedlib/
    pinfit.yaml                      # (2)! its own project — boundary starts here
    sensor.interface.yaml
    deep/other.module.yaml         # (3)!
```

1.  Running `Pinfit` anywhere in `firmware/` — except under a nested project — uses **this**
    configuration.
2.  The moment the scan sees this file, it stops descending.
3.  Not scanned, not generated, not deleted by the outer project. Ever.

Running `pinfit generate` (or `detach`) from `firmware/` scans its own tree and stops at
`Lib/importedlib/`. Nothing underneath it, however deep, is touched.

Each project uses **its own** `pinfit.yaml` settings. A library generated with
`lineEnding: crlf` and `functionNaming: camelCase` keeps those, whatever the project importing it
prefers.

## Working on a nested project

Run Pinfit from inside it:

```console
cd Lib/importedlib
pinfit generate
```

It resolves its own `pinfit.yaml` and generates with its own settings — there is nothing else to
configure.

!!! warning "The outer project cannot write into it"

    `pinfit create ... Lib/importedlib` from the outer project is refused. You would be writing a
    spec file into someone else's project, to be generated under the wrong `pinfit.yaml` rules.

## Cross-project references

Interface resolution is per-project. A module in the outer project cannot `implements:` an
interface declared inside a nested project, and vice versa — each `generate` only sees the specs
within its own boundary.

What crosses the boundary is plain C: the nested project's **generated headers** are ordinary
files, so the outer project's code includes them like any other third-party header.

## Generating everything at once: `--also-nested`

When you do want the outer project *and* every nested project generated in one run:

```console
pinfit generate --also-nested
```

Every nested project found anywhere under the scanned directory is generated too — nested inside
nested included — each still using its own `pinfit.yaml`, never the outer project's.

Unlike plain `generate`, this also works when the directory you run it from has **no** `pinfit.yaml`
of its own or above it. The directory is then used purely as a search root: no outer project is
generated, just every nested one found underneath.

### Why it asks first

That walk is not bounded by any project root and can reach arbitrarily far — point it at a drive
root and it will walk the whole drive. So `--also-nested` never scans silently and never generates
anything before asking.

It first runs a fast, multithreaded, recursive **directory count** — cheap enough to run before
you have decided anything, and enough to gauge how big the tree really is — shown live as it goes,
then asks:

```console
--also-nested walks every subdirectory under <directory> looking for nested pinfit.yaml projects.
On a large or deep directory (an entire drive, say) that can take a while.
Found 48213 directories under <directory> (612 ms).
Search all of them for nested pinfit.yaml projects and generate what's found? [y/N]:
```

Only once you confirm does the real scan run — the one that actually checks each directory for a
`pinfit.yaml`, also multithreaded. Because the fast pass already produced an exact directory count,
this stage shows a real `[bar] N/total` progress bar rather than an open-ended counter, followed by
how many nested projects it found.

### The summary

Once every nested project has been generated, the run ends with a full list of every file
generated or regenerated across the outer project and all nested ones, then the final count:

```console
Generated files:
  outer.h
  outer.c
  lib1\lib1_iic_I.h
  lib2\deep\lib2_iic_I.h

4 file(s) generated
```
