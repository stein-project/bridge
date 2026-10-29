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

### Every graphics mode the machine has

At the start the benchmark finds out what the machine can run: the fixed-function pipeline always;
Stein GFX on OpenGL when the driver gives a 4.1 core context (asked with a hidden window, closed at
once); Stein GFX on Vulkan when there is a Vulkan 1.2 device with dynamic rendering (asked of a
Vulkan instance made only for that). The window is created for one API, so each mode is a run of its
own: when a run ends, the game reopens itself with the same Java, arguments and folder in the next
mode, and the last run joins everything into one zip, `stein-bench-<session>.zip`, with each mode's
report in its own folder and `compare.txt`/`compare.json` side by side (average fps, 1% low, p99 and
GPU time per part and mode). A mode that does not come up (Vulkan falling back to OpenGL, for one) is
noted instead of measured twice. `-Dsteinbridge.bench.modes=current` runs only the mode the game
started in; `-Dsteinbridge.bench.modes=vulkan,opengl` picks them. Running every mode takes three times
as long; `bench.scale` and `bench.only` shorten each run.

The game reopens itself only when it was started through the Bridge's launch class (the installer's
profile and the Stein Client do this); otherwise the report says which modes are left to run by hand.

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
| `blocks` | every block of the game in every variant (about 1,400): the camera goes down the gallery's rows, looking at the rows ahead |
| `inventory` | the creative inventory over the fight, tab after tab: every item's texture |
| `everything` | fight, mobs, tiles, glass, particles, thunderstorm and lightning at once |
| `caves` | underground, through the stone and the caves |
| `flight` | fast and straight over new land: chunks generating and loading |
| `biome_*` | every overworld biome the seed has within 2,000 blocks, low over the land, in the order of a short trip; the ones the seed does not have nearby are listed as skipped. Each path is planned on the integrated server from the world itself: the starts are the points of a 16-block grid within 256 blocks of the place found that really are in the biome (nearest first), 16 directions each, at the fastest of 0.4, 0.2, 0.1 or 0.05 blocks per tick where the camera stays over the biome at every measured tick (checked about every block; a narrow biome such as a river or a beach gets a slower, shorter glide), the ground ahead most in the biome breaking ties, and the height of every tick from the server's own land (its chunks generated first), so no path depends on how fast the client gets its chunks |
| `nether` | the Nether, from where the player arrives (the game's own dimension change and portal) |
| `end` | the End's island, its pillars, crystals and the dragon |
| `menus_title` | out of the world: the title screen and its menus, measured without the game's 30 fps cap on menus (the other parts have no frame limit either) |

Each part waits for its dimension and for its chunks before it is measured: every chunk within the
render distance (less two) around the camera on the client, and a second with nothing left to build or
upload. The camera follows the part's scene time, not the ticks: it moves through the warm-up, holds
still while the part waits for its land and for the server, and moves on while measured, so every mode
films the same path from the same point (the fight, the lightning, the menus and the creative tabs follow
the same time). A start far from where the camera is goes through the integrated server (see below).

The arena and the block gallery are built during the preparation, on glass floors (glass lets the sky
light through, so the 160 x 160 floor costs the server no relighting; the stone one kept it busy for
about a minute). Their chunks are loaded first, the gallery goes in a hundred-odd blocks per server tick
and the biome search one biome per server task, so the server is never stuck in one long task. The
players exist only in the client's world (the same class and drawing as a player on a server, with ids
no server uses); the mobs, blocks and lightning are placed with the game's own commands in the
benchmark's world, run by a sender that prints nothing: no chat, no log line (on one machine the server
thread spent 5 s writing one command's line to a console nobody read).

### Far moves go through the server

When the client jumps far on its own, 1.8.9's integrated server drops the old area's chunks while
handling the move and unloads them at the start of the same tick, before the player is filed in its new
chunk: the unload takes the player out of the world's entity list, nothing ticks it any more, no chunk
is sent, and the part films a void. So a biome part, and any part that starts far from the camera, moves
the player on the server's thread: new place, new area loaded, player filed in its new chunk, then the
client told where it is (the packet a `/tp` sends). The part waits until the client is there.

