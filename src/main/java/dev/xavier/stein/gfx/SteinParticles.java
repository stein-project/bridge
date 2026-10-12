/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The base drawing of a particle (EntityFX.renderParticle, beb.a), in place of the game's body
 * (patcher, body mode). Stage 4 of the renderer.
 *
 * <p>Measured in the particle storm: the particle pass was ~60% of the render thread, and almost
 * all of it was building the vertices through WorldRenderer — each element (position, texture,
 * colour, light) is a call that finds the next element of the format; the drawing itself (one
 * glDrawArrays per layer) does not show up. Here the 4 vertices of a particle are written at once
 * into WorldRenderer's own buffer, with the same arithmetic in the same order (the same float in
 * every byte), and the end of each vertex is the game's (endVertex: count and buffer growth). Since
 * the buffer is the same, the particles with their own drawing (block and item pieces, explosion)
 * stay interleaved in the game's order.
 *
 * <p>It applies when the WorldRenderer is in the particle format (PARTICLE_POSITION_TEX_COLOR_LMAP,
 * 28 bytes) and has colour; otherwise, and when {@code steinbridge.gfx} is off or {@code
 * steinbridge.gfx.particles=false}, the game's drawing runs call by call, as always. All the
 * subclasses that end up in the base drawing (fire, smoke, critical, potion, cloud...) pass through
 * here via {@code super}.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinParticles {

    private static final long STATS_INTERVAL_NANOS = 5_000_000_000L;

    private static final boolean FAST =
            SteinGfxOptions.PARTICLES && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private static final int STRIDE = 28;

    /**
     * {@code -Dsteinbridge.gfx.particleCheck=true}: each particle is written both ways and the
     * bytes compared.
     */
    private static final boolean CHECK = SteinGfxOptions.PARTICLE_CHECK;

    private static final byte[] FAST_BYTES = new byte[4 * STRIDE];
    private static long checked;
    private static long mismatched;
    private static long checkAt = System.nanoTime();

    // DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP
    private static final VertexFormat PARTICLE_FORMAT =
            DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP;

    private SteinParticles() {}

    /**
     * In place of the body of EntityFX.renderParticle(worldRenderer, entity, partialTicks, rotX,
     * rotZ, rotYZ, rotXY, rotXZ).
     */
    public static void render(
            Object fxObject,
            Object wrObject,
            Object entity,
            float pt,
            float rx,
            float rz,
            float ryz,
            float rxy,
            float rxz) {
        EntityFX fx = (EntityFX) fxObject;
        WorldRenderer wr = (WorldRenderer) wrObject;
        float u0 = (float) fx.particleTextureIndexX / 16.0F;
        float u1 = u0 + 0.0624375F;
        float v0 = (float) fx.particleTextureIndexY / 16.0F;
        float v1 = v0 + 0.0624375F;
        float s = 0.1F * fx.particleScale;
        TextureAtlasSprite icon = fx.particleIcon;
        if (icon != null) {
            u0 = icon.getMinU();
            u1 = icon.getMaxU();
            v0 = icon.getMinV();
            v1 = icon.getMaxV();
        }
        double px = fx.prevPosX;
        double py = fx.prevPosY;
        double pz = fx.prevPosZ;
        float x = (float) (px + (fx.posX - px) * (double) pt - EntityFX.interpPosX);
        float y = (float) (py + (fx.posY - py) * (double) pt - EntityFX.interpPosY);
        float z = (float) (pz + (fx.posZ - pz) * (double) pt - EntityFX.interpPosZ);
        int light = fx.getBrightnessForRender(pt);
        int sky = light >> 16 & 65535;
        int block = light & 65535;
        float r = fx.particleRed;
        float g = fx.particleGreen;
        float b = fx.particleBlue;
        float a = fx.particleAlpha;

        float x0 = x - rx * s - rxy * s;
        float y0 = y - rz * s;
        float z0 = z - ryz * s - rxz * s;
        float x1 = x - rx * s + rxy * s;
        float y1 = y + rz * s;
        float z1 = z - ryz * s + rxz * s;
        float x2 = x + rx * s + rxy * s;
        float y2 = y + rz * s;
        float z2 = z + ryz * s + rxz * s;
        float x3 = x + rx * s - rxy * s;
        float y3 = y - rz * s;
        float z3 = z + ryz * s - rxz * s;

        if (FAST && wr.vertexFormat == PARTICLE_FORMAT && !wr.noColor) {
            double ox = wr.xOffset;
            double oy = wr.yOffset;
            double oz = wr.zOffset;
            // colour: the game's color(float) = (int) (c * 255), one byte each (RGBA)
            int color =
                    ((int) (r * 255.0F) & 255)
                            | ((int) (g * 255.0F) & 255) << 8
                            | ((int) (b * 255.0F) & 255) << 16
                            | ((int) (a * 255.0F) & 255) << 24;
            // lightmap(sky, block): the block short first
            int lm = (block & 0xFFFF) | (sky & 0xFFFF) << 16;
            int start = wr.vertexCount;
            vertex(wr, x0, y0, z0, ox, oy, oz, u1, v1, color, lm);
            vertex(wr, x1, y1, z1, ox, oy, oz, u1, v0, color, lm);
            vertex(wr, x2, y2, z2, ox, oy, oz, u0, v0, color, lm);
            vertex(wr, x3, y3, z3, ox, oy, oz, u0, v1, color, lm);
            if (CHECK) {
                check(
                        wr, start, x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, u0, u1, v0, v1,
                        r, g, b, a, sky, block);
            }
            return;
        }
        // the game's drawing, call by call
        game(wr, x0, y0, z0, u1, v1, r, g, b, a, sky, block);
        game(wr, x1, y1, z1, u1, v0, r, g, b, a, sky, block);
        game(wr, x2, y2, z2, u0, v0, r, g, b, a, sky, block);
        game(wr, x3, y3, z3, u0, v1, r, g, b, a, sky, block);
    }

    /**
     * One vertex straight into the buffer, at the end of what is there (the game's endVertex leaves
     * room for it).
     */
    private static void vertex(
            WorldRenderer wr,
            float x,
            float y,
            float z,
            double ox,
            double oy,
            double oz,
            float u,
            float v,
            int color,
            int lm) {
        ByteBuffer buf = wr.byteBuffer;
        long p = MemoryUtil.memAddress(buf, 0) + (long) wr.vertexCount * STRIDE;
        MemoryUtil.memPutFloat(p, (float) ((double) x + ox));
        MemoryUtil.memPutFloat(p + 4, (float) ((double) y + oy));
        MemoryUtil.memPutFloat(p + 8, (float) ((double) z + oz));
        MemoryUtil.memPutFloat(p + 12, u);
        MemoryUtil.memPutFloat(p + 16, v);
        MemoryUtil.memPutInt(p + 20, color);
        MemoryUtil.memPutInt(p + 24, lm);
        wr.endVertex();
    }

    /**
     * Rewrites the particle through the game's path in the same place and compares it with what the
     * fast one wrote.
     */
    private static void check(
            WorldRenderer wr,
            int start,
            float x0,
            float y0,
            float z0,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float u0,
            float u1,
            float v0,
            float v1,
            float r,
            float g,
            float b,
            float a,
            int sky,
            int block) {
        long at = MemoryUtil.memAddress(wr.byteBuffer, 0) + (long) start * STRIDE;
        for (int i = 0; i < FAST_BYTES.length; i++) {
            FAST_BYTES[i] = MemoryUtil.memGetByte(at + i);
        }
        wr.vertexCount = start;
        game(wr, x0, y0, z0, u1, v1, r, g, b, a, sky, block);
        game(wr, x1, y1, z1, u1, v0, r, g, b, a, sky, block);
        game(wr, x2, y2, z2, u0, v0, r, g, b, a, sky, block);
        game(wr, x3, y3, z3, u0, v1, r, g, b, a, sky, block);
        at = MemoryUtil.memAddress(wr.byteBuffer, 0) + (long) start * STRIDE;
        checked++;
        for (int i = 0; i < FAST_BYTES.length; i++) {
            if (FAST_BYTES[i] != MemoryUtil.memGetByte(at + i)) {
                mismatched++;
                break;
            }
        }
        long now = System.nanoTime();
        if (now - checkAt > STATS_INTERVAL_NANOS) {
            checkAt = now;
            Log.info(
                    "gfx",
                    "particles, check: "
                            + checked
                            + " checked, "
                            + mismatched
                            + " differ from the game");
        }
    }

    private static void game(
            WorldRenderer wr,
            float x,
            float y,
            float z,
            float u,
            float v,
            float r,
            float g,
            float b,
            float a,
            int sky,
            int block) {
        wr.pos((double) x, (double) y, (double) z)
                .tex((double) u, (double) v)
                .color(r, g, b, a)
                .lightmap(sky, block)
                .endVertex();
    }
}
