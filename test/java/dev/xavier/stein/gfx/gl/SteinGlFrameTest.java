/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

// How to run (standalone, outside the game; not part of the Bridge jar): ./gradlew gpuFrameTest
//   It sets -Dsteinbridge.gfx.test.noGame=true itself (SteinGlGame.NO_GAME, the test switch).

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.gfx.SteinBuffer;
import dev.xavier.stein.gfx.SteinCommands;
import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinFence;
import dev.xavier.stein.gfx.SteinFormat;
import dev.xavier.stein.gfx.SteinGfxApiTest;
import dev.xavier.stein.gfx.SteinGfxSelfTest;
import dev.xavier.stein.gfx.SteinGlTrack;
import dev.xavier.stein.gfx.SteinRenderPass;
import dev.xavier.stein.gfx.SteinRenderTarget;
import dev.xavier.stein.gfx.SteinTexture;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Test of the recorded frame ({@link SteinGlFrame}) outside the game, in a hidden OpenGL 4.6 core
 * context, with the game state at the OpenGL default ({@code SteinGlGame.NO_GAME}):
 *
 * <ul>
 *   <li>the API tests ({@link SteinGfxSelfTest}, {@link SteinGfxApiTest}: draws, compute, copies,
 *       scissor, read back as pixels), with the passes recorded and submitted at the sync points
 *       (the reads);
 *   <li>the order of the list: an upload recorded after a pass, a copy, another upload, a read
 *       (what the copy saw must be the middle upload); a buffer deleted with a copy pending; a
 *       recorded fence (it did not pass until replay; waiting forces the submit); the game's
 *       glClear recorded (colour from recording time, target from that time).
 * </ul>
 *
 * Runs with the check ({@code recordFrameCheck}) and the pass-state check ({@code passStateCheck})
 * on; with {@code -Dsteinbridge.gfx.recordFrame=false} the same tests with immediate submission.
 * Exits with 0 if it passed.
 *
 * <p>{@code ./gradlew gpuFrameTest} (extra flags with {@code -Pstein.jvmArgs=...}, the number of
 * rounds with {@code -Pstein.args=N}).
 */
public final class SteinGlFrameTest {

    private SteinGlFrameTest() {}

    private static void defaults() {
        def("steinbridge.gfx.test.noGame", "true");
        def("steinbridge.gfx.bindCache", "true");
        def("steinbridge.gfx.recordFrame", "true");
        def("steinbridge.gfx.deferFences", "true");
        def("steinbridge.gfx.recordFrameCheck", "true");
        def("steinbridge.gfx.recordFrameCheck.every", "1");
        def("steinbridge.gfx.passStateCheck", "true");
        def("steinbridge.gfx.frameSubmitStats", "true");
    }

    private static void def(String k, String v) {
        if (System.getProperty(k) == null) {
            System.setProperty(k, v);
        }
    }

    public static void main(String[] args) {
        defaults();
        // As Display does in the game, before SteinGfxOptions loads: the context below is core, so
        // Stein GFX is on (SteinGfxOptions.ENABLED) and the test runs its real path.
        dev.xavier.stein.bridge.core.Win.coreContext = true;
        GLFW.glfwInit();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(64, 64, "test", 0, 0);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        int code;
        try {
            SteinGlDevice dev = new SteinGlDevice();
            System.out.println("[gfx] " + dev.caps());
            SteinDevice.use(dev);
            List<String> fails = new ArrayList<>();
            int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 3;
            for (int round = 0; round < rounds; round++) {
                fails.addAll(SteinGfxSelfTest.run());
                fails.addAll(SteinGfxApiTest.run());
                order(dev, fails);
                SteinGlFrame.endFrame();
            }
            System.out.println("[gfx] recorded frame: " + SteinGlFrame.summary());
            if (SteinGlFrame.checkFailed()) {
                fails.add("recorded-frame check");
            }
            System.out.println("[gfx] pass check: " + SteinGlPassCheck.ever() + " divergences");
            if (SteinGlPassCheck.ever() > 0) {
                fails.add("pass check");
            }
            if (fails.isEmpty()) {
                System.out.println(
                        "[gfx] recorded-frame test: OK (recording "
                                + (SteinGlFrame.recordingAvailable() ? "on" : "off")
                                + ")");
                code = 0;
            } else {
                System.out.println(
                        "[gfx] recorded-frame test: FAILED in " + fails.size() + ": " + fails);
                code = 1;
            }
        } catch (Throwable e) {
            System.out.println("[gfx] recorded-frame test: error " + e);
            e.printStackTrace(System.out);
            code = 2;
        } finally {
            SteinDevice.use(null);
        }
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        System.exit(code);
    }

    private static ByteBuffer bytes(int n, int value) {
        ByteBuffer b = MemoryUtil.memAlloc(n);
        for (int i = 0; i < n; i++) {
            b.put(i, (byte) value);
        }
        return b;
    }

