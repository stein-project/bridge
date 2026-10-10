# Contributing to Stein Bridge

Read the [Stein contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md) first: it
covers the rules, code style and commits. This page is what is specific to the Bridge.

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
Mojang's servers (`./gradlew fetchMinecraft`, never published). The SDK is checked out next to the Bridge. With the
`SDK_DEPLOY_KEY` secret (a deploy key of a private `sdk` repository) it is cloned over SSH; without it, as in a pull
request from a fork, where secrets are empty, `actions/checkout` falls back to HTTPS with the workflow token, which
works while the `sdk` repository is public. A fork does not need to set anything.

## Patcher rules

Each line of `patcher/redirects-*.tsv` points a call site of the game at a method here, by name and descriptor.
When you rename or change the signature of such a method, change the rule in the same commit. The coverage check
in `./gradlew build` fails if any rule points at something that does not exist.

## Vulkan

`gfx/vk` is experimental and not used by the game yet. Changes there are welcome but must not affect the OpenGL
path.

## The obfuscated build (what reaches players)

The Bridge reaches players only inside the installer, and that copy is obfuscated:

```sh
./gradlew dist    # build/dist/steinbridge.jar and build/dist/rules, mapping in build/dist-mapping
```

- ProGuard 7.10.0 (a Gradle dependency, `libs.proguard`) only renames: no shrinking, no optimization. What keeps its
  name is in `bridgeKeeps` in `build.gradle.kts`: the `org.lwjgl` compatibility layer, `launch.Main`, `HookSink`,
  what mods call by name (`ModelBatch`, `GLMatrix`) and a few classes recognised by name in stack frames.
- The `-Dsteinbridge.<area>.<name>` options become codes (`tools/Codes.java`, table in `build/dist-mapping/codes.txt`);
  the patcher rules are translated to the obfuscated names and lose their comments (`tools/MapRules.java`,
  `build/dist/rules`). `dist` then patches the game with those rules and runs the coverage check and
  `tools/DistCheck.java` (every reference between our classes resolves) on the result.
- Never put anything of `build/dist-mapping` in a release or a commit (`build/` is ignored).
- Code that reaches one of our classes or members **by name** (reflection, `MethodHandles`, a string, a lambda the
  Loader writes for a Bridge interface) needs a keep in `bridgeKeeps`, or the obfuscated game breaks where the plain
  one works. A `VarHandle`/`invokeExact` descriptor must use only `Object` and primitives for our types.
- Test it in the game: in `../loader`, `./gradlew verify -Pstein.world=<world> -Pstein.dist` and
  `./gradlew runClient -Pstein.dist` (the `-D` names are translated through the codes table).

### Reading a player's stack trace

A stack trace from the obfuscated build names `dev.xavier.stein.bridge.d.d.a(SourceFile:123)`. Translate it with the
mapping of the same build (keep `build/dist-mapping` of every release you ship) and ProGuard's retrace, from the
ProGuard release zip (`bin/retrace.sh` / `bin/retrace.bat`) or the `com.guardsquare:proguard-retrace` artifact:

```sh
retrace build/dist-mapping/steinbridge.txt stacktrace.txt
```

With the Loader in the trace, use `../loader/build/dist-mapping/steinloader.txt`: it has both. An option code from a
log (`-Ds1a2...`) is looked up in `build/dist-mapping/codes.txt`.
