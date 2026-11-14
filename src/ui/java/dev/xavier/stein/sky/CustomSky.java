/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */
package dev.xavier.stein.sky;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * The resource packs' own sky over the overworld: the layers of {@code
 * optifine/sky/world0/skyN.properties} or {@code mcpatcher/sky/world0/skyN.properties}, each a
 * picture of the six sides of a box around the camera, drawn in order over the game's sky. Made
 * from the format's public description.
 *
 * <p>Where it goes: RenderGlobal.renderSky binds the sun's texture right after the sky's colours
 * and the sunrise; in place of that bind (patcher/redirects-mods.tsv) the layers are drawn, then
 * the sun's texture is bound as the game asked. The matrix there is already turned for the sun (a
 * quarter turn about the vertical, then the time of day): the layers undo it, and turn about their
 * own axis with the time of day instead. The game's state at that point (additive blend, the rain's
 * fading colour) is given back after.
 *
 * <p>The picture is a grid of three by two squares — the bottom, the top and the south in the first
 * row, the west, the north and the east in the second — each laid on its side of the box the way
 * the bottom is laid on the floor, and the box turned to put it there.
 *
 * <p>Pipeline: both — the layers are drawn through the Tessellator and the GlStateManager, which
 * both pipelines take.
 */
public final class CustomSky {

    private static final String[] FOLDERS = {"optifine/sky/world0/", "mcpatcher/sky/world0/"};

    /** The highest N of {@code skyN.properties} looked for (the numbers may skip). */
    private static final int MAX_LAYERS = 64;

    private static final String SUN = "textures/environment/sun.png";

    /** Half the box's side, as the game's own sky boxes. */
    private static final float SIZE = 100f;

    /**
     * The six sides: the turn that puts the floor there (about x, about z), the grid square, and
     * how many quarter turns the square takes on its quad. The quarter turns were measured on real
     * packs' skies: with them every edge of the box continues from one side into the next.
     */
    private static final float[][] SIDES = {
        {0f, 0f, 0, 0, 0}, // bottom
        {180f, 0f, 1, 0, 0}, // top
        {-90f, 0f, 2, 0, 2}, // south
        {0f, -90f, 0, 1, 1}, // west
        {90f, 0f, 1, 1, 0}, // north
        {0f, 90f, 2, 1, 3} // east
    };

    /** The quad's corners, in the order drawn, as (u, v) on its square before the turns. */
    private static final float[][] CORNERS = {{0f, 0f}, {0f, 1f}, {1f, 1f}, {1f, 0f}};

    private static volatile boolean enabled = true;
    private static volatile List<SkyLayer> layers = Collections.emptyList();
    private static long lastNanos;

    private CustomSky() {}

    /** The option (any thread). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** How many layers the packs gave, for the option's hint. */
    public static int layerCount() {
        return layers.size();
    }

    /** After every resource reload: the layers of the packs now on. */
    public static void reload() {
        List<SkyLayer> found = new ArrayList<>();
        try {
            IResourceManager rm = Minecraft.getMinecraft().getResourceManager();
            for (String dir : FOLDERS) {
                for (int n = 1; n <= MAX_LAYERS; n++) {
                    Properties p =
                            properties(rm, new ResourceLocation(dir + "sky" + n + ".properties"));
                    if (p == null) {
                        continue;
                    }
                    StringBuilder why = new StringBuilder();
                    SkyLayer l = SkyLayer.parse(p, dir, n, why);
                    if (l == null) {
                        Log.info("sky", dir + "sky" + n + ".properties left out: " + why);
                    } else if (!exists(rm, l.texture)) {
                        Log.info("sky", dir + "sky" + n + ".properties left out: no " + l.texture);
                    } else {
                        found.add(l);
                    }
                }
                if (!found.isEmpty()) {
                    break; // one folder: the newer name wins over the older
                }
            }
        } catch (RuntimeException e) {
            Log.warn("sky", "the packs' sky could not be read: " + e);
            found.clear();
        }
        layers = Collections.unmodifiableList(found);
        if (!found.isEmpty()) {
            Log.info("sky", found.size() + " sky layer(s) from the resource packs");
        }
    }

