/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

// How to run (standalone, outside the game; not part of the Bridge jar): ./gradlew gpuTimingTest
//   (it passes -Dsteinbridge.gfx.gpuPassTiming=true).

package dev.xavier.stein.gfx.gl;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/**
 * Test of {@link SteinGlGpuTiming} outside the game: an OpenGL 4.6 core context in a hidden window,
 * five fake passes per frame with known GPU work (20 clears each; "loading" with 200, so ~10x the
 * others), two "terrain" (numbered) and two "hud" (summed). The ring wraps around several times;
 * with {@code -Dsteinbridge.gfx.gpuPassTiming.slots=16} it fills up and discards. Expected:
 * plausible microseconds, no "invalid".
 *
 * <p>{@code ./gradlew gpuTimingTest [-Pstein.args=seconds]}
 */
public final class SteinGlGpuTimingTest {

    private SteinGlGpuTimingTest() {}

    public static void main(String[] args) {
        // As Display does in the game, before SteinGfxOptions loads: the context below is core.
        dev.xavier.stein.bridge.core.Win.coreContext = true;
        if (!SteinGlGpuTiming.ON) {
            System.out.println("run with -Dsteinbridge.gfx.gpuPassTiming=true");
            return;
        }
        GLFW.glfwInit();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(256, 256, "test", 0, 0);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        long seconds = args.length > 0 ? Long.parseLong(args[0]) : 11;
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        String[] names = {"terrain", "terrain", "hud", "hud", "loading"};
        int frames = 0;
        while (System.nanoTime() < end) {
            for (String n : names) {
                int slot = SteinGlGpuTiming.begin(n);
                int clears = n.equals("loading") ? 200 : 20;
                for (int i = 0; i < clears; i++) {
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                }
                SteinGlGpuTiming.end(slot);
            }
            SteinGlGpuTiming.frame();
            if (++frames % 3 == 0) {
                GLFW.glfwSwapBuffers(window);
            }
        }
        System.out.println("[gfx] GPU time test: " + frames + " frames");
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }
}
