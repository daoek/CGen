# Third-party software

CGen (MIT, see [LICENSE](LICENSE)) uses the following third-party software.

## Bundled in the shaded jar

| Library | License | Notes |
| --- | --- | --- |
| [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) 2.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | YAML parsing. Shaded into `cgen-*-shaded.jar` by the Maven Shade plugin; not modified. |

## Invoked as an external tool, not redistributed

| Tool | License | Notes |
| --- | --- | --- |
| [StateSmith](https://github.com/StateSmith/StateSmith) (`ss.cli`) | [Apache-2.0](https://github.com/StateSmith/StateSmith/blob/main/LICENSE) | Used only by the [`engine: statesmith`](docs/generators/state-machine.md#the-statesmith-engine) state-machine generator. CGen shells out to a separately installed `ss.cli` (see [install/version pinning](docs/generators/state-machine.md#install-and-version-pinning)); it is never downloaded, bundled, or redistributed by CGen. Code that `ss.cli` generates (`<name>_sm.h`/`.c`) is StateSmith's own output, not CGen's, and is outside CGen's [MISRA](docs/guide/misra.md) claims. |
