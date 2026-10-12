/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinPlayerPose.MODEL_SCALE;
import static dev.xavier.stein.gfx.SteinPlayerPoseGame.*;

import dev.xavier.stein.bridge.engine.GLMatrix;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.List;

/**
 * The game's player models as {@link SteinPlayerPose} sees them: the part layout, and one copy of
 * each RenderPlayer's part tree (display lists, offsets, rotation points, starting angles) that the
 * pose threads read instead of the game's shared model.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPlayerPoseModels {
    private SteinPlayerPoseModels() {}

    // ---- parts (game draw order: ModelBiped.render and then the layers of
    // ModelPlayer.render) ----

    static final int HEAD = 0;
    static final int BODY = 1;
    static final int RARM = 2;
    static final int LARM = 3;
    static final int RLEG = 4;
    static final int LLEG = 5;
    static final int HEADWEAR = 6;
    static final int LLEGW = 7;
    static final int RLEGW = 8;
    static final int LARMW = 9;
    static final int RARMW = 10;
    static final int BODYW = 11;
    static final int PLAYER_PARTS = 12;
    static final int BIPED_PARTS = 7;

    /** Models: the player's, the armor's (helmet, chest, boots) and the leggings'. */
    static final int M_PLAYER = 0;

    static final int M_ARMOR = 1;
    static final int M_LEGS = 2;

    /** Parts of each piece (slot 4 helmet .. 1 boots), in draw order. */
    static final int[] SLOT_MASK = {
        1 << HEAD | 1 << HEADWEAR, // 4
        1 << BODY | 1 << RARM | 1 << LARM, // 3
        1 << BODY | 1 << RLEG | 1 << LLEG, // 2
        1 << RLEG | 1 << LLEG
    }; // 1

    // ---- the copied part tree ----

    /**
     * A model copied from the game: the parts in draw order, with what setRotationAngles never
     * changes.
     */
    static final class Model {
        final ModelRenderer[] parts;
        final int n;
        final int[] list;
        final float[] off;
        final float[] rp;
        final float[] ang;
        final boolean[] hidden;
        boolean ready;

        Model(ModelRenderer[] parts) {
            this.parts = parts;
            this.n = parts.length;
            this.list = new int[n];
            this.off = new float[n * 3];
            this.rp = new float[n * 3];
            this.ang = new float[n * 3];
            this.hidden = new boolean[n];
        }

        /**
         * Copies again, if it is not ready yet: all the parts compiled, with the geometry on the
         * GPU, without children.
         */
        boolean refresh() {
            if (ready) {
                return true;
            }
            for (int i = 0; i < n; i++) {
                ModelRenderer p = parts[i];
                List<?> kids = p.childModels;
                if (kids != null && !kids.isEmpty()) {
                    return false; // model tampered with by a mod: it is not the game's
                }
                if (!p.compiled) {
                    // The game only compiles a part when it draws it (the leggings never draw head
                    // and arms; the hidden hat layer is never drawn): compile it here, through its
                    // own routine, on the game thread.
                    if (GLMatrix.compiling()) {
                        return false;
                    }
                    p.compileDisplayList(MODEL_SCALE);
                    if (!p.compiled) {
                        return false;
                    }
                }
                int id = p.displayList;
                if (!SteinModelInstancer.has(id)) {
                    return false;
                }
                list[i] = id;
                off[i * 3] = p.offsetX;
                off[i * 3 + 1] = p.offsetY;
                off[i * 3 + 2] = p.offsetZ;
                rp[i * 3] = p.rotationPointX;
                rp[i * 3 + 1] = p.rotationPointY;
                rp[i * 3 + 2] = p.rotationPointZ;
                ang[i * 3] = p.rotateAngleX;
                ang[i * 3 + 1] = p.rotateAngleY;
                ang[i * 3 + 2] = p.rotateAngleZ;
                hidden[i] = p.isHidden;
            }
            ready = true;
            return true;
        }
    }

    /** A RenderPlayer (the game has two: wide arm and slim) with its models copied. */
    static final class Info {
        final RenderPlayer renderer;
        final ModelPlayer mainModel;
        final Model[] models = new Model[3];
        List<?> layers;
        int layersSize = -1;
        LayerBipedArmor armorLayer;
        boolean layersOk;

        Info(RenderPlayer renderer, ModelPlayer mainModel) {
            this.renderer = renderer;
            this.mainModel = mainModel;
            ModelRenderer[] p = new ModelRenderer[PLAYER_PARTS];
            for (int i = 0; i < PLAYER_PARTS; i++) {
                p[i] = playerPart(mainModel, i);
            }
            models[M_PLAYER] = new Model(p);
        }

        /**
         * Are the layers exactly the game's? (a mod that adds one leaves the player on the game's
         * path)
         */
        boolean layers() {
            List<?> list = renderer.layerRenderers;
            int size = list.size();
            if (list == layers && size == layersSize) {
                return layersOk;
            }
            layers = list;
            layersSize = size;
            layersOk = false;
            if (size != PLAYER_LAYERS.length) {
                return false;
            }
            for (int i = 0; i < size; i++) {
                if (list.get(i).getClass() != PLAYER_LAYERS[i]) {
                    return false;
                }
            }
            LayerBipedArmor armor = (LayerBipedArmor) list.get(0);
            if (armor != armorLayer) {
                armorLayer = armor;
                models[M_ARMOR] = new Model(bipedParts((ModelBiped) armor.modelArmor));
                models[M_LEGS] = new Model(bipedParts((ModelBiped) armor.modelLeggings));
            }
            layersOk = true;
            return true;
        }
    }

    private static ModelRenderer[] bipedParts(ModelBiped model) {
        ModelRenderer[] p = new ModelRenderer[BIPED_PARTS];
        for (int i = 0; i < BIPED_PARTS; i++) {
            p[i] = bipedPart(model, i);
        }
        return p;
    }

    /** The first {@link #BIPED_PARTS} parts, in draw order (head, body, arms, legs, headwear). */
    private static ModelRenderer bipedPart(ModelBiped m, int i) {
        switch (i) {
            case HEAD:
                return m.bipedHead;
            case BODY:
                return m.bipedBody;
            case RARM:
                return m.bipedRightArm;
            case LARM:
                return m.bipedLeftArm;
            case RLEG:
                return m.bipedRightLeg;
            case LLEG:
                return m.bipedLeftLeg;
            default:
                return m.bipedHeadwear;
        }
    }

    /** Every part of the player's model: the biped's, then the "wear" layers. */
    private static ModelRenderer playerPart(ModelPlayer m, int i) {
        switch (i) {
            case LLEGW:
                return m.bipedLeftLegwear;
            case RLEGW:
                return m.bipedRightLegwear;
            case LARMW:
                return m.bipedLeftArmwear;
            case RARMW:
                return m.bipedRightArmwear;
            case BODYW:
                return m.bipedBodyWear;
            default:
                return bipedPart(m, i);
        }
    }

    static Info[] infos = new Info[4];
    private static int infoCount;

    static int info(Object renderer) {
        for (int i = 0; i < infoCount; i++) {
            if (infos[i].renderer == renderer) {
                return i;
            }
        }
        Object model = ((RenderPlayer) renderer).mainModel;
        if (!(model instanceof ModelPlayer)) {
            return -1;
        }
        if (infoCount == infos.length) {
            infos = Arrays.copyOf(infos, infoCount * 2);
        }
        infos[infoCount] = new Info((RenderPlayer) renderer, (ModelPlayer) model);
        return infoCount++;
    }

    // ---- the table of the models for the compute shader ----

    /** Models in the compute shader's input table: 3 per RenderPlayer (player, armor, leggings). */
    static int gpuModelSlots() {
        return infoCount * 3;
    }

    /**
     * The model table at {@code p} (mapped memory): per model, 16 parts of {@link
     * SteinPoseGpu#PART_WORDS} words — offset, first vertex, rotation point and starting angles
     * (what setRotationAngles does not change), vertex count. Model not yet copied: zeros (no entry
     * points to it).
     */
    static void gpuWriteModels(long p) {
        int w = SteinPoseGpu.PART_WORDS;
        long modelBytes = (long) SteinPoseGpu.MODEL_PARTS * w * 4;
        for (int i = 0; i < infoCount; i++) {
            for (int kind = 0; kind < 3; kind++) {
                long base = p + (long) (i * 3 + kind) * modelBytes;
                Model m = infos[i].models[kind];
                MemoryUtil.memSet(base, 0, modelBytes);
                if (m == null || !m.ready) {
                    continue;
                }
                for (int part = 0; part < m.n; part++) {
                    long q = base + (long) part * w * 4;
                    int list = m.list[part];
                    MemoryUtil.memPutFloat(q, m.off[part * 3]);
                    MemoryUtil.memPutFloat(q + 4, m.off[part * 3 + 1]);
                    MemoryUtil.memPutFloat(q + 8, m.off[part * 3 + 2]);
                    MemoryUtil.memPutInt(q + 12, SteinModelInstancer.firstVertex(list));
                    MemoryUtil.memPutFloat(q + 16, m.rp[part * 3]);
                    MemoryUtil.memPutFloat(q + 20, m.rp[part * 3 + 1]);
                    MemoryUtil.memPutFloat(q + 24, m.rp[part * 3 + 2]);
                    MemoryUtil.memPutInt(q + 28, SteinModelInstancer.vertexCount(list));
                    MemoryUtil.memPutFloat(q + 32, m.ang[part * 3]);
                    MemoryUtil.memPutFloat(q + 36, m.ang[part * 3 + 1]);
                    MemoryUtil.memPutFloat(q + 40, m.ang[part * 3 + 2]);
                }
            }
        }
    }

    // ---- names, for the diagnostics ----

    static final String[] PART_NAMES = {
        "head",
        "body",
        "right arm",
        "left arm",
        "right leg",
        "left leg",
        "hat",
        "left pants",
        "right pants",
        "left sleeve",
        "right sleeve",
        "jacket"
    };
    static final String[] MODEL_NAMES = {"", "armor ", "armor leggings "};
}