## Validation

Every part is checked, and `summary.txt` starts with the result: PASS, WARN (the numbers are less
trustworthy) or FAIL (something did not work), with the reason.

| Check | Fails or warns when |
|---|---|
| `frames` | fewer than 20 frames were measured |
| `window` | the window was out of focus, minimized or resized (WARN); in a run asked to be in the background (`-Dsteinbridge.diag.background=true`, the summary says so in a note) the window is minimized on purpose, and a part warns only when the window was restored or resized during it |
| `world_drawn` | fewer than 30 chunks were on screen |
| `dimension` | the world was not the part's dimension |
| `stopped` | the part was ended early because what it films was not there: the integrated server stopped ticking the player (3 s of its ticks), no chunk on the client for 10 s, or a biome part's camera not over its biome when the measurement was about to start. The part fails at once instead of measuring a void, and the reason is in the summary and in `events.log` |
| `land_settled` | the land around the camera had not all arrived, or was still being built, when the measurement started (WARN) |
| `biome` | a biome part's camera, sampled every 10 ticks, was over its biome less than 80% of the time (WARN) or less than half (FAIL) |
| `quiet_machine` | other processes used more than 15% of the machine's processor on average while the part was measured (WARN): the part is marked `noisy` in the summary and in `compare.txt` |
| `entities_drawn`, `tile_entities`, `particles` | fewer than the part puts on screen |
| `picture` | the part's picture is black, white or a single colour |
| `exceptions` | the console printed an exception during the part |
| `opengl_errors` | the OpenGL driver had errors queued at the end of the part (OpenGL only) |
| `gpu_timing` | Stein GFX measured no GPU frame (WARN) |
| `server_ticks` | the integrated server averaged under 19 ticks per second, or fell under 15 (WARN) |

## The report

| File | Contents |
|---|---|
| `summary.txt` | how long the setup took (world open, preparation); per part: average fps, 1% and 0.1% low, p50/p99/max frame time, GPU time, and its status when it is not PASS, `noisy` and why it was stopped; where the CPU time of a frame goes; the frame handoff |
| `summary.json` | the same and more: environment (Stein and mod versions, graphics API and GPU, window, the player's settings, Java and its arguments, every Stein option and which are diagnostics), the machine, and per part the frame statistics, every CPU zone, every counter and every GPU pass |
| `frames.csv` | one row per frame of the whole run: time, part, frame time, each CPU zone, draws, passes, early submissions, Vulkan render breaks, ring waits, texture and chunk uploads, chunks/entities/tile entities/particles, render-thread allocation, garbage collections, focus, size |
| `samples.csv` | once a second: CPU per thread group (render, integrated server, chunk builders, pose workers, network, the JVM's own), heap, collections, JIT, integrated server tick, each mod's time (from the Loader), dimension, chunks loaded on the client and on the server, chunks waiting to be built, entities on each side, GPU clock/power/temperature/load (NVIDIA), processor clock and the game's 3D load (Windows), the processor the other processes used (the machine's minus the game's), how far the server's player ticked in that second (about 20 while the server keeps it in its world, 0 once it fell out) and whether the server's world has it in its entity list (asked on the server's thread) |
| `gpu.csv` | each part's GPU time per pass |
| `shots/` | each part's picture, taken right after it was measured |
| `console.log` | everything the game printed during the run (the Bridge's log, the mods', every stack trace) |
| `recording.jfr` | Java's flight recording of the whole run with the "profile" settings: method samples, allocation samples, collections, compilations, locks, I/O, threads, and Stein's frame events (open it in JDK Mission Control; `-Dsteinbridge.bench.jfr=false` leaves it out) |
| `events.log` | what the benchmark did, and when |
| `game/` | `options.txt`, the graphics choice and the game log |

Paths under the user's folder are written as `~`. The machine description comes from read-only
registry and `powercfg` queries and from Windows' power status; no script is run.

### CPU zones

The game's phases are timed around their call sites (patcher rules at the top of `redirects-opt.tsv`
and the bottom of `redirects-mods.tsv`), so each zone includes the Stein hooks around the call:
`tick` (`mods_tick`, `tick_textures` — the animated textures, `tick_entities` — every entity and tile
entity of the client's world, `tick_world` — the client world's own tick, `tick_particles`, and
`tick_other`, the rest of the tick), `frame_start` (the game loop clearing and binding the main framebuffer),
`render` > `world` > `lightmap`, `terrain_setup`, `chunk_upload`, `terrain`, `sky`, `clouds`,
`entities`, `particles`, `weather`, `mods_world`, `hand`; `render` > `hud` (`mods_hud`), `screen`
(`mods_screen`); `blit` (the main framebuffer drawn to the window). Then the handoff: `submit` (the
recorded OpenGL frame going to the driver, and with both APIs the engine's end of frame, the GPU pass
timer's reads included), `swap` (with Vulkan, the submission and the
presentation), `events`, `await_gpu`, `fps_limiter`, `idle_limiter`, and `bench` (the benchmark's own
work). The `mods_*` zones are every hook listener: the Loader's mods and the Lens. The summary's
handoff table lists `frame_start` and `blit` too, since they sit outside `render`.

