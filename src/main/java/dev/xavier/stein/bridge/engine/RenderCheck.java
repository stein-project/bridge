/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.compat.GLCompat;
import dev.xavier.stein.bridge.core.SteinLog;
import dev.xavier.stein.gfx.SteinGfxOptions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;

import org.lwjgl.opengl.GL11;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The render diagnostic ({@link SteinLog.Topic#RENDER}, in {@code logs/stein/render.log}): what it
 * takes to tell, on the player's own machine, whether an item is drawn the way the game builds it.
 *
 * <p>It writes the environment once (card, driver, pipeline, resource packs, mipmap, 1.7
 * animations), then each item model the first time it shows up: which item, whether a listener
 * swapped the game's model (the CIT textures or a mod), how many faces and how many edges it has,
 * its texture, and how many vertices reach the draw. A flat item — the two sides without the edges
 * that join them — or a draw with fewer vertices than the model has comes out as a {@code WARNING}
 * line: that is the difference between a model that is already born wrong and one that loses its
 * geometry on the way to the screen.
 *
 * <p>Off, it costs one volatile read per item drawn ({@link #on()}); on, one identity lookup.
 * Render thread only.
 *
 * <p>Pipeline: both.
 */
public final class RenderCheck {

    /** A generated item's two sides; everything else in its model is an edge. */
    private static final int SIDES = 2;

    /** Each item model already written, with the vertices of its first draw (-1: not drawn yet). */
    private static final Map<Object, int[]> SEEN = new IdentityHashMap<>();

    /** The model RenderItem is drawing now, for {@link #drawn}. */
    private static Object drawing;

    /**
     * Models written at most: a listener that builds a new model on every call (a mod) would
     * otherwise grow the map and the file for as long as the diagnostic is on.
     */
    private static final int MAX_MODELS = 4096;

    private static boolean environmentWritten;

    private RenderCheck() {}

    /** Is the diagnostic on? Callers ask first, so that off it costs nothing more. */
    public static boolean on() {
        return SteinLog.on(SteinLog.Topic.RENDER);
    }

    /**
     * The model chosen for an item ({@code ItemModelMesher.getItemModel}, after the listeners):
     * written once per model.
     */
    public static void model(Object stack, Object gameModel, Object model) {
        if (model == null || SEEN.containsKey(model)) {
            return;
        }
        if (SEEN.size() >= MAX_MODELS) {
            return;
        }
        // A diagnostic must never take the game down: a model a mod built differently is written
        // as not described, and the draw goes on.
        try {
            if (!environmentWritten) {
                environmentWritten = true;
                environment();
            }
            int[] seen = {-1, 0};
            SEEN.put(model, seen);
            seen[1] = quads(model);
            describe((ItemStack) stack, (IBakedModel) model, model != gameModel);
            if (SEEN.size() == MAX_MODELS) {
                write("WARNING " + MAX_MODELS + " models written, the next ones are not");
            }
        } catch (RuntimeException e) {
            write("model: could not describe " + model + ": " + e);
        }
    }

    /** RenderItem.renderModel is about to draw this model (null when it is done). */
    public static void drawing(Object model) {
        drawing = model;
    }

    /**
     * Before Tessellator.draw inside RenderItem.renderModel: the vertices that actually go to the
     * draw, on the first draw of each model.
     */
    public static void drawn(int vertices) {
        int[] seen = drawing == null ? null : SEEN.get(drawing);
        if (seen == null || seen[0] >= 0) {
            return;
        }
        seen[0] = vertices;
        int expected = seen[1] * 4;
        String name = String.valueOf(drawing.hashCode());
        if (vertices < expected) {
            write(
                    "WARNING model @"
                            + name
                            + ": "
                            + vertices
                            + " vertices reached the draw, the model has "
                            + expected
                            + " (geometry lost on the way)");
        } else {
            write("model @" + name + ": " + vertices + " vertices reached the draw");
        }
    }

    /** After a resource reload the models are new: they are written again. */
    public static void clear() {
        SEEN.clear();
        drawing = null;
    }

    private static void describe(ItemStack stack, IBakedModel model, boolean swapped) {
        int general = model.getGeneralQuads().size();
        int sides = 0;
        int edges = 0;
        int faced = 0;
        for (Object o : model.getGeneralQuads()) {
            EnumFacing f = ((BakedQuad) o).getFace();
            if (f == EnumFacing.NORTH || f == EnumFacing.SOUTH) {
                sides++;
            } else {
                edges++;
            }
        }
        for (EnumFacing f : EnumFacing.values()) {
            faced += model.getFaceQuads(f).size();
        }
        Item item = stack == null ? null : stack.getItem();
        StringBuilder sb = new StringBuilder();
        // Generated or the pack's own: two sides and nothing else is a plane, never a thick item.
        boolean flat = !model.isBuiltInRenderer() && sides == SIDES && edges == 0 && faced == 0;
        if (flat) {
            sb.append("WARNING flat ");
        }
        sb.append("model @")
                .append(model.hashCode())
                .append(": ")
                .append(item == null ? "?" : Item.itemRegistry.getNameForObject(item))
                .append(stack != null && stack.isItemEnchanted() ? " (enchanted)" : "")
                .append(swapped ? ", swapped by a listener (CIT or a mod)" : ", the game's model")
                .append(model.isGui3d() ? ", 3D" : ", flat item")
                .append(model.isBuiltInRenderer() ? ", own renderer" : "")
                .append(" | quads: ")
                .append(general)
                .append(" general (")
                .append(sides)
                .append(" sides, ")
                .append(edges)
                .append(" edges), ")
                .append(faced)
                .append(" culled");
        TextureAtlasSprite sprite = model.getParticleTexture();
        if (sprite != null) {
            sb.append(" | texture ")
                    .append(sprite.getIconName())
                    .append(' ')
                    .append(sprite.getIconWidth())
                    .append('x')
                    .append(sprite.getIconHeight());
        }
        if (flat) {
            sb.append(" | no edges: the item is drawn as two flat sides");
        }
        write(sb.toString());
    }

    /** All the model's quads (the general ones and those of each face). */
    private static int quads(Object model) {
        IBakedModel m = (IBakedModel) model;
        int n = m.getGeneralQuads().size();
        for (EnumFacing f : EnumFacing.values()) {
            n += m.getFaceQuads(f).size();
        }
        return n;
    }

    private static void environment() {
        Minecraft mc = Minecraft.getMinecraft();
        write(
                "gl: "
                        + GLCompat.glGetString(GL11.GL_VENDOR)
                        + " | "
                        + GLCompat.glGetString(GL11.GL_RENDERER)
                        + " | "
                        + GLCompat.glGetString(GL11.GL_VERSION));
        write(
                "pipeline: "
                        + (SteinGfxOptions.ENABLED ? "Stein GFX" : "fixed function")
                        + " | item model cache "
                        + (EngineOptions.VANILLA_ITEM_MODEL ? "off" : "on")
                        + " | 1.7 animations "
                        + Integer.toBinaryString(OldAnimations.activeParts())
                        + " (bits: block hit, items, sword block, bow, rod, block pose, armor hurt)");
        write(
                "options: mipmap "
                        + mc.gameSettings.mipmapLevels
                        + ", graphics "
                        + (mc.gameSettings.fancyGraphics ? "fancy" : "fast"));
        StringBuilder packs = new StringBuilder("resource packs:");
        List<?> entries = mc.getResourcePackRepository().getRepositoryEntries();
        if (entries.isEmpty()) {
            packs.append(" none");
        }
        for (Object o : entries) {
            packs.append(" [")
                    .append(((ResourcePackRepository.Entry) o).getResourcePackName())
                    .append(']');
        }
        write(packs.toString());
    }

    private static void write(String line) {
        SteinLog.debug(SteinLog.Topic.RENDER, "render", line);
    }
}
