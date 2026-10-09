# Stein Bridge

**Minecraft 1.8.9 on Java 25 and LWJGL 3, with a faster renderer that does not change how the game looks.**

Stein Bridge is the engine underneath Stein. It takes the player's own copy of Minecraft 1.8.9, patches it on the
player's machine and runs it on a modern stack:

- **Java 25** instead of Java 8: a modern JVM and garbage collector (ZGC).
- **LWJGL 3** instead of LWJGL 2, through a compatibility layer that implements the part of the LWJGL 2 API the game
  uses (`org.lwjgl.*` in `src/main/java`).
- **Stein GFX**, Stein's own graphics API and renderer (`dev.xavier.stein.gfx`): the game's drawing is captured and
  drawn by it in an **OpenGL 4.1+ core** context (4.6 when available), with one shader source (Vulkan-style GLSL
  compiled to SPIR-V). On a graphics card or driver without OpenGL 4.1, or with `-Dsteinbridge.gfx=false`, the game
  falls back to its own fixed-function pipeline, still with the engine's optimizations. A Vulkan backend
  (`dev.xavier.stein.gfx.vk`) is in the tree as an **experimental** work in progress: it runs the
  Stein GFX self-tests, but the game does not run on it yet. Which code serves which pipeline is in
  [docs/PIPELINES.md](docs/PIPELINES.md).
- **Invisible optimizations**: everything that makes the game faster without changing what the player sees or does
  is on by default, and only after being measured A/B and compared pixel by pixel.

Game logic and physics stay identical to vanilla. Stein Bridge works on any vanilla server.

> Stein is not an official Minecraft product. It is not approved by or associated with Mojang or Microsoft.

## How it fits together

```
Mods            HUD, Combat, ...           .steinmod files in the game's mods folder
Stein Loader    mod API, loader, menus     github.com/Stein-Project/loader
Stein Bridge    this repository            engine, renderer, hooks, patcher
Minecraft       the player's own jar       never redistributed
```

The Bridge knows nothing about mods. The patcher places hooks in the game (`patcher/redirects-mods.tsv`) that call
`dev.xavier.stein.bridge.hooks.GameHooks`. If a `HookSink` is on the classpath, it receives the events. The Stein
Loader is one. Without a sink, every hook does exactly what the game would do.

That is also how another mod loader could run on top of Stein Bridge: implement `HookSink` and put it on the
classpath.

## Nothing from Mojang is redistributed

The game jar and assets come from the player's own installation. The patched jar is derived from it, on the player's
machine, and must not be redistributed. The build downloads what it needs to compile from the original sources
(Mojang's CDN, the MCP mappings, Maven Central) into `build/`, which is never committed.

## Building

You need a **JDK 17 or newer** to start Gradle (`JAVA_HOME`). The Gradle daemon and the compiler run on **JDK 25**:
Gradle uses an installed one or downloads it (`gradle/gradle-daemon-jvm.properties`, written by
`./gradlew updateDaemonJvm --jvm-version=25`, and the foojay toolchain resolver). You also need a graphics card
with **OpenGL 4.1** or newer (for Stein GFX; older ones run the fixed-function fallback) and Minecraft 1.8.9
installed (or run `./gradlew fetchMinecraft`, which downloads it to `build/mc`; pass `-Pstein.mcDir=build/mc`
afterwards). It builds on Windows, Linux and macOS.

```sh
./gradlew build            # compiles the Bridge, the shaders, the patcher and the visual options, patches your game
                           # jar into build/patched/1.8.9-steinbridge.jar, checks coverage, preflight and tests
./gradlew runClient        # runs the game in an isolated game folder (build/gamedir)
./gradlew spotlessApply    # formats the Java code
```

The Bridge is written against the game with MCP's readable names, so the build also needs the
[sdk](https://github.com/Stein-Project/sdk) repository next to this one (`../sdk`, or `-Pstein.sdkDir=/path/to/sdk`;
it is included as a composite build): its `NamedBuild` and `OpenJar` tools produce the readable-name game jar used only
to compile, as in the Loader's build. The build then translates the compiled classes back to the game's real names
(SRG, then obfuscated, checking that every reference resolves in the game jar) and binds what the real game keeps
private through `invokedynamic` (`core.GameAccess`, `tools/AccessRewrite.java`). Dependency versions are in
`gradle/libs.versions.toml`, and downloads are checked against `gradle/verification-metadata.xml`.

To run with the Stein Loader, clone [loader](https://github.com/Stein-Project/loader) next to this repository and use
its `./gradlew runClient`.

## Repository layout

| Path | What it is |
|---|---|
| `src/main/java` | The Bridge (`dev.xavier.stein.bridge`), Stein GFX (`dev.xavier.stein.gfx`) and the LWJGL 2 compatibility layer (`org.lwjgl`) |
| `src/main/shaders` | Shader sources (Vulkan-style GLSL), compiled at build time |
| `src/launch` | The game's entry point (Java 8 target, to explain a missing Java 25 instead of crashing) |
| `src/lens` | Visual options, built against the game jar |
| `patcher/` | The bytecode patcher and its rules (`redirects-*.tsv`) |
| `scan/` | The LWJGL 2 surface the game uses and its LWJGL 3 mapping |
| `tools/` | Java build tools (patcher steps, coverage check, shader build, options list) and the bash scan scripts (need `./gradlew devLibs`) |
| `test/` | Sanity and preflight checks |

## Contributing

Issues and pull requests are welcome. Read the [contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md)
first. Contributions require signing the [Contributor License Agreement](https://github.com/Stein-Project/.github/blob/main/CLA.md).

## Licence

Stein Bridge is licensed under the [Mozilla Public License 2.0](LICENSE). Third-party notices are in [NOTICE](NOTICE).
The Stein name and logo are not covered by the licence: see the [trademark policy](TRADEMARK.md).

Created by [Breno Xavier](https://github.com/brenoxavier).
