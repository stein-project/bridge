/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.KHRDebug;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenGL debug output ({@code -Dsteinbridge.gfx.glDebug=true}; debug context requested on the
 * Display): each distinct driver error message is logged once, with the Java stack of the caller
 * (the output is synchronous: the message arrives inside the call itself), and the total count
 * every 5 s. Meant for the port to the core profile (what still calls the fixed-function pipeline)
 * and for the AMD hangs (the driver usually warns beforehand).
 *
 * <p>Pipeline: both — driver debug output, installed in either context.
 */
public final class SteinGlDebug {

    private static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;
    private static final int MAX_DISTINCT_MESSAGES = 60;
    private static final int MAX_STACK_LINES = 7;

    public static final boolean ENABLED = SteinGlOptions.GL_DEBUG;
    private static GLDebugMessageCallback callback;
    private static final Map<String, int[]> SEEN = new HashMap<>();
    private static long total;
    private static long reportAt = System.nanoTime();

    private SteinGlDebug() {}

    /** After GL.createCapabilities, with the context current. */
    public static void install() {
        if (!ENABLED || callback != null) {
            return;
        }
        boolean khr = GL.getCapabilities().GL_KHR_debug;
        if (!GL.getCapabilities().OpenGL43 && !khr) {
            Log.info("gl-debug", "the driver has no KHR_debug");
            return;
        }
        callback =
                GLDebugMessageCallback.create(
                        (source, type, id, severity, length, message, user) -> {
                            if (type != GL43.GL_DEBUG_TYPE_ERROR
                                    && severity != GL43.GL_DEBUG_SEVERITY_HIGH) {
                                // Only errors are what the driver calls severe (the rest is
                                // informational or performance-related)
                                return;
                            }
                            total++;
                            String msg = GLDebugMessageCallback.getMessage(length, message);
                            int[] n = SEEN.computeIfAbsent(msg, k -> new int[1]);
                            if (n[0]++ == 0 && SEEN.size() <= MAX_DISTINCT_MESSAGES) {
                                StringBuilder b = new StringBuilder("").append(msg);
                                StackTraceElement[] st = new Throwable().getStackTrace();
                                for (int i = 1, shown = 0;
                                        i < st.length && shown < MAX_STACK_LINES;
                                        i++) {
                                    String c = st[i].getClassName();
                                    if (c.startsWith("org.lwjgl.")
                                            || c.startsWith("jdk.")
                                            || c.startsWith("java.")) {
                                        continue;
                                    }
                                    b.append("\n[gl-debug]     ").append(st[i]);
                                    shown++;
                                }
                                Log.info("gl-debug", b.toString());
                            }
                            long now = System.nanoTime();
                            if (now - reportAt > REPORT_INTERVAL_NANOS) {
                                reportAt = now;
                                Log.info(
                                        "gl-debug",
                                        total
                                                + " errors in 5 s, "
                                                + SEEN.size()
                                                + " distinct messages");
                                total = 0;
                            }
                        });
        if (GL.getCapabilities().OpenGL43) {
            GL43.glDebugMessageCallback(callback, 0L);
            GL11.glEnable(GL43.GL_DEBUG_OUTPUT);
            GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        } else {
            KHRDebug.glDebugMessageCallback(callback, 0L);
            GL11.glEnable(KHRDebug.GL_DEBUG_OUTPUT);
            GL11.glEnable(KHRDebug.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        }
        Log.info("gl-debug", "OpenGL debug output enabled");
    }
}
