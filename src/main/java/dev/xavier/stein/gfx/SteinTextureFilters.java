/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.ModelBatch;
import dev.xavier.stein.gfx.gl.SteinCoreGl;
import dev.xavier.stein.gfx.gl.SteinGlFrame;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;

/**
 * Bookkeeping of the game textures' min/mag filters while a pass is recorded (the atlas switches
 * between item and world filtering in the middle of the pass): which filter each texture has, which
 * ones the driver has not received yet, and how to apply or restore one.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTextureFilters {
    private SteinTextureFilters() {}

    private static final int[] MIN_FILTERS = {
        GL11.GL_NEAREST,
        GL11.GL_LINEAR,
        GL11.GL_NEAREST_MIPMAP_NEAREST,
        GL11.GL_LINEAR_MIPMAP_NEAREST,
        GL11.GL_NEAREST_MIPMAP_LINEAR,
        GL11.GL_LINEAR_MIPMAP_LINEAR
    };
    private static int[] minOf = new int[4096];
    private static int[] magOf = new int[4096];

    /**
     * Per texture: the filter requested in the recorded pass and not yet applied to the driver (bit
     * 1 = min, bit 2 = mag).
     */
    private static byte[] filterPending = new byte[4096];

    private static int[] pendingTex = new int[16];
    private static int pendingCount;

    private static void ensureFilter(int tex) {
        if (tex >= minOf.length) {
            int n = Math.max(tex + 1, minOf.length * 2);
            minOf = Arrays.copyOf(minOf, n);
            magOf = Arrays.copyOf(magOf, n);
            filterPending = Arrays.copyOf(filterPending, n);
        }
    }

    /**
     * GL11.glTexParameteri inside AbstractTexture.setBlurMipmapDirect and
     * TextureUtil.setTextureBlurMipmap (patcher rules, solo mode: the batch flush before the call
     * is done here): records the bound texture's filter and moves on. RenderItem turns the atlas
     * mipmap off for each item and restores it afterwards; the recorded draw comes out with the
     * filter it had when recorded.
     *
     * <p>With the deferred state ({@link SteinLazyGl}), the filter is only registered: sending it
     * to the driver would require first applying all the deferred state (the texture actually
     * bound) and two calls per item, four times per player holding a sword. It goes to the driver
     * with the rest of the state ({@link #syncFilters}), when something draws immediately, and
     * before each flush of the recorded data — the item's back-and-forth almost always ends at the
     * value the driver already has.
     */
    static void texParameter(int target, int pname, int value) {
        boolean filter =
                target == GL11.GL_TEXTURE_2D
                        && (pname == GL11.GL_TEXTURE_MIN_FILTER
                                || pname == GL11.GL_TEXTURE_MAG_FILTER);
        if (filter && SteinLazyGl.deferring()) {
            int tex = SteinGameState.textureName(SteinGameState.activeUnit());
            if (tex > 0) {
                // Parts of today's batch still pending go out with the previous filter.
                SteinLazyGl.before();
                ensureFilter(tex);
                int bit;
                if (pname == GL11.GL_TEXTURE_MIN_FILTER) {
                    minOf[tex] = value;
                    bit = 1;
                } else {
                    magOf[tex] = value;
                    bit = 2;
                }
                if (filterPending[tex] == 0) {
                    if (pendingCount == pendingTex.length) {
                        pendingTex = Arrays.copyOf(pendingTex, pendingCount * 2);
                    }
                    pendingTex[pendingCount++] = tex;
                }
                filterPending[tex] |= (byte) bit;
                return;
            }
        }
        // The batch's "before" hook, which the solo rule removes.
        ModelBatch.flush();
        if (SteinEntityDeferred.REQUESTED && filter) {
            int tex = SteinGameState.textureName(SteinGameState.activeUnit());
            if (tex > 0) {
                ensureFilter(tex);
                if (pname == GL11.GL_TEXTURE_MIN_FILTER) {
                    minOf[tex] = value;
                } else {
                    magOf[tex] = value;
                }
                filterPending[tex] &= (byte) (pname == GL11.GL_TEXTURE_MIN_FILTER ? ~1 : ~2);
            }
        }
        // through SteinCoreGl: with a pending recorded frame the parameter enters the list, by name
        SteinCoreGl.glTexParameteri(target, pname, value);
    }

    /** Is there a registered filter waiting for the driver? */
    static boolean filtersPending() {
        return pendingCount != 0;
    }

    /**
     * Applies to the driver the filters registered in the pass (in {@link SteinLazyGl#sync} and
     * before each flush): after this the driver has what {@link #filterCode} says.
     */
    static void syncFilters() {
        int n = pendingCount;
        if (n == 0) {
            return;
        }
        pendingCount = 0;
        boolean dsa = GL.getCapabilities().OpenGL45;
        for (int i = 0; i < n; i++) {
            int tex = pendingTex[i];
            int bits = filterPending[tex];
            filterPending[tex] = 0;
            if (bits == 0) {
                continue;
            }
            if (dsa) {
                if ((bits & 1) != 0) {
                    SteinGlFrame.textureParameteri(tex, GL11.GL_TEXTURE_MIN_FILTER, minOf[tex]);
                }
                if ((bits & 2) != 0) {
                    SteinGlFrame.textureParameteri(tex, GL11.GL_TEXTURE_MAG_FILTER, magOf[tex]);
                }
                continue;
            }
            // No direct access (4.1): binds on the active unit and restores the one that was
            // actually bound.
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            if ((bits & 1) != 0) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minOf[tex]);
            }
            if ((bits & 2) != 0) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magOf[tex]);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    /**
     * The texture was deleted: the registered filter no longer goes to it, and the known one goes
     * away (the name may come back for another texture, which is born with the driver's filter:
     * {@link #filterCode} returns 0, "unknown").
     */
    static void forgetFilter(int tex) {
        if (tex > 0 && tex < filterPending.length) {
            filterPending[tex] = 0;
        }
        if (tex > 0 && tex < minOf.length) {
            minOf[tex] = 0;
            magOf[tex] = 0;
        }
    }

    /** The texture's known filter: 1 + (min index * 2 + mag index), or 0 if unknown. */
    static int filterCode(int tex) {
        if (tex <= 0 || tex >= minOf.length) {
            return 0;
        }
        int min = -1;
        for (int i = 0; i < MIN_FILTERS.length; i++) {
            if (MIN_FILTERS[i] == minOf[tex]) {
                min = i;
            }
        }
        int mag = magOf[tex] == GL11.GL_NEAREST ? 0 : magOf[tex] == GL11.GL_LINEAR ? 1 : -1;
        return min < 0 || mag < 0 ? 0 : 1 + min * 2 + mag;
    }

    /**
     * Sets filter {@code code} on the texture (in the recorded draw); returns what was there, for
     * {@link #restore}.
     */
    static int applyFilter(int tex, int code) {
        int now = filterCode(tex);
        if (code == 0 || now == 0 || now == code) {
            return 0;
        }
        setFilter(tex, code);
        return now;
    }

    static void restore(int tex, int code) {
        if (code != 0) {
            setFilter(tex, code);
        }
    }

    private static void setFilter(int tex, int code) {
        int c = code - 1;
        int min = MIN_FILTERS[c >> 1];
        int mag = (c & 1) == 0 ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        if (GL.getCapabilities().OpenGL45) {
            SteinGlFrame.textureParameteri(tex, GL11.GL_TEXTURE_MIN_FILTER, min);
            SteinGlFrame.textureParameteri(tex, GL11.GL_TEXTURE_MAG_FILTER, mag);
            return;
        }
        // No direct access (4.1): binds on the active unit and restores the one that was actually
        // bound.
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, min);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, mag);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
    }
}
