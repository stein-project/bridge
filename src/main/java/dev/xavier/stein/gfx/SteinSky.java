/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.vertex.VertexBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The sky in Stein's graphics API (renderer stage 4). The game's RenderGlobal.renderSky stays as is
 * — colors, angles, sun, moon and what shows up —, recorded like the entity pass ({@link
 * SteinEntityDeferred#skyBegin}) and in strict order. Its three fixed VBOs (stars, dome and the
 * void below the horizon) are registered in the recording's fixed geometry when the game fills them
 * ({@link #stars}, {@link #sky}, {@link #sky2}), one range per role; at draw time ({@link #draw})
 * they become an instance with the current matrix, with no per-frame conversion.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinSky {

    /**
     * The roles (owner of the range in the fixed geometry): the same each time the game rebuilds
     * the sky.
     */
    private static final Object STARS = new Object();

    private static final Object SKY = new Object();
    private static final Object SKY2 = new Object();

    /** Game VBO -> role. */
    private static final Map<Object, Object> ROLE = new IdentityHashMap<>();

    private SteinSky() {}

    /** In place of starVBO.bufferData(...) in RenderGlobal.generateStars. */
    public static void stars(Object vb, Object data) throws Throwable {
        register(STARS, vb, data);
    }

    /** In place of skyVBO.bufferData(...) in RenderGlobal.generateSky. */
    public static void sky(Object vb, Object data) throws Throwable {
        register(SKY, vb, data);
    }

    /** In place of sky2VBO.bufferData(...) in RenderGlobal.generateSky2. */
    public static void sky2(Object vb, Object data) throws Throwable {
        register(SKY2, vb, data);
    }

    private static void register(Object role, Object vb, Object buffer) throws Throwable {
        ByteBuffer data = (ByteBuffer) buffer;
        ((VertexBuffer) vb).bufferData(data);
        // Forgets the old VBO of this role (the game rebuilds the sky on reload).
        ROLE.values().remove(role);
        // All three are quads in the position-only format (the game builds them with begin(7,
        // POSITION)).
        boolean registered =
                SteinEntityDeferred.REQUESTED
                        && SteinTessCapture.slot(
                                role,
                                MemoryUtil.memAddress(data),
                                ((VertexBuffer) vb).vertexFormat,
                                GL11.GL_QUADS,
                                ((VertexBuffer) vb).count);
        if (registered) {
            ROLE.put(vb, role);
        }
    }

    /** In place of vbo.drawArrays(mode) in RenderGlobal.renderSky. */
    public static void draw(Object vb, int mode) throws Throwable {
        Object role = ROLE.get(vb);
        if (role != null && mode == GL11.GL_QUADS) {
            SteinEntityDeferred.skyNoUv = true;
            try {
                if (SteinTessCapture.drawSlot(role)) {
                    return;
                }
            } finally {
                SteinEntityDeferred.skyNoUv = false;
            }
        }
        ((VertexBuffer) vb).drawArrays(mode);
    }

    /**
     * In place of GlStateManager.callList(list) in renderSky. At its end the game always calls the
     * void list (glSkyList2), also in VBO mode, where that list is never filled (the void goes
     * through the VBO): the call draws nothing, and it is the last one of the old pipeline in the
     * sky. With Stein's renderer and the sky in VBOs (the VBOs registered here), it is skipped; the
     * other renderSky lists only run outside VBO mode and stay as in the game.
     */
    public static void callList(int list) throws Throwable {
        if (SteinEntityDeferred.REQUESTED && !ROLE.isEmpty()) {
            return;
        }
        GlStateManager.callList(list);
    }
}
