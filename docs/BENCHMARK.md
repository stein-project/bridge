# Built-in benchmark

The Bridge can play a fixed scene on its own and measure every frame, so a run on one machine can be
compared with a run on another (an NVIDIA card, an Intel laptop) and with another version of Stein. It
only measures: nothing about the game's logic changes, and without the option the code does nothing.

## Running it

Add to the game's Java arguments (the Stein Client's developer options, or the launcher's "JVM
arguments"):

    -Dsteinbridge.bench=true

Start the game and wait on the title screen. After a few seconds the benchmark:

1. deletes the world `stein-benchmark` of the last run and creates it again with a fixed seed, so every
   machine generates the same land;
2. switches to the same video settings on every machine (render distance 12, Fancy, smooth lighting,
   all particles, fancy clouds, no frame limit, no VSync). The player's own settings come back at the
   end and `options.txt` is never written;
3. plays the route below, about twenty minutes (most of it the biome tour), with the camera always
   moving;
4. writes `stein-bench/stein-bench-<date>-<api>.zip` in the game folder and says so in the chat.

While it runs, **the keyboard and mouse do nothing** (the window's input never reaches the game or the
mods) and the window cannot be resized. Minimizing it or switching to another window cannot be
prevented: those frames are flagged and the part they fall in is marked in the report, so keep the game
in front. Closing the window stops the run and still writes the report, marked as stopped.

To compare the graphics APIs, run once per API: `-Dsteinbridge.gfx.api=opengl`,
`-Dsteinbridge.gfx.api=vulkan`, and `-Dsteinbridge.gfx=false` for the fixed-function pipeline.

## The route

The passes are shot in spectator mode, like a film camera (through blocks, no hand); the first-person
fight, the inventory and the menus are in creative, with the hand and the HUD.

| Part | What is on screen |
|---|---|
| `spawn_flyover` | a slow circle over the land around the spawn |
| `menus_ingame` | the game's menus over the world, one every 2 s: pause, options, video, controls, sounds, skin, language, resource packs, achievements, chat (not the server list: it would ping servers) |
| `mobs` | 304 mobs of 16 kinds, some in enchanted armour, with name tags, on a floating arena |
| `fight_far` | 400 players fighting in 200 pairs, seen from far above: circling, closing in, jumping for crits, hitting in turn with the hurt flash, a step back and crit particles; mixed armour (half enchanted), held items, name tags |
| `fight_close` | the same fight from inside, weaving between the pairs at head height |
| `fight_first_person` | walking inside the fight in first person, swinging the sword: hand, sword and HUD |
| `fight_rain` | the fight in the rain |
| `storm` | the fight in a thunderstorm, lightning striking every 0.6 s |
| `particles` | the fight inside a cloud of flame, smoke, spell, redstone and crit particles |
| `tiles` | 450 chests, 450 signs with text and a row of enchanting tables beside the fight |
| `translucent` | stained glass and ice walls with the fight behind |
| `blocks` | every block of the game in every variant (about 1,400), low over the gallery |
| `inventory` | the creative inventory over the fight, tab after tab: every item's texture |
| `everything` | fight, mobs, tiles, glass, particles, thunderstorm and lightning at once |
| `caves` | underground, through the stone and the caves |
| `flight` | fast and straight over new land: chunks generating and loading |
| `biome_*` | every overworld biome the seed has within 2,000 blocks, low over the land (the camera follows the ground), in the order of a short trip; the ones the seed does not have nearby are listed as skipped |
| `nether` | the Nether, from where the player arrives (the game's own dimension change and portal) |
| `end` | the End's island, its pillars, crystals and the dragon |
| `menus_title` | out of the world: the title screen and its menus (the game itself holds the title screen at 30 fps) |

Each part waits for its dimension and for its chunks before it is measured. The arena and the block
gallery are built during the preparation, so the server's relighting falls in no part. The players exist
only in the client's world (the same class and drawing as a player on a server, with ids no server
uses); the mobs, blocks and lightning are placed with the game's own commands in the benchmark's world.

## Validation

Every part is checked, and `summary.txt` starts with the result: PASS, WARN (the numbers are less
trustworthy) or FAIL (something did not work), with the reason.

