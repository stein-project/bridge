/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.gfx.SteinBuffer;
import dev.xavier.stein.gfx.SteinCaps;
import dev.xavier.stein.gfx.SteinCommands;
import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinRenderPass;
import dev.xavier.stein.gfx.SteinSampler;
import dev.xavier.stein.gfx.SteinTexture;

/**
 * A pass of the recorded frame ({@link SteinGlFrame}): each command goes into the list with the
 * OpenGL names of the moment (a buffer deleted later keeps a valid name until replay, because the
 * delete also goes into the list), and {@link SteinGlCommands} executes them at replay. The errors
 * the immediate pass would give (binding out of range, draw with compute bound, indexed without an
 * index, a resource the device does not have) come out here, at recording, as they would there.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlRecorder extends SteinCommands {

    private static final int MAX_BINDINGS = SteinGlLimits.MAX_VERTEX_BINDINGS;
    private static final int MAX_UNITS = SteinGlLimits.MAX_TEXTURE_UNITS;

    /** Uniform and storage binding points each; ranges hold both (storage after uniform). */
    private static final int BUFFER_POINTS = SteinGlLimits.BUFFER_POINTS;

    private static final int BUFFER_RANGES = 2 * BUFFER_POINTS;

    private final SteinCaps caps;
    private final SteinRenderPass pass;
    private boolean ended;
    private SteinGlPipeline pipeline;

    // ---- what is bound in the pass: to continue the pass after a synchronisation in the middle
    // of it ----
    private SteinPipeline boundPipeline;
    private boolean pipelineSet;
    private final int[] vertexBufferHandle = new int[MAX_BINDINGS];
    private final long[] vertexBufferOffset = new long[MAX_BINDINGS];
    private int vertexBindingMask;
    private int indexHandle;
    private boolean indexUint16;
    private boolean indexSet;
    private final int[] texId = new int[MAX_UNITS];
    private final int[] texArrayId = new int[MAX_UNITS];
    private final int[] texSampler = new int[MAX_UNITS];
    private long texture2dMask;
    private long texArraySet;
    private final int[] bufHandle = new int[BUFFER_RANGES];
    private final long[] bufOffset = new long[BUFFER_RANGES];
    private final long[] bufSize = new long[BUFFER_RANGES];
    private final boolean[] bufSet = new boolean[BUFFER_RANGES];
    private final int[] viewport = new int[4];
    private boolean viewportSet;
    private boolean scissorSet;
    private boolean scissorOn;
    private final int[] scissor = new int[4];
    private boolean offsetSet;
    private boolean offsetOn;
    private float offsetFactor;
    private float offsetUnits;

    /** The line width of the opening recording (the continuation uses the same one). */
    float lineWidth;

    SteinGlRecorder(SteinGlDevice device, SteinRenderPass pass) {
        SteinGlGame.flushEngine(); // the engine's batch goes out first, as in the immediate pass
        this.caps = device.caps();
        this.pass = pass;
        SteinGlFrame.beginPass(this, pass);
    }

    String passName() {
        return pass.name;
    }

    /** The direct polygon offset (SteinGlCommands.polygonOffset) recorded in this pass. */
    void noteOffset(boolean on, float factor, float units) {
        offsetSet = true;
        offsetOn = on;
        offsetFactor = factor;
        offsetUnits = units;
    }

    /**
     * A synchronisation fell in the middle of this pass: the part recorded up to there has already
     * been replayed and closed (the game's state returned, because the game may run before the
     * rest). The continuation opens again on the same target and area, without clearing, and binds
     * again everything that was bound — the list goes on as if the pass had never stopped.
     */
    void resume() {
        SteinRenderPass keep = SteinRenderPass.keep(pass.name, pass.target);
        if (pass.viewport != null) {
            keep =
                    keep.viewport(
                            pass.viewport[0], pass.viewport[1], pass.viewport[2], pass.viewport[3]);
        }
        SteinGlFrame.resumePass(this, keep, lineWidth);
        if (viewportSet) {
            SteinGlFrame.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
        if (scissorSet) {
            SteinGlFrame.scissor(scissorOn ? scissor : null);
        }
        if (offsetSet) {
            SteinGlFrame.polygonOffset(offsetOn, offsetFactor, offsetUnits);
        }
        if (indexSet) {
            SteinGlFrame.index(indexHandle, indexUint16);
        }
        for (int i = 0; i < MAX_BINDINGS; i++) {
            if ((vertexBindingMask & 1 << i) != 0) {
                SteinGlFrame.vertex(i, vertexBufferHandle[i], vertexBufferOffset[i]);
            }
        }
        for (int s = 0; s < MAX_UNITS; s++) {
            long bit = 1L << s;
            if ((texture2dMask & bit) != 0) {
                SteinGlFrame.texture(s, texId[s], false, texSampler[s]);
            }
            if ((texArraySet & bit) != 0) {
                SteinGlFrame.texture(s, texArrayId[s], true, texSampler[s]);
            }
        }
        for (int k = 0; k < BUFFER_RANGES; k++) {
            if (bufSet[k]) {
                SteinGlFrame.uniforms(
                        k >= BUFFER_POINTS,
                        k % BUFFER_POINTS,
                        bufHandle[k],
                        bufOffset[k],
                        bufSize[k]);
            }
        }
        if (pipelineSet) {
            SteinGlFrame.pipeline(boundPipeline);
        }
    }

    private void keepBuffer(boolean storage, int slot, int h, long offset, long size) {
        if (slot < 0 || slot >= BUFFER_POINTS) {
            throw new IllegalArgumentException("buffer point out of range: " + slot);
        }
        int k = storage ? BUFFER_POINTS + slot : slot;
        bufHandle[k] = h;
        bufOffset[k] = offset;
        bufSize[k] = size;
        bufSet[k] = true;
    }

    private void check() {
        SteinGlPassRules.notEnded(ended);
    }

    @Override
    public void bindPipeline(SteinPipeline p) {
        check();
        SteinGlPipeline gp = (SteinGlPipeline) p;
        pipeline = gp == null || !gp.valid() ? null : gp;
        boundPipeline = p;
        pipelineSet = true;
        SteinGlFrame.pipeline(p);
    }

    @Override
    public void bindVertexBuffer(int index, SteinBuffer buffer, long offset) {
        check();
        SteinGlPassRules.vertexBinding(index);
        vertexBufferHandle[index] = buffer == null ? 0 : (int) buffer.handle();
        vertexBufferOffset[index] = offset;
        vertexBindingMask |= 1 << index;
        SteinGlFrame.vertex(index, vertexBufferHandle[index], offset);
    }

    @Override
    public void bindIndexBuffer(SteinBuffer buffer, IndexType type) {
        check();
        int h = buffer == null ? 0 : (int) buffer.handle();
        indexHandle = h;
        indexUint16 = type == IndexType.UINT16;
        indexSet = true;
        SteinGlFrame.index(h, indexUint16);
    }

    @Override
    public void bindTexture(int slot, SteinTexture texture, SteinSampler sampler) {
        check();
        SteinGlPassRules.textureSlot(slot);
        int id = texture == null ? 0 : (int) texture.handle();
        boolean array =
                texture != null && texture.desc().kind == SteinTexture.Kind.TEXTURE_2D_ARRAY;
        int s = sampler == null ? 0 : (int) sampler.handle();
        // The 2D and the array texture of one slot coexist (different targets): the continuation
        // rebinds both and the last sampler.
        if (array) {
            texArrayId[slot] = id;
            texArraySet |= 1L << slot;
        } else {
            texId[slot] = id;
            texture2dMask |= 1L << slot;
        }
        texSampler[slot] = s;
        SteinGlFrame.texture(slot, id, array, s);
    }

    @Override
    public void bindUniforms(int slot, SteinBuffer buffer, long offset, long size) {
        check();
        keepBuffer(false, slot, (int) buffer.handle(), offset, size);
        SteinGlFrame.uniforms(false, slot, (int) buffer.handle(), offset, size);
    }

    @Override
    public void bindStorage(int slot, SteinBuffer buffer, long offset, long size) {
        check();
        SteinGlPassRules.storageSupported(caps);
        keepBuffer(true, slot, (int) buffer.handle(), offset, size);
        SteinGlFrame.uniforms(true, slot, (int) buffer.handle(), offset, size);
    }

    @Override
    public void setViewport(int x, int y, int w, int h) {
        check();
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = w;
        viewport[3] = h;
        viewportSet = true;
        SteinGlFrame.viewport(x, y, w, h);
    }

    @Override
    public void setScissor(int[] rect) {
        check();
        scissorSet = true;
        scissorOn = rect != null;
        if (rect != null) {
            System.arraycopy(rect, 0, scissor, 0, 4);
        }
        SteinGlFrame.scissor(rect);
    }

    /**
     * Like graphics() of the immediate pass: without a pipeline the draw is skipped; with compute
     * bound, an error.
     */
    private boolean graphics() {
        check();
        SteinGlPassRules.notComputeBound(pipeline);
        return pipeline != null;
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        graphics();
        SteinGlFrame.draw(vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void drawIndexed(
            int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        if (graphics() && indexCount > 0 && instanceCount > 0) {
            SteinGlPassRules.indexRequired("drawIndexed", indexHandle, pipeline);
        }
        SteinGlFrame.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
    }

    @Override
    public void drawIndirect(SteinBuffer buffer, long offset, int count) {
        graphics();
        SteinGlPassRules.indirectSupported(caps);
        SteinGlFrame.drawIndirect(false, (int) buffer.handle(), offset, count);
    }

    @Override
    public void drawIndexedIndirect(SteinBuffer buffer, long offset, int count) {
        boolean g = graphics();
        SteinGlPassRules.indirectSupported(caps);
        if (g && count > 0) {
            SteinGlPassRules.indexRequired("drawIndexedIndirect", indexHandle, pipeline);
        }
        SteinGlFrame.drawIndirect(true, (int) buffer.handle(), offset, count);
    }

    @Override
    public void dispatch(int x, int y, int z) {
        check();
        SteinGlPassRules.computeSupported(caps);
        SteinGlPassRules.notGraphicsBound(pipeline);
        SteinGlFrame.dispatch(x, y, z);
    }

    @Override
    public void end() {
        if (ended) {
            return;
        }
        ended = true;
        SteinGlFrame.endPass(this);
    }
}