### What it costs and what it cannot see

- Per frame: about 30 `System.nanoTime`, a few counters and one row copied into a preallocated block. A
  low-priority thread writes the CSV. The GPU time per pass (two timestamps per pass) is on during the
  run, in one frame out of 16 (`-Dsteinbridge.gfx.gpuPassTiming.every`): those frames are marked
  `measured` 2 in `frames.csv` and left out of the frame statistics, since writing the timestamps of
  every pass cost Vulkan on an AMD card a fifth of its fps and half its 1% low in heavy scenes;
  `-Dsteinbridge.bench.gpu=false` leaves it off, to measure what it costs. Its results are read
  once per frame: on Vulkan one `vkGetQueryPoolResults` over the frame's whole range of queries and
  one reset of that range (two of each when the ring wraps); on OpenGL one availability query per
  frame, then one result call per timestamp (`GL_QUERY_RESULT_NO_WAIT` with GL 4.4, so it never
  waits).
- The pause of a collection is reported with millisecond resolution (ZGC's pauses are below that and
  count as 0; the number of collections is still there).
- The processor's temperature and the GPU clock of AMD and Intel cards are not readable without a
  driver of our own: use HWiNFO alongside if they matter.
- GPU time per pass exists only with Stein GFX; the fixed-function pipeline reports `-`.

### Comparing two runs

The session zip's `compare.txt` starts with each mode's setup time and marks each cell F (failed), W
(warned) or N (noisy); the mean over the parts leaves the failed ones out.

    ./gradlew benchCompare -Pa=<zipA> -Pb=<zipB>

prints, for every graphics mode and part present in both zips (a single-mode zip or a session zip):
average fps, 1% and 0.1% low, p99, GPU time and each CPU zone in ms per frame as `A -> B (change %)`,
then the allocation rate and the number of collections. It reads `summary.json`; it is a developer
tool (`tools/BenchCompare.java`), not part of the jar.

### Reading the flight recording

`jfr summary recording.jfr` lists the event types and how many of each there are. `jfr print`
truncates every stack to its top few frames unless it is given `--stack-depth 200` (for example
`jfr print --events jdk.ExecutionSample --stack-depth 200 recording.jfr`): without it, samples from
different callers look the same and the hot path seems to end inside a helper.

## Options

`-Dsteinbridge.bench.settings=player` (the player's own settings), `bench.scale=0.25` (shorter parts),
`bench.repeat=N`, `bench.only=fight_far,flight`, `bench.maxBiomes=N` (a shorter biome tour), `bench.size=1920x1080` (the window's size for the run),
`bench.quit=true` (close the game at the end), `bench.startAtSec=N`, `bench.external=false` (no
nvidia-smi, typeperf, reg or powercfg), `bench.dir=path`. All of them are in [OPTIONS.md](OPTIONS.md).
