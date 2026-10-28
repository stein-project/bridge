# Render pipelines

The Bridge has two ways to draw the game. This page records which code belongs to which, so a change
to rendering does not break the one you are not looking at.

- **Stein GFX** (the default): the game's drawing is captured and drawn by `dev.xavier.stein.gfx`
  in an OpenGL 4.1+ core context (4.6 when available). The driver has no fixed-function pipeline
  there, so nothing may draw through it, not even by mistake. Stein GFX draws with OpenGL (the
  default) or, when the player picks it in the video options, with Vulkan (see below).
- **Fixed-function pipeline** (the fallback): the game draws with its own fixed-function calls in a
  compatibility context, still with the engine's optimizations.

Each class says which one it serves in the last paragraph of its Javadoc:
`Pipeline: Stein GFX only.`, `Pipeline: fixed-function only (the fallback).` or
`Pipeline: both — <the difference>.` Engine classes that never touch drawing have no such line.

## How the pipeline is chosen

1. `org.lwjgl.opengl.Display.createWindow` asks for a core context (4.6, then 4.1) when
   `-Dsteinbridge.gfx` is on (`CoreOptions.GFX`, default true). If the driver gives neither, or the
   option is `false`, it creates the compatibility context the driver offers and logs
   `no OpenGL 4.1 core context: drawing with the fixed-function pipeline`. The result is
   `Win.coreContext`.
2. `SteinGfxOptions.ENABLED = CoreOptions.GFX && Win.coreContext` is read once, when the class
   loads (after the window exists). It is the single switch: `SteinGfxOptions.CORE`,
   `SteinCoreGl.CORE` and `SteinEntityDeferred.REQUESTED` follow it, and
   `SteinGfxOptions.feature(name, default)` returns false without it, so every
   `-Dsteinbridge.gfx.<feature>` key (lazyState, ownPose, textCache...) is off in the fallback.
3. F3 shows the result, from `SteinGfxOptions.describe()`: `Stein GFX, OpenGL 4.6 core` or
   `Fixed-function pipeline, OpenGL 4.x`.

The choice is fixed for the whole run; there is no in-game switch.

## Stein GFX only

These classes do nothing, or are never reached, in the fixed-function pipeline. Where the game's
code calls into them through a patcher rule, the entry point is a no-op or forwards to the original
call in the fallback.

### API and OpenGL backend

| Class | What it does |
|---|---|
| `SteinDevice`, `SteinCaps`, `SteinFormat`, `SteinFence` | Entry point of the graphics API, what the device can do, vertex/texture formats, GPU fence |
| `SteinBuffer`, `SteinTexture`, `SteinSampler`, `SteinRenderTarget`, `SteinNativeBuffer` | GPU resources of the API and their native scratch memory |
| `SteinRenderPass`, `SteinCommands`, `SteinPipeline`, `SteinPipelineCache`, `SteinShader`, `SteinVertexLayout` | A pass, its commands, closed draw state, shader stages, vertex layout |
| `gl.SteinGlDevice` | The OpenGL backend of the API |
| `gl.SteinGlBuffer`, `gl.SteinGlTexture`, `gl.SteinGlSampler`, `gl.SteinGlRenderTarget`, `gl.SteinGlVao`, `gl.SteinGlFence`, `gl.SteinGlFenceBatch`, `gl.SteinGlFormats` | OpenGL objects and format translation |
| `gl.SteinGlPipeline`, `gl.SteinGlCommands` | Pipeline on OpenGL; one immediate OpenGL pass |
| `gl.SteinGlBindings`, `gl.SteinGlBindPoints`, `gl.SteinGlPassBindings`, `gl.SteinGlPassFixed`, `gl.SteinGlPassRules`, `gl.SteinGlLimits`, `gl.SteinGlGame` | Binding and state tracking around a pass, shared limits and rules, the game as seen by the backend |
| `gl.SteinGlFrame`, `gl.SteinGlRecorder`, `gl.SteinGlReplay`, `gl.SteinGlOpList`, `gl.SteinGlOps` | The recorded frame: passes become command lists, replayed at the end of the frame or at a sync point (needs GL 4.5) |

### Renderer (what the game draws, on the API)

