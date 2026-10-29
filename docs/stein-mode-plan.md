# Stein graphics mode (above Ultra): plan

Goal (the user's words, 29/10/2026): a graphics mode "Stein", above Ultra, that reaches the look of the reference
pictures (Minecraft RTX / RTX Remix screenshots kept on his desktop, folder `RT`; ideas only, nothing copied): a lake
reflecting the shore, lava lighting the Nether orange, a lighthouse's beam in the night fog, water glowing blue in a
tunnel, soft shadows in a blossoming tree. It uses ray tracing (Vulkan), it may be heavy ("not for everyone"); quality
first.

## Rules

- The player keeps their own textures and resource packs: materials come from a table of ours plus what is read from
  the player's textures; no relief (no normal maps made from the textures), only how each material takes the light
  (gloss, metal, own light, light through).
- Nothing copied: textures, code and shaders of RTX, Remix, shader packs, OptiFine, Lunar and Mojang only as ideas.
  Resource packs that ship LabPBR maps (an open format) may be read.
- Lighting without ray tracing too: Ultra and below get the lighting that works without rays.
- Off costs nothing: Lens off and the presets up to Ultra keep the performance they have now; the cost lives in Stein.
- Physics and game logic untouched; the picture only.
- Crisp: pixel art stays sharp; accumulate over frames where possible instead of blurring.

## Stages

| # | What | Seen |
|---|---|---|
| 1 | Separate the light from the drawing: in Stein, the terrain, the entities and the hand write what each pixel is (colour, face direction, material, depth); a light pass for the whole screen then traces the rays. The cutout shaders no longer trace (the RX 7600 discard bug below) | nothing new: the same picture through the new path |
| 2 | Materials: a table per block (gloss, metal, own light, light through) and per-texel masks read from the player's textures when the atlas loads (only the gems of an ore shine), into a material atlas with the same coordinates; then items, armour and mobs | a scene of many blocks, before and after |
| 3 | Our water and lava, in shaders (no drawn texture): water with waves and wind, seeing the bottom through it, colour by depth, reflection and refraction by rays, foam at the edges, under water; lava flowing, crust and glow, lighting around; falls following the current | rivers, lakes, the sea, the Nether |
| 4 | Lighting: a copy of the world around the camera on the GPU per block (solid, own light and its colour). Without RT: coloured light spread from the blocks that give light. With RT: sun and sky by rays (sharp shadows with no distance limit, in place of the shadow map), light bouncing with colour several times, kept per block face and accumulated over frames (sharp and steady), every light-giving block casting shadows | the references' light: lava, torches, caves, forest |
| 5 | Reflections by rays on everything that shines, by material (a mirror on ice and water, blurred on wood and quartz), lit by stage 4 | |
| 6 | Entities in the rays: players, mobs, items on the ground and the hand cast shadows in the rays, show in the reflections and take the bounced light | the player seen in a diamond block |
| 7 | Air and sky: beams from the lights (the lighthouse), fog under water, rain with mist, light shafts that respect the light-giving blocks | |
| 8 | The Stein preset: a Lens option above Ultra that turns ray tracing on; what it does on a GPU without ray tracing (Stein without RT, or unavailable); benchmark, tests on NVIDIA, round of bugs | |

The earlier list (wind, Nether/End, coloured shadows through glass) fits in or after these, where it belongs.

## What exists already (29/10/2026, local, no commit)

- Ray scene of the terrain (Vulkan, `rt=true` in config/stein-graphics.properties or `-Dsteinbridge.gfx.rt=true`):
  device features only when asked for and present (`SteinBackend.rayTracingRequested`, `SteinVkDevice.rayTracing`);
  `SteinVkRay` (a bottom level per terrain slice in batches, a top level per frame in three turns, retired after the
  frames that read them); `SteinRayScene` (hooks in the terrain upload/release, frame in `SteinShadows.prepare`; slices
  within 96 blocks, first vertex below 2^24).
- An acceleration-structure resource kind through the shader build (reflection), the API
  (`SteinPipeline.Builder.acceleration`, `SteinCommands.bindAccelerationStructure`, Vulkan only) and the Vulkan
  descriptors; shaders with the `RT` define are Vulkan only (GLSL 4.60, no OpenGL output).
- `terrain_lens_rt.frag`: the reflective pixels (`SteinMaterials` codes) trace a ray; the hit's vertex is rebuilt from
  the terrain buffer bound as storage (instance custom index = the slice's first vertex), lit by its lightmap; a miss
  keeps the sky. Iron and gold show the trees in front of them.
- The Lens without rays: sun shadows, sky light, ambient occlusion, volume light and motes, the Stein sky, exposure by
  place, the 16-bit scene, the material mask (gems, metals, ice, glass, polished stone).

## Notes found so far

- RX 7600: `VK_KHR_acceleration_structure`, `VK_KHR_ray_query`, `VK_KHR_ray_tracing_pipeline`, buffer device address,
  `rayQuery`; `R16G16B16A16_UNORM` is accepted as acceleration-structure vertex format here (not required by the spec:
  check `ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT` on other GPUs).
- LWJGL's `pGeometries(...)` does not set `geometryCount` (there is also `ppGeometries`): without setting it every build
  was empty and the validation layer's size check crashed inside the AMD driver. With it the layer is clean.
- RX 7600: a fragment shader with ray queries and a `discard` draws the clear texels (black glass), even with the trace
  call removed; stage 1 moves the rays out of those shaders.
- The terrain is one big buffer that grows by copying (`SteinTerrainRegion`): a bottom level keeps its own copy of the
  geometry, so growing does not invalidate it; the storage binding for hits is bound again each frame. Quads are drawn
  through the shared index buffer (two triangles each): primitive index / 2 is the quad.
- Cost of stage R2 (same session, Vulkan, Lens Ultra with reflections, short benchmark): +0.1 to +0.25 ms of GPU a
  frame with RT on (spawn 1.30 -> 1.50 ms, caves 0.76 -> 1.01, everything 2.44 -> 2.55), also where nothing reflects;
  CPU +0.035 ms. Loading a world builds ~800 bottom levels in the first seconds (~300 ms CPU a second meanwhile).
- Bench: `-Dsteinlens.config` with a Windows path; the reflections need `"reflections": true` in the Lens config.
- Validation layer: `-Dsteinbridge.gfx.vkValidation=true` (Vulkan SDK under `C:\VulkanSDK`).

## Progress (29/10/2026, local, no commit; installed in the user's Stein Client at 17:33)

- Stage 1: `SteinRayLight` — the opaque terrain draws into the game's target plus a G-buffer (`common/gbuffer.glsl`:
  albedo + metal, octahedral face + smoothness + map shadow, sky/block light + own light + through), the light pass
  (`ray_light.frag`) after the cutout layer (`ChunkArena`). The rays left the cutout shaders: glass is clear again
  and reflects the world. The eye-space point comes from the G-buffer's exact distance (`gDist`, R32F; the depth
  buffer is too coarse far away) along the pixel's direction with ndc.y = 2 uv.y - 1 (the Vulkan viewport is flipped:
  the texture's v runs with the eye's y). Getting it mirrored put the upper half of the screen inside the ground (a
  line through the middle of the screen, shadow above, too much bounced light). gDist is cleared at the first opaque
  layer: the clouds (drawn before the terrain, with depth) are not lit as terrain. The ray's start undoes the whole
  game matrix (`pointFromCamera`: first person's step back, bobbing, third person's distance), not only its turn.
