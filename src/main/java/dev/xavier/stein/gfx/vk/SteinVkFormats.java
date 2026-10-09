/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_CONSTANT_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_CONSTANT_COLOR;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_DST_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_DST_COLOR;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_COLOR;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ZERO;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_ALWAYS;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_NEVER;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_NOT_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_BACK_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_FRONT_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_X8_D24_UNORM_PACK32;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;

import dev.xavier.stein.gfx.SteinFormat;
import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinSampler;

/**
 * Translation of Stein API enums to Vulkan: formats, blend factors, comparisons, filters.
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkFormats {

    private SteinVkFormats() {}

    /**
     * The texture's VkFormat. {@code DEPTH24} is X8_D24 when the card has it (for sampling and as a
     * target); otherwise it becomes D32_SFLOAT (some AMD cards lack D24) — {@code depth24} says
     * which one the device chose.
     */
    static int texture(SteinFormat f, int depth24) {
        return switch (f) {
            case RGBA8 -> VK_FORMAT_R8G8B8A8_UNORM;
            case R8 -> VK_FORMAT_R8_UNORM;
            case RG8 -> VK_FORMAT_R8G8_UNORM;
            case RGBA16F -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case R32F -> VK_FORMAT_R32_SFLOAT;
            case DEPTH24 -> depth24;
            case DEPTH32F -> VK_FORMAT_D32_SFLOAT;
            default -> throw new IllegalArgumentException("vertex format used as a texture: " + f);
        };
    }

    /**
     * The VkFormat of the vertex attribute (integers arrive as uint/int in the shader, as in
     * OpenGL).
     */
    static int attribute(SteinFormat f) {
        return switch (f) {
            case FLOAT1 -> VK_FORMAT_R32_SFLOAT;
            case FLOAT2 -> VK_FORMAT_R32G32_SFLOAT;
            case FLOAT3 -> VK_FORMAT_R32G32B32_SFLOAT;
            case FLOAT4 -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case UBYTE4_NORM -> VK_FORMAT_R8G8B8A8_UNORM;
            case BYTE4_NORM -> VK_FORMAT_R8G8B8A8_SNORM;
            case USHORT2_NORM -> VK_FORMAT_R16G16_UNORM;
            case USHORT4 -> VK_FORMAT_R16G16B16A16_UINT;
            case USHORT2 -> VK_FORMAT_R16G16_UINT;
            case INT1 -> VK_FORMAT_R32_SINT;
            case UINT1 -> VK_FORMAT_R32_UINT;
            case UINT4 -> VK_FORMAT_R32G32B32A32_UINT;
            default ->
                    throw new IllegalArgumentException("texture format used as an attribute: " + f);
        };
    }

    static boolean isDepth(int vkFormat) {
        return vkFormat == VK_FORMAT_D32_SFLOAT
                || vkFormat == VK_FORMAT_X8_D24_UNORM_PACK32
                || vkFormat == VK_FORMAT_D16_UNORM
                || vkFormat == VK_FORMAT_D24_UNORM_S8_UINT
                || vkFormat == VK_FORMAT_D32_SFLOAT_S8_UINT;
    }

    static int topology(SteinPipeline.Topology t) {
        return switch (t) {
            case TRIANGLES -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case LINES -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case POINTS -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
        };
    }

    static int compare(SteinPipeline.Depth d) {
        return switch (d) {
            case ALWAYS -> VK_COMPARE_OP_ALWAYS;
            case LESS -> VK_COMPARE_OP_LESS;
            case LEQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case EQUAL -> VK_COMPARE_OP_EQUAL;
            case GREATER -> VK_COMPARE_OP_GREATER;
            case GEQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            case NOTEQUAL -> VK_COMPARE_OP_NOT_EQUAL;
            case NEVER -> VK_COMPARE_OP_NEVER;
        };
    }

    static int cull(SteinPipeline.Cull c) {
        return switch (c) {
            case NONE -> VK_CULL_MODE_NONE;
            case BACK -> VK_CULL_MODE_BACK_BIT;
            case FRONT -> VK_CULL_MODE_FRONT_BIT;
        };
    }

    static int factor(SteinPipeline.Factor f) {
        return switch (f) {
            case ZERO -> VK_BLEND_FACTOR_ZERO;
            case ONE -> VK_BLEND_FACTOR_ONE;
            case SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case DST_COLOR -> VK_BLEND_FACTOR_DST_COLOR;
            case ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case DST_ALPHA -> VK_BLEND_FACTOR_DST_ALPHA;
            case ONE_MINUS_DST_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case CONSTANT_COLOR -> VK_BLEND_FACTOR_CONSTANT_COLOR;
            case ONE_MINUS_CONSTANT_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case CONSTANT_ALPHA -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case SRC_ALPHA_SATURATE -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
        };
    }

    /**
     * Color and alpha factors (source, destination) of the blend mode, the same as in the OpenGL
     * backend: outside {@link SteinPipeline.Blend#CUSTOM}, alpha uses the color factors.
     */
    static int[] blendFactors(SteinPipeline.Desc d) {
        int src;
        int dst;
        switch (d.blend) {
            case ALPHA -> {
                src = VK_BLEND_FACTOR_SRC_ALPHA;
                dst = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            }
            case ADDITIVE -> {
                src = VK_BLEND_FACTOR_ONE;
                dst = VK_BLEND_FACTOR_ONE;
            }
            case ADDITIVE_COLOR -> {
                src = VK_BLEND_FACTOR_SRC_COLOR;
                dst = VK_BLEND_FACTOR_ONE;
            }
            case PREMULTIPLIED -> {
                src = VK_BLEND_FACTOR_ONE;
                dst = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            }
            case CUSTOM -> {
                return new int[] {
                    factor(d.factors[0]),
                    factor(d.factors[1]),
                    factor(d.factors[2]),
                    factor(d.factors[3])
                };
            }
            default -> {
                src = VK_BLEND_FACTOR_ONE;
                dst = VK_BLEND_FACTOR_ZERO;
            }
        }
        return new int[] {src, dst, src, dst};
    }

    static int filter(SteinSampler.Filter f) {
        return f == SteinSampler.Filter.LINEAR ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    }

    static int wrap(SteinSampler.Wrap w) {
        return w == SteinSampler.Wrap.CLAMP
                ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
                : VK_SAMPLER_ADDRESS_MODE_REPEAT;
    }
}
