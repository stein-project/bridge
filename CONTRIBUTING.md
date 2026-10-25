# Contributing to Stein Bridge

Read the [Stein contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md) first: it
covers the rules, code style, commits and the [CLA](https://github.com/Stein-Project/.github/blob/main/CLA.md) your first
pull request asks you to sign. This page is what is specific to the Bridge.

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
| Rendering (Stein GFX) | `gfx/` (API and renderer), `gfx/gl/` (OpenGL backend), `gfx/vk/` (Vulkan backend), `gfx/game/` (the game's OpenGL objects on the API, for Vulkan), shaders in `src/main/shaders` |
| An OpenGL call the game makes by itself | a method of `gfx/game/SteinGameGl` that calls OpenGL as before and emulates it with Vulkan, plus its rule in `patcher/redirects-opt.tsv` (the one exception to that file's kill switch and check mode: on OpenGL it changes nothing) |
| The LWJGL 2 compatibility layer | `org.lwjgl.*`: it must match the LWJGL 2 API exactly as the game calls it |
| A diagnostic scene | `bridge/diag/`, read from a `steinbridge.diag.*` option; it costs nothing unless one is set |
| A new `-D` option | a `static final` field in the package's `*Options` class, read with `core.Opt`, with a Javadoc starting `{@code -Dname=...}`; then run `./gradlew options` (`./gradlew build` checks `docs/OPTIONS.md` is up to date) |
| A log line | `core.Log.info("tag", ...)`, never `System.out`; `SteinLog` is only the player's topic log |

## Writing code that touches the game

Game code in the Bridge is plain typed Java with MCP names: `Minecraft.getMinecraft().thePlayer`,
`entity.getEyeHeight()`, `GuiUtilRenderComponents.splitText(...)`, imported from `net.minecraft.*`. No
`MethodHandle`, `Field` or string name lookups for game members: the build compiles against the game with
readable names (every member opened, only to compile), translates to the real names and fails the build if a
reference does not resolve in the game jar. A member the real game keeps private, package-private or protected
is written as if it were public; the build turns the access into an `invokedynamic` bound once by `core.GameAccess`
(as fast as a direct access after linking). A closed constructor cannot be bound and fails the build, and so does
a write to a final game field (the readable jar opens finals too; the only one written on purpose, `Minecraft.session`
for the Loader's accounts, is listed in `tools/AccessRewrite.java`). A field access is resolved with its own kind
(static or instance): the obfuscated game has static and instance fields of the same name and type along one
hierarchy, such as the static constants of `BlockPos` and the inherited `Vec3i` coordinates.

Look names up in the MCP CSVs in `build/tools/mcp-stable-22` and `build/tools/joined-1.8.9.srg` (`./gradlew extractMcp`
fills them), or with `tools/mcp-name.sh` (readable name to SRG name).

Things that are not game members stay reflective: fields the patcher adds to game classes (they do not exist in the
readable jar, see `engine/SignText`), the Bridge's own classes, LWJGL and objects passed as `Object` on purpose. The
entry points a patcher rule targets keep their exact descriptors (`Object` parameters stay `Object`; cast inside).
The Bridge needs `../sdk` to build (see the README).

Exceptions, where typed Java cannot express the access; each site says why in a one-line comment:

- nested classes the game keeps closed and that therefore cannot be named, even in the readable jar: the
  `GlStateManager` state objects (`gfx/SteinGameState`), `RenderGlobal.ContainerLocalRenderInformation`
  (`engine/ChunkGraph`) and the compiler's synthetic switch map `LayerArmorBase$1` (`gfx/SteinPlayerPoseGame`);
- `super` calls made from outside the subclass (`invokespecial` through `MethodHandles.findSpecial`):
  `engine/AsyncSkins`, `engine/OldAnimations`, `gfx/SteinPlayerPoseGame`;
- volatile access to a plain game field (`VarHandle`): `engine/DataWatchers`;
- scans of the declared methods of arbitrary game subclasses by their real name: `engine/LightCache`,
  `gfx/SteinLabelKinds`;
- the visual options (`src/lens`), which compile like a mod against the SRG jar, where private members stay
  closed: their few private fields are reached by reflection under the SRG name, which the mod remapper translates.

Output goes through `core.Log` (standard output for information, error output for warnings and errors); `Log`
is the only class that writes to `System.out` and `System.err` (apart from GLFW's own error callback, which
prints to the error output).

## GPU tests and benches

These open a window (or a Vulkan device) and need a graphics card, so they are not part of `./gradlew build` or
CI. Each runs one program of `test/java` against the Bridge jar and LWJGL 3 with its natives:

```sh
./gradlew gpuFrameTest      # Stein GFX API tests and the recorded frame, hidden OpenGL 4.6 core window
./gradlew gpuTimingTest     # GPU pass timing with fake passes
./gradlew gpuFenceBench -Pstein.args="fence3"   # the gap a fence leaves before a pass
./gradlew vkSelfTest        # the API tests on the experimental Vulkan backend, offscreen
```

`-Pstein.args="..."` passes the program's arguments and `-Pstein.jvmArgs="-D..."` extra JVM flags (for example
`-Pstein.jvmArgs=-Dsteinbridge.gfx.recordFrame=false`, or `-Dsteinbridge.gfx.vkValidation=true` with the Vulkan
SDK installed). Each exits with 0 when it passed.

## Continuous integration

`.github/workflows/build.yml` runs `./gradlew build` on every pull request and push to `main`, with Minecraft downloaded from
Mojang's servers (`./gradlew fetchMinecraft`, never published). The public SDK is checked out next to the Bridge.
A fork does not need to set anything.

## Patcher rules

Each line of `patcher/redirects-*.tsv` points a call site of the game at a method here, by name and descriptor.
When you rename or change the signature of such a method, change the rule in the same commit. The coverage check
in `./gradlew build` fails if any rule points at something that does not exist. The Loader's ModPatch applies
`redirects-mc`, `-opt` and `-modgl` to mods too: a change there changes what every mod runs.

## Vulkan

`gfx/vk` is experimental and not used by the game yet. Changes there are welcome but must not affect the OpenGL
path.

## What reaches players

Players get `build/libs/steinbridge.jar` as it is built, inside the installer, together with the patcher rules (the
installer patches the player's own game jar with them). The rules also travel inside the jar (`steinbridge/rules/`):
the Loader's ModPatch reads them from there when it adapts a mod, so a Loader always applies the rules of the Bridge it
runs on. The jar is reproducible: same sources, same bytes.
