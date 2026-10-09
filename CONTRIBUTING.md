# Contributing to Stein Bridge

Read the [Stein contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md) first: it
covers the rules, code style, commits and the CLA. This page is what is specific to the Bridge.

## Build and check

```sh
./gradlew spotlessApply     # google-java-format --aosp on every Java file
./gradlew build             # Bridge, shaders, patcher, visual options, patched game, coverage, preflight, tests
```

`./gradlew build` must pass the coverage and preflight checks (`FULL COVERAGE`, `PREFLIGHT OK`). For changes to
rendering, the engine or a patcher rule, also build the Loader next to this repository and run its `./gradlew verify -Pstein.world=<world>`.
Every rendering change must work in both pipelines (Stein GFX and the fixed-function fallback); see
[docs/PIPELINES.md](docs/PIPELINES.md).

## Where things go

| Change | Place |
|---|---|
| Something the game calls (a hook) | a method in `hooks/` plus a rule in `patcher/redirects-mods.tsv`; the Loader receives it through `HookSink` |
| An invisible optimization | `bridge/engine/`, with its rule in `patcher/redirects-opt.tsv`, a `steinbridge.opt.vanilla*` kill switch and a `steinbridge.opt.check*` verification mode |
| Rendering (Stein GFX) | `gfx/` (API and renderer), `gfx/gl/` (OpenGL backend), shaders in `src/main/shaders` |
| The LWJGL 2 compatibility layer | `org.lwjgl.*`: it must match the LWJGL 2 API exactly as the game calls it |
| A diagnostic scene | `bridge/diag/`, read from a `steinbridge.diag.*` option; it costs nothing unless one is set |
| A new `-D` option | a `static final` field in the package's `*Options` class, read with `core.Opt`, with a Javadoc starting `{@code -Dname=...}`; then run `bash tools/options.sh` (CI checks `docs/OPTIONS.md` is up to date) |
| A log line | `core.Log.info("tag", ...)`, never `System.out`; `SteinLog` is only the player's topic log |

## Writing code that touches the game

Game code in the Bridge is plain typed Java with MCP names: `Minecraft.getMinecraft().thePlayer`,
`entity.getEyeHeight()`, `GuiUtilRenderComponents.splitText(...)`, imported from `net.minecraft.*`. No
`MethodHandle`, `Field` or string name lookups for game members: the build compiles against the game with
readable names (every member opened, only to compile), translates to the real names and fails the build if a
reference does not resolve in the game jar. A member the real game keeps private, package-private or protected
is written as if it were public; the build turns the access into an `invokedynamic` bound once by `core.GameAccess`
(as fast as a direct access after linking). A closed constructor cannot be bound and fails the build.

Look names up in the MCP CSVs in `build/tools/mcp-stable-22` and `build/tools/joined-1.8.9.srg` (`./gradlew extractMcp`
fills them), or with `tools/mcp-name.sh` (readable name to SRG name).

Things that are not game members stay reflective: fields the patcher adds to game classes (they do not exist in the
readable jar, see `engine/SignText`), the Bridge's own classes, LWJGL and objects passed as `Object` on purpose. The
entry points a patcher rule targets keep their exact descriptors (`Object` parameters stay `Object`; cast inside).
The Bridge needs `../sdk` to build (see the README).

## Patcher rules

Each line of `patcher/redirects-*.tsv` points a call site of the game at a method here, by name and descriptor.
When you rename or change the signature of such a method, change the rule in the same commit. The coverage check
in `./gradlew build` fails if any rule points at something that does not exist.

## Vulkan

`gfx/vk` is experimental and not used by the game yet. Changes there are welcome but must not affect the OpenGL
path.