| Check | Fails or warns when |
|---|---|
| `frames` | fewer than 20 frames were measured |
| `window` | the window was out of focus, minimized or resized (WARN) |
| `world_drawn` | fewer than 30 chunks were on screen |
| `dimension` | the world was not the part's dimension |
| `land_settled` | chunks were still loading when the measurement started (WARN) |
| `entities_drawn`, `tile_entities`, `particles` | fewer than the part puts on screen |
| `picture` | the part's picture is black, white or a single colour |
| `exceptions` | the console printed an exception during the part |
| `opengl_errors` | the OpenGL driver had errors queued at the end of the part (OpenGL only) |
| `gpu_timing` | Stein GFX measured no GPU frame (WARN) |
| `server_ticks` | the integrated server averaged under 19 ticks per second, or fell under 15 (WARN) |

## The report

| File | Contents |
|---|---|
| `summary.txt` | per part: average fps, 1% and 0.1% low, p50/p99/max frame time, GPU time; where the CPU time of a frame goes; the frame handoff |
| `summary.json` | the same and more: environment (Stein and mod versions, graphics API and GPU, window, the player's settings, Java and its arguments, every Stein option and which are diagnostics), the machine, and per part the frame statistics, every CPU zone, every counter and every GPU pass |
| `frames.csv` | one row per frame of the whole run: time, part, frame time, each CPU zone, draws, passes, early submissions, Vulkan render breaks, ring waits, texture and chunk uploads, chunks/entities/tile entities/particles, render-thread allocation, garbage collections, focus, size |
| `samples.csv` | once a second: CPU per thread group (render, integrated server, chunk builders, pose workers, network, the JVM's own), heap, collections, JIT, integrated server tick, each mod's time (from the Loader), dimension, chunks loaded on the client and on the server, chunks waiting to be built, entities on each side, GPU clock/power/temperature/load (NVIDIA), processor clock and the game's 3D load (Windows) |
| `gpu.csv` | each part's GPU time per pass |
| `shots/` | each part's picture, taken right after it was measured |
| `console.log` | everything the game printed during the run (the Bridge's log, the mods', every stack trace) |
| `events.log` | what the benchmark did, and when |
| `game/` | `options.txt`, the graphics choice and the game log |

Paths under the user's folder are written as `~`. The machine description comes from read-only
registry and `powercfg` queries and from Windows' power status; no script is run.

### CPU zones

The game's phases are timed around their call sites (patcher rules at the top of `redirects-opt.tsv`
and the bottom of `redirects-mods.tsv`), so each zone includes the Stein hooks around the call:
`tick` (`mods_tick`), `render` > `world` > `lightmap`, `terrain_setup`, `chunk_upload`, `terrain`,
`sky`, `clouds`, `entities`, `particles`, `weather`, `mods_world`, `hand`; `render` > `hud`
(`mods_hud`), `screen` (`mods_screen`). Then the handoff: `submit` (the recorded OpenGL frame going to
the driver), `swap` (with Vulkan, the submission and the presentation), `events`, `await_gpu`,
`fps_limiter`, `idle_limiter`, and `bench` (the benchmark's own work). The `mods_*` zones are every hook
listener: the Loader's mods and the Lens.

### What it costs and what it cannot see

- Per frame: about 30 `System.nanoTime`, a few counters and one row copied into a preallocated block. A
  low-priority thread writes the CSV. The GPU time per pass (two timestamps per pass) is on during the
  run; `-Dsteinbridge.bench.gpu=false` leaves it off, to measure what it costs.
- The pause of a collection is reported with millisecond resolution (ZGC's pauses are below that and
  count as 0; the number of collections is still there).
- The processor's temperature and the GPU clock of AMD and Intel cards are not readable without a
  driver of our own: use HWiNFO alongside if they matter.
- GPU time per pass exists only with Stein GFX; the fixed-function pipeline reports `-`.

## Options

`-Dsteinbridge.bench.settings=player` (the player's own settings), `bench.scale=0.25` (shorter parts),
`bench.repeat=N`, `bench.only=fight_far,flight`, `bench.maxBiomes=N` (a shorter biome tour), `bench.size=1920x1080` (the window's size for the run),
`bench.quit=true` (close the game at the end), `bench.startAtSec=N`, `bench.external=false` (no
nvidia-smi, typeperf, reg or powercfg), `bench.dir=path`. All of them are in [OPTIONS.md](OPTIONS.md).
