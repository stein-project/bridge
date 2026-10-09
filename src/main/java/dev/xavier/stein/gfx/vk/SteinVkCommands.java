/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdBindVertexBuffers;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdDraw;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexed;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndexedIndirect;
import static org.lwjgl.vulkan.VK10.vkCmdDrawIndirect;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

import dev.xavier.stein.gfx.SteinBuffer;
import dev.xavier.stein.gfx.SteinCommands;
import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinRenderPass;
import dev.xavier.stein.gfx.SteinSampler;
import dev.xavier.stein.gfx.SteinShader;
import dev.xavier.stein.gfx.SteinTexture;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.util.List;

/**
 * A pass in Vulkan, recorded into the device's command buffer. The contract is OpenGL's: what is
 * bound stays valid until it is replaced or until {@link #end()}, even when switching pipelines.
 *
 * <p>Everything is stored and emitted at draw time, only what changed. The render (dynamic
 * rendering) opens at the start of the pass if it clears (the clear happens there, as in OpenGL),
 * otherwise at the first draw; a copy, upload or dispatch in the middle closes the render ({@link
 * #suspend()}, the images return to rest) and the next draw reopens it keeping what was already
 * drawn. A submission in the middle of the pass ({@link #suspendForSubmit()}) switches the command
 * buffer: the whole state is emitted again on the next one.
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkCommands extends SteinCommands {

    private static final int MAX_BINDINGS = 16;
    private static final int MAX_SLOTS = 32;

    private final SteinVkDevice device;
    private final SteinRenderPass pass;
    private final SteinVkRenderTarget target;
    private final SteinVkRenderTarget.Formats formats;
    private boolean ended;

    /**
     * The command buffer in which the state below was already emitted (another = emit everything
     * again).
     */
    private VkCommandBuffer cb;

    private boolean rendering;
    private boolean begunOnce;

    private SteinVkPipeline pipeline;
    private long boundGraphics = VK_NULL_HANDLE;
    private long boundCompute = VK_NULL_HANDLE;
    private boolean descDirty = true;

    private final long[] vbBuffer = new long[MAX_BINDINGS];
    private final long[] vbOffset = new long[MAX_BINDINGS];
    private int vbDirty;
    private long indexBuffer = VK_NULL_HANDLE;
    private int indexType = VK_INDEX_TYPE_UINT32;
    private boolean indexDirty;

    private final SteinVkTexture[] textures = new SteinVkTexture[MAX_SLOTS];
    private final SteinVkSampler[] samplers = new SteinVkSampler[MAX_SLOTS];
    private final SteinBuffer[] uniforms = new SteinBuffer[MAX_SLOTS];
    private final long[] uniformOffset = new long[MAX_SLOTS];
    private final long[] uniformSize = new long[MAX_SLOTS];
    private final SteinBuffer[] storage = new SteinBuffer[MAX_SLOTS];
    private final long[] storageOffset = new long[MAX_SLOTS];
    private final long[] storageSize = new long[MAX_SLOTS];

    private final int[] viewport = new int[4];

    /** null = the whole target. */
    private int[] scissor;

    private boolean dynDirty = true;

    SteinVkCommands(SteinVkDevice device, SteinRenderPass pass) {
        this.device = device;
        this.pass = pass;
        if (!(pass.target instanceof SteinVkRenderTarget t)) {
            throw new IllegalArgumentException(
                    "vulkan: target from another backend in pass '" + pass.name + "'");
        }
        this.target = t;
        this.formats = t.formats();
        if (pass.viewport != null) {
            System.arraycopy(pass.viewport, 0, viewport, 0, 4);
        } else {
            viewport[2] = t.width();
            viewport[3] = t.height();
        }
        device.passOpened(this);
        if ((pass.colorLoad == SteinRenderPass.Load.CLEAR
                        || pass.depthLoad == SteinRenderPass.Load.CLEAR)
                && t.width() > 0
                && t.height() > 0) {
            // clears now, like OpenGL: a copy or read before the first draw sees the cleared
            // target
            ensureRendering(current());
        }
    }

    String name() {
        return pass.name;
    }

    private void check() {
        if (ended) {
            throw new IllegalStateException("pass '" + pass.name + "' already closed");
        }
    }

    // ---- render open and closed ----

    private static int load(SteinRenderPass.Load l) {
        return switch (l) {
            case KEEP -> VK_ATTACHMENT_LOAD_OP_LOAD;
            case CLEAR -> VK_ATTACHMENT_LOAD_OP_CLEAR;
            case DONT_CARE -> VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        };
    }

    /**
     * Opens the render (the first time with the pass's clears; afterwards keeping what is there).
     */
    private void ensureRendering(VkCommandBuffer c) {
        if (rendering) {
            return;
        }
        boolean first = !begunOnce;
        SteinVkTexture[] colors = target.colors();
        SteinVkTexture depth = target.depthTexture();
        for (SteinVkTexture t : colors) {
            t.toAttachment(
                    c,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    first && pass.colorLoad != SteinRenderPass.Load.KEEP);
        }
        if (depth != null) {
            depth.toAttachment(
                    c,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    first && pass.depthLoad != SteinRenderPass.Load.KEEP);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            float[] clear = pass.clearColor != null ? pass.clearColor : new float[4];
            VkRenderingAttachmentInfo.Buffer ca =
                    colors.length == 0
                            ? null
                            : VkRenderingAttachmentInfo.calloc(colors.length, stack);
            for (int i = 0; i < colors.length; i++) {
                ca.get(i)
                        .sType$Default()
                        .imageView(colors[i].attachView())
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(first ? load(pass.colorLoad) : VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                ca.get(i)
                        .clearValue()
                        .color()
                        .float32(0, clear[0])
                        .float32(1, clear[1])
                        .float32(2, clear[2])
                        .float32(3, clear[3]);
            }
            VkRenderingAttachmentInfo da = null;
            if (depth != null) {
                da =
                        VkRenderingAttachmentInfo.calloc(stack)
                                .sType$Default()
                                .imageView(depth.attachView())
                                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                                .loadOp(first ? load(pass.depthLoad) : VK_ATTACHMENT_LOAD_OP_LOAD)
                                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                da.clearValue().depthStencil().depth(pass.clearDepth).stencil(0);
            }
            VkRenderingInfo info =
                    VkRenderingInfo.calloc(stack)
                            .sType$Default()
                            .renderArea(
                                    r ->
                                            r.offset(o -> o.set(0, 0))
                                                    .extent(
                                                            e ->
                                                                    e.set(
                                                                            target.width(),
                                                                            target.height())))
                            .layerCount(1)
                            .pColorAttachments(ca)
                            .pDepthAttachment(da);
            device.beginRendering(c, info);
        }
        rendering = true;
        begunOnce = true;
        dynDirty = true;
    }

    /**
     * Closes the render if it is open (a copy or dispatch follows); the target's images return to
     * rest.
     */
    void suspend() {
        if (!rendering) {
            return;
        }
        VkCommandBuffer c = device.cmd();
        device.endRendering(c);
        for (SteinVkTexture t : target.colors()) {
            t.toRest(c);
        }
        if (target.depthTexture() != null) {
            target.depthTexture().toRest(c);
        }
        rendering = false;
    }

    /**
     * The device is about to submit the command buffer: closes the render and forgets what was
     * emitted into it.
     */
    void suspendForSubmit() {
        suspend();
        cb = null;
    }

    /**
     * The current command buffer; if it changed since last time, everything must be emitted again.
     */
    private VkCommandBuffer current() {
        VkCommandBuffer c = device.cmd();
        if (c != cb) {
            cb = c;
            boundGraphics = VK_NULL_HANDLE;
            boundCompute = VK_NULL_HANDLE;
            descDirty = true;
            dynDirty = true;
            vbDirty = -1;
            indexDirty = indexBuffer != VK_NULL_HANDLE;
        }
        return c;
    }

    // ---- pipeline ----

    @Override
    public void bindPipeline(SteinPipeline p) {
        check();
        SteinVkPipeline vp = (SteinVkPipeline) p;
        if (vp == pipeline) {
            return;
        }
        pipeline = vp == null || !vp.valid() ? null : vp;
        descDirty = true;
    }

    // ---- vertices and indices ----

    @Override
    public void bindVertexBuffer(int index, SteinBuffer buffer, long offset) {
        check();
        if (index < 0 || index >= MAX_BINDINGS) {
            throw new IllegalArgumentException("vertex binding out of range: " + index);
        }
        vbBuffer[index] = buffer == null ? VK_NULL_HANDLE : buffer.handle();
        vbOffset[index] = offset;
        vbDirty |= 1 << index;
    }

    @Override
    public void bindIndexBuffer(SteinBuffer buffer, IndexType type) {
        check();
        indexBuffer = buffer == null ? VK_NULL_HANDLE : buffer.handle();
        indexType = type == IndexType.UINT16 ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
        indexDirty = indexBuffer != VK_NULL_HANDLE;
    }

    // ---- resources ----

    @Override
    public void bindTexture(int slot, SteinTexture texture, SteinSampler sampler) {
        check();
        if (slot < 0 || slot >= MAX_SLOTS) {
            throw new IllegalArgumentException("texture slot out of range: " + slot);
        }
        textures[slot] = (SteinVkTexture) texture;
        samplers[slot] = (SteinVkSampler) sampler;
        descDirty = true;
    }

    @Override
    public void bindUniforms(int slot, SteinBuffer buffer, long offset, long size) {
        check();
        if (slot < 0 || slot >= MAX_SLOTS) {
            throw new IllegalArgumentException("uniforms slot out of range: " + slot);
        }
        uniforms[slot] = buffer;
        uniformOffset[slot] = offset;
        uniformSize[slot] = size;
        descDirty = true;
    }

    @Override
    public void bindStorage(int slot, SteinBuffer buffer, long offset, long size) {
        check();
        if (slot < 0 || slot >= MAX_SLOTS) {
            throw new IllegalArgumentException("storage slot out of range: " + slot);
        }
        storage[slot] = buffer;
        storageOffset[slot] = offset;
        storageSize[slot] = size;
        descDirty = true;
    }

    /**
     * All the bindings of the pipeline's set 0: the slot's resource, or an empty one (black
     * texture, zero buffer) where there is none, like texture 0 in OpenGL — no descriptor is left
     * unwritten. Push descriptor, or a set from the submission's pool.
     */
    private void writeDescriptors(VkCommandBuffer c, int bindPoint, SteinVkPipeline p) {
        List<SteinVkPipeline.Binding> bindings = p.bindings();
        if (p.setLayout() == VK_NULL_HANDLE || bindings.isEmpty()) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int n = bindings.size();
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(n, stack);
            for (int i = 0; i < n; i++) {
                SteinVkPipeline.Binding b = bindings.get(i);
                VkWriteDescriptorSet d = w.get(i);
                d.sType$Default()
                        .dstBinding(b.binding())
                        .dstArrayElement(0)
                        .descriptorType(SteinVkPipeline.descriptorType(b.kind()));
                int slot = b.slot();
                if (b.kind() == SteinShader.Resource.Kind.TEXTURE) {
                    SteinVkTexture t = slot >= 0 ? textures[slot] : null;
                    SteinVkSampler smp = slot >= 0 ? samplers[slot] : null;
                    if (t == null) {
                        t = device.emptyTexture(b.arrayed());
                    } else if (isAttachment(t)) {
                        throw new IllegalStateException(
                                "pass '"
                                        + pass.name
                                        + "' samples its own target ('"
                                        + t.desc().name
                                        + "')");
                    }
                    if (smp == null) {
                        smp = device.defaultSampler(t);
                    }
                    VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack);
                    ii.get(0)
                            .sampler(smp.handle())
                            .imageView(t.view())
                            .imageLayout(SteinVkTexture.REST);
                    d.pImageInfo(ii);
                } else {
                    boolean u = b.kind() == SteinShader.Resource.Kind.UNIFORM;
                    SteinBuffer buf = slot < 0 ? null : u ? uniforms[slot] : storage[slot];
                    VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack);
                    if (buf == null) {
                        bi.get(0)
                                .buffer(device.emptyBuffer().handle())
                                .offset(0L)
                                .range(SteinVkDevice.EMPTY_BUFFER);
                    } else {
                        bi.get(0)
                                .buffer(buf.handle())
                                .offset(u ? uniformOffset[slot] : storageOffset[slot])
                                .range(u ? uniformSize[slot] : storageSize[slot]);
                    }
                    d.pBufferInfo(bi);
                }
                d.descriptorCount(1);
            }
            if (p.push()) {
                KHRPushDescriptor.vkCmdPushDescriptorSetKHR(c, bindPoint, p.layout(), 0, w);
            } else {
                long set = device.descriptorSet(p.setLayout());
                for (int i = 0; i < n; i++) {
                    w.get(i).dstSet(set);
                }
                vkUpdateDescriptorSets(device.vk(), w, null);
                vkCmdBindDescriptorSets(c, bindPoint, p.layout(), 0, stack.longs(set), null);
            }
        }
    }

    private boolean isAttachment(SteinVkTexture t) {
        if (t == target.depthTexture()) {
            return true;
        }
        for (SteinVkTexture a : target.colors()) {
            if (a == t) {
                return true;
            }
        }
        return false;
    }

    // ---- area ----

    @Override
    public void setViewport(int x, int y, int w, int h) {
        check();
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = w;
        viewport[3] = h;
        dynDirty = true;
    }

    @Override
    public void setScissor(int[] rect) {
        check();
        scissor = rect == null ? null : rect.clone();
        dynDirty = true;
    }

    private void applyDynamic(VkCommandBuffer c) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // same row order as OpenGL (see SteinVkDevice): the viewport and scissor numbers apply
            // unchanged
            VkViewport.Buffer v = VkViewport.calloc(1, stack);
            v.get(0)
                    .x(viewport[0])
                    .y(viewport[1])
                    .width(viewport[2])
                    .height(viewport[3])
                    .minDepth(0f)
                    .maxDepth(1f);
            vkCmdSetViewport(c, 0, v);
            VkRect2D.Buffer s = VkRect2D.calloc(1, stack);
            if (scissor == null) {
                s.get(0)
                        .offset(o -> o.set(0, 0))
                        .extent(e -> e.set(target.width(), target.height()));
            } else {
                int x = Math.max(0, scissor[0]);
                int y = Math.max(0, scissor[1]);
                int w = Math.max(0, scissor[2] - (x - scissor[0]));
                int h = Math.max(0, scissor[3] - (y - scissor[1]));
                s.get(0).offset(o -> o.set(x, y)).extent(e -> e.set(w, h));
            }
            vkCmdSetScissor(c, 0, s);
        }
        dynDirty = false;
    }

    // ---- draw ----

    /** Gets everything ready to draw; the command buffer, or null if no draw pipeline is bound. */
    private VkCommandBuffer prepareDraw() {
        check();
        SteinVkPipeline p = pipeline;
        if (p == null) {
            return null;
        }
        if (p.isCompute()) {
            throw new IllegalStateException("draw with a compute pipeline bound: " + p.desc().name);
        }
        if (target.width() == 0 || target.height() == 0 || viewport[2] <= 0 || viewport[3] <= 0) {
            return null; // as in OpenGL: an empty viewport draws nothing
        }
        long handle = p.handle(formats);
        if (handle == VK_NULL_HANDLE) {
            return null;
        }
        VkCommandBuffer c = current();
        ensureRendering(c);
        if (handle != boundGraphics) {
            vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
            boundGraphics = handle;
            descDirty = true;
        }
        if (dynDirty) {
            applyDynamic(c);
        }
        if (vbDirty != 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                for (int i = 0; i < MAX_BINDINGS; i++) {
                    if ((vbDirty & (1 << i)) != 0 && vbBuffer[i] != VK_NULL_HANDLE) {
                        vkCmdBindVertexBuffers(
                                c, i, stack.longs(vbBuffer[i]), stack.longs(vbOffset[i]));
                    }
                }
            }
            vbDirty = 0;
        }
        if (indexDirty) {
            vkCmdBindIndexBuffer(c, indexBuffer, 0L, indexType);
            indexDirty = false;
        }
        if (descDirty) {
            writeDescriptors(c, VK_PIPELINE_BIND_POINT_GRAPHICS, p);
            descDirty = false;
        }
        return c;
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (vertexCount <= 0 || instanceCount <= 0) {
            return;
        }
        VkCommandBuffer c = prepareDraw();
        if (c != null) {
            vkCmdDraw(c, vertexCount, instanceCount, firstVertex, firstInstance);
        }
    }

    @Override
    public void drawIndexed(
            int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        if (indexCount <= 0 || instanceCount <= 0) {
            return;
        }
        if (indexBuffer == VK_NULL_HANDLE && pipeline != null) {
            throw new IllegalStateException(
                    "drawIndexed without an index buffer: " + pipeline.desc().name);
        }
        VkCommandBuffer c = prepareDraw();
        if (c != null) {
            vkCmdDrawIndexed(c, indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
        }
    }

    @Override
    public void drawIndirect(SteinBuffer buffer, long offset, int count) {
        if (count <= 0) {
            return;
        }
        VkCommandBuffer c = prepareDraw();
        if (c != null) {
            indirect(c, buffer.handle(), offset, count, 16, false);
        }
    }

    @Override
    public void drawIndexedIndirect(SteinBuffer buffer, long offset, int count) {
        if (count <= 0) {
            return;
        }
        if (indexBuffer == VK_NULL_HANDLE && pipeline != null) {
            throw new IllegalStateException(
                    "drawIndexedIndirect without an index buffer: " + pipeline.desc().name);
        }
        VkCommandBuffer c = prepareDraw();
        if (c != null) {
            indirect(c, buffer.handle(), offset, count, 20, true);
        }
    }

    /**
     * Without multiDrawIndirect, one command per call; with it, in blocks up to the GPU's limit.
     */
    private void indirect(
            VkCommandBuffer c, long buffer, long offset, int count, int stride, boolean indexed) {
        int step = device.multiDrawIndirect ? Math.max(1, device.maxDrawIndirectCount) : 1;
        for (int done = 0; done < count; done += step) {
            int n = Math.min(step, count - done);
            long at = offset + (long) done * stride;
            if (indexed) {
                vkCmdDrawIndexedIndirect(c, buffer, at, n, stride);
            } else {
                vkCmdDrawIndirect(c, buffer, at, n, stride);
            }
        }
    }

    @Override
    public void dispatch(int x, int y, int z) {
        check();
        SteinVkPipeline p = pipeline;
        if (p == null) {
            return;
        }
        if (!p.isCompute()) {
            throw new IllegalStateException(
                    "dispatch with a draw pipeline bound: " + p.desc().name);
        }
        device.outside(); // compute outside the render
        VkCommandBuffer c = current();
        SteinVkDevice.memoryBarrier(c);
        if (p.computeHandle() != boundCompute) {
            vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_COMPUTE, p.computeHandle());
            boundCompute = p.computeHandle();
        }
        writeDescriptors(c, VK_PIPELINE_BIND_POINT_COMPUTE, p);
        vkCmdDispatch(c, x, y, z);
        // the contract: the results are visible to the following draws (vertex, index, indirect,
        // shader)
        SteinVkDevice.memoryBarrier(c);
    }

    // ---- end ----

    @Override
    public void end() {
        check();
        suspend();
        ended = true;
        device.passEnded(this);
    }
}
