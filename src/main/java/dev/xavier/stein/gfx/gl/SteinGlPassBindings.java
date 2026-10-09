/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

/**
 * The game's bindings around an OpenGL pass ({@link SteinGlCommands}): read at opening from {@link
 * SteinGlBindings} and the GlStateManager cache (without asking the driver), and given back at
 * {@link SteinGlCommands#end()} only where the pass changed them. The state itself lives in the
 * pass; this is the code that moves it.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlPassBindings {

    private SteinGlPassBindings() {}

    private static final int UNKNOWN = SteinGlCommands.UNKNOWN;

    /** Reads the game's bindings and starts the pass state as equal to them. */
    static void capture(SteinGlCommands cmd) {
        cmd.prevProgram = SteinGlBindings.program();
        cmd.prevVao = SteinGlBindings.vao();
        cmd.prevDrawFbo = SteinGlBindings.drawFbo();
        cmd.prevArrayBuffer = UNKNOWN;
        cmd.prevIndirectBuffer = SteinGlBindings.indirect();
        cmd.prevUniformBuffer = SteinGlBindings.uniform();
        cmd.prevStorageBuffer = cmd.compute() ? SteinGlBindings.storage() : 0;
        cmd.prevActive = GL13.GL_TEXTURE0 + SteinGlGame.activeUnit();
        cmd.program = cmd.prevProgram;
        cmd.vao = cmd.prevVao;
        // The active unit and the 2D textures come from the GlStateManager cache, which is only
        // right as long as nobody touches OpenGL outside of it. Once it is off (seen after
        // switching packs through the screen), skipping the call "because it is already there"
        // left the terrain reading another texture in place of the lightmap, black forever. The
        // first switch of each one in the pass always goes to the driver, and the return leaves
        // the driver equal to the cache: the mismatch disappears within the pass.
        cmd.active = UNKNOWN;
        cmd.arrayBuffer = UNKNOWN;
        cmd.indirectBuffer = cmd.prevIndirectBuffer;
        cmd.uniformGeneric = cmd.prevUniformBuffer;
        cmd.storageGeneric = cmd.prevStorageBuffer;
    }

    /** The texture and sampler of every unit the pass used go back to the game's. */
    static void restoreUnits(SteinGlCommands cmd) {
        long units = cmd.savedUnits;
        while (units != 0) {
            int slot = Long.numberOfTrailingZeros(units);
            units &= units - 1;
            boolean arr = cmd.curArray[slot] != cmd.prevTexArray[slot];
            boolean tex = cmd.cur2D[slot] != cmd.prevTex2D[slot];
            if (arr || tex) {
                cmd.activeUnit(slot);
                if (arr) {
                    GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, cmd.prevTexArray[slot]);
                }
                if (tex) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, cmd.prevTex2D[slot]);
                }
            }
            if (cmd.curSampler[slot] != cmd.prevSampler[slot]) {
                GL33.glBindSampler(slot, cmd.prevSampler[slot]);
            }
        }
    }

    /**
     * The generic buffers, program, VAO and, last, the active unit (the draw framebuffer and the
     * fixed state were given back before).
     */
    static void restoreRest(SteinGlCommands cmd) {
        if (cmd.prevArrayBuffer != UNKNOWN && cmd.arrayBuffer != cmd.prevArrayBuffer) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cmd.prevArrayBuffer);
        }
        if (cmd.touchedIndirect && cmd.indirectBuffer != cmd.prevIndirectBuffer) {
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, cmd.prevIndirectBuffer);
        }
        if (cmd.uniformGeneric != cmd.prevUniformBuffer) {
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, cmd.prevUniformBuffer);
        }
        if (cmd.compute() && cmd.storageGeneric != cmd.prevStorageBuffer) {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, cmd.prevStorageBuffer);
        }
        if (cmd.program != cmd.prevProgram) {
            GL20.glUseProgram(cmd.prevProgram);
        }
        if (cmd.vao != cmd.prevVao) {
            GL30.glBindVertexArray(cmd.prevVao);
        }
        if (cmd.active != cmd.prevActive) {
            GL13.glActiveTexture(cmd.prevActive);
        }
    }
}
