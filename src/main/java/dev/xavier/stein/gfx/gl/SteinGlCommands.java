/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.gfx.SteinBuffer;
import dev.xavier.stein.gfx.SteinCaps;
import dev.xavier.stein.gfx.SteinCommands;
import dev.xavier.stein.gfx.SteinGfxOptions;
import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinRenderPass;
import dev.xavier.stein.gfx.SteinRenderTarget;
import dev.xavier.stein.gfx.SteinSampler;
import dev.xavier.stein.gfx.SteinTexture;
import dev.xavier.stein.gfx.SteinVertexLayout;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import java.util.Arrays;

/**
 * One OpenGL pass: the commands run immediately. The constructor opens the pass — draws the
 * engine's pending batch ({@code ModelBatch.flush()}, which also takes the deferred state to the
 * driver), saves the game's state, binds the target and applies the clears — and {@link #end()}
 * gives the game state back exactly as it was (the 1.8.9 GlStateManager caches the texture per
 * unit, blend, depth and masks; any difference becomes a drawing error in the game later).
 *
 * <p>The pass saves the game state without asking the driver: the bindings come from {@link
 * SteinGlBindings} (followed at the game's and Stein's call sites), the 2D texture per unit and the
 * active unit from the GlStateManager cache, and the fixed state too comes from the cache ({@code
 * SteinGameState}, with the deferred state already synchronized by the flush above). What the pass
 * emits is compared with what was there: the first pipeline only emits what differs from the game,
 * and {@link #end()} only gives back what changed; the core context has no glPushAttrib. The only
 * query left is the enabled scissor (the Loader's interface scissors outside the game), one per
 * pass.
 *
 * <p>{@code -Dsteinbridge.gfx.passStateCheck=true} checks the tracked state against the driver
 * ({@link SteinGlPassCheck}).
 *
 * <p>What is bound stays bound until replaced, even across pipeline changes. With {@code bindCache}
 * pipelines with the same layout share the VAO ({@link SteinGlVao}) and what is already attached to
 * it (vertex and index buffers), just like the uniform and storage binding points, is not sent to
 * the driver again; without it each pipeline has its own VAO and, when the VAO changes, the pass's
 * buffers are rebound on it.
 *
 * <p>This class holds the pass state and executes the commands. Reading and giving back the game's
 * state is in {@link SteinGlPassBindings} (bindings) and {@link SteinGlPassFixed} (fixed-function
 * state), the validations shared with the recorded pass in {@link SteinGlPassRules}, the uniform
 * and storage points already bound in {@link SteinGlBindPoints}, the opening and closing times in
 * {@link SteinGlPassTiming}. It is one cohesive class: the pass state is read by those helpers and
 * by {@link SteinGlPassCheck}, so it stays in one place.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGlCommands extends SteinCommands {

    /** Vertex bindings per pass. */
    private static final int MAX_BINDINGS = SteinGlLimits.MAX_VERTEX_BINDINGS;

    /** Texture units the pass knows how to save (the mask is a long). */
    private static final int MAX_UNITS = SteinGlLimits.MAX_TEXTURE_UNITS;

    /**
     * Units with space already allocated per pass (passes use 0 to 2; above that the arrays grow).
     */
    private static final int FEW_UNITS = 8;

    /** Color attachments a render target can have. */
    private static final int MAX_COLOR_ATTACHMENTS = 8;

    /** Bytes of one indirect command: DrawArraysIndirectCommand (4 ints). */
    private static final int DRAW_ARRAYS_INDIRECT_STRIDE = 16;

    /** Bytes of one indirect command: DrawElementsIndirectCommand (5 ints). */
    private static final int DRAW_ELEMENTS_INDIRECT_STRIDE = 20;

    /** Not read yet (the game's value is only asked when the pass is about to change it). */
    static final int UNKNOWN = Integer.MIN_VALUE;

    static final boolean BIND_CACHE = SteinGfxOptions.BIND_CACHE;
    private static final boolean CHECK = SteinGfxOptions.PASS_STATE_CHECK;
    private static final boolean GPU_TIMING = SteinGlGpuTiming.ON;
    private static final boolean TIMING = SteinGlPassTiming.ON;

    /** The open pass (the direct polygon offset of the entity flush records itself on it). */
    private static SteinGlCommands open;

    private final SteinCaps caps;
    final String name;
    private boolean ended;
    private int gpuSlot = -1;

    // ---- game state ----
    int prevProgram;
    int prevVao;
    int prevDrawFbo;

    /**
     * UNKNOWN in tracked mode until the pass needs to change the ARRAY_BUFFER (only on the 4.1
     * path).
     */
    int prevArrayBuffer;

    int prevIndirectBuffer;
    int prevUniformBuffer;
    int prevStorageBuffer;
    int prevActive;

    /** The game's viewport (in core: no glPushAttrib there). */
    final int[] prevViewport = new int[4];

    long savedUnits;
    int[] prevTex2D = new int[FEW_UNITS];
    int[] prevTexArray = new int[FEW_UNITS];
    int[] prevSampler = new int[FEW_UNITS];

    // ---- game state, fixed (tracked in core: the GlStateManager cache at opening) ----
    boolean gBlend;
    int gBlendSrc;
    int gBlendDst;
    int gBlendSrcAlpha;
    int gBlendDstAlpha;
    boolean gDepthTest;
    int gDepthFunc;
    boolean gDepthMask;
    boolean gCull;
    int gCullFace;
    final boolean[] gMask = new boolean[4];
    boolean gOffsetFill;
    float gOffsetFactor;
    float gOffsetUnits;
    boolean gLogicOp;
    boolean scissorWas;

    // ---- pass state ----
    private SteinGlPipeline pipeline;
    int program;
    int vao;
    int active;
    int drawFbo;
    int arrayBuffer;
    boolean touchedIndirect;
    int indirectBuffer;
    int uniformGeneric;
    int storageGeneric;
    final int[] viewport = new int[4];
    int[] cur2D = new int[FEW_UNITS];
    int[] curArray = new int[FEW_UNITS];
    int[] curSampler = new int[FEW_UNITS];
    private final int[] vbHandle = new int[MAX_BINDINGS];
    private final long[] vbOffset = new long[MAX_BINDINGS];
    private int indexHandle;
    private int indexType = GL11.GL_UNSIGNED_INT;
    private int indexSize = 4;
    boolean scissorOn;
    boolean scissorBoxSaved;
    final int[] prevScissorBox = new int[4];
    boolean offsetFill;
    boolean offsetTouched;

    /**
     * Fixed state emitted last (tracked in core: starts as the game's; otherwise the first pipeline
     * emits everything).
     */
    boolean fixedKnown;

    boolean blend;
    int blendSrc;
    int blendDst;
    int blendSrcAlpha;
    int blendDstAlpha;
    boolean depthTest;
    int depthFunc;
    boolean depthWrite;
    boolean cull;
    int cullFace;
    final boolean[] mask = new boolean[4];

    public SteinGlCommands(SteinGlDevice device, SteinRenderPass pass) {
        this(device, pass, false);
    }

    /**
     * {@code replay}: the recorded pass being replayed ({@link SteinGlFrame}); the engine's batch
     * already went out at recording time (and during replay nothing from outside may run in the
     * middle).
     */
    SteinGlCommands(SteinGlDevice device, SteinRenderPass pass, boolean replay) {
        if (!replay) {
            SteinGlGame.flushEngine();
        }
        this.caps = device.caps();
        this.name = pass.name;
        if (GPU_TIMING) {
            gpuSlot = SteinGlGpuTiming.begin(pass.name);
        }
        openTracked(pass);
        open = this;
    }

    /**
     * Tracked: the bindings from {@link SteinGlBindings} and the game cache, and the game's fixed
     * state read from the cache and set as the "already emitted" (the first pipeline only changes
     * what differs). Scissor, stencil, polygon mode, front face and blend equation: the game never
     * touches the last three nor the stencil (they stay at the OpenGL default, checked in
     * passStateCheck) and no Stein pipeline uses them; the scissor is the only one queried.
     */
    private void openTracked(SteinRenderPass pass) {
        long t0 = TIMING ? System.nanoTime() : 0L;
        SteinGlPassBindings.capture(this);
        SteinGlPassFixed.capture(this);
        if (CHECK) {
            SteinGlPassCheck.atOpen(this);
        }
        long t1 = TIMING ? System.nanoTime() : 0L;
        SteinGlPassFixed.disableForPass(this);
        long t2 = TIMING ? System.nanoTime() : 0L;
        SteinRenderTarget target = pass.target;
        drawFbo = (int) target.handle();
        if (drawFbo != prevDrawFbo) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        }
        setPassViewport(pass, target);
        clear(pass, target);
        if (TIMING) {
            SteinGlPassTiming.opened(t0, t1, t2, System.nanoTime());
        }
    }

    /**
     * The pass's viewport (its own or the whole target), sent only when it differs from the last
     * one.
     */
    private void setPassViewport(SteinRenderPass pass, SteinRenderTarget target) {
        if (pass.viewport != null) {
            viewport(pass.viewport[0], pass.viewport[1], pass.viewport[2], pass.viewport[3]);
        } else {
            viewport(0, 0, target.width(), target.height());
        }
    }

    private void viewport(int x, int y, int w, int h) {
        if (viewport[0] != x || viewport[1] != y || viewport[2] != w || viewport[3] != h) {
            GL11.glViewport(x, y, w, h);
            viewport[0] = x;
            viewport[1] = y;
            viewport[2] = w;
            viewport[3] = h;
        }
    }

    private static final float[] CLEAR_ZERO = new float[4];
    private final float[] clearDepth = new float[1];

    /** The pass's clears (with the masks open and no scissor, which also apply to the clear). */
    private void clear(SteinRenderPass pass, SteinRenderTarget target) {
        if (pass.colorLoad == SteinRenderPass.Load.CLEAR) {
            GL11.glColorMask(true, true, true, true);
            mask[0] = mask[1] = mask[2] = mask[3] = true;
            float[] color = pass.clearColor != null ? pass.clearColor : CLEAR_ZERO;
            int n = Math.max(1, colorCount(target));
            for (int i = 0; i < n; i++) {
                GL30.glClearBufferfv(GL11.GL_COLOR, i, color);
            }
        }
        if (pass.depthLoad == SteinRenderPass.Load.CLEAR) {
            GL11.glDepthMask(true);
            depthWrite = true;
            clearDepth[0] = pass.clearDepth;
            GL30.glClearBufferfv(GL11.GL_DEPTH, 0, clearDepth);
        }
    }

    private static int colorCount(SteinRenderTarget target) {
        int n = 0;
        while (n < MAX_COLOR_ATTACHMENTS && target.color(n) != null) {
            n++;
        }
        return n;
    }

    // ---- pipeline ----

    @Override
    public void bindPipeline(SteinPipeline p) {
        check();
        SteinGlPipeline gp = (SteinGlPipeline) p;
        if (gp == pipeline) {
            return;
        }
        if (gp == null || !gp.valid()) {
            pipeline = null;
            return;
        }
        pipeline = gp;
        if (program != gp.program()) {
            GL20.glUseProgram(gp.program());
            program = gp.program();
        }
        if (gp.isCompute()) {
            return;
        }
        boolean newVao = vao != gp.vao();
        if (newVao) {
            GL30.glBindVertexArray(gp.vao());
            vao = gp.vao();
        }
        if (newVao || !gp.vertexBinding) {
            // the pass's buffers on this pipeline's VAO (on 4.1 the pointer depends on the layout,
            // so always)
            if (indexHandle != 0) {
                applyIndex(gp);
            }
            for (SteinVertexLayout.Binding b : gp.bindings) {
                if (b.index < MAX_BINDINGS && vbHandle[b.index] != 0) {
                    applyVertexBuffer(gp, b, 0);
                }
            }
        }
        SteinGlPassFixed.apply(this, gp);
    }

    // ---- vertices and indices ----

    @Override
    public void bindVertexBuffer(int index, SteinBuffer buffer, long offset) {
        vertexBuffer(index, buffer == null ? 0 : (int) buffer.handle(), offset);
    }

    /** By buffer name (replaying the recorded frame keeps the name from recording time). */
    void vertexBuffer(int index, int handle, long offset) {
        check();
        SteinGlPassRules.vertexBinding(index);
        vbHandle[index] = handle;
        vbOffset[index] = offset;
        SteinGlPipeline p = pipeline;
        if (p != null && !p.isCompute() && vbHandle[index] != 0) {
            SteinVertexLayout.Binding b = binding(p, index);
            if (b != null) {
                applyVertexBuffer(p, b, 0);
            }
        }
    }

    private static SteinVertexLayout.Binding binding(SteinGlPipeline p, int index) {
        for (SteinVertexLayout.Binding b : p.bindings) {
            if (b.index == index) {
                return b;
            }
        }
        return null;
    }

    /**
     * Binds the binding's buffer on the pipeline's VAO (already bound). {@code instanceShift} moves
     * the per-instance bindings {@code instanceShift} instances forward (the base instance for
     * whoever lacks GL 4.2).
     */
    private void applyVertexBuffer(
            SteinGlPipeline p, SteinVertexLayout.Binding b, int instanceShift) {
        int handle = vbHandle[b.index];
        long offset = vbOffset[b.index] + (b.perInstance ? (long) instanceShift * b.stride : 0L);
        if (p.vertexBinding) {
            SteinGlVao v = p.shared();
            if (v != null && v.vertexBound(b.index, handle, offset, b.stride)) {
                return; // already attached this way on this VAO
            }
            GL43.glBindVertexBuffer(b.index, handle, offset, b.stride);
            return;
        }
        if (arrayBuffer != handle) {
            if (prevArrayBuffer == UNKNOWN) {
                // tracked: the game's ARRAY_BUFFER is only read when the pass is about to change it
                // (the 4.1 path)
                prevArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, handle);
            arrayBuffer = handle;
        }
        for (SteinVertexLayout.Attribute a : p.attributes) {
            if (a.binding != b.index) {
                continue;
            }
            int size = SteinGlFormats.attribSize(a.format);
            int type = SteinGlFormats.attribType(a.format);
            if (SteinGlFormats.attribInteger(a.format)) {
                GL30.glVertexAttribIPointer(a.location, size, type, b.stride, offset + a.offset);
            } else {
                GL20.glVertexAttribPointer(
                        a.location,
                        size,
                        type,
                        SteinGlFormats.attribNormalized(a.format),
                        b.stride,
                        offset + a.offset);
            }
            GL20.glEnableVertexAttribArray(a.location);
        }
    }

    /** The pass's index buffer on the pipeline's VAO (already bound). */
    private void applyIndex(SteinGlPipeline p) {
        SteinGlVao v = p.shared();
        if (v != null && v.indexBound(indexHandle)) {
            return;
        }
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexHandle);
    }

    @Override
    public void bindIndexBuffer(SteinBuffer buffer, IndexType type) {
        indexBuffer(buffer == null ? 0 : (int) buffer.handle(), type == IndexType.UINT16);
    }

    void indexBuffer(int handle, boolean uint16) {
        check();
        indexHandle = handle;
        indexType = uint16 ? GL11.GL_UNSIGNED_SHORT : GL11.GL_UNSIGNED_INT;
        indexSize = uint16 ? 2 : 4;
        // the index is VAO state: only with one of our VAOs bound (the game's is never touched)
        if (pipeline != null && !pipeline.isCompute() && vao == pipeline.vao()) {
            applyIndex(pipeline);
        }
    }

    // ---- resources ----

    @Override
    public void bindTexture(int slot, SteinTexture texture, SteinSampler sampler) {
        texture(
                slot,
                texture == null ? 0 : (int) texture.handle(),
                texture != null && texture.desc().kind == SteinTexture.Kind.TEXTURE_2D_ARRAY,
                sampler == null ? 0 : (int) sampler.handle());
    }

    /**
     * By name: {@code array} = array texture (otherwise 2D; name 0 = disables 2D), {@code s} =
     * sampler (0 = none).
     */
    void texture(int slot, int id, boolean array, int s) {
        check();
        SteinGlPassRules.textureSlot(slot);
        if (slot >= prevTex2D.length) {
            growUnits();
        }
        long bit = 1L << slot;
        if ((savedUnits & bit) == 0) {
            savedUnits |= bit;
            // the 2D: what the game believes (the GlStateManager cache; -1 = erased, it is not
            // known which one remained); array and sampler: only Stein binds them, tracked. Reading
            // from the driver (the first time, or outside the cache) requires the unit.
            int known = slot < SteinGlGame.textureUnits() ? SteinGlGame.textureName(slot) : -1;
            if (known < 0 || !SteinGlBindings.unitKnown(slot)) {
                activeUnit(slot);
            }
            prevTex2D[slot] = known >= 0 ? known : GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            prevTexArray[slot] = SteinGlBindings.texArray(slot);
            prevSampler[slot] = SteinGlBindings.sampler(slot);
            cur2D[slot] = prevTex2D[slot];
            curArray[slot] = prevTexArray[slot];
            curSampler[slot] = prevSampler[slot];
            if (CHECK) {
                SteinGlPassCheck.unit(this, slot);
            }
            // the cache may be out of sync with the driver (see openTracked): the first bind of the
            // unit always goes
            cur2D[slot] = UNKNOWN;
        }
        if (array) {
            if (curArray[slot] != id) {
                activeUnit(slot);
                GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, id);
                curArray[slot] = id;
            }
        } else {
            if (cur2D[slot] != id) {
                activeUnit(slot);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
                cur2D[slot] = id;
            }
        }
        if (curSampler[slot] != s) {
            GL33.glBindSampler(slot, s);
            curSampler[slot] = s;
        }
    }

    private void growUnits() {
        prevTex2D = Arrays.copyOf(prevTex2D, MAX_UNITS);
        prevTexArray = Arrays.copyOf(prevTexArray, MAX_UNITS);
        prevSampler = Arrays.copyOf(prevSampler, MAX_UNITS);
        cur2D = Arrays.copyOf(cur2D, MAX_UNITS);
        curArray = Arrays.copyOf(curArray, MAX_UNITS);
        curSampler = Arrays.copyOf(curSampler, MAX_UNITS);
    }

    /** The device has compute (generic storage exists and is saved). */
    boolean compute() {
        return caps.compute;
    }

    void activeUnit(int slot) {
        int unit = GL13.GL_TEXTURE0 + slot;
        if (active != unit) {
            GL13.glActiveTexture(unit);
            active = unit;
        }
    }

    @Override
    public void bindUniforms(int slot, SteinBuffer buffer, long offset, long size) {
        uniforms(slot, (int) buffer.handle(), offset, size);
    }

    void uniforms(int slot, int h, long offset, long size) {
        check();
        if (BIND_CACHE && SteinGlBindPoints.uniformBound(slot, h, offset, size)) {
            return; // the point already has this range (points stay between passes)
        }
        GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, slot, h, offset, size);
        uniformGeneric = h; // glBindBufferRange also changes the generic one
    }

    @Override
    public void bindStorage(int slot, SteinBuffer buffer, long offset, long size) {
        storage(slot, (int) buffer.handle(), offset, size);
    }

    void storage(int slot, int h, long offset, long size) {
        check();
        SteinGlPassRules.storageSupported(caps);
        if (BIND_CACHE && SteinGlBindPoints.storageBound(slot, h, offset, size)) {
            return;
        }
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, slot, h, offset, size);
        storageGeneric = h;
    }

    // ---- area ----

    @Override
    public void setViewport(int x, int y, int w, int h) {
        check();
        viewport(x, y, w, h);
    }

    @Override
    public void setScissor(int[] rect) {
        check();
        if (rect == null) {
            if (scissorOn) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                scissorOn = false;
            }
            return;
        }
        if (!scissorBoxSaved) {
            // the game's rectangle (rare: only a scissoring pass pays for the query)
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevScissorBox);
            scissorBoxSaved = true;
        }
        if (!scissorOn) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            scissorOn = true;
        }
        GL11.glScissor(rect[0], rect[1], rect[2], rect[3]);
    }

    /**
     * The direct polygon offset of the entity flush (the API does not have it yet: a setDepthBias,
     * like Vulkan's dynamic state, is pending). The same calls as before; on the open pass it is
     * noted, so the tracked {@link #end()} gives the game's back (the enable and the factor and
     * units).
     */
    public static void polygonOffset(boolean on, float factor, float units) {
        if (SteinGlFrame.passOpen()) {
            SteinGlFrame.polygonOffset(
                    on, factor, units); // on the pass being recorded: becomes a command
            return;
        }
        polygonOffsetNow(on, factor, units);
    }

    /** The actual calls (immediately, or when replaying the recorded command). */
    static void polygonOffsetNow(boolean on, float factor, float units) {
        if (on) {
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(factor, units);
        } else {
            GL11.glPolygonOffset(0f, 0f);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        }
        SteinGlCommands c = open;
        if (c != null) {
            c.offsetFill = on;
            c.offsetTouched = true;
        }
    }

    // ---- drawing ----

    /**
     * The bound draw pipeline, or null (none, or its shader did not compile: the draw is skipped).
     */
    private SteinGlPipeline graphics() {
        check();
        SteinGlPipeline p = pipeline;
        if (p == null) {
            return null;
        }
        SteinGlPassRules.notComputeBound(p);
        return p;
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        SteinGlPipeline p = graphics();
        if (p == null || vertexCount <= 0 || instanceCount <= 0) {
            return;
        }
        if (firstInstance == 0) {
            GL31.glDrawArraysInstanced(p.mode, firstVertex, vertexCount, instanceCount);
        } else if (caps.baseInstance) {
            GL42.glDrawArraysInstancedBaseInstance(
                    p.mode, firstVertex, vertexCount, instanceCount, firstInstance);
        } else {
            shiftInstances(p, firstInstance);
            GL31.glDrawArraysInstanced(p.mode, firstVertex, vertexCount, instanceCount);
            shiftInstances(p, 0);
        }
    }

    @Override
    public void drawIndexed(
            int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        SteinGlPipeline p = graphics();
        if (p == null || indexCount <= 0 || instanceCount <= 0) {
            return;
        }
        SteinGlPassRules.indexRequired("drawIndexed", indexHandle, p);
        long at = (long) firstIndex * indexSize;
        if (firstInstance == 0) {
            if (baseVertex == 0) {
                GL31.glDrawElementsInstanced(p.mode, indexCount, indexType, at, instanceCount);
            } else {
                GL32.glDrawElementsInstancedBaseVertex(
                        p.mode, indexCount, indexType, at, instanceCount, baseVertex);
            }
        } else if (caps.baseInstance) {
            GL42.glDrawElementsInstancedBaseVertexBaseInstance(
                    p.mode, indexCount, indexType, at, instanceCount, baseVertex, firstInstance);
        } else {
            shiftInstances(p, firstInstance);
            GL32.glDrawElementsInstancedBaseVertex(
                    p.mode, indexCount, indexType, at, instanceCount, baseVertex);
            shiftInstances(p, 0);
        }
    }

    /**
     * Without GL 4.2: the base instance becomes an offset in the per-instance buffers. Per-instance
     * attributes come out the same; only {@code gl_InstanceID} still starts at zero (as with a real
     * base instance in GL).
     */
    private void shiftInstances(SteinGlPipeline p, int firstInstance) {
        for (SteinVertexLayout.Binding b : p.bindings) {
            if (b.perInstance && b.index < MAX_BINDINGS && vbHandle[b.index] != 0) {
                applyVertexBuffer(p, b, firstInstance);
            }
        }
    }

    private void indirect(int handle) {
        SteinGlPassRules.indirectSupported(caps);
        touchedIndirect = true;
        if (indirectBuffer != handle) {
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, handle);
            indirectBuffer = handle;
        }
    }

    @Override
    public void drawIndirect(SteinBuffer buffer, long offset, int count) {
        drawIndirect((int) buffer.handle(), offset, count);
    }

    void drawIndirect(int handle, long offset, int count) {
        SteinGlPipeline p = graphics();
        indirect(handle);
        if (p == null || count <= 0) {
            return;
        }
        GL43.glMultiDrawArraysIndirect(p.mode, offset, count, DRAW_ARRAYS_INDIRECT_STRIDE);
    }

    @Override
    public void drawIndexedIndirect(SteinBuffer buffer, long offset, int count) {
        drawIndexedIndirect((int) buffer.handle(), offset, count);
    }

    void drawIndexedIndirect(int handle, long offset, int count) {
        SteinGlPipeline p = graphics();
        indirect(handle);
        if (p == null || count <= 0) {
            return;
        }
        SteinGlPassRules.indexRequired("drawIndexedIndirect", indexHandle, p);
        GL43.glMultiDrawElementsIndirect(
                p.mode, indexType, offset, count, DRAW_ELEMENTS_INDIRECT_STRIDE);
    }

    @Override
    public void dispatch(int x, int y, int z) {
        check();
        SteinGlPassRules.computeSupported(caps);
        SteinGlPipeline p = pipeline;
        if (p == null) {
            return;
        }
        SteinGlPassRules.notGraphicsBound(p);
        GL43.glDispatchCompute(x, y, z);
        GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);
    }

    private void check() {
        SteinGlPassRules.notEnded(ended);
    }

    /**
     * Restores the game state. Order matters: the per-unit textures (touches the active unit); the
     * game's framebuffers before glPopAttrib (it restores glDrawBuffer, which applies to the bound
     * framebuffer and on ours would error); the generic buffers; program and VAO; the active unit
     * last.
     */
    @Override
    public void end() {
        if (ended) {
            return;
        }
        ended = true;
        if (open == this) {
            open = null;
        }
        endTracked();
        pipeline = null;
        if (GPU_TIMING) {
            SteinGlGpuTiming.end(gpuSlot);
        }
    }

    /**
     * Tracked: only what the pass changed goes back, to the value the game (or the tracked state)
     * had.
     */
    private void endTracked() {
        long e0 = TIMING ? System.nanoTime() : 0L;
        SteinGlPassBindings.restoreUnits(this);
        long e1 = TIMING ? System.nanoTime() : 0L;
        // only the draw one: the pass never binds the read one
        if (drawFbo != prevDrawFbo) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        }
        long e2 = TIMING ? System.nanoTime() : 0L;
        SteinGlPassFixed.restore(this);
        long e3 = TIMING ? System.nanoTime() : 0L;
        SteinGlPassBindings.restoreRest(this);
        if (CHECK) {
            SteinGlPassCheck.atClose(this);
        }
        if (TIMING) {
            SteinGlPassTiming.closed(e0, e1, e2, e3, System.nanoTime());
        }
    }
}
