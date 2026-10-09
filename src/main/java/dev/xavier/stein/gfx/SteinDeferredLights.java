/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinEntityDeferred.*;

import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.gfx.gl.SteinCoreGl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * The entity lights and the Frame uniform block of the deferred entities: reads the lights in force
 * from the driver and writes the block once per flush.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredLights {

    /** The lights have not been read yet in this extra pass. */
    static boolean lightsStale;

    /**
     * Before recording a draw with light: the current lights, if the pass has not read them yet.
     */
    static void needLights() {
        if (lightsStale) {
            lightsStale = false;
            readLights(lights);
        }
    }

    // ---- entity lights: those in force when the pending item was recorded ----

    /**
     * Direction 0 (4), direction 1 (4), diffuse 0 (4), diffuse 1 (4), summed ambient (4): the
     * lights in force for everything pending. Read at the start of the pass and again after each
     * change by the game.
     */
    static final float[] lights = new float[20];

    static final float[] lightsNow = new float[20];

    /**
     * After each glLightModel and glPopAttrib of the game (patcher rules): RenderHelper redoes the
     * lights with the current matrix — the arrow layer on the body and the item frame do this in
     * the middle of the pass, and the game draws what comes after with the lights rotated. If they
     * changed, the pending items go out with the previous ones; the rest records with the new ones.
     * Redoing the same (the start of tile entities, the piston) flushes nothing.
     */
    public static void lightsChanged() {
        if (!capturing || GLMatrix.compiling() || lightsStale) {
            return; // (no draw with light yet in the pass: the current ones are read at the first)
        }
        readLights(lightsNow);
        if (Arrays.equals(lights, lightsNow)) {
            return;
        }
        flushInOrder();
        System.arraycopy(lightsNow, 0, lights, 0, lights.length);
    }

    /** Lights 0 and 1 and the ambient, from the driver (on or not by the GlStateManager cache). */
    static void readLights(float[] out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer b = stack.mallocFloat(16);
            SteinCoreGl.glGetFloatv(GL11.GL_LIGHT_MODEL_AMBIENT, b);
            float ar = b.get(0);
            float ag = b.get(1);
            float ab = b.get(2);
            for (int light = 0; light < 2; light++) {
                int id = GL11.GL_LIGHT0 + light;
                boolean on = SteinGameState.light(light);
                SteinCoreGl.glGetLightfv(id, GL11.GL_POSITION, b);
                float x = b.get(0);
                float y = b.get(1);
                float z = b.get(2);
                float len = (float) Math.sqrt(x * x + y * y + z * z);
                if (len > 0f) {
                    x /= len;
                    y /= len;
                    z /= len;
                }
                int dir = light * 4;
                out[dir] = x;
                out[dir + 1] = y;
                out[dir + 2] = z;
                out[dir + 3] = 0f;
                SteinCoreGl.glGetLightfv(id, GL11.GL_DIFFUSE, b);
                int dif = 8 + light * 4;
                for (int k = 0; k < 4; k++) {
                    out[dif + k] = on ? b.get(k) : 0f;
                }
                if (on) {
                    SteinCoreGl.glGetLightfv(id, GL11.GL_AMBIENT, b);
                    ar += b.get(0);
                    ag += b.get(1);
                    ab += b.get(2);
                }
            }
            out[16] = ar;
            out[17] = ag;
            out[18] = ab;
            out[19] = 1f;
        }
    }

    /** The Frame block: projection, the two entity lights, the fog — once per flush. */
    static void writeFrame(long p) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer b = stack.mallocFloat(16);
            SteinCoreGl.glGetFloatv(GL11.GL_PROJECTION_MATRIX, b);
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(p + i * 4L, b.get(i));
            }
            // directions (64), diffuse (96), ambient (128): the pending ones
            for (int i = 0; i < 20; i++) {
                MemoryUtil.memPutFloat(p + 64 + i * 4L, lights[i]);
            }
            SteinCoreGl.glGetFloatv(GL11.GL_FOG_COLOR, b);
            for (int k = 0; k < 4; k++) {
                MemoryUtil.memPutFloat(p + 144 + k * 4L, b.get(k));
            }
            int mode = SteinGameState.fogMode();
            MemoryUtil.memPutFloat(
                    p + 160, mode == GL11.GL_EXP ? 1f : mode == GL11.GL_EXP2 ? 2f : 0f);
            MemoryUtil.memPutFloat(p + 164, SteinGameState.fogDensity());
            MemoryUtil.memPutFloat(p + 168, SteinGameState.fogStart());
            MemoryUtil.memPutFloat(p + 172, SteinGameState.fogEnd());
        }
    }
}
