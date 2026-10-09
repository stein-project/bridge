/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinEntityDeferred.*;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;

import java.util.Arrays;

/**
 * The names above heads for {@link SteinEntityDeferred}: stored during the entity pass and drawn at
 * the end, after all the bodies.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredNames {

    // ---- names: after all the bodies ----

    static Object[] nameRender = new Object[64];
    static Object[] nameEntity = new Object[64];
    static double[] nameXyz = new double[64 * 3];
    static float[] nameLight = new float[64 * 2];
    static int names;

    /** Render.renderName inside doRender (patcher rule): during the pass, stores it for the end. */
    public static void renderName(Object render, Object entity, double x, double y, double z)
            throws Throwable {
        // With the colour mask off nothing shows: immediate, like the
        // game.
        if (!capturing || draining || !SteinGameState.colorMaskAll()) {
            ((Render) render).renderName((Entity) entity, x, y, z);
            return;
        }
        if (names == nameRender.length) {
            int n = names * 2;
            nameRender = Arrays.copyOf(nameRender, n);
            nameEntity = Arrays.copyOf(nameEntity, n);
            nameXyz = Arrays.copyOf(nameXyz, n * 3);
            nameLight = Arrays.copyOf(nameLight, n * 2);
        }
        nameRender[names] = render;
        nameEntity[names] = entity;
        nameXyz[names * 3] = x;
        nameXyz[names * 3 + 1] = y;
        nameXyz[names * 3 + 2] = z;
        nameLight[names * 2] = SteinGameState.lightmapU();
        nameLight[names * 2 + 1] = SteinGameState.lightmapV();
        names++;
    }

    static void drainNames() {
        int n = names;
        names = 0;
        if (n == 0) {
            return;
        }
        // renderEntities ends up turning the lightmap off (EntityRenderer.disableLightmap); the
        // names, which the game draws inside it, go out with the lightmap on — turns on unit 1's
        // texture for the drain and turns it off afterwards.
        boolean lightmapOff = !SteinGameState.texture2D(1);
        try {
            if (lightmapOff) {
                LightmapUnit.set(true);
            }
            // Direct label (SteinNameLabels): the common case goes out as a record and commands;
            // the
            // rest through the game.
            boolean labels = SteinNameLabels.REQUESTED;
            if (labels) {
                SteinNameLabels.begin();
            }
            try {
                for (int i = 0; i < n; i++) {
                    SteinGameState.setLightmap(nameLight[i * 2], nameLight[i * 2 + 1]);
                    if (labels
                            && SteinNameLabels.draw(
                                    nameRender[i],
                                    nameEntity[i],
                                    nameXyz[i * 3],
                                    nameXyz[i * 3 + 1],
                                    nameXyz[i * 3 + 2])) {
                        continue;
                    }
                    if (labels) {
                        SteinNameLabels.slow();
                    }
                    ((Render) nameRender[i])
                            .renderName(
                                    (Entity) nameEntity[i],
                                    nameXyz[i * 3],
                                    nameXyz[i * 3 + 1],
                                    nameXyz[i * 3 + 2]);
                }
            } finally {
                if (labels) {
                    SteinNameLabels.end();
                }
            }
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        } finally {
            if (lightmapOff) {
                try {
                    LightmapUnit.set(false);
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            }
            Arrays.fill(nameRender, 0, n, null);
            Arrays.fill(nameEntity, 0, n, null);
        }
    }

    /**
     * Turns the lightmap unit's texture on and off through the GlStateManager (setActiveTexture,
     * enableTexture2D, disableTexture2D; OpenGlHelper's default and lightmap units).
     */
    static final class LightmapUnit {
        static final int DEFAULT_UNIT = OpenGlHelper.defaultTexUnit;
        static final int LIGHTMAP_UNIT = OpenGlHelper.lightmapTexUnit;

        static void set(boolean on) {
            GlStateManager.setActiveTexture(LIGHTMAP_UNIT);
            if (on) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            GlStateManager.setActiveTexture(DEFAULT_UNIT);
        }
    }
}
