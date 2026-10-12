/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package org.lwjgl.util.glu;

import dev.xavier.stein.bridge.engine.GLMatrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GLU projection routines. Removed in LWJGL 3.
 *
 * <p>Unlike the other ported classes, this one calls OpenGL — and it can do so because {@code
 * org.lwjgl.util.glu.Project} does not exist in LWJGL 3, so there is no collision and the {@code
 * GL11} resolved here is LWJGL 3's own.
 */
public final class Project {

    /** Reused on every call: gluPerspective runs once per frame. */
    private static final ThreadLocal<FloatBuffer> SCRATCH =
            ThreadLocal.withInitial(
                    () ->
                            ByteBuffer.allocateDirect(16 * Float.BYTES)
                                    .order(ByteOrder.nativeOrder())
                                    .asFloatBuffer());

    private Project() {}

    /**
     * Multiplies the current matrix by the perspective matrix, like OpenGL's {@code
     * gluPerspective}.
     *
     * @param fovy vertical field-of-view angle, in degrees
     */
    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) {
        float radians = (float) Math.toRadians(fovy / 2.0f);
        float deltaZ = zFar - zNear;
        float sine = (float) Math.sin(radians);
        if (deltaZ == 0.0f || sine == 0.0f || aspect == 0.0f) {
            return;
        }
        float cotangent = (float) Math.cos(radians) / sine;

        FloatBuffer m = SCRATCH.get();
        m.clear();
        // Column-major, in the order glMultMatrixf expects.
        m.put(cotangent / aspect).put(0).put(0).put(0);
        m.put(0).put(cotangent).put(0).put(0);
        m.put(0).put(0).put(-(zFar + zNear) / deltaZ).put(-1);
        m.put(0).put(0).put(-2 * zNear * zFar / deltaZ).put(0);
        m.flip();

        // Through the SteinBridge stack, not straight to GL: the driver's matrix mode
        // may lag behind the game's (GLMatrix defers glMatrixMode).
        try {
            GLMatrix.multMatrix(m);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
