/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import static java.lang.invoke.MethodType.methodType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * 1.7 animations ("1.7 animations" option in the video settings, Lens). Visual only: nothing
 * changes in what goes to the server, in the physics or in the game logic. Each part is an option,
 * all under the master key:
 *
 * <ul>
 *   <li><b>swing while blocking</b> ({@link #BLOCK_HIT}): in first person, the swing keeps turning
 *       the item while it is in use (blocking with a sword, eating, drinking, bow) — 1.8 passes
 *       swing zero in that case and cuts it; and, blocking with the attack held over a block, the
 *       hand swings as in 1.7. That swing is local only: the game still does not mine and does not
 *       send the animation packet (it is the EntityLivingBase swingItem, without the EntityPlayerSP
 *       one, which is what sends it);
 *   <li><b>items</b>, <b>sword blocking</b>, <b>bow</b> and <b>rod</b> ({@link #ITEMS}, {@link
 *       #SWORD_BLOCK}, {@link #BOW}, {@link #ROD}): a flat item (not a block) in the first-person
 *       hand in the 1.7 position. In place of the model's "firstperson" transform comes the 1.7
 *       math (item drawn sideways, a bit smaller and lower), redone over the 1.8 geometry: the 1.7
 *       sprite went from 0 to 1 mirrored in x with the thickness pointing backwards; the 1.8 one is
 *       centered — the difference is a 180 degree turn in y and a translation. The rod (and the
 *       carrot on a stick, the items the game makes turn) still turns 180 degrees first, as in 1.7;
 *   <li><b>third-person blocking</b> ({@link #BLOCK_POSE}): the arm without the -30 degree turn in
 *       y that 1.8 applies when blocking, and the sword across the front of the body, with the full
 *       1.7 math in place of the model's "thirdperson";
 *   <li><b>red armor on damage</b> ({@link #ARMOR_HURT}): the armor layer now accepts the damage
 *       color of RendererLivingEntity (1.8 refuses it through shouldCombineTextures), and flashes
 *       together with the body.
 * </ul>
 *
 * <p>The drawing goes through {@link GLMatrix}, like the game's: it works with Stein GFX on
 * (recorded like the rest of first person and the layers) and off. With Stein GFX's own player
 * pose, whoever is blocking or red from damage goes through the game's path ({@link #gamePose}).
 *
 * <p>Hooks in patcher/redirects-mods.tsv. Initial value without the Lens: {@code
 * -Dsteinbridge.oldAnimations=false} turns everything off.
 */
public final class OldAnimations {

    public static final int BLOCK_HIT = 1;
    public static final int ITEMS = 1 << 1;
    public static final int SWORD_BLOCK = 1 << 2;
    public static final int BOW = 1 << 3;
    public static final int ROD = 1 << 4;
    public static final int BLOCK_POSE = 1 << 5;
    public static final int ARMOR_HURT = 1 << 6;
    public static final int ALL =
            BLOCK_HIT | ITEMS | SWORD_BLOCK | BOW | ROD | BLOCK_POSE | ARMOR_HURT;

    private static volatile boolean on = EngineOptions.OLD_ANIMATIONS;
    private static volatile int parts = ALL;

    /**
     * The y turn of the right arm that 1.8 applies when blocking (ModelBiped.setRotationAngles,
     * heldItemRight 3).
     */
    private static final float BLOCK_ARM_YAW = 0.5235988F;

    private static final int MODE_NONE = 0;
    private static final int MODE_FIRST_PERSON = 1;
    private static final int MODE_FIRST_PERSON_FLIP = 2;
    private static final int MODE_BLOCK_POSE = 3;

    /** The ModelBiped heldItemRight value that means blocking. */
    public static final int HELD_ITEM_BLOCKING = 3;

    /**
     * Item (first person or third-person block) waiting for the model transform ({@link
     * #cameraTransform}).
     */
    private static int pending = MODE_NONE;

    private static Object pendingType;

    /**
     * The frame's swing in progress in first person (the getSwingProgress of
     * renderItemInFirstPerson).
     */
    private static float frameSwing;

    // The EntityLivingBase swingItem called as super from the EntityPlayerSP: the EntityPlayerSP
    // one sends the C0APacketAnimation, the EntityLivingBase one only marks the swing (in the
    // client world it sends nothing). Typed Java cannot call it non-virtually from outside the
    // subclass, so it stays a handle (swingItem is "bw" in the game's own names).
    private static final MethodHandle LOCAL_SWING;

    static {
        try {
            LOCAL_SWING =
                    MethodHandles.privateLookupIn(EntityPlayerSP.class, MethodHandles.lookup())
                            .findSpecial(
                                    EntityLivingBase.class,
                                    "bw",
                                    methodType(void.class),
                                    EntityPlayerSP.class)
                            .asType(methodType(void.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private OldAnimations() {}

    // ---- options (Lens) ----

    /** Master key and the enabled parts (bits {@link #BLOCK_HIT} .. {@link #ARMOR_HURT}). */
    public static void set(boolean enabled, int enabledParts) {
        parts = enabledParts & ALL;
        on = enabled;
    }

    private static boolean active(int part) {
        return on && (parts & part) != 0;
    }

    /** The parts in effect (bits {@link #BLOCK_HIT} .. {@link #ARMOR_HURT}); zero when off. */
    public static int activeParts() {
        return on ? parts : 0;
    }

    /**
     * For Stein GFX's own player pose (SteinPlayerPose.collect): does this player need the game's
     * path, which goes through the hooks here? Blocking (heldItemRight 3) with the 1.7 block.
     */
    public static boolean gamePose(boolean blocking) {
        return blocking && active(BLOCK_POSE);
    }

    /**
     * Red armor on damage ({@link #ARMOR_HURT}) in effect: Stein GFX's own player pose paints the
     * armor, its leather overlay and its glint with the damage color, as the game's renderLayers
     * does through {@link #combineTextures}.
     */
    public static boolean redArmor() {
        return active(ARMOR_HURT);
    }

    // ---- first person (ItemRenderer.renderItemInFirstPerson, bfn.a(F)V) ----

    /** The player's getSwingProgress: kept for the same frame's transformFirstPersonItem. */
    public static float swingProgress(Object player, float partialTicks) {
        float swing = ((EntityLivingBase) player).getSwingProgress(partialTicks);
        frameSwing = swing;
        return swing;
    }

    /**
     * transformFirstPersonItem(equip, swing): with the item in use the game passes swing zero; with
     * the blocking swing the frame's swing goes. Without use the game already passes the same
     * value, so the swap always applies.
     */
    public static void transformItem(Object itemRenderer, float equip, float swing) {
        ((ItemRenderer) itemRenderer)
                .transformFirstPersonItem(equip, active(BLOCK_HIT) ? frameSwing : swing);
    }

    /** ItemRenderer.renderItem of the held item: marks the 1.7 position for the model transform. */
    public static void firstPersonItem(
            Object itemRenderer, Object entity, Object stack, Object type) {
        int mode = on && stack != null ? firstPersonMode(itemRenderer, entity, stack) : MODE_NONE;
        render(mode, itemRenderer, entity, stack, type);
    }

    /** Whether the item is drawn as a block (shouldRenderItemIn3D). */
    private static boolean rendersAsBlock(Object itemRenderer, Object stack) {
        return ((ItemRenderer) itemRenderer).itemRenderer.shouldRenderItemIn3D((ItemStack) stack);
    }

    /** Whether the entity is a player blocking with a sword. */
    private static boolean isBlocking(Object entity) {
        return entity instanceof EntityPlayer && ((EntityPlayer) entity).isBlocking();
    }

    private static int firstPersonMode(Object itemRenderer, Object entity, Object stack) {
        Item item = ((ItemStack) stack).getItem();
        // Map (drawn separately) and an item drawn as a block stay as they are.
        if (item == null || item instanceof ItemMap || rendersAsBlock(itemRenderer, stack)) {
            return MODE_NONE;
        }
        if (item.shouldRotateAroundWhenRendering()) {
            return active(ROD) ? MODE_FIRST_PERSON_FLIP : MODE_NONE;
        }
        if (item instanceof ItemBow) {
            return active(BOW) ? MODE_FIRST_PERSON : MODE_NONE;
        }
        if (isBlocking(entity)) {
            return active(SWORD_BLOCK) ? MODE_FIRST_PERSON : MODE_NONE;
        }
        return active(ITEMS) ? MODE_FIRST_PERSON : MODE_NONE;
    }

    // ---- third person (LayerHeldItem.doRenderLayer, bky.a(Lpr;FFFFFFF)V) ----

    /**
     * ItemRenderer.renderItem in the held-item layer: when blocking, the sword goes to the 1.7
     * position.
     */
    public static void heldItem(Object itemRenderer, Object entity, Object stack, Object type) {
        int mode = MODE_NONE;
        if (active(BLOCK_POSE)
                && stack != null
                && isBlocking(entity)
                && !rendersAsBlock(itemRenderer, stack)) {
            mode = MODE_BLOCK_POSE;
        }
        render(mode, itemRenderer, entity, stack, type);
    }

    private static void render(
            int mode, Object itemRenderer, Object entity, Object stack, Object type) {
        ItemRenderer renderer = (ItemRenderer) itemRenderer;
        if (mode == MODE_NONE) {
            renderer.renderItem((EntityLivingBase) entity, (ItemStack) stack, (TransformType) type);
            return;
        }
        pending = mode;
        pendingType = type;
        try {
            renderer.renderItem((EntityLivingBase) entity, (ItemStack) stack, (TransformType) type);
        } finally {
            pending = MODE_NONE;
            pendingType = null;
        }
    }

    /**
     * ItemCameraTransforms.applyTransform in RenderItem.renderItemModelTransform. With a marked
     * item, the 1.7 transform replaces the model's. The incoming matrix already has the
     * preTransform scale of 2 (flat item), and after this point renderItem scales 0.5 and centers
     * the model; the math below already discounts both.
     */
    public static void cameraTransform(Object transforms, Object type) {
        int mode = pending;
        if (mode == MODE_NONE || type != pendingType) {
            ((ItemCameraTransforms) transforms).applyTransform((TransformType) type);
            return;
        }
        pending = MODE_NONE; // once per item
        if (mode == MODE_BLOCK_POSE) {
            blockPose();
        } else {
            firstPerson(mode == MODE_FIRST_PERSON_FLIP);
        }
    }

    /**
     * First person of 1.7, over the same arm point that transformFirstPersonItem leaves (1.7 got
     * there by the same math): the item goes down, grows 1.5x, turns 50 degrees in y and 25 in z,
     * and the 1.8 centered sprite goes where the 1.7 one sat (corner on the point, thickness
     * backwards, mirrored in x = turned 180 degrees in y).
     */
    private static void firstPerson(boolean flip) {
        if (flip) {
            GLMatrix.rotate(180.0F, 0.0F, 1.0F, 0.0F);
        }
        GLMatrix.translate(0.0F, -0.15F, 0.0F);
        GLMatrix.scale(1.5F, 1.5F, 1.5F);
        GLMatrix.rotate(50.0F, 0.0F, 1.0F, 0.0F);
        GLMatrix.rotate(-25.0F, 0.0F, 0.0F, 1.0F);
        GLMatrix.translate(-0.21875F, 0.21875F, -0.015625F);
        GLMatrix.rotate(180.0F, 0.0F, 1.0F, 0.0F);
    }

    /**
     * Sword blocking in third person, as in 1.7: after the hand point, the sword turns to lie
     * across the body (-50 in y, -10 in x, -60 in z) and follows the 1.7 "3D" item math (scale with
     * negative y, -100 in x, 45 in y) and the sprite's. 1.7 drew it mirrored; the x mirror at the
     * end undoes the y one (the sprite is symmetric in thickness), so that the model comes out with
     * the faces and light on the right side.
     */
    private static void blockPose() {
        GLMatrix.translate(0.025F, 0.0F, -0.05F);
        GLMatrix.rotate(-50.0F, 0.0F, 1.0F, 0.0F);
        GLMatrix.rotate(-10.0F, 1.0F, 0.0F, 0.0F);
        GLMatrix.rotate(-60.0F, 0.0F, 0.0F, 1.0F);
        GLMatrix.translate(0.0F, 0.09375F, 0.0F);
        GLMatrix.scale(0.3125F, -0.3125F, 0.3125F);
        GLMatrix.rotate(-100.0F, 1.0F, 0.0F, 0.0F);
        GLMatrix.rotate(45.0F, 0.0F, 1.0F, 0.0F);
        GLMatrix.translate(0.0F, -0.3F, 0.0F);
        GLMatrix.scale(3.0F, 3.0F, 3.0F);
        GLMatrix.rotate(50.0F, 0.0F, 1.0F, 0.0F);
        GLMatrix.rotate(-25.0F, 0.0F, 0.0F, 1.0F);
        GLMatrix.translate(-0.21875F, 0.21875F, -0.015625F);
        GLMatrix.scale(-1.0F, 1.0F, 1.0F);
    }

    /**
     * ModelBiped.setRotationAngles called by ModelBiped.render (player and armor): when blocking,
     * the right arm loses the 1.8 -30 degree turn in y (the swing and the rest added afterwards
     * stay). In ModelPlayer, the sleeve was already copied from the arm inside its
     * setRotationAngles: it gets the same correction.
     */
    public static void bipedAngles(
            Object model,
            float limbSwing,
            float limbAmount,
            float age,
            float yaw,
            float pitch,
            float scale,
            Object entity) {
        ModelBiped biped = (ModelBiped) model;
        biped.setRotationAngles(limbSwing, limbAmount, age, yaw, pitch, scale, (Entity) entity);
        if (!active(BLOCK_POSE) || biped.heldItemRight != HELD_ITEM_BLOCKING || biped.aimedBow) {
            return;
        }
        ModelRenderer arm = biped.bipedRightArm;
        arm.rotateAngleY = arm.rotateAngleY + BLOCK_ARM_YAW;
        if (model instanceof ModelPlayer) {
            ModelRenderer wear = ((ModelPlayer) model).bipedRightArmwear;
            wear.rotateAngleY = wear.rotateAngleY + BLOCK_ARM_YAW;
        }
    }

    // ---- red armor on damage (RendererLivingEntity.renderLayers, bjl.a(Lpr;FFFFFFF)V) ----

    /**
     * LayerRenderer.shouldCombineTextures of each layer: the armor one accepts the damage color.
     */
    public static boolean combineTextures(Object layer) {
        if (active(ARMOR_HURT) && layer instanceof LayerArmorBase) {
            return true;
        }
        return ((LayerRenderer<?>) layer).shouldCombineTextures();
    }

    // ---- swing while blocking over a block (Minecraft.sendClickBlockToController, ave.b(Z)V) ----

    /**
     * The isUsingItem that makes sendClickBlockToController leave without mining: the answer is the
     * game's (no mining and no packet). Using an item, with attack held in the game and aiming at a
     * block, the hand swings only here, as 1.7 showed.
     */
    public static boolean usingItem(Object player) throws Throwable {
        boolean using = ((EntityPlayer) player).isUsingItem();
        if (using && active(BLOCK_HIT)) {
            Minecraft mc = Minecraft.getMinecraft();
            KeyBinding attack = mc.gameSettings.keyBindAttack;
            if (mc.currentScreen == null && mc.inGameHasFocus && attack.isKeyDown()) {
                MovingObjectPosition over = mc.objectMouseOver;
                if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                    LOCAL_SWING.invokeExact(player);
                }
            }
        }
        return using;
    }
}
