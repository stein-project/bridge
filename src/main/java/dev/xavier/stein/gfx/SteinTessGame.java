/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static java.lang.invoke.MethodType.methodType;

import dev.xavier.stein.bridge.engine.Obf;

import net.minecraft.client.renderer.GlStateManager;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * What the Tessellator capture keeps from the game: the on/off state of the four texture-coordinate
 * generators (s, t, r, q), resolved once; loaded only when the capture runs. The state classes
 * (GlStateManager$BooleanState and the like) are package-private in the game, which the compile jar
 * does not open, so they are read reflectively.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTessGame {
    private SteinTessGame() {}

    static final MethodHandle BOOL; // GlStateManager$BooleanState.currentState (bfl$c.b)
    static final Object[] TEXGEN =
            new Object[4]; // texGenState (bfl.k): s, t, r, q -> textureGen (bfl$p.a)

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            Class<?> bool = Obf.cls("bfl$c");
            BOOL =
                    MethodHandles.privateLookupIn(bool, l)
                            .findGetter(bool, "b", boolean.class)
                            .asType(methodType(boolean.class, Object.class));
            Class<?> genState = Obf.cls("bfl$q");
            Class<?> gen = Obf.cls("bfl$p");
            Object state = GlStateManager.texGenState;
            MethodHandles.Lookup gs = MethodHandles.privateLookupIn(genState, l);
            MethodHandle on = MethodHandles.privateLookupIn(gen, l).findGetter(gen, "a", bool);
            String[] coords = {"a", "b", "c", "d"};
            for (int i = 0; i < 4; i++) {
                Object p = gs.findGetter(genState, coords[i], gen).invoke(state);
                TEXGEN[i] = on.invoke(p);
            }
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