| Class | What it does |
|---|---|
| `SteinTerrain`, `SteinTerrainLayers`, `SteinTerrainPipelines`, `SteinTerrainRegion`, `SteinTerrainSlice`, `SteinChunkFade` | The world: chunk geometry in region buffers, one indirect draw per region and layer, fade-in |
| `SteinSky` | Sky, recorded in strict order with fixed VBOs |
| `SteinParticles` | Base drawing of a particle |
| `SteinEntityDeferred`, `SteinDeferredFrame`, `SteinDeferredGroups`, `SteinDeferredKeys`, `SteinDeferredLights`, `SteinDeferredResources`, `SteinDeferredSessions`, `SteinDeferredStats`, `SteinInstanceWriter` | Deferred entities: the entity pass, HUD and screens recorded as data and drawn per state group |
| `SteinDeferredModel`, `SteinDeferredDynamic`, `SteinDeferredNames`, `SteinDeferredPose`, `SteinDeferredRetained`, `SteinDeferredText` | Recording of model parts, dynamic geometry, name tags, poses, retained tiles and resident text |
| `SteinStaticGeometry`, `SteinCaptureFormat`, `SteinVertexConverter`, `SteinTexGen`, `SteinTextureArrays`, `SteinTextureFilters` | Tessellator capture support: fixed geometry on the GPU, vertex conversion, texgen on the CPU, texture arrays, filter bookkeeping |
| `SteinPlayerPose`, `SteinPlayerPoseCollect`, `SteinPlayerPoseCompute`, `SteinPlayerPoseModels`, `SteinPlayerPoseGame`, `SteinPlayerPoseJobs`, `SteinPlayerPoseEndState`, `SteinPoseWorkers`, `SteinPoseGpu`, `SteinHeldItem` | Players' pose computed by us (CPU threads or GPU) and held items |
| `SteinTileRetain`, `SteinTileEntry`, `SteinTileKey`, `SteinTileState`, `SteinTileGame`, `SteinCameraCorrection` | Retained tile entities re-emitted without running their renderers |
| `SteinNameLabels`, `SteinLabelContext`, `SteinLabelEmit`, `SteinLabelKinds`, `SteinLabelSlots`, `SteinLabelTail`, `SteinLabelTemplates`, `SteinLabelStats`, `SteinLabelCheck` | Name tags built directly, without the game's surrounding code |
| `SteinTextCache`, `SteinTextRecorder`, `SteinTextReplay`, `SteinTextGpu`, `SteinTextBackgrounds`, `SteinOwnText` | Cached text (names, signs, the mods' HUD) and names on the GPU |
| `SteinGameState` | The game's drawing state read from the GlStateManager cache without asking the driver |

### Diagnostics and tests

`SteinGfxSelfTest`, `SteinGfxApiTest`, `SteinPassTiming`, `SteinSegmentCheck`, `SteinPlayerPoseCheck`,
`SteinPlayerPoseGpuCheck`, `SteinTileCheck`, `SteinTextCacheCheck`, `SteinTextCacheStats`,
`SteinTextGpuStats`, `gl.SteinGlAudit`, `gl.SteinGlFrameCheck`,
`gl.SteinGlFrameDump`, `gl.SteinGlFrameStats`, `gl.SteinGlGpuTiming`, `gl.SteinGlOpTiming`,
`gl.SteinGlPassCheck`, `gl.SteinGlPassTiming`, `gl.SteinGlTimestampRing`, `gl.SteinGlOptions`:
self-tests, per-pass timing and the `-Dsteinbridge.gfx.*Check` verification modes.

### Vulkan

Stein GFX with the second backend of the same device API, chosen by `SteinBackend` (the "API" row of the
video options, saved in `config/stein-graphics.properties`; `-Dsteinbridge.gfx.api` for a session). OpenGL
stays the default; when Vulkan does not start, or the last start with it died in its first seconds, the game
falls back to OpenGL and the video options say why.

- The backend, `gfx/vk`: `SteinVkDevice` (with `SteinVkInstance`, `SteinVkProbe`, `SteinVkMemory`,
  `SteinVkDefaults`, `SteinVkStaging`, `SteinVkDescriptors`, `SteinVkTransfers`, `SteinVkPipelineCacheFile`),
  `SteinVkBuffer`, `SteinVkTexture`, `SteinVkSampler`, `SteinVkPipeline`, `SteinVkRenderTarget`,
  `SteinVkCommands`, `SteinVkFence`, `SteinVkBarriers`, `SteinVkFormats`, `SteinVkOptions`, `SteinVkDebug`.
- The window, `vk.SteinVkWindow` and `vk.SteinVkSwapchain`: no OpenGL context in the game's window (a hidden
  one absorbs the OpenGL calls of mods that call it directly), the frame presented at `Display.update`.
