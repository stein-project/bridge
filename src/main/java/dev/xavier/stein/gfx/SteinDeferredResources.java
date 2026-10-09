/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredFrame.*;
import static dev.xavier.stein.gfx.SteinDeferredKeys.*;
import static dev.xavier.stein.gfx.SteinDeferredText.*;
import static dev.xavier.stein.gfx.SteinEntityDeferred.*;
import static dev.xavier.stein.gfx.SteinInstanceWriter.*;

import dev.xavier.stein.bridge.core.Log;

/**
 * The GPU objects of the deferred entities ({@link SteinEntityDeferred}): rings, shaders, vertex
 * layouts and pipelines, created on first use, and the ring operations on them.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredResources {

    /** Dynamic geometry (Tessellator, font, items) recorded too. */
    static final boolean DYNAMIC = REQUESTED && SteinGfxOptions.DEFERRED_DYNAMIC;

    static boolean initDone;
    static boolean ready;
    static boolean dynamicReady;

    static SteinBuffer instances;
    static SteinBuffer commands;
    static SteinBuffer frame;
    static SteinShader vertex;
    static SteinShader fragment;
    static SteinShader fragmentGameTex;
    static SteinVertexLayout layout;
    static SteinBuffer dynVertices;
    static SteinShader dynVertex;
    static SteinShader dynFragment;
    static SteinVertexLayout dynLayout;
    static SteinPipelineCache pipelines;
    static SteinSampler pixel;
    static SteinSampler linear;
    static SteinTexture lightmap;
    static int lightmapId;

    // ---- startup ----

    static boolean ready() {
        if (initDone) {
            return ready;
        }
        initDone = true;
        if (!REQUESTED || !SteinModelInstancer.geometryReady()) {
            Log.info(
                    "gfx",
                    "deferred entities off: they need the part geometry on the GPU (GL 3.3)");
            return false;
        }
        SteinDevice dev = SteinDevice.get();
        createRings(dev);
        createEntityPipeline(dev);
        if (!instances.mapped()
                || !commands.mapped()
                || !frame.mapped()
                || sorted != null && !sorted.mapped()) {
            Log.info("gfx", "deferred entities off: buffer without an address (mapping failed)");
            return false;
        }
        // Checks compilation with the most common group (no blend): without it, nothing is
        // deferred.
        if (!pipeline(OPAQUE_KEY).valid()) {
            Log.info("gfx", "deferred entities off: the shader did not compile");
            return false;
        }
        ready = true;
        Log.info("gfx", "deferred entities: parts grouped by state, array textures, own shader");
        if (DYNAMIC) {
            createDynamicResources(dev);
        }
        return true;
    }

    /** The rings of instances, commands and the Frame block (and the CPU copy below GL 4.4). */
    private static void createRings(SteinDevice dev) {
        SteinCaps caps = dev.caps();
        // 4.4+: persistent ring and indirect draw, one per group. Below (Mac, 4.1): a CPU copy
        // that is uploaded at flush, and each group's instances reordered by part, one instanced
        // draw per part.
        indirect = caps.multiDrawIndirect && caps.persistentMapping;
        if (!indirect) {
            sorted =
                    dev.createBuffer(
                                    new SteinBuffer.Desc(
                                            "entities: instances per part",
                                            (long) RING_PARTS * INSTANCE_BYTES * REGIONS,
                                            SteinBuffer.Memory.UPLOAD,
                                            SteinBuffer.Usage.VERTEX))
                            .ring(REGIONS);
        }
        // STORAGE: the on-GPU pose (SteinPoseGpu) writes the players' instances here
        instances =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "entities: instances",
                                        (long) RING_PARTS * INSTANCE_BYTES * REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.VERTEX,
                                        SteinBuffer.Usage.STORAGE))
                        .ring(REGIONS);
        commands =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "entities: commands",
                                        (long) RING_PARTS * COMMAND_BYTES * REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.INDIRECT))
                        .ring(REGIONS);
        frame =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "entities: frame",
                                        16384L * REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.UNIFORM))
                        .ring(REGIONS);
    }

    /** The shaders, the vertex layout of the model parts, the pipeline cache and the samplers. */
    private static void createEntityPipeline(SteinDevice dev) {
        // src/main/shaders/entity.* (GAME_TEX: the game's texture directly in place of the
        // array), compiled at build time
        vertex = SteinShader.load(SteinShader.Stage.VERTEX, "entity.vert");
        fragment = SteinShader.load(SteinShader.Stage.FRAGMENT, "entity.frag");
        fragmentGameTex = SteinShader.load(SteinShader.Stage.FRAGMENT, "entity_gametex.frag");
        layout =
                instanceBinding(
                                SteinVertexLayout.builder()
                                        .binding(0, SteinModelInstancer.VERTEX_BYTES, false)
                                        .attribute(0, 0, SteinFormat.FLOAT3, 0)
                                        .attribute(1, 0, SteinFormat.FLOAT2, 12)
                                        .attribute(2, 0, SteinFormat.FLOAT3, 20))
                        .build();
        pipelines = new SteinPipelineCache(dev);
        pixel = dev.createSampler(SteinSampler.Desc.pixel());
        linear = dev.createSampler(SteinSampler.Desc.linearClamp());
    }

    /** The dynamic geometry: the frame vertex ring, its shaders and layout, and its pipeline. */
    private static void createDynamicResources(SteinDevice dev) {
        dynVertices =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "entities: frame vertices",
                                        (long) RING_VERTS * SteinTessCapture.VERTEX_BYTES * REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.VERTEX))
                        .ring(REGIONS);
        dynVertex = SteinShader.load(SteinShader.Stage.VERTEX, "entity_dynamic.vert");
        dynFragment = SteinShader.load(SteinShader.Stage.FRAGMENT, "entity_dynamic.frag");
        dynLayout =
                instanceBinding(
                                SteinVertexLayout.builder()
                                        .binding(0, SteinTessCapture.VERTEX_BYTES, false)
                                        .attribute(0, 0, SteinFormat.FLOAT3, 0)
                                        .attribute(1, 0, SteinFormat.FLOAT2, 12)
                                        .attribute(11, 0, SteinFormat.UBYTE4_NORM, 20)
                                        .attribute(2, 0, SteinFormat.BYTE4_NORM, 24)
                                        .attribute(12, 0, SteinFormat.USHORT2_NORM, 28))
                        .build();
        long blendKey = OWN_BLEND_KEY | KEY_DYNAMIC;
        dynamicReady = pipeline(blendKey).valid();
        Log.info(
                "gfx",
                dynamicReady
                        ? "deferred entities: Tessellator, font and items recorded too"
                        : "deferred entities: the dynamic geometry shader did not compile"
                                + " (it stays immediate)");
    }

    /** Binding 1 (per instance): the 128-byte record as eight vec4 at locations 3..10. */
    private static SteinVertexLayout.Builder instanceBinding(SteinVertexLayout.Builder b) {
        b.binding(1, INSTANCE_BYTES, true);
        for (int i = 0; i < 8; i++) {
            b.attribute(3 + i, 1, SteinFormat.FLOAT4, i * 16);
        }
        return b;
    }

    /** Closes the current region of all rings (the frame ended, or a region filled up). */
    static void rotate() {
        // The text record of the region being closed is no longer reused (commitText)
        regionSerial++;
        if (sorted != null) {
            sorted.nextRegion();
        }
        instances.nextRegion();
        commands.nextRegion();
        frame.nextRegion();
        if (dynVertices != null) {
            dynVertices.nextRegion();
        }
    }

    static SteinPipeline pipeline(long full) {
        int key = (int) full;
        boolean dyn = (full & KEY_DYNAMIC) != 0;
        boolean gameTex = (full & KEY_GAMETEX) != 0;
        boolean text = (full & KEY_TEXT) != 0;
        SteinPipeline.Builder b =
                SteinPipeline.builder(
                                (text ? "entities text " : dyn ? "entities dynamic " : "entities ")
                                        + Integer.toHexString(key & 0x7FFFFFF0))
                        .vertex(text ? SteinTextGpu.vertexShader() : dyn ? dynVertex : vertex)
                        .fragment(dyn ? dynFragment : gameTex ? fragmentGameTex : fragment)
                        .layout(text ? SteinTextGpu.layout() : dyn ? dynLayout : layout)
                        .texture("tex", 0)
                        .texture("lightmap", 1)
                        .uniforms("Frame", 0);
        if ((full & KEY_NOALPHA) != 0) {
            b.alphaWrite(false);
        }
        if ((full & KEY_NOCOLOR) != 0) {
            b.colorWrite(false);
        }
        if ((full & KEY_LINES) != 0) {
            b.topology(SteinPipeline.Topology.LINES);
        }
        if (keyBlend(key)) {
            b.blend(
                    keyFactor(key, KEY_SRC_SHIFT),
                    keyFactor(key, KEY_DST_SHIFT),
                    keyFactor(key, KEY_SRC_ALPHA_SHIFT),
                    keyFactor(key, KEY_DST_ALPHA_SHIFT));
        }
        b.depth(keyDepthTest(key) ? keyDepthFunc(key) : null, keyDepthMask(key));
        b.cull(
                !keyCull(key)
                        ? SteinPipeline.Cull.NONE
                        : keyCullBack(key) ? SteinPipeline.Cull.BACK : SteinPipeline.Cull.FRONT);
        return pipelines.get(b.build());
    }

    static long reserveInstance() {
        long inst = instances.reserve(INSTANCE_BYTES);
        if (inst == -2) {
            flush();
            rotate();
            inst = instances.reserve(INSTANCE_BYTES);
        }
        return inst;
    }

    /** 4.4+: indirect draw per group; otherwise, {@link SteinDeferredFrame#drawSorted}. */
    static boolean indirect = true;

    /** Without indirect draw: each group's instances copied here in part order. */
    static SteinBuffer sorted;
}
