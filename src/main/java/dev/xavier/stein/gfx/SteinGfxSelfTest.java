/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-test of the Stein graphics API, inside the game ({@code -Dsteinbridge.gfx.selfTest=true}, at
 * the end of startup). It uses only the public API, as the renderer will: it draws off screen and
 * reads the pixels back.
 *
 * <ul>
 *   <li>64x64 target with colour and depth, a pass that clears;
 *   <li>texture array with two layers (red and green), pixel-by-pixel sampler;
 *   <li>a square in a GPU buffer, instances in a persistent ring (offset, layer, depth);
 *   <li>uniform block with the multiplication colour;
 *   <li>indirect draw when the device supports it (otherwise, direct instanced);
 *   <li>depth: an instance behind must not cover the one in front;
 *   <li>additive blend: a second pipeline adds blue to everything.
 * </ul>
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGfxSelfTest {

    private SteinGfxSelfTest() {}

    /** Runs if requested; prints the result. */
    public static void runIfRequested() {
        if (!SteinGfxOptions.SELF_TEST) {
            return;
        }
        try {
            List<String> fails = run();
            if (fails.isEmpty()) {
                Log.info("gfx", "self-test: OK");
            } else {
                Log.info("gfx", "self-test: FAILED with " + fails.size() + ": " + fails);
            }
        } catch (Throwable e) {
            Log.error("gfx", "self-test: error", e);
        }
    }

    /**
     * Runs on the current device ({@link SteinDevice#get}); the list of failures (empty = passed).
     */
    public static List<String> run() {
        List<String> fails = new ArrayList<>();
        SteinDevice dev = SteinDevice.get();
        SteinCaps caps = dev.caps();
        int size = 64;

        SteinTexture color =
                dev.createTexture(
                        SteinTexture.Desc.target("test color", SteinFormat.RGBA8, size, size));
        SteinTexture depth =
                dev.createTexture(
                        SteinTexture.Desc.target("test depth", SteinFormat.DEPTH24, size, size));
        SteinRenderTarget target = dev.createTarget(new SteinTexture[] {color}, depth);

        // 4x4 texture array with two layers: red and green.
        SteinTexture array =
                dev.createTexture(
                        SteinTexture.Desc.array("test array", SteinFormat.RGBA8, 4, 4, 2, 1));
        array.upload(0, 0, 0, 0, 4, 4, solid(4, 4, 255, 0, 0));
        array.upload(1, 0, 0, 0, 4, 4, solid(4, 4, 0, 255, 0));
        SteinSampler sampler = dev.createSampler(SteinSampler.Desc.pixel());

        // 1x1 square (two triangles): position and uv.
        float[] quad = {
            -0.25f, -0.25f, 0f, 0f, 0.25f, -0.25f, 1f, 0f, 0.25f, 0.25f, 1f, 1f,
            -0.25f, -0.25f, 0f, 0f, 0.25f, 0.25f, 1f, 1f, -0.25f, 0.25f, 0f, 1f
        };
        SteinBuffer vertices =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "test quad",
                                quad.length * 4L,
                                SteinBuffer.Memory.GPU,
                                SteinBuffer.Usage.VERTEX));
        vertices.upload(0, quad, quad.length);

        // Instances: red on the left in front, green on the right, and a green one BEHIND the left
        // one (it must not show).
        float[] inst = {
            -0.5f, 0f, 0f, 0.2f,
            0.5f, 0f, 1f, 0.2f,
            -0.5f, 0f, 1f, 0.8f
        };
        SteinBuffer instances =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "test instances",
                                        4096,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.VERTEX))
                        .ring(2);
        long instAt = instances.reserve(inst.length * 4L);
        if (instances.mapped()) {
            long p = instances.address(instAt);
            for (int i = 0; i < inst.length; i++) {
                MemoryUtil.memPutFloat(p + i * 4L, inst[i]);
            }
            instances.commit(instAt, inst.length * 4L);
        } else {
            instances.upload(instAt, inst, inst.length);
        }

        SteinBuffer uniforms =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "test uniforms",
                                1024,
                                SteinBuffer.Memory.UPLOAD,
                                SteinBuffer.Usage.UNIFORM));
        uniforms.upload(0, new float[] {1f, 1f, 1f, 1f}, 4);

        SteinVertexLayout layout =
                SteinVertexLayout.builder()
                        .binding(0, 16, false)
                        .attribute(0, 0, SteinFormat.FLOAT2, 0)
                        .attribute(1, 0, SteinFormat.FLOAT2, 8)
                        .binding(1, 16, true)
                        .attribute(2, 1, SteinFormat.FLOAT4, 0)
                        .build();
        SteinPipelineCache cache = new SteinPipelineCache(dev);
        SteinPipeline quads =
                cache.get(
                        SteinPipeline.builder("test quads")
                                .vertex(SteinShader.load(SteinShader.Stage.VERTEX, "selftest.vert"))
                                .fragment(
                                        SteinShader.load(
                                                SteinShader.Stage.FRAGMENT, "selftest.frag"))
                                .layout(layout)
                                .depth(SteinPipeline.Depth.LESS, true)
                                .cull(SteinPipeline.Cull.NONE)
                                .texture("tex", 0)
                                .uniforms("Tint", 0)
                                .build());
        SteinPipeline blue =
                cache.get(
                        SteinPipeline.builder("test blue")
                                .vertex(SteinShader.load(SteinShader.Stage.VERTEX, "blue.vert"))
                                .fragment(SteinShader.load(SteinShader.Stage.FRAGMENT, "blue.frag"))
                                .blend(SteinPipeline.Blend.ADDITIVE)
                                .depth(null, false)
                                .cull(SteinPipeline.Cull.NONE)
                                .build());
        if (!quads.valid() || !blue.valid()) {
            fails.add("pipeline did not link");
            return fails;
        }
        if (cache.get(quads.desc()) != quads) {
            fails.add("pipeline cache returned a different one");
        }

        // Indirect draw: one command per instance (the one in back last).
        SteinBuffer indirect = null;
        if (caps.multiDrawIndirect) {
            indirect =
                    dev.createBuffer(
                            new SteinBuffer.Desc(
                                    "test indirect",
                                    3 * 16L,
                                    SteinBuffer.Memory.UPLOAD,
                                    SteinBuffer.Usage.INDIRECT));
            ByteBuffer cmds = MemoryUtil.memAlloc(3 * 16);
            for (int i = 0; i < 3; i++) {
                cmds.putInt(6).putInt(1).putInt(0).putInt((int) (instAt / 16) + i);
            }
            cmds.flip();
            indirect.upload(0, cmds);
            MemoryUtil.memFree(cmds);
        }

        SteinCommands c = dev.beginPass(SteinRenderPass.clear("self-test", target, 0f, 0f, 0f, 1f));
        try {
            c.bindPipeline(quads);
            c.bindVertexBuffer(0, vertices, 0);
            c.bindVertexBuffer(1, instances, 0);
            c.bindTexture(0, array, sampler);
            c.bindUniforms(0, uniforms, 0, 16);
            if (indirect != null) {
                c.drawIndirect(indirect, 0, 3);
            } else {
                c.draw(6, 3, 0, (int) (instAt / 16));
            }
        } finally {
            c.end();
        }
        ByteBuffer px = MemoryUtil.memAlloc(size * size * 4);
        try {
            dev.readPixels(color, 0, 0, size, size, px);
            expect(
                    fails,
                    px,
                    size,
                    16,
                    32,
                    255,
                    0,
                    0,
                    "left red (the green one behind must not cover it)");
            expect(fails, px, size, 48, 32, 0, 255, 0, "right green");
            expect(fails, px, size, 2, 2, 0, 0, 0, "clean corner");

            // Second pass on top (keeps what is there): blue added to everything.
            SteinCommands b = dev.beginPass(SteinRenderPass.keep("self-test blue", target));
            try {
                b.bindPipeline(blue);
                b.draw(3, 1, 0, 0);
            } finally {
                b.end();
            }
            dev.readPixels(color, 0, 0, size, size, px);
            expect(fails, px, size, 16, 32, 255, 0, 255, "left + blue");
            expect(fails, px, size, 2, 2, 0, 0, 255, "blue corner");
        } finally {
            MemoryUtil.memFree(px);
        }

        cache.clear();
        if (indirect != null) {
            indirect.destroy();
        }
        uniforms.destroy();
        instances.destroy();
        vertices.destroy();
        sampler.destroy();
        array.destroy();
        target.destroy();
        depth.destroy();
        color.destroy();
        Log.info(
                "gfx",
                "self-test ran on "
                        + caps.backend
                        + (indirect != null ? ", indirect" : ", direct"));
        return fails;
    }

    private static ByteBuffer solid(int w, int h, int r, int g, int b) {
        ByteBuffer p = MemoryUtil.memAlloc(w * h * 4);
        for (int i = 0; i < w * h; i++) {
            p.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
        }
        p.flip();
        // The upload copies immediately; the block released here is not returned (one-off test, few
        // bytes).
        return p;
    }

    private static void expect(
            List<String> fails,
            ByteBuffer px,
            int size,
            int x,
            int y,
            int r,
            int g,
            int b,
            String what) {
        int i = (y * size + x) * 4;
        int pr = px.get(i) & 255;
        int pg = px.get(i + 1) & 255;
        int pb = px.get(i + 2) & 255;
        if (Math.abs(pr - r) > 2 || Math.abs(pg - g) > 2 || Math.abs(pb - b) > 2) {
            fails.add(
                    what
                            + ": expected "
                            + r
                            + ","
                            + g
                            + ","
                            + b
                            + " got "
                            + pr
                            + ","
                            + pg
                            + ","
                            + pb);
        }
    }
}