- Stage 2: `SteinMaterialMap` (RGBA8 the size of the atlas, from the player's textures at stitch time via the
  `SteinOpaqueCutout.uploadMipmap` hook; rules by name, spreads ALL/BRIGHT/ORE/GLOW_BRIGHT). Reflections by
  smoothness/metal (Fresnel) and a GGX sun highlight in the light pass. Items, armour and mobs: with stage 6.
- Stage 3: our water (`common/water.glsl`, translucent layer, `terrain_water.*`, no discard): our colour
  (WATER_COLOR times the biome's), waves on the pixel grid, bottom through it (copies of the image and depth before
  it), body that grows with depth, reflection by a ray, glitter, foam (shore, falls). Our lava (`common/lava.glsl`):
  crust and flowing veins on the pixel grid. The game's water and lava textures are not used.
- Stage 4: sun shadow by a ray (the difference to the map's added through `worldLight`), bounced light
  (`ray_gi.frag`: 2 cosine rays a pixel, the hit lit by the sky/sun part of the lightmap plus its own light, settled
  over frames with reprojection from the last camera; History block; 3x3 depth-aware spread).
- Stage 5: satin reflections (smoothness 0.15..0.92) — one jittered ray a frame in `ray_gi.frag`'s second output,
  settled the same way; mirrors (>= 0.92) keep their own ray.
- Stage 8 (part): the "Stein" quality level above Ultra (only when the GPU has ray tracing on Vulkan:
  `SteinBackend.rayTracingSupported`), the terms popup (GuiYesNo, pt/en/es), `rt=true` for the next start.
- Cost (short benchmark, small window, noisy run): GPU 1.30 -> 1.92 ms spawn, 0.76 -> 1.78 caves, 2.44 -> 2.83
  everything, against Ultra with reflections.
- Next: stage 6 (entities in the rays), stage 7 (air and sky), the rest of 8 (benchmark verdict line, NVIDIA).
