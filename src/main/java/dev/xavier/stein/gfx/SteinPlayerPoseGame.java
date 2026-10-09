/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static java.lang.invoke.MethodType.methodType;

import dev.xavier.stein.bridge.engine.Obf;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.client.renderer.entity.layers.LayerArrow;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.client.renderer.entity.layers.LayerDeadmau5Head;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.item.ItemArmor;
import net.minecraft.util.ResourceLocation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * What {@link SteinPlayerPose} reads from the game once and keeps: the classes it recognises and a
 * few static values. The game's methods and fields are used directly by the other classes of the
 * pose, with the game's readable names; only what Java cannot express stays here (the call to the
 * super method of RenderPlayer, and the compiler-generated switch map of the armor materials).
 *
 * <p>The lookups may throw, so this class must initialise at the same point the player pose does:
 * {@link SteinPlayerPose}'s static initialiser calls {@link #load()}.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPlayerPoseGame {
    private SteinPlayerPoseGame() {}

    /** Forces the class initialisation (the lookups below). */
    static void load() {}

    // ---- access to the game ----

    /**
     * RendererLivingEntity.doRender(EntityLivingBase, DDDFF), without virtual dispatch
     * (invokespecial from RenderPlayer's class: Java source cannot call a super method of the super
     * class).
     */
    static final MethodHandle SUPER_DO_RENDER;

    /**
     * LayerArmorBase$1.a, the switch map over the armor materials' ordinals (a synthetic class: no
     * readable name exists).
     */
    static final int[] MATERIAL_CASE;

    static final EnumPlayerModelParts CAPE_PART = EnumPlayerModelParts.CAPE;
    static final ResourceLocation GLINT_RES; // LayerArmorBase.ENCHANTED_ITEM_GLINT_RES
    static final int LIGHTMAP_UNIT;
    static final int DEFAULT_UNIT;
    static final Class<?> ARMOR_ITEM = ItemArmor.class;
    static final Class<?> ARMOR_LAYER = LayerBipedArmor.class;
    static final Class<?>[] PLAYER_LAYERS = {
        ARMOR_LAYER,
        LayerHeldItem.class,
        LayerArrow.class,
        LayerDeadmau5Head.class,
        LayerCape.class,
        LayerCustomHead.class
    };

    // The state the game's held item leaves behind (heldState).
    static final ResourceLocation BLOCKS_ATLAS; // TextureMap.locationBlocksTexture

    static final Class<?> LIVING = EntityLivingBase.class;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            Class<?> v = void.class;
            Class<?> f = float.class;
            Class<?> d = double.class;
            Class<?> o = Object.class;
            SUPER_DO_RENDER =
                    MethodHandles.privateLookupIn(RenderPlayer.class, l)
                            // "a" is doRender in the game's names (the lookup works on the real
                            // ones)
                            .findSpecial(
                                    RendererLivingEntity.class,
                                    "a",
                                    methodType(v, EntityLivingBase.class, d, d, d, f, f),
                                    RenderPlayer.class)
                            .asType(methodType(v, o, o, d, d, d, f, f));
            Class<?> sw = Obf.cls("bkn$1"); // LayerArmorBase$1
            MATERIAL_CASE =
                    ((int[])
                                    MethodHandles.privateLookupIn(sw, l)
                                            .findStaticGetter(sw, "a", int[].class)
                                            .invoke())
                            .clone();
            GLINT_RES = LayerArmorBase.ENCHANTED_ITEM_GLINT_RES;
            LIGHTMAP_UNIT = OpenGlHelper.lightmapTexUnit;
            DEFAULT_UNIT = OpenGlHelper.defaultTexUnit;
            BLOCKS_ATLAS = TextureMap.locationBlocksTexture;
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
