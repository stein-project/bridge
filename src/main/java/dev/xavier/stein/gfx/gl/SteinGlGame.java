/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.engine.ModelBatch;
import dev.xavier.stein.gfx.SteinGameState;
import dev.xavier.stein.gfx.SteinLazyGl;

import org.lwjgl.opengl.GL11;

/**
 * The game as seen by the OpenGL backend: what it believes about the fixed state (the
 * GlStateManager cache, through {@link SteinGameState}), the engine batch and the deferred state.
 * In the game, it just forwards.
 *
 * <p>Outside the game ({@code SteinGlFrameTest} in bridge/test, with {@code
 * -Dsteinbridge.gfx.test.noGame=true}: there is no GlStateManager or engine to load) the game
 * "believes" the OpenGL default, which is the state the test leaves the driver in between passes.
 * The key is read once (a constant for the JIT); the game never turns it on. This is the test
 * switch.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlGame {

    static final boolean NO_GAME = SteinGlOptions.TEST_NO_GAME;

    private SteinGlGame() {}

    static void flushEngine() {
        if (!NO_GAME) {
            ModelBatch.flush();
        }
    }

    static boolean lazyDirty() {
        return !NO_GAME && SteinLazyGl.dirty();
    }

    static void lazySync() {
        if (!NO_GAME) {
            SteinLazyGl.sync();
        }
    }

    static int activeUnit() {
        return NO_GAME ? 0 : SteinGameState.activeUnit();
    }

    static int textureUnits() {
        return NO_GAME ? 0 : SteinGameState.textureUnits();
    }

    static int textureName(int unit) {
        return NO_GAME ? -1 : SteinGameState.textureName(unit);
    }

    static boolean blend() {
        return !NO_GAME && SteinGameState.blend();
    }

    static int blendSrc() {
        return NO_GAME ? GL11.GL_ONE : SteinGameState.blendSrc();
    }

    static int blendDst() {
        return NO_GAME ? GL11.GL_ZERO : SteinGameState.blendDst();
    }

    static int blendSrcAlpha() {
        return NO_GAME ? GL11.GL_ONE : SteinGameState.blendSrcAlpha();
    }

    static int blendDstAlpha() {
        return NO_GAME ? GL11.GL_ZERO : SteinGameState.blendDstAlpha();
    }

    static boolean depthTest() {
        return !NO_GAME && SteinGameState.depthTest();
    }

    static int depthFunc() {
        return NO_GAME ? GL11.GL_LESS : SteinGameState.depthFunc();
    }

    static boolean depthMask() {
        return NO_GAME || SteinGameState.depthMask();
    }

    static boolean cull() {
        return !NO_GAME && SteinGameState.cull();
    }

    static int cullMode() {
        return NO_GAME ? GL11.GL_BACK : SteinGameState.cullMode();
    }

    static boolean colorMask(int channel) {
        return NO_GAME || SteinGameState.colorMask(channel);
    }

    static boolean colorMaskAll() {
        return NO_GAME || SteinGameState.colorMaskAll();
    }

    static boolean colorMaskNone() {
        return !NO_GAME && SteinGameState.colorMaskNone();
    }

    static boolean polygonOffsetFill() {
        return !NO_GAME && SteinGameState.polygonOffsetFill();
    }

    static float polygonOffsetFactor() {
        return NO_GAME ? 0f : SteinGameState.polygonOffsetFactor();
    }

    static float polygonOffsetUnits() {
        return NO_GAME ? 0f : SteinGameState.polygonOffsetUnits();
    }

    static boolean logicOp() {
        return !NO_GAME && SteinGameState.logicOp();
    }

    static int logicOpMode() {
        return NO_GAME ? GL11.GL_COPY : SteinGameState.logicOpMode();
    }
}
