/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.gfx.SteinFormat;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/**
 * Translation of {@link SteinFormat} to OpenGL: texture and vertex attribute formats.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGlFormats {

    private SteinGlFormats() {}

    // ---- texture ----

    /** The texture's internal format. */
    public static int internalFormat(SteinFormat f) {
        return switch (f) {
            case RGBA8 -> GL11.GL_RGBA8;
            case R8 -> GL30.GL_R8;
            case RG8 -> GL30.GL_RG8;
            case RGBA16F -> GL30.GL_RGBA16F;
            case R32F -> GL30.GL_R32F;
            case DEPTH24 -> GL14.GL_DEPTH_COMPONENT24;
            case DEPTH32F -> GL30.GL_DEPTH_COMPONENT32F;
            default -> throw new IllegalArgumentException("vertex format used as a texture: " + f);
        };
    }

    /** The format of the pixels uploaded to the texture. */
    public static int pixelFormat(SteinFormat f) {
        return switch (f) {
            case RGBA8, RGBA16F -> GL11.GL_RGBA;
            case R8, R32F -> GL11.GL_RED;
            case RG8 -> GL30.GL_RG;
            case DEPTH24, DEPTH32F -> GL11.GL_DEPTH_COMPONENT;
            default -> throw new IllegalArgumentException("vertex format used as a texture: " + f);
        };
    }

    /** The type of the pixels uploaded to the texture. */
    public static int pixelType(SteinFormat f) {
        return switch (f) {
            case RGBA8, R8, RG8 -> GL11.GL_UNSIGNED_BYTE;
            case RGBA16F -> GL30.GL_HALF_FLOAT;
            case R32F, DEPTH32F -> GL11.GL_FLOAT;
            case DEPTH24 -> GL11.GL_UNSIGNED_INT;
            default -> throw new IllegalArgumentException("vertex format used as a texture: " + f);
        };
    }

    // ---- vertex attribute ----

    /** How many components the attribute has. */
    public static int attribSize(SteinFormat f) {
        return switch (f) {
            case FLOAT1, INT1, UINT1 -> 1;
            case FLOAT2, USHORT2_NORM, USHORT2 -> 2;
            case FLOAT3 -> 3;
            case FLOAT4, UBYTE4_NORM, BYTE4_NORM, UINT4, USHORT4 -> 4;
            default ->
                    throw new IllegalArgumentException("texture format used as an attribute: " + f);
        };
    }

    /** The type of each component of the attribute. */
    public static int attribType(SteinFormat f) {
        return switch (f) {
            case FLOAT1, FLOAT2, FLOAT3, FLOAT4 -> GL11.GL_FLOAT;
            case UBYTE4_NORM -> GL11.GL_UNSIGNED_BYTE;
            case BYTE4_NORM -> GL11.GL_BYTE;
            case USHORT2_NORM, USHORT4, USHORT2 -> GL11.GL_UNSIGNED_SHORT;
            case INT1 -> GL11.GL_INT;
            case UINT1, UINT4 -> GL11.GL_UNSIGNED_INT;
            default ->
                    throw new IllegalArgumentException("texture format used as an attribute: " + f);
        };
    }

    /** Integers normalized to 0..1 or -1..1 in the shader. */
    public static boolean attribNormalized(SteinFormat f) {
        return f == SteinFormat.UBYTE4_NORM
                || f == SteinFormat.BYTE4_NORM
                || f == SteinFormat.USHORT2_NORM;
    }

    /** Reaches the shader as an integer (glVertexAttribIFormat / glVertexAttribIPointer). */
    public static boolean attribInteger(SteinFormat f) {
        return f == SteinFormat.INT1
                || f == SteinFormat.UINT1
                || f == SteinFormat.UINT4
                || f == SteinFormat.USHORT4
                || f == SteinFormat.USHORT2;
    }
}
