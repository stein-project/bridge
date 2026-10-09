/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Which renderer classes are the common case for the direct name label ({@link SteinNameLabels}):
 * what counts is whether the classes on the path override anything of the name.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinLabelKinds {

    /**
     * RendererLivingEntity with nothing of the name overridden; and RenderPlayer (the scoreboard
     * line below the name).
     */
    static final int LIVING = 1;

    static final int PLAYER = 2;

    private SteinLabelKinds() {}

    /** The kind of the renderer class: 0 (through the game), {@link #LIVING} or {@link #PLAYER}. */
    static int of(Class<?> renderer) {
        return KIND.get(renderer);
    }

    /**
     * Per renderer class: 0 = something of the name overridden (goes through the game), {@link
     * #LIVING}, {@link #PLAYER}. What counts is the class that declares each method on the path:
     * renderName (Render.renderName and RendererLivingEntity.renderName), renderOffsetLivingLabel
     * (only RenderPlayer's, which adds the scoreboard line), renderLivingLabel and the font
     * (Render.getFontRendererFromRenderManager). canRenderName may come from any of them: it is
     * called through virtual dispatch, as in the game.
     */
    private static final ClassValue<Integer> KIND =
            new ClassValue<>() {
                @Override
                protected Integer computeValue(Class<?> type) {
                    if (!RendererLivingEntity.class.isAssignableFrom(type)) {
                        return 0;
                    }
                    boolean player = false;
                    for (Class<?> k = type;
                            k != RendererLivingEntity.class;
                            k = k.getSuperclass()) {
                        if (k == RenderPlayer.class) {
                            player = true;
                        }
                        for (Method m : k.getDeclaredMethods()) {
                            if (Modifier.isStatic(m.getModifiers())) {
                                continue;
                            }
                            boolean playerLabel = k == RenderPlayer.class && isOffsetLabel(m);
                            if (overridesName(m) && !playerLabel) {
                                return 0;
                            }
                        }
                    }
                    return player ? PLAYER : LIVING;
                }
            };

    // The method names below are the obfuscated ones: they are matched against the declared methods
    // of the running game's classes (getDeclaredMethods), which have no readable names.
    private static boolean entityParam(Class<?> c) {
        return Entity.class.isAssignableFrom(c);
    }

    private static boolean isOffsetLabel(Method m) {
        Class<?>[] p = m.getParameterTypes();
        return m.getName().equals("a")
                && p.length == 7
                && entityParam(p[0])
                && p[1] == double.class
                && p[2] == double.class
                && p[3] == double.class
                && p[4] == String.class
                && p[5] == float.class
                && p[6] == double.class;
    }

    private static boolean overridesName(Method m) {
        Class<?>[] p = m.getParameterTypes();
        String n = m.getName();
        if (isOffsetLabel(m)) {
            return true;
        }
        if (p.length == 4
                && entityParam(p[0])
                && p[1] == double.class
                && p[2] == double.class
                && p[3] == double.class) {
            // b(pr, DDD) is the living entity's renderName; a(pk, DDD) is Render's (a(pr, DDD) is
            // renderLivingAt)
            return n.equals("b") || n.equals("a") && p[0] == Entity.class;
        }
        if (n.equals("a")
                && p.length == 6
                && entityParam(p[0])
                && p[1] == String.class
                && p[2] == double.class
                && p[3] == double.class
                && p[4] == double.class
                && p[5] == int.class) {
            return true; // renderLivingLabel
        }
        return n.equals("c") && p.length == 0 && m.getReturnType() == FontRenderer.class;
    }
}
