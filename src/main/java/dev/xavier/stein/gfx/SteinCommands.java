/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

/**
 * The commands of a pass: bind pipeline and resources per slot, draw. On OpenGL they run
 * immediately; on Vulkan they are recorded into a command buffer. The contract is the same:
 * everything that is bound holds until the end of the pass or until it is replaced, and {@link
 * #end()} closes the pass (on OpenGL, it restores the game state as it was).
 *
 * <p>Indirect drawing: the buffer holds 16-byte commands {@code (count, instanceCount, first,
 * baseInstance)} for {@link #drawIndirect}, or 20-byte commands {@code (count, instanceCount,
 * firstIndex, baseVertex, baseInstance)} for {@link #drawIndexedIndirect}.
 *
 * <p>Pipeline: Stein GFX only.
 */
public abstract class SteinCommands {

    public enum IndexType {
        UINT16,
        UINT32
    }

    public abstract void bindPipeline(SteinPipeline pipeline);

    /**
     * The buffer at binding {@code index} of the pipeline layout, starting at {@code offset} bytes.
     */
    public abstract void bindVertexBuffer(int index, SteinBuffer buffer, long offset);

    public abstract void bindIndexBuffer(SteinBuffer buffer, IndexType type);

    /** Texture and sampler in the slot (the slot comes from the sampler name in the pipeline). */
    public abstract void bindTexture(int slot, SteinTexture texture, SteinSampler sampler);

    /**
     * Uniform block (std140) in the slot: {@code size} bytes of {@code buffer} starting at {@code
     * offset}.
     */
    public abstract void bindUniforms(int slot, SteinBuffer buffer, long offset, long size);

    /** Storage block (std430) in the slot. */
    public abstract void bindStorage(int slot, SteinBuffer buffer, long offset, long size);

    public abstract void setViewport(int x, int y, int w, int h);

    /** Scissor; null disables it. */
    public abstract void setScissor(int[] rect);

    public abstract void draw(
            int vertexCount, int instanceCount, int firstVertex, int firstInstance);

    public abstract void drawIndexed(
            int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance);

    /** {@code count} 16-byte commands in {@code buffer} starting at {@code offset}. */
    public abstract void drawIndirect(SteinBuffer buffer, long offset, int count);

    /** {@code count} 20-byte commands in {@code buffer} starting at {@code offset}. */
    public abstract void drawIndexedIndirect(SteinBuffer buffer, long offset, int count);

    /**
     * Compute (the bound pipeline is a compute one). The results are visible to the following
     * draws.
     */
    public abstract void dispatch(int x, int y, int z);

    /**
     * Clears the target inside {@code rect} (x, y, width, height; null = all of it), the way the
     * game's glClear does: the colour attachments with ({@code r, g, b, a}) when {@code color}, the
     * depth with {@code depth} when {@code clearDepth}; no other drawing state applies. Only the
     * Vulkan backend has it (on OpenGL the game's own glClear reaches the driver).
     */
    public void clear(
            boolean color,
            float r,
            float g,
            float b,
            float a,
            boolean clearDepth,
            float depth,
            int[] rect) {
        throw new UnsupportedOperationException("clear inside a pass: Vulkan only");
    }

    /**
     * Depth bias (OpenGL's polygon offset) for the following draws: {@code factor} times the depth
     * slope plus {@code units} times the smallest depth step; off with {@code on} false. Dynamic
     * state, as in Vulkan: it lasts until changed or until the pass ends.
     */
    public abstract void setDepthBias(boolean on, float factor, float units);

    /** Ends the pass. */
    public abstract void end();
}
