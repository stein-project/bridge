/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.bridge.engine.ItemModelCache;
import dev.xavier.stein.bridge.engine.Mat4;
import dev.xavier.stein.bridge.engine.Visuals;
import dev.xavier.stein.bridge.hooks.GameHooks;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemTransformVec3f;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumWorldBlockLayer;

import org.lwjgl.util.vector.Vector3f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Players' held item as one more "part" of the pose ({@link SteinPlayerPose}; on the GPU, kind 3 of
 * pose.comp): the matrix comes out of the right arm with the math of LayerHeldItem,
 * ItemRenderer.renderItem and RenderItem (postRenderArm, the item offset, render block 2, sneaking,
 * the scale 2 of the 3D item or of preTransform, the model's third-person transform, the 0.5 scale
 * and the -0.5), and the draw is the item model already resident on the GPU (the {@link
 * ItemModelCache} list copied into the fixed item geometry) with the state the game would set.
 *
 * <p>The state of each draw (group, fixed-geometry range, the 20 floats of the instance's "rest")
 * comes from a <b>template</b>: the first time for each (item model, frame context, glint), the
 * player goes through the game's path and the draws that the capture records ({@link
 * SteinEntityDeferred#commitStatic}) are noted. From then on the pose repeats the template swapping
 * what changes per player: the lightmap and, for the glint, the texture matrix (the game clock one,
 * as renderEffect does). A template that does not come out as expected (a draw outside the fixed
 * geometry, immediate, a different count) leaves that item on the game's path.
 *
 * <p>Whitelist (the rest goes through the game's layer): player without a cast fishing rod, without
 * a bow being drawn, model without its own renderer (head, banner, chest), a block that is not
 * translucent, model list with color -1 (no tint or white tint: sword, tools, flat items, common
 * blocks). The model choice goes through the Loader hook ({@link GameHooks#itemModel}), as in the
 * game.
 *
 * <p>{@code -Dsteinbridge.gfx.gpuHeldItem=false} turns it off (the item goes back to the game's
 * layer). {@code -Dsteinbridge.gfx.gpuHeldItemCheck=true}: every item that would be ours goes
 * through the game and is compared with the prediction (template and matrix from the pose math);
 * logged every 5 s.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinHeldItem {

    static final boolean ON = SteinGfxOptions.GPU_HELD_ITEM;
    static final boolean CHECK = ON && SteinGfxOptions.GPU_HELD_ITEM_CHECK;

    /** Draws of an item: the model and the two glint passes. */
    static final int DRAWS = 3;

    static final int REST = 20;

    /** Instance option bits that put the instance outside the template (REST word 14). */
    private static final int INSTANCE_OVERLAY_FLAG = 64;

    private static final int INSTANCE_CURRENT_NORMAL_FLAG = 512;

    // Item bits per player (SteinPlayerPose.jItemFlags; the same in pose.comp).
    /** The right arm is shown (postRender transforms). */
    static final int IF_ARM = 1;

    /** Thin arm: rotationPointX + 1 during postRender. */
    static final int IF_SMALL = 2;

    /**
     * Block ItemBlock with render 2: the offset, the 20 and 45 degree turns and the -0.375 scale.
     */
    static final int IF_BLOCK2 = 4;

    /** The entity is sneaking (LayerHeldItem reads its isSneaking). */
    static final int IF_SNEAK = 8;

    /** The model has a third-person transform (not the default one). */
    static final int IF_XFORM = 16;

    private SteinHeldItem() {}

    // ---- the templates ----

    /**
     * One item (model, context, glint): the template of the draws and the model's third-person
     * transform.
     */
    static final class Entry {
        static final int NEW = 0;
        static final int READY = 1;
        static final int REJECTED = 2;

        int state;
        int tries;
        final boolean glint;

        /** Expected draws: 1, or 3 with the glint. */
        final int expected;

        final long[] key = new long[DRAWS];
        final int[] first = new int[DRAWS];
        final int[] verts = new int[DRAWS];
        final float[] rest = new float[DRAWS * REST];

        /**
         * translate (3), rotate degrees x y z (3), scale (3), already with the game's debug
         * adjustments.
         */
        final float[] xform = new float[9];

        /** IF_BLOCK2 and IF_XFORM. */
        int flags;

        Entry(boolean glint) {
            this.glint = glint;
            this.expected = glint ? DRAWS : 1;
        }

        int draws() {
            return expected;
        }
    }

    private static final Map<Object, Entry[]> ENTRIES = new IdentityHashMap<>();
    private static Object mesher;

    /**
     * Attempts to record a template that did not come out (immediate draw, for example) before
     * giving up on the item.
     */
    private static final int MAX_TRIES = 4;

    /**
     * Resource reload (ItemModelCache.clear): the templates point to lists that are about to be
     * rebuilt.
     */
    public static void clear() {
        ENTRIES.clear();
        mesher = null;
    }

    /**
     * The template of item {@code stack} in the hand of player {@code e}, or null if it is not on
     * the whitelist (goes through the game's layer). The template may not be ready yet ({@link
     * Entry#NEW}: this player records) or may have been refused. Game thread, inside doRender
     * (Visuals' distance-based glint already has this player's distance).
     */
    static Entry decide(Object e, Object stack) throws Throwable {
        EntityPlayer player = (EntityPlayer) e;
        ItemStack itemStack = (ItemStack) stack;
        if (stack == null || player.fishEntity != null) {
            return null; // cast rod: the game swaps the stack and the model
        }
        Item item = itemStack.getItem();
        if (item == null || item == Items.bow && player.getItemInUse() != null) {
            return null; // bow being drawn: the pulling model
        }
        Object m = mesher;
        if (m == null) {
            m = Minecraft.getMinecraft().getItemRenderer().itemRenderer.itemModelMesher;
            mesher = m;
        }
        Object modelObject = GameHooks.itemModel(m, stack);
        if (modelObject == null) {
            return null;
        }
        IBakedModel baked = (IBakedModel) modelObject;
        Object model = modelObject;
        if (baked.isBuiltInRenderer()) {
            return null;
        }
        Block block = Block.getBlockFromItem(item);
        if (block != null && block.getBlockLayer() == EnumWorldBlockLayer.TRANSLUCENT) {
            return null; // ItemRenderer touches the depth write
        }
        if (!ItemModelCache.plainList(model, stack)) {
            return null;
        }
        boolean glint = itemStack.hasEffect() && !Visuals.glintSkipped();
        int ctx =
                (SteinGameState.texture2D(1) ? 1 : 0)
                        | (SteinGameState.fog() ? 2 : 0)
                        | (SteinGameState.depthTest() ? 4 : 0)
                        | (SteinGameState.normalize() ? 8 : 0);
        int slot = ctx | (glint ? 16 : 0);
        Entry[] all = ENTRIES.get(model);
        if (all == null) {
            all = new Entry[32];
            ENTRIES.put(model, all);
        }
        Entry en = all[slot];
        if (en == null) {
            en = new Entry(glint);
            if (item instanceof ItemBlock && block != null && block.getRenderType() == 2) {
                en.flags |= IF_BLOCK2;
            }
            ItemTransformVec3f v =
                    baked.getItemCameraTransforms()
                            .getTransform(ItemCameraTransforms.TransformType.THIRD_PERSON);
            if (v != ItemTransformVec3f.DEFAULT) {
                // ItemCameraTransforms.applyTransform: each component plus the debug adjustment
                // (0 in the game)
                en.flags |= IF_XFORM;
                Vector3f t = v.translation;
                Vector3f r = v.rotation;
                Vector3f s = v.scale;
                float[] x = en.xform;
                x[0] = t.x + ItemCameraTransforms.field_181690_b;
                x[1] = t.y + ItemCameraTransforms.field_181691_c;
                x[2] = t.z + ItemCameraTransforms.field_181692_d;
                x[3] = r.x + ItemCameraTransforms.field_181693_e;
                x[4] = r.y + ItemCameraTransforms.field_181694_f;
                x[5] = r.z + ItemCameraTransforms.field_181695_g;
                x[6] = s.x + ItemCameraTransforms.field_181696_h;
                x[7] = s.y + ItemCameraTransforms.field_181697_i;
                x[8] = s.z + ItemCameraTransforms.field_181698_j;
            }
            all[slot] = en;
        }
        return en;
    }

    // ---- the matrix (CPU: the pose's CPU path is the GPU reference) ----

    /**
     * The item's matrix in {@code m} (16), from the root without the ModelBiped.render sneak
     * ({@code root0}), with the rotation point and final angles of the right arm ({@code rp},
     * {@code ang} at [a..a+2]): the GlStateManager operations (degrees, Mat4) in the game's order.
     */
    static void matrix(
            float[] m, float[] root0, float[] rp, float[] ang, int a, int flags, float[] xform) {
        System.arraycopy(root0, 0, m, 0, 16);
        if ((flags & IF_ARM) != 0) {
            // ModelPlayer.postRenderArm: ++rotationPointX on the thin arm,
            // ModelRenderer.postRender(0.0625)
            float rx = rp[a];
            if ((flags & IF_SMALL) != 0) {
                rx = rx + 1.0F;
            }
            float ry = rp[a + 1];
            float rz = rp[a + 2];
            float ax = ang[a];
            float ay = ang[a + 1];
            float az = ang[a + 2];
            float s = 0.0625F;
            if (ax == 0.0F && ay == 0.0F && az == 0.0F) {
                if (rx != 0.0F || ry != 0.0F || rz != 0.0F) {
                    Mat4.translate(m, rx * s, ry * s, rz * s);
                }
            } else {
                Mat4.translate(m, rx * s, ry * s, rz * s);
                if (az != 0.0F) {
                    Mat4.rotate(m, az * 57.295776F, 0.0F, 0.0F, 1.0F);
                }
                if (ay != 0.0F) {
                    Mat4.rotate(m, ay * 57.295776F, 0.0F, 1.0F, 0.0F);
                }
                if (ax != 0.0F) {
                    Mat4.rotate(m, ax * 57.295776F, 1.0F, 0.0F, 0.0F);
                }
            }
        }
        Mat4.translate(m, -0.0625F, 0.4375F, 0.0625F); // LayerHeldItem
        if ((flags & IF_BLOCK2) != 0) {
            Mat4.translate(m, 0.0F, 0.1875F, -0.3125F);
            Mat4.rotate(m, 20.0F, 1.0F, 0.0F, 0.0F);
            Mat4.rotate(m, 45.0F, 0.0F, 1.0F, 0.0F);
            Mat4.scale(m, -0.375F, -0.375F, 0.375F);
        }
        if ((flags & IF_SNEAK) != 0) {
            Mat4.translate(m, 0.0F, 0.203125F, 0.0F);
        }
        // ItemRenderer.renderItem (3D item) or RenderItem.preTransform (flat item): a scale of 2
        // either way
        Mat4.scale(m, 2.0F, 2.0F, 2.0F);
        if ((flags & IF_XFORM) != 0) {
            Mat4.translate(m, xform[0], xform[1], xform[2]); // ItemCameraTransforms.applyTransform
            Mat4.rotate(m, xform[4], 0.0F, 1.0F, 0.0F);
            Mat4.rotate(m, xform[3], 1.0F, 0.0F, 0.0F);
            Mat4.rotate(m, xform[5], 0.0F, 0.0F, 1.0F);
            Mat4.scale(m, xform[6], xform[7], xform[8]);
        }
        Mat4.scale(m, 0.5F, 0.5F, 0.5F); // RenderItem.renderItem
        Mat4.translate(m, -0.5F, -0.5F, -0.5F);
    }

    // ---- the glint: the renderEffect texture matrix ----

    private static long glintMs = Long.MIN_VALUE;
    private static boolean glintBaseIdentity;
    private static final float[] GLINT = new float[32];
    private static final float[] BASE = new float[16];

    /**
     * The 6 numbers of the texture matrix of glint pass {@code pass} (t0, t4, t12, t1, t5, t13, as
     * the rest of the instance), in {@code rest[at..]} at positions 0, 1, 2, 4, 5 and 6: the
     * current texture one, scale 8, the game clock offset (Minecraft.getSystemTime) and the turn,
     * like renderEffect. One computation per millisecond.
     */
    static void glintTex(int pass, float[] rest, int at) {
        long ms = Minecraft.getSystemTime();
        float[] base = GLMatrix.textureOrNull();
        if (ms != glintMs || glintBaseIdentity != (base == null) || base != null) {
            glintMs = ms;
            glintBaseIdentity = base == null;
            for (int p = 0; p < 2; p++) {
                float[] t = BASE;
                if (base == null) {
                    Mat4.identity(t);
                } else {
                    System.arraycopy(base, 0, t, 0, 16);
                }
                Mat4.scale(t, 8.0F, 8.0F, 8.0F);
                if (p == 0) {
                    float f = (float) (ms % 3000L) / 3000.0F / 8.0F;
                    Mat4.translate(t, f, 0.0F, 0.0F);
                    Mat4.rotate(t, -50.0F, 0.0F, 0.0F, 1.0F);
                } else {
                    float f1 = (float) (ms % 4873L) / 4873.0F / 8.0F;
                    Mat4.translate(t, -f1, 0.0F, 0.0F);
                    Mat4.rotate(t, 10.0F, 0.0F, 0.0F, 1.0F);
                }
                System.arraycopy(t, 0, GLINT, p * 16, 16);
            }
        }
        int o = pass * 16;
        rest[at] = GLINT[o];
        rest[at + 1] = GLINT[o + 4];
        rest[at + 2] = GLINT[o + 12];
        rest[at + 4] = GLINT[o + 1];
        rest[at + 5] = GLINT[o + 5];
        rest[at + 6] = GLINT[o + 13];
    }

    // ---- recording the template (the game's path, with the capture noting) ----

    static boolean recording;
    private static boolean bad;
    private static int recN;
    private static final long[] REC_KEY = new long[DRAWS];
    private static final int[] REC_FIRST = new int[DRAWS];
    private static final int[] REC_VERTS = new int[DRAWS];
    private static final float[] REC_REST = new float[DRAWS * REST];
    private static final float[] REC_MV = new float[DRAWS * 16];

    static void recordBegin() {
        recording = true;
        bad = false;
        recN = 0;
    }

    /**
     * A draw that is not from the fixed geometry (or is immediate) while recording: the template is
     * no good.
     */
    static void recordBad() {
        if (recording) {
            bad = true;
        }
    }

    /** A fixed-geometry item draw ({@link SteinEntityDeferred#commitStatic}) while recording. */
    static void note(long key, int first, int verts) {
        if (recN >= DRAWS) {
            bad = true;
            return;
        }
        REC_KEY[recN] = key;
        REC_FIRST[recN] = first;
        REC_VERTS[recN] = verts;
        SteinEntityDeferred.dynRest(REC_REST, recN * REST);
        System.arraycopy(GLMatrix.current(), 0, REC_MV, recN * 16, 16);
        recN++;
    }

    /**
     * End of the item's game path: the template of {@code en}, if it does not have one yet, comes
     * out of what was noted. In the check ({@code predicted} = the matrix the pose predicts; {@code
     * lmU}, {@code lmV} the player's), it compares.
     */
    static void recordEnd(Entry en, float[] predicted, float lmU, float lmV) {
        recording = false;
        boolean ok = !bad && recN == en.expected;
        for (int d = 0; d < recN && ok; d++) {
            int flags = (int) REC_REST[d * REST + 14];
            // no overlay or current normal: their place in the instance is not part of the template
            ok = (flags & (INSTANCE_OVERLAY_FLAG | INSTANCE_CURRENT_NORMAL_FLAG)) == 0;
        }
        if (en.state == Entry.NEW) {
            if (!ok) {
                if (++en.tries >= MAX_TRIES) {
                    en.state = Entry.REJECTED;
                    rejected++;
                }
                return;
            }
            for (int d = 0; d < recN; d++) {
                en.key[d] = REC_KEY[d];
                en.first[d] = REC_FIRST[d];
                en.verts[d] = REC_VERTS[d];
            }
            System.arraycopy(REC_REST, 0, en.rest, 0, recN * REST);
            en.state = Entry.READY;
            templates++;
            return;
        }
        if (!CHECK || en.state != Entry.READY || predicted == null) {
            return;
        }
        checkAgainstTemplate(en, predicted, lmU, lmV, ok);
    }

    /**
     * The check of {@link #recordEnd}: the game's path ({@code ok}: it recorded a usable shape)
     * against the template and the matrix the pose predicts.
     */
    private static void checkAgainstTemplate(
            Entry en, float[] predicted, float lmU, float lmV, boolean ok) {
        // check: the game's path against the template and the pose matrix
        checkItems++;
        if (!ok) {
            checkShape++;
            return;
        }
        for (int d = 0; d < recN; d++) {
            if (REC_KEY[d] != en.key[d]
                    || REC_FIRST[d] != en.first[d]
                    || REC_VERTS[d] != en.verts[d]) {
                checkShape++;
                return;
            }
        }
        for (int d = 0; d < recN; d++) {
            checkDraws++;
            boolean exact = true;
            float worst = 0f;
            for (int col = 0; col < 4; col++) {
                float scale = 0f;
                float diff = 0f;
                for (int r = 0; r < 3; r++) {
                    float want = predicted[col * 4 + r];
                    float got = REC_MV[d * 16 + col * 4 + r];
                    exact &= Float.floatToRawIntBits(want) == Float.floatToRawIntBits(got);
                    scale = Math.max(scale, Math.max(Math.abs(want), Math.abs(got)));
                    diff = Math.max(diff, Math.abs(want - got));
                }
                float e = scale > 1e-6f ? diff / scale : diff;
                worst = Math.max(worst, Float.isNaN(e) ? Float.POSITIVE_INFINITY : e);
            }
            if (exact) {
                checkExact++;
            }
            checkWorst = Math.max(checkWorst, worst);
            // the rest: 7..15 (alpha, color, lightmap, options) equal, with the player's lightmap;
            // the glint texture matrix may be one millisecond ahead
            int o = d * REST;
            boolean restOk = true;
            for (int i = 7; i < 16; i++) {
                float want = i == 12 ? lmU : i == 13 ? lmV : en.rest[o + i];
                restOk &= Float.floatToRawIntBits(want) == Float.floatToRawIntBits(REC_REST[o + i]);
            }
            if (d == 0) {
                for (int i = 0; i < 7; i++) {
                    restOk &=
                            Float.floatToRawIntBits(en.rest[o + i])
                                    == Float.floatToRawIntBits(REC_REST[o + i]);
                }
            }
            if (!restOk) {
                checkRest++;
            }
        }
    }

    // ---- numbers ----

    private static long templates;
    private static long rejected;
    private static long checkItems;
    private static long checkShape;
    private static long checkDraws;
    private static long checkExact;
    private static long checkRest;
    private static float checkWorst;
    static long ours;
    static long game;

    /** The log lines (SteinPlayerPose.stats, every 5 s), and resets. */
    static void report(long frames) {
        StringBuilder b = new StringBuilder("held item: ");
        b.append(ours / frames)
                .append(" by the pose, ")
                .append(game / frames)
                .append(" by the game per frame; ")
                .append(templates)
                .append(" templates recorded, ")
                .append(rejected)
                .append(" items refused");
        Log.info("gfx", b.toString());
        ours = 0;
        game = 0;
        if (CHECK) {
            Log.info(
                    "gfx",
                    "held item check: "
                            + checkItems
                            + " items, "
                            + checkDraws
                            + " draws, "
                            + (checkDraws > 0 ? checkExact * 100 / checkDraws : 0)
                            + "% matrices bit-identical to the game, worst relative error "
                            + checkWorst
                            + ", template differs in "
                            + checkShape
                            + ", rest differs in "
                            + checkRest);
            checkItems = 0;
            checkShape = 0;
            checkDraws = 0;
            checkExact = 0;
            checkRest = 0;
            checkWorst = 0f;
        }
    }
}
