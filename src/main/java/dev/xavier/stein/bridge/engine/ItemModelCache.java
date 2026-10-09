/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGfxOptions;
import dev.xavier.stein.gfx.SteinHeldItem;
import dev.xavier.stein.gfx.SteinLazyGl;
import dev.xavier.stein.gfx.SteinTessCapture;
import dev.xavier.stein.gfx.gl.SteinCoreGl;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;

import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Item models recorded once into a display list, instead of being resent on every draw.
 *
 * <p>RenderItem.renderModel builds all the model's quads in the Tessellator and draws them with
 * client arrays: the driver copies the vertices on every call. A 1.8.9 3D sword has hundreds of
 * quads (the edges are generated per pixel), and every player holding a sword draws it three times
 * per frame (model + two glint passes). In a crowd, 54% of the glDrawArrays samples on the render
 * thread are this.
 *
 * <p>The built vertices depend only on the model and the colour. The first call for each (model,
 * colour) runs the original code itself inside a display list — same bytes, same order, same
 * primitive type — and the following ones just call the list.
 *
 * <p>With colour -1, an ItemStack and a quad with a tint index, the colour of each tinted quad
 * comes from the item: the game's renderQuads does {@code k = item.getColorFromItemStack(stack,
 * index) | 0xFF000000}. And that holds for almost every item: the 1.8.9 model generator puts tint
 * index 0 on layer 0 of every texture-based item (sword, pickaxe, apple). For those the returned
 * colour is white (0xFFFFFF), k gives -1 and the bytes are those of the model with colour -1 — the
 * same list. Dyed leather, potions and eggs give other colours: the key becomes the colour of each
 * of the model's tint indices, computed as the game computes it, and each combination has its own
 * list. With anaglyph on (the colour still goes through its filter), the original path. In a crowd
 * (enchanted swords), this was the path for 1659 of the 6188 glDrawArrays samples on the game
 * thread: the cache caught no sword at all.
 *
 * <p>On by default. While the driver was the bottleneck, the A/B was a tie: the render thread was
 * waiting on the driver queue there, and did not pay for the building. With the matrix stack and
 * the parts batch, the bottleneck became the render thread itself, and building the sword's quads
 * three times per player per frame became a real cost: +16% fps (73.0 vs 63.0, orders reversed).
 *
 * <p>{@code -Dsteinbridge.opt.vanillaItemModel=true}: always the original path. {@code
 * -Dsteinbridge.opt.vanillaItemTint=true}: a tinted item always on the original path (as before).
 * {@code -Dsteinbridge.opt.checkItemModel=true}: original path, and the bytes that would go to the
 * driver are compared with those of the first time for each (model, colour) — the white-tint item
 * is compared with the same model at colour -1.
 *
 * <p>Pipeline: both — the fixed pipeline calls the recorded display list; Stein GFX records the
 * geometry and draws it through the API.
 */
public final class ItemModelCache {

    /** Check mode: compares the bytes of each draw with those of the first time. */
    private static final boolean CHECK = EngineOptions.CHECK_ITEM_MODEL;

    /** On by default (A/B measured); {@code vanillaItemModel=true} turns it off. */
    private static final boolean VANILLA = !CHECK && EngineOptions.VANILLA_ITEM_MODEL;

    private static final boolean VANILLA_TINT = EngineOptions.VANILLA_ITEM_TINT;

    /** Above this, new models follow the original path. */
    private static final int MAX_LISTS = 4096;

    private static final class Entry {
        /** Distinct tint indices of the model's quads; empty = no tint. */
        final int[] tints;

        final boolean tinted;
        int[] colors = new int[2];
        int[] lists = new int[2];
        byte[][] snapshots = new byte[2][];
        int colorCount;
        // Tint that is not white: the colour of each tint index (in the order of tints) -> list.
        int[][] tintKeys = new int[1][];
        int[] tintLists = new int[1];
        int tintCount;

        Entry(int[] tints) {
            this.tints = tints;
            this.tinted = tints.length > 0;
        }

        int tintIndexOf(int[] key) {
            for (int i = 0; i < tintCount; i++) {
                if (Arrays.equals(tintKeys[i], 0, tints.length, key, 0, tints.length)) {
                    return i;
                }
            }
            return -1;
        }

        int addTint(int[] key) {
            if (tintCount == tintKeys.length) {
                tintKeys = Arrays.copyOf(tintKeys, tintCount * 2);
                tintLists = Arrays.copyOf(tintLists, tintCount * 2);
            }
            tintKeys[tintCount] = Arrays.copyOf(key, tints.length);
            return tintCount++;
        }

        int indexOf(int color) {
            for (int i = 0; i < colorCount; i++) {
                if (colors[i] == color) {
                    return i;
                }
            }
            return -1;
        }