- The game's OpenGL objects on the API, `gfx/game`: `SteinGameGl` (the entry points), `SteinGameTextures`,
  `SteinGameTexture`, `SteinGameSamplers`, `SteinGameFramebuffers` (with the window's framebuffer 0). On
  OpenGL every entry point is the original call.
- Classes shared with OpenGL take a `SteinGfxOptions.VULKAN` branch where they would reach the driver
  (`SteinCoreGl`, `SteinLazyGl`, `SteinGlTrack`, `GLCompat`, `CloudCache`, `FontBatch`, `SteinTextureArrays`,
  `SteinTextureFilters`, `GLMatrix`, `GLContext`, `Display`).

## Fixed-function pipeline only

No class belongs to the fallback alone: it is the game's own pipeline plus the engine's
optimizations, and those classes are shared. What exists only there is a path inside a shared class:

- `ChunkArena` regions (multi-draw per region and layer, GL 4.4), when `SteinGfxOptions.ENABLED` is
  false.
- `ModelBatch`'s own batch of model parts (mapped buffer, one draw per run of parts); in a core
  context `ready()` is false and the part goes through the recorded display list and the instancer.
- `GLMatrix`'s lazy `glLoadMatrixf` to the driver, and its `-Dsteinbridge.opt.vanillaMatrix` and
  `checkMatrix` modes (`CORE` turns them off).
- `FontBatch`'s direct `glDrawArrays` of each text batch, `CloudCache`'s `drawVbo`,
  `ItemModelCache`'s `glCallList` of real display lists.
- `SteinModelInstancer` with `-Dsteinbridge.gfx.instancedModels=true` (experimental, GL 4.4).

## Both pipelines

| Class | In the fixed-function pipeline | In Stein GFX |
|---|---|---|
| `org.lwjgl.opengl.Display` | Creates the compatibility context; swaps buffers | Creates the core context (4.6, then 4.1); also submits the recorded frame at the end of the frame |
| `SteinGfxOptions` | Every feature key reads false; `describe()` says `Fixed-function pipeline` | The single switch (`ENABLED`/`CORE`) and the `-Dsteinbridge.gfx.*` keys |
| `SteinCoreGl` | Passes each fixed-function call to the driver | Tracks it in Java or drops it (the driver has no such function) |
| `GLMatrix` | Matrix stacks in Java; sends the finished matrix when something draws | Same stacks; Stein GFX reads them, nothing is sent |
| `ChunkArena` | Terrain per region (GL 4.4) | Hooks hand the geometry to `SteinTerrain` |
| `ModelBatch` | Draws the model parts from its own mapped buffer | Only captures their geometry for the instancer |
| `FontBatch` | One `glDrawArrays` per text batch | Batches feed `SteinTextCache` and the recorded passes |
| `CloudCache` | Cached clouds drawn from a buffer | A copy kept in the fixed geometry, drawn by the API |
| `ItemModelCache` | Calls the recorded display list | Records the geometry and draws it through the API |
| `DirectRender` | Draws the world and GUI straight to the window | Same, and also drops the post-processing shaders and entity outlines |
| `LightCache` | Cached entity light | Also the entity brightness shortcut |
| `SteinTessCapture` | `Tessellator.draw` runs as the game does | Captures the draw as data |
| `SteinLazyGl` | Always makes the original call | Defers state changes during a recorded pass |
| `SteinGlTrack` | Forwards the game's call to the driver | Also tracks the state the recorded pass needs |
| `SteinEntityInstancer` | Forwards to the game's `ModelRenderer.render` | Draws model parts per instance |
| `SteinModelInstancer` | Only with `-Dsteinbridge.gfx.instancedModels=true` | The default per-instance draw of model parts |
| `gl.SteinGlDebug` | Driver debug output (`-Dsteinbridge.gfx.glDebug`) | Same |

## Pipeline-independent engine classes

These have no Pipeline line because they do not change how anything is drawn: chunk building and
search (`Chunk*` except `ChunkArena`), network and threads (`Net`, `NetAddress`, `PacketLog`,
`Watchdog`, `ServerWatch`, `TaskPost`, `AsyncSkins`, `SoundGuard`, `ClientSettings`), game-logic caches
(`ActiveChunks`, `BiomeLock`, `BlockMath`, `DataWatchers`, `EnchCache`, `EntityFlags`, `FontIndex`,
`NameCache`, `SignText`, `ArmorTextures`, `CraftingInit`, `AngleTable`, `Particles`, `Rain`), state and
option plumbing (`EngineOptions`, `CheckReport`, `FrameTime`, `KeyBindings`, `Mat4`, `Obf`, `Sprint`,
`TextHook`, `Visuals`, `OldAnimations`). The built-in benchmark (`bridge/bench`, [BENCHMARK.md](BENCHMARK.md)) only
measures: its counters sit in both backends and the fixed pipeline, and do nothing without `-Dsteinbridge.bench`. `HudBatch` and `ArmorGlint` batch the game's own calls
through the GlStateManager and the Tessellator, which both pipelines handle; `./gradlew verify` (in the Loader) only
runs the `HudBatch` check in the fixed run.

## Rules for changes

- **Any rendering change must work in both pipelines.** If you touch a class in the "Both" table, run
  the fallback too. If your change only makes sense in one, gate it with `SteinGfxOptions.ENABLED`
  (or `SteinCoreGl.CORE`) and make the other pipeline keep doing exactly what it did.
- **Never call fixed-function GL directly from Stein GFX code.** In the core context LWJGL does not
  load those functions and calling one aborts the JVM. Go through `SteinCoreGl`.
- **Verify both.** Build the Loader next to this repository and run `./gradlew verify -Pstein.world=<world>` in the loader.
  It plays the world four times: `gfx` (Stein GFX with every engine and Stein GFX check), `gpu`
  (the GPU pose against the CPU one), `vulkan` (Stein GFX on Vulkan, with the checks that do not read the
  OpenGL driver and without the hidden OpenGL context) and `fixed` (`-Dsteinbridge.gfx=false`, with the
  checks of that pipeline only: `checkMatrix`, `checkHudBatch`). A change is not done until all four pass.
- **No OpenGL call outside the backend and `SteinGameGl`.** With Vulkan there is no OpenGL context; a
  call that reaches LWJGL's OpenGL there aborts the JVM (the `vulkan` run of verify finds it).
- **Keep the Pipeline line in the class Javadoc up to date**, and this page with it, when a class
  starts or stops serving a pipeline.
- **Patcher rules apply to both pipelines**: the game is patched once and the class a rule points to
  decides at run time. A rule that calls a Stein GFX class must be harmless when
  `SteinGfxOptions.ENABLED` is false. By file (`patcher/redirects-*.tsv`):
  - `redirects-opt.tsv`: the engine optimizations. Rules to `GLMatrix`, `SteinCoreGl`, `FontBatch`,
    `ItemModelCache`, `CloudCache`, `LightCache` and `ChunkArena` serve both; rules to
    `SteinEntityDeferred`, `SteinLazyGl`, `SteinGlTrack`, `SteinTessCapture`, `SteinPlayerPose`,
    `SteinSky`, `SteinParticles`, `SteinTextCache`, `SteinGameState`, `SteinTextureArrays` serve
    Stein GFX (they forward to the original call, or do nothing, in the fallback); the `Chunk*`,
    `DataWatchers`, `NameCache` and similar rules are pipeline-independent. The rules to
    `gfx/game/SteinGameGl` (the game's remaining OpenGL calls: textures, framebuffers, buffers, shaders,
    queries) are not optimizations: on OpenGL each is the original call, and on Vulkan the emulation.
  - `redirects-batch.tsv` (generated by `tools/gen-batch-rules.sh`): the flush/restore hooks before
    every game OpenGL call, and the capture rules for model-part lists, serve the fixed pipeline's
    `ModelBatch` (they are inert in a core context); the `bct.a` rule to `SteinEntityInstancer`
    serves Stein GFX.
  - `redirects-modgl.tsv`: mods that call GL11 directly. Matrix calls go to `GLMatrix` (both);
    fixed-function calls (`glBegin`, `glEnd`...) go to `SteinCoreGl`, which drops them in the core
    context so a mod cannot abort the JVM.
  - `redirects-mods.tsv`: events for mods and the visual options (`GameHooks`, `Visuals`,
    `OldAnimations`), independent of the pipeline; `DirectRender` serves both and the
    `SteinEntityDeferred` HUD/screen hooks serve Stein GFX.
  - `redirects-mc.tsv` and `redirects-al.tsv`: compatibility (shader source, client brand, OpenAL);
    not render pipeline.
