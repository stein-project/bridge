/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Test of the conventions and ordering of the graphics API, beyond {@link SteinGfxSelfTest}: what a
 * new backend (Vulkan) most easily gets wrong. Public API only, on the current device; each case in
 * a pass that clears a 64x64 target and reads the pixels (row 0 = the bottom one, as in
 * glReadPixels).
 *
 * <ul>
 *   <li>row order and front face: a counter-clockwise triangle at bottom left shows up; the
 *       clockwise one disappears;
 *   <li>NDC depth from -1 to 1: z = -0.5 in front of z = 0.3 (must not be clipped);
 *   <li>upload to a GPU buffer between two draws: the first sees the earlier contents;
 *   <li>fence wait in the middle of the pass (Vulkan submits there): the following draw stays
 *       correct;
 *   <li>draw with 16-bit indices;
 *   <li>scissor with origin at bottom left;
 *   <li>texture and buffer copy on the GPU (also right after the pass's clear); mipmaps generated;
 *   <li>compute writing an instance and an indirect command read by the next draw of the same pass
 *       (the dispatch barrier), and reading the buffer back to the CPU.
 * </ul>
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGfxApiTest {

    private static final int SIZE = 64;

    private SteinGfxApiTest() {}

    /** Runs on the current device; the list of failures (empty = passed). */
    public static List<String> run() {
        List<String> fails = new ArrayList<>();
        SteinDevice dev = SteinDevice.get();
        SteinTexture color =
                dev.createTexture(
                        SteinTexture.Desc.target("api color", SteinFormat.RGBA8, SIZE, SIZE));
        SteinTexture depth =
                dev.createTexture(
                        SteinTexture.Desc.target("api depth", SteinFormat.DEPTH24, SIZE, SIZE));
        SteinTexture copy =
                dev.createTexture(
                        SteinTexture.Desc.target("api copy", SteinFormat.RGBA8, SIZE, SIZE));
        SteinRenderTarget target = dev.createTarget(new SteinTexture[] {color}, depth);

        // layers: 0 red, 1 green, 2 blue
        SteinTexture layers =
                dev.createTexture(
                        SteinTexture.Desc.array("api layers", SteinFormat.RGBA8, 4, 4, 3, 1));
        int[][] rgb = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}};
        for (int i = 0; i < 3; i++) {
            ByteBuffer p = solid(4, 4, rgb[i]);
            layers.upload(i, 0, 0, 0, 4, 4, p);
            MemoryUtil.memFree(p);
        }
        SteinSampler sampler = dev.createSampler(SteinSampler.Desc.pixel());

        // geometry (position, uv): 0-2 counter-clockwise at bottom left; 3-5 clockwise at bottom
        // right; 6-11 square [-0.25, 0.25] (counter-clockwise); 12-15 the square's 4 corners (for
        // the indices)
        float h = 0.25f;
        float[] geo = {
            -0.9f, -0.9f, 0.5f, 0.5f, -0.1f, -0.9f, 0.5f, 0.5f, -0.5f, -0.1f, 0.5f, 0.5f, 0.1f,
            -0.9f, 0.5f, 0.5f, 0.5f, -0.1f, 0.5f, 0.5f, 0.9f, -0.9f, 0.5f, 0.5f, -h, -h, 0.5f, 0.5f,
            h, -h, 0.5f, 0.5f, h, h, 0.5f, 0.5f, -h, -h, 0.5f, 0.5f, h, h, 0.5f, 0.5f, -h, h, 0.5f,
            0.5f, -h, -h, 0.5f, 0.5f, h, -h, 0.5f, 0.5f, h, h, 0.5f, 0.5f, -h, h, 0.5f, 0.5f
        };
        SteinBuffer vertices =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "api geometry",
                                geo.length * 4L,
                                SteinBuffer.Memory.GPU,
                                SteinBuffer.Usage.VERTEX));
        vertices.upload(0, geo, geo.length);
        SteinBuffer indices =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "api indices",
                                16,
                                SteinBuffer.Memory.GPU,
                                SteinBuffer.Usage.INDEX));
        ByteBuffer idx = MemoryUtil.memAlloc(12);
        idx.putShort((short) 0)
                .putShort((short) 1)
                .putShort((short) 2)
                .putShort((short) 0)
                .putShort((short) 2)
                .putShort((short) 3)
                .flip();
        indices.upload(0, idx);
        MemoryUtil.memFree(idx);

        // instances (xy offset, layer, z): written in the mapping
        SteinBuffer inst =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "api instances",
                                1024,
                                SteinBuffer.Memory.UPLOAD,
                                SteinBuffer.Usage.VERTEX));
        SteinBuffer instGpu =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "api instances gpu",
                                64,
                                SteinBuffer.Memory.GPU,
                                SteinBuffer.Usage.VERTEX));
        SteinBuffer instCopy =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "api instances copy",
                                64,
                                SteinBuffer.Memory.GPU,
                                SteinBuffer.Usage.VERTEX));
        SteinBuffer tint =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "api tint",
                                256,
                                SteinBuffer.Memory.UPLOAD,
                                SteinBuffer.Usage.UNIFORM));
        tint.upload(0, new float[] {1f, 1f, 1f, 1f}, 4);

        SteinVertexLayout layout =
                SteinVertexLayout.builder()
                        .binding(0, 16, false)
                        .attribute(0, 0, SteinFormat.FLOAT2, 0)
                        .attribute(1, 0, SteinFormat.FLOAT2, 8)
                        .binding(1, 16, true)
                        .attribute(2, 1, SteinFormat.FLOAT4, 0)
                        .build();
        SteinShader vs = SteinShader.load(SteinShader.Stage.VERTEX, "selftest.vert");
        SteinShader fs = SteinShader.load(SteinShader.Stage.FRAGMENT, "selftest.frag");
        SteinPipelineCache cache = new SteinPipelineCache(dev);
        SteinPipeline culled =
                cache.get(
                        SteinPipeline.builder("api culled")
                                .vertex(vs)
                                .fragment(fs)
                                .layout(layout)
                                .depth(SteinPipeline.Depth.LESS, true)
                                .cull(SteinPipeline.Cull.BACK)
                                .texture("tex", 0)
                                .uniforms("Tint", 0)
                                .build());
        SteinPipeline plain =
                cache.get(
                        SteinPipeline.builder("api plain")
                                .vertex(vs)
                                .fragment(fs)
                                .layout(layout)
                                .depth(SteinPipeline.Depth.LESS, true)
                                .cull(SteinPipeline.Cull.NONE)
                                .texture("tex", 0)
                                .uniforms("Tint", 0)
                                .build());
        SteinPipeline blue =
                cache.get(
                        SteinPipeline.builder("api blue")
                                .vertex(SteinShader.load(SteinShader.Stage.VERTEX, "blue.vert"))
                                .fragment(SteinShader.load(SteinShader.Stage.FRAGMENT, "blue.frag"))
                                .blend(SteinPipeline.Blend.ADDITIVE)
                                .depth(null, false)
                                .cull(SteinPipeline.Cull.NONE)
                                .build());
        if (!culled.valid() || !plain.valid() || !blue.valid()) {
            fails.add("pipeline did not link");
            return fails;
        }
        ByteBuffer px = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        try {
            // ---- 1: row order and front face ----
            putInst(inst, 0, 0f, 0f, 0f, 0f);
            SteinCommands c = begin(dev, target, culled, vertices, inst, layers, sampler, tint);
            c.draw(6, 1, 0, 0);
            c.end();
            dev.readPixels(color, 0, 0, SIZE, SIZE, px);
            expect(fails, px, 16, 12, rgb[0], "1: counter-clockwise at the bottom left");
            expect(fails, px, 48, 12, null, "1: clockwise hidden (back face)");
            expect(fails, px, 16, 52, null, "1: nothing at the top (rows inverted?)");

            // ---- 2: depth from -1 to 1 ----
            putInst(inst, 0, 0f, 0f, 0f, -0.5f);
            putInst(inst, 1, 0f, 0f, 1f, 0.3f);
            c = begin(dev, target, plain, vertices, inst, layers, sampler, tint);
            c.draw(6, 1, 6, 0);
            c.draw(6, 1, 6, 1);
            c.end();
            dev.readPixels(color, 0, 0, SIZE, SIZE, px);
            expect(fails, px, 32, 32, rgb[0], "2: z -0.5 in front of z 0.3 (near half clipped?)");

            // ---- 3: upload to the GPU between two draws ----
            instGpu.upload(0, new float[] {-0.5f, 0f, 0f, 0.5f}, 4);
            c = begin(dev, target, plain, vertices, instGpu, layers, sampler, tint);
            c.draw(6, 1, 6, 0);
            instGpu.upload(0, new float[] {0.5f, 0f, 1f, 0.5f}, 4);
            c.draw(6, 1, 6, 0);
            c.end();
            dev.readPixels(color, 0, 0, SIZE, SIZE, px);
            expect(
                    fails,
                    px,
                    16,
                    32,
                    rgb[0],
                    "3: the first draw sees the contents from before the upload");
            expect(fails, px, 48, 32, rgb[1], "3: the second sees the new one");

            // ---- 4: fence wait in the middle of the pass ----
            putInst(inst, 0, -0.5f, 0f, 0f, 0.5f);
            putInst(inst, 1, 0.5f, 0f, 1f, 0.5f);
            c = begin(dev, target, plain, vertices, inst, layers, sampler, tint);
            c.draw(6, 1, 6, 0);
            SteinFence f = dev.fence();
            if (!f.waitFor(2_000_000_000L)) {
                fails.add("4: fence in the middle of the pass did not signal");
            }
            f.destroy();
            c.draw(6, 1, 6, 1);
            c.end();
            dev.readPixels(color, 0, 0, SIZE, SIZE, px);
            expect(fails, px, 16, 32, rgb[0], "4: draw before the wait");
            expect(fails, px, 48, 32, rgb[1], "4: draw after the wait");

            // ---- 5: 16-bit indices ----
            putInst(inst, 0, 0f, 0f, 2f, 0.5f);
            c = begin(dev, target, plain, vertices, inst, layers, sampler, tint);
            c.bindIndexBuffer(indices, SteinCommands.IndexType.UINT16);
            c.drawIndexed(6, 1, 0, 12, 0);
            c.end();
            dev.readPixels(color, 0, 0, SIZE, SIZE, px);
            expect(fails, px, 32, 32, rgb[2], "5: square by indices");
            expect(fails, px, 2, 2, null, "5: clean corner");

            // ---- 6: copies on the GPU (the target now has the blue square) ----
            dev.copyTexture(color, 0, copy, 0, SIZE, SIZE);
            dev.readPixels(copy, 0, 0, SIZE, SIZE, px);
            expect(fails, px, 32, 32, rgb[2], "6: texture copy");
            putInst(inst, 0, 0.5f, 0.5f, 1f, 0.5f);
            dev.copyBuffer(inst, 0, instCopy, 0, 16);
            c = begin(dev, target, plain, vertices, instCopy, layers, sampler, tint);
            c.draw(6, 1, 6, 0);
            c.end();
            dev.readPixels(color, 0, 0, SIZE, SIZE, px);
            expect(fails, px, 48, 48, rgb[1], "6: buffer copy used in the draw");

            // ---- 6b: the clear belongs to the pass opening: a copy before the first draw stays
            // ----
            c = dev.beginPass(SteinRenderPass.clear("api clear and copy", target, 0f, 0f, 0f, 1f));
            dev.copyTexture(copy, 0, color, 0, SIZE, SIZE);
            c.end();
            dev.readPixels(color, 0, 0, SIZE, SIZE, px);
            expect(fails, px, 32, 32, rgb[2], "6b: copy after the pass clear");

            // ---- 7: scissor (origin at bottom left) ----
            c = dev.beginPass(SteinRenderPass.clear("api scissor", target, 0f, 0f, 0f, 1f));
            c.bindPipeline(blue);
            c.setScissor(new int[] {0, 0, SIZE, SIZE / 2});
            c.draw(3, 1, 0, 0);
            c.end();
            dev.readPixels(color, 0, 0, SIZE, SIZE, px);
            expect(fails, px, 32, 12, rgb[2], "7: inside the scissor (bottom half)");
            expect(fails, px, 32, 52, null, "7: outside the scissor");

            // ---- 8: mipmaps (only runs: the test shader reads level 0) ----
            SteinTexture mips =
                    dev.createTexture(
                            SteinTexture.Desc.array("api mips", SteinFormat.RGBA8, 16, 16, 2, 5));
            ByteBuffer p = solid(16, 16, rgb[1]);
            mips.upload(0, 0, 0, 0, 16, 16, p);
            mips.upload(1, 0, 0, 0, 16, 16, p);
            MemoryUtil.memFree(p);
            mips.generateMips();
            mips.destroy();

            // ---- 9: compute writes an instance and an indirect command for the next draw ----
            SteinCaps caps = dev.caps();
            if (caps.compute && caps.multiDrawIndirect && caps.baseInstance) {
                computeCase(dev, fails, target, plain, vertices, layers, sampler, tint, color, px);
            }
        } finally {
            MemoryUtil.memFree(px);
        }

        cache.clear();
        tint.destroy();
        instCopy.destroy();
        instGpu.destroy();
        inst.destroy();
        indices.destroy();
        vertices.destroy();
        sampler.destroy();
        layers.destroy();
        target.destroy();
        copy.destroy();
        depth.destroy();
        color.destroy();
        Log.info("gfx", "API test ran on " + dev.caps().backend);
        return fails;
    }

    /**
     * Case 9: in the same pass, a dispatch writes instance 1 and command 1 into GPU buffers, and an
     * indirect draw reads both (the dispatch contract puts in the barrier); then reading the buffer
     * back checks the command.
     */
    private static void computeCase(
            SteinDevice dev,
            List<String> fails,
            SteinRenderTarget target,
            SteinPipeline plain,
            SteinBuffer vertices,
            SteinTexture layers,
            SteinSampler sampler,
            SteinBuffer tint,
            SteinTexture color,
            ByteBuffer px) {
        SteinPipeline comp =
                dev.createPipeline(
                        SteinPipeline.builder("api compute")
                                .compute(
                                        SteinShader.load(
                                                SteinShader.Stage.COMPUTE, "selftest.comp"))
                                .storage("TestInstances", 0)
                                .storage("TestCommands", 1)
                                .build());
        SteinBuffer inst =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "api compute instances",
                                64,
                                SteinBuffer.Memory.GPU,
                                SteinBuffer.Usage.VERTEX,
                                SteinBuffer.Usage.STORAGE));
        SteinBuffer cmd =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "api compute commands",
                                64,
                                SteinBuffer.Memory.GPU,
                                SteinBuffer.Usage.INDIRECT,
                                SteinBuffer.Usage.STORAGE));
        ByteBuffer back = MemoryUtil.memAlloc(16);
        try {
            if (!comp.valid()) {
                fails.add("9: compute pipeline did not link");
                return;
            }
            inst.upload(0, new float[16], 16);
            cmd.upload(0, new float[16], 16);
            SteinCommands c =
                    dev.beginPass(SteinRenderPass.clear("api compute", target, 0f, 0f, 0f, 1f));
            c.bindPipeline(comp);
            c.bindStorage(0, inst, 0, 64);
            c.bindStorage(1, cmd, 0, 64);
            c.dispatch(1, 1, 1);
            c.bindPipeline(plain);
            c.bindVertexBuffer(0, vertices, 0);
            c.bindVertexBuffer(1, inst, 0);
            c.bindTexture(0, layers, sampler);
            c.bindUniforms(0, tint, 0, 16);
            c.drawIndirect(cmd, 16, 1);
            c.end();
            dev.readPixels(color, 0, 0, SIZE, SIZE, px);
            expect(
                    fails,
                    px,
                    16,
                    48,
                    new int[] {0, 0, 255},
                    "9: indirect draw with what the compute wrote");
            expect(fails, px, 48, 16, null, "9: nothing outside the square");
            dev.readBuffer(cmd, 16, back);
            back.order(ByteOrder.nativeOrder());
            int[] want = {6, 1, 6, 1};
            for (int i = 0; i < 4; i++) {
                if (back.getInt(i * 4) != want[i]) {
                    fails.add(
                            "9: buffer read: command["
                                    + i
                                    + "] = "
                                    + back.getInt(i * 4)
                                    + ", expected "
                                    + want[i]);
                    break;
                }
            }
        } finally {
            MemoryUtil.memFree(back);
            cmd.destroy();
            inst.destroy();
            comp.destroy();
        }
    }

    private static SteinCommands begin(
            SteinDevice dev,
            SteinRenderTarget target,
            SteinPipeline p,
            SteinBuffer vertices,
            SteinBuffer instances,
            SteinTexture tex,
            SteinSampler sampler,
            SteinBuffer tint) {
        SteinCommands c = dev.beginPass(SteinRenderPass.clear("api", target, 0f, 0f, 0f, 1f));
        c.bindPipeline(p);
        c.bindVertexBuffer(0, vertices, 0);
        c.bindVertexBuffer(1, instances, 0);
        c.bindTexture(0, tex, sampler);
        c.bindUniforms(0, tint, 0, 16);
        return c;
    }

    private static void putInst(SteinBuffer b, int i, float x, float y, float layer, float z) {
        long at = i * 16L;
        if (b.mapped()) {
            long a = b.address(at);
            MemoryUtil.memPutFloat(a, x);
            MemoryUtil.memPutFloat(a + 4, y);
            MemoryUtil.memPutFloat(a + 8, layer);
            MemoryUtil.memPutFloat(a + 12, z);
            b.commit(at, 16);
        } else {
            b.upload(at, new float[] {x, y, layer, z}, 4);
        }
    }

    private static ByteBuffer solid(int w, int h, int[] rgb) {
        ByteBuffer p = MemoryUtil.memAlloc(w * h * 4);
        for (int i = 0; i < w * h; i++) {
            p.put((byte) rgb[0]).put((byte) rgb[1]).put((byte) rgb[2]).put((byte) 255);
        }
        return p.flip();
    }

    /** {@code rgb} null = black (nothing drawn). */
    private static void expect(
            List<String> fails, ByteBuffer px, int x, int y, int[] rgb, String what) {
        int[] want = rgb == null ? new int[3] : rgb;
        int i = (y * SIZE + x) * 4;
        int r = px.get(i) & 255;
        int g = px.get(i + 1) & 255;
        int b = px.get(i + 2) & 255;
        if (Math.abs(r - want[0]) > 2 || Math.abs(g - want[1]) > 2 || Math.abs(b - want[2]) > 2) {
            fails.add(
                    what
                            + ": expected "
                            + want[0]
                            + ","
                            + want[1]
                            + ","
                            + want[2]
                            + " got "
                            + r
                            + ","
                            + g
                            + ","
                            + b);
        }
    }
}