    private static int read(SteinDevice dev, SteinBuffer buf) {
        ByteBuffer out = MemoryUtil.memAlloc(16);
        try {
            dev.readBuffer(buf, 0, out);
            return out.get(0) & 0xFF;
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    /** The order of the list against what the immediate pass would do. */
    private static void order(SteinGlDevice dev, List<String> fails) {
        SteinTexture color =
                dev.createTexture(SteinTexture.Desc.target("test: color", SteinFormat.RGBA8, 4, 4));
        SteinRenderTarget target = dev.createTarget(new SteinTexture[] {color}, null);
        SteinBuffer b1 =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "test: b1", 16, SteinBuffer.Memory.GPU, SteinBuffer.Usage.VERTEX));
        SteinBuffer b2 =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "test: b2", 16, SteinBuffer.Memory.GPU, SteinBuffer.Usage.VERTEX));
        SteinBuffer b3 =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "test: b3", 16, SteinBuffer.Memory.GPU, SteinBuffer.Usage.VERTEX));
        ByteBuffer a = bytes(16, 0x11);
        ByteBuffer b = bytes(16, 0x22);
        ByteBuffer c = bytes(16, 0x33);
        ByteBuffer d = bytes(16, 0x44);
        try {
            b1.upload(0, a);
            b3.upload(0, d);
            // a recorded pass (clears to red): from then on there is a pending list
            SteinCommands p =
                    dev.beginPass(SteinRenderPass.clear("test: clear", target, 1f, 0f, 0f, 1f));
            p.end();
            b1.upload(0, b); // recorded
            dev.copyBuffer(b1, 0, b2, 0, 16); // recorded: must see 0x22
            b1.upload(0, c); // recorded
            SteinFence f = dev.fence(); // recorded
            if (SteinGlFrame.recordingAvailable() && f.done()) {
                fails.add("order: recorded fence already passed before the replay");
            }
            SteinBuffer b4 =
                    dev.createBuffer(
                            new SteinBuffer.Desc(
                                    "test: b4",
                                    16,
                                    SteinBuffer.Memory.GPU,
                                    SteinBuffer.Usage.VERTEX));
            dev.copyBuffer(b3, 0, b4, 0, 16); // copy of b3, which is deleted right after
            b3.destroy();
            int v2 = read(dev, b2); // sync point
            int v1 = read(dev, b1);
            int v4 = read(dev, b4);
            if (v2 != 0x22) {
                fails.add(String.format("order: copy saw 0x%02x (expected 0x22)", v2));
            }
            if (v1 != 0x33) {
                fails.add(String.format("order: buffer ended as 0x%02x (expected 0x33)", v1));
            }
            if (v4 != 0x44) {
                fails.add(
                        String.format(
                                "order: copy before the deleted buffer saw 0x%02x (expected 0x44)",
                                v4));
            }
            if (!f.waitFor(1_000_000_000L)) {
                fails.add("order: fence did not pass");
            }
            f.destroy();
            b4.destroy();
            // the game's glClear recorded: green, on the target bound at that time, after a pass
            // that clears to red
            p = dev.beginPass(SteinRenderPass.clear("test: clear 2", target, 1f, 0f, 0f, 1f));
            p.end();
            int prev = SteinGlBindings.drawFbo();
            SteinGlBindings.bindDrawFramebuffer((int) target.handle());
            SteinGlTrack.glClearColor(0f, 1f, 0f, 1f);
            SteinGlTrack.glClear(GL11.GL_COLOR_BUFFER_BIT);
            SteinGlTrack.glClearColor(
                    0f, 0f, 0f, 0f); // the "game" changes it afterwards: it does not count
            SteinGlBindings.bindDrawFramebuffer(prev);
            ByteBuffer px = MemoryUtil.memAlloc(4 * 4 * 4);
            try {
                dev.readPixels(color, 0, 0, 4, 4, px); // sync point
                int r = px.get(0) & 0xFF;
                int g = px.get(1) & 0xFF;
                if (r != 0 || g != 255) {
                    fails.add(
                            "order: recorded game clear gave r="
                                    + r
                                    + " g="
                                    + g
                                    + " (expected green)");
                }
            } finally {
                MemoryUtil.memFree(px);
            }
            // sync point in the middle of a pass: the replayed part closes (the driver returns to
            // the game's state while the game runs) and the continuation reopens without clearing
            int fboBefore = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            p = dev.beginPass(SteinRenderPass.clear("test: split", target, 0f, 0f, 1f, 1f));
            p.setViewport(0, 0, 2, 2);
            read(dev, b2); // sync point with the pass open
            if (SteinGlFrame.recordingAvailable()
                    && GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != fboBefore) {
                fails.add("split: the pass stayed open in the driver during the sync");
            }
            p.setScissor(new int[] {0, 0, 1, 1});
            p.end();
            ByteBuffer px2 = MemoryUtil.memAlloc(4 * 4 * 4);
            try {
                dev.readPixels(color, 0, 0, 4, 4, px2);
                if ((px2.get(2) & 0xFF) != 255 || (px2.get(0) & 0xFF) != 0) {
                    fails.add(
                            "split: the blue clear of the split pass did not stick (did the"
                                    + " continuation clear again?)");
                }
            } finally {
                MemoryUtil.memFree(px2);
            }
            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) {
                fails.add("order: OpenGL error 0x" + Integer.toHexString(err));
            }
        } finally {
            MemoryUtil.memFree(a);
            MemoryUtil.memFree(b);
            MemoryUtil.memFree(c);
            MemoryUtil.memFree(d);
            b1.destroy();
            b2.destroy();
            target.destroy();
            color.destroy();
        }
        if (GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != 0) {
            fails.add("order: the draw framebuffer did not return to 0");
        }
    }
}