    private static Properties properties(IResourceManager rm, ResourceLocation at) {
        try {
            IResource r = rm.getResource(at);
            try (InputStream in = r.getInputStream()) {
                Properties p = new Properties();
                p.load(in);
                return p;
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean exists(IResourceManager rm, ResourceLocation at) {
        try {
            rm.getResource(at).getInputStream().close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * In place of TextureManager.bindTexture inside RenderGlobal.renderSky: before the sun's, the
     * layers. Every bind then happens as the game asked.
     */
    public static void bindTexture(Object textureManager, Object location) {
        TextureManager tm = (TextureManager) textureManager;
        ResourceLocation at = (ResourceLocation) location;
        List<SkyLayer> now = layers;
        if (enabled && !now.isEmpty() && SUN.equals(at.getResourcePath())) {
            draw(tm, now);
        }
        tm.bindTexture(at);
    }

    private static void draw(TextureManager tm, List<SkyLayer> list) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        Entity view = mc.getRenderViewEntity();
        if (world == null || view == null || world.provider.getDimensionId() != 0) {
            return;
        }
        long t = System.nanoTime();
        float seconds = lastNanos == 0L ? 1f : Math.min(1f, (t - lastNanos) / 1e9f);
        lastNanos = t;
        long time = world.getWorldTime();
        // the game turned the matrix for the sun with the partial tick; a tick's turn is 1/24000
        float angle = world.getCelestialAngle(0f) * 360f;
        boolean rain = world.isRaining();
        boolean thunder = world.isThundering();
        BlockPos at = new BlockPos(view);
        int biome = world.getBiomeGenForCoords(at).biomeID;
        float rainFade = 1f - world.getRainStrength(0f);
        GlStateManager.pushMatrix();
        GlStateManager.rotate(-angle, 1f, 0f, 0f);
        GlStateManager.rotate(90f, 0f, 1f, 0f);
        try {
            for (SkyLayer l : list) {
                float target = l.applies(rain, thunder, time, biome, at.getY()) ? l.fade(time) : 0f;
                l.shown =
                        l.shown < 0f || l.transition <= 0f
                                ? target
                                : l.shown
                                        + (target - l.shown) * Math.min(1f, seconds / l.transition);
                if (l.shown > 0.002f) {
                    layer(tm, l, l.shown, time, angle);
                }
            }
        } finally {
            GlStateManager.popMatrix();
            // what renderSky had set for the sun
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 1, 1, 0);
            GlStateManager.color(1f, 1f, 1f, rainFade);
        }
    }

    private static void layer(TextureManager tm, SkyLayer l, float b, long time, float sun) {
        GlStateManager.pushMatrix();
        if (l.rotate) {
            // with the sun at speed 1 (the same angle); slower or faster over the days
            double days = Math.floorDiv(time, 24000L);
            float turn = (float) ((days * 360.0 + sun) * l.speed % 360.0);
            GlStateManager.rotate(turn, l.axisX, l.axisY, l.axisZ);
        }
        blend(l.blend, b);
        tm.bindTexture(l.texture);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        for (float[] side : SIDES) {
            GlStateManager.pushMatrix();
            if (side[0] != 0f) {
                GlStateManager.rotate(side[0], 1f, 0f, 0f);
            }
            if (side[1] != 0f) {
                GlStateManager.rotate(side[1], 0f, 0f, 1f);
            }
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            for (float[] corner : CORNERS) {
                float u = corner[0];
                float v = corner[1];
                for (int i = 0; i < (int) side[4]; i++) {
                    float w = u;
                    u = 1f - v;
                    v = w;
                }
                wr.pos(corner[0] == 0f ? -SIZE : SIZE, -SIZE, corner[1] == 0f ? -SIZE : SIZE)
                        .tex((side[2] + u) / 3f, (side[3] + v) / 2f)
                        .endVertex();
            }
            tess.draw();
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();
    }

    /** The blend of the layer over what is below, with its brightness {@code b}. */
    private static void blend(SkyLayer.Blend mode, float b) {
        GlStateManager.enableBlend();
        switch (mode) {
            case ADD -> {
                GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                GlStateManager.color(1f, 1f, 1f, b);
            }
            case SUBTRACT -> {
                // what is below darkened by the layer: dst * (1 - src)
                GlStateManager.blendFunc(GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_COLOR);
                GlStateManager.color(b, b, b, 1f);
            }
            case MULTIPLY -> {
                GlStateManager.blendFunc(GL11.GL_DST_COLOR, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GlStateManager.color(b, b, b, b);
            }
            case DODGE -> {
                GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
                GlStateManager.color(b, b, b, 1f);
            }
            case BURN -> {
                GlStateManager.blendFunc(GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_COLOR);
                GlStateManager.color(b, b, b, 1f);
            }
            case SCREEN -> {
                GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_COLOR);
                GlStateManager.color(b, b, b, 1f);
            }
            case REPLACE -> {
                GlStateManager.disableBlend();
                GlStateManager.color(1f, 1f, 1f, 1f);
            }
            case OVERLAY -> {
                GlStateManager.blendFunc(GL11.GL_DST_COLOR, GL11.GL_SRC_COLOR);
                GlStateManager.color(b, b, b, 1f);
            }
            case ALPHA -> {
                GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GlStateManager.color(1f, 1f, 1f, b);
            }
        }
    }
}
