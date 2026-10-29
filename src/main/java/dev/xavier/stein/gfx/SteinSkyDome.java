/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.gfx.gl.SteinCoreGl;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * The Lens's own sky (the "Stein sky" option): in the overworld, at the start of the game's
 * renderSky, a sky of our own is drawn over the whole screen (sky_dome.frag), and the game's dome,
 * its sunrise fan and the void under the horizon are left out; the sun, the moon and the stars are
 * still the game's, drawn over it. Its colours come from the game's own for the place and the hour
 * — the horizon is the fog's colour, so the far land fades into it without a seam; the zenith is
 * the biome's sky; the sunset band takes the game's sunrise colours — but the gradient is smooth
 * and deeper, the sunset band wider and warmer, a soft glow sits around the sun, and the night is
 * darker. Its layers are written one by one in the shader, where effects can be added.
 *
 * <p>The sun is ours too, square like the game's and set the same way, but with a crisp edge, a
 * core past white (in the Lens's 16-bit scene it blinds) and a square glow; the game's sun texture
 * is left out while our sky is drawn ({@link #skipsTexture}). The moon stays the game's, phases and
 * all, with a cold halo of ours around it.
 *
 * <p>Off, or outside the overworld, nothing changes. Pipeline: Stein GFX only.
 */
public final class SteinSkyDome {

    private static final int BLOCK_BYTES = 304;

    /**
     * The sun's core in the 16-bit scene, times white: the exposure and the bloom bring it back.
     */
    private static final float SUN_CORE = 4f;

    private static final int RING_REGIONS = 3;

    private static volatile boolean on;
    private static boolean failed;

    /** Between skyBegin and skyEnd, when our sky was drawn: the game's dome is left out. */
    static boolean drawing;

    /** The game's sun texture (its GL name; 0 not loaded): left out under our sky. */
    private static int sunTexture;

    private static final ResourceLocation SUN =
            new ResourceLocation("textures/environment/sun.png");

    private static SteinPipeline pipeline;
    private static SteinBuffer blocks;
    private static final float[] B = new float[BLOCK_BYTES / 4];

    private SteinSkyDome() {}

    /** The Lens option (any thread). */
    public static void configure(boolean enabled) {
        on = enabled;
    }

    /** Before renderSky (render thread): draws our sky when it applies. */
    static void begin() {
        drawing = false;
        if (!on || failed || !SteinGfxOptions.ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        Entity view = mc.getRenderViewEntity();
        if (world == null || view == null || !world.provider.isSurfaceWorld()) {
            return;
        }
        try {
            SteinDevice dev = SteinDevice.get();
            if (pipeline == null) {
                create(dev);
            }
            ITextureObject sun = mc.getTextureManager().getTexture(SUN);
            sunTexture = sun == null ? 0 : sun.getGlTextureId();
            long at = write(dev, mc, world, view);
            SteinCommands c = dev.beginPass(SteinRenderPass.keep("stein sky", dev.gameTarget()));
            try {
                c.bindPipeline(pipeline);
                c.bindUniforms(0, blocks, at, BLOCK_BYTES);
                c.draw(3, 1, 0, 0);
            } finally {
                c.end();
            }
            drawing = true;
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "Stein sky turned off for this session: " + e);
        }
    }

    /** Whether a draw with the game texture {@code tex} is the game's sun, which ours replaces. */
    static boolean skipsTexture(int tex) {
        return drawing && tex != 0 && tex == sunTexture;
    }

    /** After renderSky. */
    static void end() {
        drawing = false;
    }

    /**
     * The block: texel; params the projection's x and y; params2..4 the camera's rotation (world ->
     * eye) by columns; params5 the zenith (the biome's sky); params6 the horizon (the fog); params7
     * the sunrise colours (rgb, a how much); params8 xyz the sun in the world, w the night; params9
     * x the rain.
     */
    private static long write(SteinDevice dev, Minecraft mc, WorldClient world, Entity view) {
        float pt = mc.timer.renderPartialTicks;
        float w = Math.max(1, mc.displayWidth);
        float h = Math.max(1, mc.displayHeight);
        B[0] = 1f / w;
        B[1] = 1f / h;
        B[2] = 1f / w;
        B[3] = 1f / h;
        float[] m = GLMatrix.current();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer proj = stack.mallocFloat(16);
            SteinCoreGl.glGetFloatv(GL11.GL_PROJECTION_MATRIX, proj);
            B[4] = proj.get(0);
            B[5] = proj.get(5);
            FloatBuffer fog = stack.mallocFloat(4);
            SteinCoreGl.glGetFloatv(GL11.GL_FOG_COLOR, fog);
            B[24] = fog.get(0);
            B[25] = fog.get(1);
            B[26] = fog.get(2);
        }
        horizon(mc, world, view, pt);
        // the rotation's columns (m is column major: the world's axes in eye space)
        for (int c = 0; c < 3; c++) {
            B[8 + c * 4] = m[c * 4];
            B[9 + c * 4] = m[c * 4 + 1];
            B[10 + c * 4] = m[c * 4 + 2];
        }
        Vec3 sky = world.getSkyColor(view, pt);
        B[20] = (float) sky.xCoord;
        B[21] = (float) sky.yCoord;
        B[22] = (float) sky.zCoord;
        float celestial = world.getCelestialAngle(pt);
        float[] sunrise = world.provider.calcSunriseSunsetColors(celestial, pt);
        if (sunrise != null) {
            B[28] = sunrise[0];
            B[29] = sunrise[1];
            B[30] = sunrise[2];
            B[31] = sunrise[3];
        }
        double angle = celestial * Math.PI * 2.0;
        float sunX = (float) -Math.sin(angle);
        float sunY = (float) Math.cos(angle);
        B[32] = sunX;
        B[33] = sunY;
        B[34] = 0f;
        B[35] = Math.max(0f, Math.min(1f, (0.15f - sunY) / 0.35f)); // the night, 0..1
        B[36] = world.getRainStrength(pt);
        // past white only where the scene holds it (the Lens's 16-bit scene): else a white square
        B[37] = SteinPostFx.wideScene() ? SUN_CORE : 1f;
        // the glow by the hour (SteinAtmosphere): params9.z its strength (1 at sunrise), w the
        // sunset's share
        B[38] = SteinAtmosphere.sunGlow(sunX, sunY);
        B[39] = SteinAtmosphere.sunsetShare(sunX, sunY);
        int align = dev.caps().uniformAlignment;
        long at = blocks.reserve(BLOCK_BYTES, align);
        if (at == -2) {
            blocks.nextRegion();
            at = blocks.reserve(BLOCK_BYTES, align);
        }
        long p = blocks.address(at);
        for (int i = 0; i < B.length; i++) {
            MemoryUtil.memPutFloat(p + 4L * i, B[i]);
            B[i] = 0f;
        }
        blocks.commitRing();
        return at;
    }

    /**
     * The horizon's colour, fixed in the world: the game mixes the sunrise's colour into its fog by
     * where the eye looks (turning towards the sun the whole sky went orange, away from it the same
     * place went the night's colour). The same fog without that look (the world's fog towards the
     * sky's colour by the view distance, darker in rain and storm), into B[24..26]; the sunset band
     * (layer 3) puts the orange only on the sun's side. In water or lava the game's own stays.
     */
    private static void horizon(Minecraft mc, WorldClient world, Entity view, float pt) {
        Block at = ActiveRenderInfo.getBlockAtEntityViewpoint(world, view, pt);
        if (at.getMaterial().isLiquid()) {
            return;
        }
        Vec3 fog = world.getFogColor(pt);
        Vec3 sky = world.getSkyColor(view, pt);
        float far =
                1f
                        - (float)
                                Math.pow(
                                        0.25 + 0.75 * mc.gameSettings.renderDistanceChunks / 32.0,
                                        0.25);
        float r = (float) (fog.xCoord + (sky.xCoord - fog.xCoord) * far);
        float g = (float) (fog.yCoord + (sky.yCoord - fog.yCoord) * far);
        float b = (float) (fog.zCoord + (sky.zCoord - fog.zCoord) * far);
        float rain = world.getRainStrength(pt);
        float storm = world.getThunderStrength(pt);
        float dim = (1f - rain * 0.5f) * (1f - storm * 0.5f);
        float dimBlue = (1f - rain * 0.4f) * (1f - storm * 0.5f);
        B[24] = r * dim;
        B[25] = g * dim;
        B[26] = b * dimBlue;
    }

    private static void create(SteinDevice dev) {
        pipeline =
                dev.createPipeline(
                        SteinPipeline.builder("stein sky")
                                .vertex(SteinShader.load(SteinShader.Stage.VERTEX, "post.vert"))
                                .fragment(
                                        SteinShader.load(
                                                SteinShader.Stage.FRAGMENT, "sky_dome.frag"))
                                .depth(null, false)
                                .cull(SteinPipeline.Cull.NONE)
                                .uniforms("Post", 0)
                                .build());
        if (!pipeline.valid()) {
            throw new IllegalStateException("the Stein sky's pipeline did not link");
        }
        blocks =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "stein sky blocks",
                                        4096L * RING_REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.UNIFORM))
                        .ring(RING_REGIONS);
    }
}
