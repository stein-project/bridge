/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

/**
 * Formats of the Stein graphics API: texture (pixel) and vertex attribute. Each backend translates
 * them to its own (OpenGL: internal format, format and type; Vulkan: VkFormat).
 *
 * <p>Pipeline: Stein GFX only.
 */
public enum SteinFormat {
    // ---- texture ----
    RGBA8(4),
    /**
     * The game's textures: 8 bits per channel with the bytes in memory as B, G, R, A (OpenGL's BGRA
     * with UNSIGNED_INT_8_8_8_8_REV on a little-endian machine). Sampled as RGBA like any other.
     */
    BGRA8(4),
    R8(1),
    RG8(2),
    RGBA16F(8),
    R32F(4),
    DEPTH24(4),
    DEPTH32F(4),
    // ---- vertex attribute ----
    FLOAT1(4),
    FLOAT2(8),
    FLOAT3(12),
    FLOAT4(16),
    /** 4 unsigned bytes, normalized to 0..1 (color). */
    UBYTE4_NORM(4),
    /** 4 signed bytes, normalized to -1..1 (normal). */
    BYTE4_NORM(4),
    /** 2 unsigned shorts, normalized (lightmap coordinate). */
    USHORT2_NORM(4),
    /**
     * 4 unsigned shorts, integers in the shader (uvec4: the terrain position in 1/1024 of a block
     * and the light).
     */
    USHORT4(8),
    /**
     * 2 unsigned shorts, integers in the shader (uvec2: the terrain texture coordinate in 1/32768).
     */
    USHORT2(4),
    INT1(4),
    UINT1(4),
    UINT4(16);

    /** Bytes per pixel (texture) or per attribute (vertex). */
    public final int bytes;

    SteinFormat(int bytes) {
        this.bytes = bytes;
    }

    public boolean isDepth() {
        return this == DEPTH24 || this == DEPTH32F;
    }
}