        int add(int color) {
            if (colorCount == colors.length) {
                colors = Arrays.copyOf(colors, colorCount * 2);
                lists = Arrays.copyOf(lists, colorCount * 2);
                snapshots = Arrays.copyOf(snapshots, colorCount * 2);
            }
            colors[colorCount] = color;
            return colorCount++;
        }
    }

    private static final Map<Object, Entry> CACHE = new IdentityHashMap<>();
    private static int listCount;

    // Check: (model, colour) of the renderModel in progress.
    private static Entry checking;
    private static int checkingColor;
    private static final CheckReport REPORT = new CheckReport(10_000_000_000L);

    private static final EnumFacing[] FACINGS = EnumFacing.values();

    /** Tint colours of the draw in progress (only the game thread draws items). */
    private static int[] tintScratch = new int[4];

    static {
        if (CHECK) {
            Log.info(
                    "itemmodel", "check: bytes of each draw compared with those of the first time");
        } else if (VANILLA) {
            Log.info("itemmodel", "item models resent on every draw (vanilla)");
        }
    }

    private ItemModelCache() {}

    /** RenderItem.renderModel(model, colour, stack), diverted from its call sites in RenderItem. */
    public static void renderModel(Object renderItem, Object model, int color, Object stack) {
        if (VANILLA) {
            ((RenderItem) renderItem).renderModel((IBakedModel) model, color, (ItemStack) stack);
            return;
        }
        Entry e = CACHE.get(model);
        if (e == null) {
            e = new Entry(tints(model));
            CACHE.put(model, e);
        }
        if (color == -1 && stack != null && e.tinted) {
            // The colour of the tinted quads comes from the item.
            if (VANILLA_TINT || EntityRenderer.anaglyphEnable) {
                ((RenderItem) renderItem)
                        .renderModel((IBakedModel) model, color, (ItemStack) stack);
                return;
            }
            if (!tintColors(e, stack)) {
                if (!CHECK) {
                    renderTinted(e, renderItem, model, stack);
                    return;
                }
                ((RenderItem) renderItem)
                        .renderModel((IBakedModel) model, color, (ItemStack) stack);
                return;
            }
            // White tint on every index: the bytes of the model with colour -1 — it proceeds as
            // (model, -1).
        }
        if (CHECK) {
            checking = e;
            checkingColor = color;
            try {
                ((RenderItem) renderItem)
                        .renderModel((IBakedModel) model, color, (ItemStack) stack);
            } finally {
                checking = null;
            }
            return;
        }
        int i = e.indexOf(color);
        if (i < 0) {
            if (listCount >= MAX_LISTS) {
                ((RenderItem) renderItem)
                        .renderModel((IBakedModel) model, color, (ItemStack) stack);
                return;
            }
            i = e.add(color);
            int list = recordList(renderItem, model, color, stack);
            e.lists[i] = list;
            listCount++;
        }
        callList(e.lists[i]);
    }

    /** Records the model drawn with the colour into a new display list and returns it. */
    private static int recordList(Object renderItem, Object model, int color, Object stack) {
        int list = SteinCoreGl.glGenLists(1);
        GLMatrix.beginList();
        SteinCoreGl.glNewList(list, GL11.GL_COMPILE);
        SteinTessCapture.listBegin(list);
        try {
            ((RenderItem) renderItem).renderModel((IBakedModel) model, color, (ItemStack) stack);
        } finally {
            SteinTessCapture.listEnd();
            GLMatrix.endList();
            SteinCoreGl.glEndList();
        }
        return list;
    }

    /**
     * After calling the list: the list recorded the colour of each vertex (the enchantment glint is
     * purple), and GL's current colour becomes that of the last vertex. The original draw ends with
     * GlStateManager's resetColor, which forgets the cached colour and lets the next color(1, 1, 1,
     * 1) reach GL; without it, the GlStateManager thought the colour was already white, skipped the
     * call, and what came next was drawn purple — the chest and the sign with the enchanted axe in
     * hand, the hearts and the armor bar dark.
     */
    private static void afterList() {
        SteinLazyGl.glColor4f(1.0F, 1.0F, 1.0F, 1.0F); // deferred in the recorded pass
        GlStateManager.resetColor();
    }

    /**
     * The colour of each of the model's tint indices in tintScratch, as the game's renderQuads
     * computes it (without anaglyph). Returns true if all gave -1 (white with full alpha).
     */
    private static boolean tintColors(Entry e, Object stack) {
        int n = e.tints.length;
        if (tintScratch.length < n) {
            tintScratch = new int[n];
        }
        Item item = ((ItemStack) stack).getItem();
        boolean white = true;
        for (int t = 0; t < n; t++) {
            int k = item.getColorFromItemStack((ItemStack) stack, e.tints[t]) | 0xFF000000;
            tintScratch[t] = k;
            white &= k == -1;
        }
        return white;
    }

