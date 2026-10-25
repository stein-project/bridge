/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_ALWAYS;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_DEPTH_BIAS;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_LINE_WIDTH;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_INSTANCE;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinShader;
import dev.xavier.stein.gfx.SteinVertexLayout;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportDepthClipControlCreateInfoEXT;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Vulkan pipeline. The shader modules and the resource layout are created at construction; the draw
 * {@code VkPipeline} is created on first use with each set of target formats (dynamic rendering:
 * the pipeline needs to know the formats, the API description does not), and is kept. The compute
 * one is created at construction.
 *
 * <p>Resources: one descriptor set (set 0) with the bindings that the build's reflection found in
 * the shaders ({@code .res}). The slot of each sampler and block comes from the description by
 * name, as in OpenGL; here the name leads to the shader's binding.
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkPipeline extends SteinPipeline {
    private static final int PUSH_DESCRIPTOR_LAYOUT_FLAG =
            KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;

    /**
     * A binding of set 0 and the API slot that feeds it ({@code slot} -1: the description gives it
     * no name; the commands bind an empty resource, like texture 0 in OpenGL). {@code arrayed}: the
     * sampler is an array one.
     */
    record Binding(SteinShader.Resource.Kind kind, int binding, int slot, boolean arrayed) {}

    private final SteinVkDevice device;
    private final List<Long> modules = new ArrayList<>();
    private long vertexModule;
    private long fragmentModule;
    private long computeModule;
    private long setLayout = VK_NULL_HANDLE;
    private long pipelineLayout = VK_NULL_HANDLE;

    /** Entry point of every shader module. */
    private static final String ENTRY_POINT = "main";

    private final List<Binding> bindings = new ArrayList<>();

    /** Resources through push descriptors (the GPU supports it, and the set fits its limit). */
    private boolean push;

    private final Map<SteinVkRenderTarget.Formats, Long> variants = new HashMap<>();

    /**
     * The last target's formats and the pipeline made for them: a draw usually repeats the last.
     */
    private SteinVkRenderTarget.Formats lastFormats;

    private long lastHandle = VK_NULL_HANDLE;
    private long computePipeline = VK_NULL_HANDLE;
    private boolean valid;
    private boolean destroyed;

    SteinVkPipeline(SteinVkDevice device, Desc desc) {
        super(desc);
        this.device = device;
        try {
            build();
            valid = true;
        } catch (RuntimeException e) {
            Log.info("gfx", "pipeline " + desc.name + " did not bind on Vulkan: " + e.getMessage());
            release();
        }
    }

    private void build() {
        SteinShader[] stages =
                desc.isCompute()
                        ? new SteinShader[] {desc.compute}
                        : new SteinShader[] {desc.vertex, desc.fragment};
        // resources of all stages, by binding (the same block can appear in the vertex and in the
        // pixel stage)
        TreeMap<Integer, SteinShader.Resource> byBinding = new TreeMap<>();
        for (SteinShader s : stages) {
            if (s == null) {
                continue;
            }
            if (s.spirv == null) {
                throw new IllegalStateException(
                        "shader " + s.name + " has no SPIR-V (assembled as text)");
            }
            long m = module(s);
            switch (s.stage) {
                case VERTEX -> vertexModule = m;
                case FRAGMENT -> fragmentModule = m;
                case COMPUTE -> computeModule = m;
            }
            for (SteinShader.Resource r : s.resources) {
                if (r.set() != 0) {
                    throw new IllegalStateException(
                            "shader "
                                    + s.name
                                    + ": "
                                    + r.name()
                                    + " in set "
                                    + r.set()
                                    + " (the backend only uses set 0)");
                }
                SteinShader.Resource prev = byBinding.putIfAbsent(r.binding(), r);
                if (prev != null && prev.kind() != r.kind()) {
                    throw new IllegalStateException(
                            "binding "
                                    + r.binding()
                                    + " is shared by "
                                    + prev.name()
                                    + " and "
                                    + r.name());
                }
            }
        }
        if (!desc.isCompute() && vertexModule == VK_NULL_HANDLE) {
            throw new IllegalStateException("no vertex shader");
        }
        Map<Integer, Integer> slotOf = new HashMap<>();
        mapSlots(SteinShader.Resource.Kind.TEXTURE, desc.textures, byBinding, slotOf);
        mapSlots(SteinShader.Resource.Kind.UNIFORM, desc.uniforms, byBinding, slotOf);
        mapSlots(SteinShader.Resource.Kind.STORAGE, desc.storage, byBinding, slotOf);
        for (SteinShader.Resource r : byBinding.values()) {
            Integer slot = slotOf.get(r.binding());
            if (slot == null) {
                Log.info(
                        "gfx",
                        "pipeline "
                                + desc.name
                                + ": "
                                + r.name()
                                + " (binding "
                                + r.binding()
                                + ") has no slot in the description: it gets an empty resource");
            }
            bindings.add(new Binding(r.kind(), r.binding(), slot == null ? -1 : slot, r.arrayed()));
        }
        push = device.pushDescriptors && bindings.size() <= device.maxPushDescriptors;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            int stageFlags =
                    desc.isCompute() ? VK_SHADER_STAGE_COMPUTE_BIT : VK_SHADER_STAGE_ALL_GRAPHICS;
            VkDescriptorSetLayoutBinding.Buffer layoutBindings =
                    VkDescriptorSetLayoutBinding.calloc(byBinding.size(), stack);
            int i = 0;
            for (SteinShader.Resource r : byBinding.values()) {
                layoutBindings
                        .get(i++)
                        .binding(r.binding())
                        .descriptorType(descriptorType(r.kind()))
                        .descriptorCount(1)
                        .stageFlags(stageFlags);
            }
            if (byBinding.size() > 0) {
                SteinVkDevice.check(
                        vkCreateDescriptorSetLayout(
                                device.vk(),
                                VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                        .sType$Default()
                                        .flags(push ? PUSH_DESCRIPTOR_LAYOUT_FLAG : 0)
                                        .pBindings(layoutBindings),
                                null,
                                lp),
                        "resource layout");
                setLayout = lp.get(0);
            }
            VkPipelineLayoutCreateInfo pli =
                    VkPipelineLayoutCreateInfo.calloc(stack)
                            .sType$Default()
                            .pSetLayouts(
                                    setLayout == VK_NULL_HANDLE ? null : stack.longs(setLayout));
            SteinVkDevice.check(
                    vkCreatePipelineLayout(device.vk(), pli, null, lp), "pipeline layout");
            pipelineLayout = lp.get(0);

            if (desc.isCompute()) {
                VkComputePipelineCreateInfo.Buffer ci =
                        VkComputePipelineCreateInfo.calloc(1, stack);
                ci.get(0)
                        .sType$Default()
                        .stage(
                                s ->
                                        s.sType$Default()
                                                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                                                .module(computeModule)
                                                .pName(stack.UTF8(ENTRY_POINT)))
                        .layout(pipelineLayout);
                SteinVkDevice.check(
                        vkCreateComputePipelines(device.vk(), device.pipelineCache(), ci, null, lp),
                        "compute pipeline");
                computePipeline = lp.get(0);
            }
        }
    }

    private void mapSlots(
            SteinShader.Resource.Kind kind,
            Map<String, Integer> byName,
            TreeMap<Integer, SteinShader.Resource> byBinding,
            Map<Integer, Integer> slotOf) {
        for (Map.Entry<String, Integer> e : byName.entrySet()) {
            SteinShader.Resource found = null;
            for (SteinShader.Resource r : byBinding.values()) {
                if (r.kind() == kind && r.name().equals(e.getKey())) {
                    found = r;
                }
            }
            if (found == null) {
                // as in OpenGL (a name the shader does not have is ignored): only the warning
                Log.info(
                        "gfx",
                        "pipeline "
                                + desc.name
                                + ": "
                                + kind
                                + " '"
                                + e.getKey()
                                + "' is not in the shaders");
                continue;
            }
            slotOf.put(found.binding(), e.getValue());
        }
    }

    static int descriptorType(SteinShader.Resource.Kind kind) {
        return switch (kind) {
            case TEXTURE -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case UNIFORM -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case STORAGE -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        };
    }

    private long module(SteinShader s) {
        ByteBuffer code = MemoryUtil.memAlloc(s.spirv.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            code.put(s.spirv).flip();
            LongBuffer lp = stack.mallocLong(1);
            SteinVkDevice.check(
                    vkCreateShaderModule(
                            device.vk(),
                            VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),
                            null,
                            lp),
                    "module " + s.name);
            modules.add(lp.get(0));
            return lp.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    // ---- use by the commands ----

    List<Binding> bindings() {
        return bindings;
    }

    boolean push() {
        return push;
    }

    long setLayout() {
        return setLayout;
    }

    long layout() {
        return pipelineLayout;
    }

    boolean isCompute() {
        return desc.isCompute();
    }

    long computeHandle() {
        return computePipeline;
    }

    /**
     * The draw VkPipeline for the target's formats (created the first time); 0 if it could not be
     * created (the log appears once, and the draw is skipped, like a pipeline that fails to link in
     * OpenGL).
     */
    long handle(SteinVkRenderTarget.Formats formats) {
        if (formats == lastFormats) {
            return lastHandle; // the same target as the last draw: no lookup
        }
        if (!valid()) {
            return VK_NULL_HANDLE;
        }
        Long p = variants.get(formats);
        if (p == null) {
            try {
                p = createGraphics(formats);
            } catch (RuntimeException e) {
                Log.info(
                        "gfx",
                        "pipeline "
                                + desc.name
                                + " was not created for this target: "
                                + e.getMessage());
                p = VK_NULL_HANDLE;
            }
            variants.put(formats, p);
        }
        lastFormats = formats;
        lastHandle = p;
        return p;
    }

    private long createGraphics(SteinVkRenderTarget.Formats formats) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int stageCount = fragmentModule == VK_NULL_HANDLE ? 1 : 2;
            VkPipelineShaderStageCreateInfo.Buffer stages =
                    VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
            stages.get(0)
                    .sType$Default()
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertexModule)
                    .pName(stack.UTF8(ENTRY_POINT));
            if (stageCount == 2) {
                stages.get(1)
                        .sType$Default()
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                        .module(fragmentModule)
                        .pName(stack.UTF8(ENTRY_POINT));
            }

            SteinVertexLayout l = desc.layout;
            VkVertexInputBindingDescription.Buffer vb =
                    VkVertexInputBindingDescription.calloc(l.bindings.size(), stack);
            for (int i = 0; i < l.bindings.size(); i++) {
                SteinVertexLayout.Binding b = l.bindings.get(i);
                vb.get(i)
                        .binding(b.index)
                        .stride(b.stride)
                        .inputRate(
                                b.perInstance
                                        ? VK_VERTEX_INPUT_RATE_INSTANCE
                                        : VK_VERTEX_INPUT_RATE_VERTEX);
            }
            VkVertexInputAttributeDescription.Buffer va =
                    VkVertexInputAttributeDescription.calloc(l.attributes.size(), stack);
            for (int i = 0; i < l.attributes.size(); i++) {
                SteinVertexLayout.Attribute a = l.attributes.get(i);
                va.get(i)
                        .location(a.location)
                        .binding(a.binding)
                        .format(SteinVkFormats.attribute(a.format))
                        .offset(a.offset);
            }
            VkPipelineVertexInputStateCreateInfo vi =
                    VkPipelineVertexInputStateCreateInfo.calloc(stack)
                            .sType$Default()
                            .pVertexBindingDescriptions(l.bindings.isEmpty() ? null : vb)
                            .pVertexAttributeDescriptions(l.attributes.isEmpty() ? null : va);
            VkPipelineInputAssemblyStateCreateInfo ia =
                    VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                            .sType$Default()
                            .topology(SteinVkFormats.topology(desc.topology));

            VkPipelineViewportStateCreateInfo vp =
                    VkPipelineViewportStateCreateInfo.calloc(stack)
                            .sType$Default()
                            .viewportCount(1)
                            .scissorCount(1);
            if (device.depthClipControl) {
                // NDC depth from -1 to 1, as in OpenGL (the projection matrices are the game's)
                VkPipelineViewportDepthClipControlCreateInfoEXT clip =
                        VkPipelineViewportDepthClipControlCreateInfoEXT.calloc(stack)
                                .sType$Default()
                                .negativeOneToOne(true);
                vp.pNext(clip.address());
            }
            // Without flipping the viewport, the image keeps the same row order as OpenGL, and its
            // counter-clockwise is clockwise here (see SteinVkDevice).
            VkPipelineRasterizationStateCreateInfo rs =
                    VkPipelineRasterizationStateCreateInfo.calloc(stack)
                            .sType$Default()
                            .polygonMode(VK_POLYGON_MODE_FILL)
                            .cullMode(SteinVkFormats.cull(desc.cull))
                            .frontFace(VK_FRONT_FACE_CLOCKWISE)
                            // always on, zero unless the pass sets it (setDepthBias): OpenGL's
                            // polygon offset is a switch, Vulkan 1.2's bias is pipeline state
                            .depthBiasEnable(true)
                            .lineWidth(1f);
            VkPipelineMultisampleStateCreateInfo ms =
                    VkPipelineMultisampleStateCreateInfo.calloc(stack)
                            .sType$Default()
                            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            boolean depthTest = desc.depthTest != null && formats.depth() != VK_FORMAT_UNDEFINED;
            VkPipelineDepthStencilStateCreateInfo ds =
                    VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                            .sType$Default()
                            .depthTestEnable(depthTest)
                            .depthWriteEnable(depthTest && desc.depthWrite)
                            .depthCompareOp(
                                    depthTest
                                            ? SteinVkFormats.compare(desc.depthTest)
                                            : VK_COMPARE_OP_ALWAYS);

            int colors = formats.colors().length;
            int[] f = SteinVkFormats.blendFactors(desc);
            // without a pixel shader the colour would be undefined: depth only
            int mask =
                    !desc.colorWrite || fragmentModule == VK_NULL_HANDLE
                            ? 0
                            : VK_COLOR_COMPONENT_R_BIT
                                    | VK_COLOR_COMPONENT_G_BIT
                                    | VK_COLOR_COMPONENT_B_BIT
                                    | (desc.alphaWrite ? VK_COLOR_COMPONENT_A_BIT : 0);
            VkPipelineColorBlendAttachmentState.Buffer att =
                    VkPipelineColorBlendAttachmentState.calloc(Math.max(1, colors), stack);
            for (int i = 0; i < colors; i++) {
                att.get(i)
                        .blendEnable(desc.blend != Blend.NONE)
                        .srcColorBlendFactor(f[0])
                        .dstColorBlendFactor(f[1])
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(f[2])
                        .dstAlphaBlendFactor(f[3])
                        .alphaBlendOp(VK_BLEND_OP_ADD)
                        .colorWriteMask(mask);
            }
            VkPipelineColorBlendStateCreateInfo cb =
                    VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
            if (colors > 0) {
                cb.pAttachments(att.limit(colors)).attachmentCount(colors);
            }
            if (desc.logicOp != null) {
                if (device.logicOp) {
                    cb.logicOpEnable(true).logicOp(desc.logicOp.ordinal());
                } else {
                    Log.info(
                            "gfx",
                            "vulkan: no logicOp feature, '" + desc.name + "' draws without it");
                }
            }
            VkPipelineDynamicStateCreateInfo dyn =
                    VkPipelineDynamicStateCreateInfo.calloc(stack)
                            .sType$Default()
                            .pDynamicStates(
                                    device.wideLines
                                            ? stack.ints(
                                                    VK_DYNAMIC_STATE_VIEWPORT,
                                                    VK_DYNAMIC_STATE_SCISSOR,
                                                    VK_DYNAMIC_STATE_DEPTH_BIAS,
                                                    VK_DYNAMIC_STATE_LINE_WIDTH)
                                            : stack.ints(
                                                    VK_DYNAMIC_STATE_VIEWPORT,
                                                    VK_DYNAMIC_STATE_SCISSOR,
                                                    VK_DYNAMIC_STATE_DEPTH_BIAS));

            IntBuffer colorFormats = colors == 0 ? null : stack.ints(formats.colors());
            VkPipelineRenderingCreateInfo ri =
                    VkPipelineRenderingCreateInfo.calloc(stack)
                            .sType$Default()
                            .pColorAttachmentFormats(colorFormats)
                            .colorAttachmentCount(colors)
                            .depthAttachmentFormat(formats.depth());

            VkGraphicsPipelineCreateInfo.Buffer gi = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            gi.get(0)
                    .sType$Default()
                    .pNext(ri.address())
                    .stageCount(stageCount)
                    .pStages(stages)
                    .pVertexInputState(vi)
                    .pInputAssemblyState(ia)
                    .pViewportState(vp)
                    .pRasterizationState(rs)
                    .pMultisampleState(ms)
                    .pDepthStencilState(ds)
                    .pColorBlendState(cb)
                    .pDynamicState(dyn)
                    .layout(pipelineLayout)
                    .renderPass(VK_NULL_HANDLE);
            LongBuffer lp = stack.mallocLong(1);
            SteinVkDevice.check(
                    vkCreateGraphicsPipelines(device.vk(), device.pipelineCache(), gi, null, lp),
                    "pipeline " + desc.name);
            return lp.get(0);
        }
    }

    @Override
    public boolean valid() {
        return valid && !destroyed;
    }

    @Override
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        device.retire(this::release);
    }

    private void release() {
        VkDevice d = device.vk();
        for (long p : variants.values()) {
            if (p != VK_NULL_HANDLE) {
                vkDestroyPipeline(d, p, null);
            }
        }
        variants.clear();
        if (computePipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(d, computePipeline, null);
            computePipeline = VK_NULL_HANDLE;
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(d, pipelineLayout, null);
            pipelineLayout = VK_NULL_HANDLE;
        }
        if (setLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(d, setLayout, null);
            setLayout = VK_NULL_HANDLE;
        }
        for (long m : modules) {
            vkDestroyShaderModule(d, m, null);
        }
        modules.clear();
    }
}
