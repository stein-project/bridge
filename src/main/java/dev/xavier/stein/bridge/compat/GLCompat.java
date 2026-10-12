/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.compat;

import dev.xavier.stein.gfx.gl.SteinCoreGl;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Adapters for the LWJGL 2 calls that LWJGL 3 does not cover by renaming. The patcher redirects
 * Minecraft's call sites to here.
 *
 * <p>Deliberately not in {@code org.lwjgl.*}: those classes would collide with LWJGL 3 on the
 * classpath.
 */
public final class GLCompat {

    private GLCompat() {}

    /**
     * LWJGL 2 accepted the shader source as a {@code ByteBuffer}; LWJGL 3 only offers {@code
     * CharSequence} or {@code PointerBuffer}.
     */
    public static void glShaderSource(int shader, ByteBuffer source) {
        GL20.glShaderSource(shader, decode(source));
    }

    /**
     * glGetString from any thread. The game's crash report reads the card's name ("OpenGL" section)
     * on the thread that crashed; a chunk thread has no GL context, and LWJGL 3 — unlike 2, which
     * returned null — aborts the JVM right there: the game closed without a report and without
     * showing the error that caused it.
     */
    public static String glGetString(int name) {
        if (GLFW.glfwGetCurrentContext() == 0L) {
            return "(no GL context on thread " + Thread.currentThread().getName() + ")";
        }
        return SteinCoreGl.glGetString(name);
    }

    /** ARB variant of the call above. */
    public static void glShaderSourceARB(int shaderObj, ByteBuffer source) {
        ARBShaderObjects.glShaderSourceARB(shaderObj, decode(source));
    }

    /**
     * Reads the remaining bytes without consuming the buffer — the caller can reuse it.
     *
     * <p>Strips trailing NULs: LWJGL 2 received an explicit length, so a terminator in the buffer
     * never reached the driver. Keeping it here would make GLSL compilation fail.
     */
    private static String decode(ByteBuffer source) {
        ByteBuffer view = source.duplicate();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);

        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