    /** Item with a non-white tint: one list per colour combination (those in tintScratch). */
    private static void renderTinted(Entry e, Object renderItem, Object model, Object stack) {
        int i = e.tintIndexOf(tintScratch);
        if (i < 0) {
            if (listCount >= MAX_LISTS) {
                ((RenderItem) renderItem).renderModel((IBakedModel) model, -1, (ItemStack) stack);
                return;
            }
            i = e.addTint(tintScratch);
            int list = recordList(renderItem, model, -1, stack);
            e.tintLists[i] = list;
            listCount++;
        }
        callList(e.tintLists[i]);
    }

    /**
     * Calls the model's list. In the deferred entity pass (new renderer), the list becomes an
     * instance of the copy of its vertices on the GPU ({@link SteinTessCapture}) and does not go to
     * the driver now.
     */
    private static void callList(int list) {
        if (!SteinTessCapture.callList(list)) {
            if (!SteinGfxOptions.ENABLED) {
                GLMatrix.flush();
                SteinLazyGl.sync();
            }
            // Refused with Stein GFX on (SteinGlAudit says why): the core profile has no display
            // list to call, so the audit only notes the draw that did not happen.
            SteinCoreGl.glCallList(list);
        }
        afterList();
    }

    /**
     * Does this item's renderModel(model, -1, stack) call the model's list with colour -1 (no tint,
     * or white tint on every index), and has the model already passed through here? The held item
     * of the pose ({@code SteinHeldItem}) reproduces only this case: the recorded mould is that of
     * the colour -1 list.
     */
    public static boolean plainList(Object model, Object stack) {
        if (VANILLA || CHECK) {
            return false;
        }
        Entry e = CACHE.get(model);
        if (e == null || e.indexOf(-1) < 0) {
            return false; // no list yet: the game draws (and records) first
        }
        if (!e.tinted) {
            return true;
        }
        if (VANILLA_TINT || EntityRenderer.anaglyphEnable) {
            return false;
        }
        return tintColors(e, stack);
    }

    /** Before Tessellator.draw inside RenderItem: in the check, compares the bytes. */
    public static void beforeDraw() {
        if (!CHECK || checking == null) {
            return;
        }
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        ByteBuffer buf = wr.getByteBuffer();
        int bytes = wr.getBufferSize() * 4;
        byte[] now = new byte[bytes + 4];
        buf.get(0, now, 0, bytes);
        int mode = wr.getDrawMode();
        now[bytes] = (byte) mode;
        int i = checking.indexOf(checkingColor);
        if (i < 0) {
            i = checking.add(checkingColor);
            checking.snapshots[i] = now;
        } else {
            if (REPORT.compared(Arrays.equals(now, checking.snapshots[i]))) {
                Log.info(
                        "itemmodel",
                        "DIVERGENCE: same model and colour, different bytes ("
                                + checking.snapshots[i].length
                                + " x "
                                + now.length
                                + ")");
            }
        }
        if (REPORT.due()) {
            Log.info(
                    "itemmodel",
                    "checked "
                            + REPORT.checks
                            + " | divergences "
                            + REPORT.divergences
                            + " | models "
                            + CACHE.size());
        }
    }

    /** After every resource reload: the models are recreated, the old lists are no use. */
    public static void clear() {
        SteinHeldItem.clear(); // the moulds point to the lists' geometry
        if (CACHE.isEmpty()) {
            return;
        }
        SteinTessCapture.clearLists();
        for (Entry e : CACHE.values()) {
            for (int i = 0; i < e.colorCount; i++) {
                if (e.lists[i] != 0) {
                    SteinCoreGl.glDeleteLists(e.lists[i], 1);
                }
            }
            for (int i = 0; i < e.tintCount; i++) {
                if (e.tintLists[i] != 0) {
                    SteinCoreGl.glDeleteLists(e.tintLists[i], 1);
                }
            }
        }
        CACHE.clear();
        listCount = 0;
    }

    /**
     * Distinct tint indices of the model's quads (colour that depends on the ItemStack); empty if
     * none.
     */
    private static int[] tints(Object model) {
        int[] found = new int[0];
        IBakedModel baked = (IBakedModel) model;
        for (EnumFacing f : FACINGS) {
            found = addTints(found, baked.getFaceQuads(f));
        }
        return addTints(found, baked.getGeneralQuads());
    }

    private static int[] addTints(int[] found, List<BakedQuad> quads) {
        for (BakedQuad q : quads) {
            if (!q.hasTintIndex()) {
                continue;
            }
            int t = q.getTintIndex();
            boolean known = false;
            for (int k : found) {
                known |= k == t;
            }
            if (!known) {
                found = Arrays.copyOf(found, found.length + 1);
                found[found.length - 1] = t;
            }
        }
        return found;
    }
}
