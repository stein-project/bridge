/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

import dev.xavier.stein.bridge.engine.ArmorTextures;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.ItemModelMesher;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * The frame and resource side of {@link GameHooks}: the camera (field of view, smooth camera),
 * partial ticks, the end of the world pass, the texture atlas and its sprite index, block and item
 * models, and the armor texture. The bodies of the {@code GameHooks} entry points live here.
 *
 * <p>{@code GameHooks} forces this class to initialise from its own static initializer.
 */
final class GameHooksRender {

    /** Floats per sprite in {@link SpriteIndex#rects}: minU, maxU, minV, maxV. */
    private static final int SPRITE_RECT_FLOATS = 4;

    // The fields the patcher adds to ItemStack do not exist in the game's own classes (nor in the
    // readable-name jar the Bridge compiles against), so this one is reached by VarHandle.
    private static final VarHandle ITEM_DATA; // ItemStack.sbModData (field created by the patcher)

    static {
        try {
            ITEM_DATA =
                    MethodHandles.privateLookupIn(ItemStack.class, MethodHandles.lookup())
                            .findVarHandle(ItemStack.class, "sbModData", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private GameHooksRender() {}

    /** Forces this class to initialise: {@code GameHooks} calls it from its static initializer. */
    static void init() {}

    /**
     * In place of getFOVModifier(pt, useFOVSetting) in EntityRenderer: the frame's field of view
     * (world, with the player's option; or hand, 70), which the listener can change — Forge's
     * EntityViewRenderEvent.FOVModifier.
     */
    static float fov(Object renderer, float partialTicks, boolean useFovSetting) {
        float f = ((EntityRenderer) renderer).getFOVModifier(partialTicks, useFovSetting);
        if (GameHooks.SINK != null) {
            try {
                return GameHooks.SINK.fov(f, partialTicks, useFovSetting);
            } catch (Throwable e) {
                GameHooks.fail("fov", e);
            }
        }
        return f;
    }

    /**
     * In place of the player's getFovModifier() in EntityRenderer.updateFovModifierHand: the FOV
     * factor from speed and from the bow, which the game smooths every tick and the listener can
     * change — Forge's FOVUpdateEvent.
     */
    static float fovModifier(Object player) {
        float f = ((AbstractClientPlayer) player).getFovModifier();
        if (GameHooks.SINK != null) {
            try {
                return GameHooks.SINK.fovModifier(f);
            } catch (Throwable e) {
                GameHooks.fail("fovModifier", e);
            }
        }
        return f;
    }

    /**
     * In place of loadTextureAtlas in TextureMap.loadSprites (on every resource reload, with the
     * models' textures already registered): the listener registers its own before the stitching —
     * Forge's TextureStitchEvent.Pre.
     */
    static void loadTextureAtlas(Object map, Object resourceManager) {
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.textureStitch(map);
            } catch (Throwable e) {
                GameHooks.fail("textureStitch", e);
            }
        }
        ((TextureMap) map).loadTextureAtlas((IResourceManager) resourceManager);
    }

    /**
     * In place of completeResourceLocation(loc, n) in loadTextureAtlas: an atlas texture with a
     * path under mcpatcher/ or optifine/ — those from packs made for the MCPatcher/OptiFine
     * formats, such as the per-enchantment item ones — comes from
     * assets/&lt;domain&gt;/&lt;path&gt;.png, without the textures/ prefix, as that pack format
     * expects. The others stay the same.
     */
    static Object spriteLocation(Object map, Object location, int mip) {
        ResourceLocation loc = (ResourceLocation) location;
        if (mip == 0) {
            String path = loc.getResourcePath();
            if (path.startsWith("mcpatcher/") || path.startsWith("optifine/")) {
                return new ResourceLocation(loc.getResourceDomain(), path + ".png");
            }
        }
        return ((TextureMap) map).completeResourceLocation(loc, mip);
    }

    /**
     * After ModelManager.onResourceManagerReload: models and atlas ready (end of Forge's
     * ModelBakeEvent).
     */
    static void modelsReloaded() {
        try {
            // Before the mods: Game.spriteAt already holds in their onModelsReloaded.
            indexSprites();
        } catch (Throwable e) {
            GameHooks.fail("indexSprites", e);
        }
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.modelsReloaded();
            } catch (Throwable e) {
                GameHooks.fail("modelsReloaded", e);
            }
        }
    }

    /**
     * End of the drawn world: after the translucent layer (the disableFog that closes it, in
     * EntityRenderer.renderWorldPass), before the upper clouds and the hand — where Forge fires
     * RenderWorldLastEvent. The matrix is the camera's.
     */
    static void renderWorld() {
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.renderWorld(partialTicks());
            } catch (Throwable e) {
                GameHooks.fail("renderWorld", e);
            }
        }
    }

    /** The field the patcher adds to Block, resolved on first use. */
    private static final class Blocks {
        static final VarHandle WATCHED; // Block.sbModelHook (field created by the patcher)

        static {
            try {
                WATCHED =
                        MethodHandles.privateLookupIn(Block.class, MethodHandles.lookup())
                                .findVarHandle(Block.class, "sbModelHook", boolean.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    /** Atlas textures and their rectangles (u0, u1, v0, v1 per texture), swapped together. */
    private static final class SpriteIndex {
        final Object[] sprites;
        final float[] rects;

        SpriteIndex(Object[] sprites, float[] rects) {
            this.sprites = sprites;
            this.rects = rects;
        }
    }

    private static volatile SpriteIndex sprites = new SpriteIndex(new Object[0], new float[0]);

    /** Index of the block and item atlas textures, on every resource reload. */
    private static void indexSprites() {
        TextureMap map = Minecraft.getMinecraft().getTextureMapBlocks();
        Object[] found = map.mapUploadedSprites.values().toArray();
        float[] rects = new float[found.length * SPRITE_RECT_FLOATS];
        for (int i = 0; i < found.length; i++) {
            int k = i * SPRITE_RECT_FLOATS;
            TextureAtlasSprite sprite = (TextureAtlasSprite) found[i];
            rects[k] = sprite.getMinU();
            rects[k + 1] = sprite.getMaxU();
            rects[k + 2] = sprite.getMinV();
            rects[k + 3] = sprite.getMaxV();
        }
        sprites = new SpriteIndex(found, rects);
    }

    /** The atlas texture that contains (u, v), or null. Any thread. */
    static Object spriteAt(float u, float v) {
        SpriteIndex idx = sprites;
        float[] r = idx.rects;
        for (int i = 0; i < idx.sprites.length; i++) {
            int k = i * SPRITE_RECT_FLOATS;
            if (u >= r[k] && u < r[k + 1] && v >= r[k + 2] && v < r[k + 3]) {
                return idx.sprites[i];
            }
        }
        return null;
    }

    /** Marks a block for the mods' blockModel (a field on the Block itself). */
    static void watchBlockModel(Object block, boolean watched) {
        Blocks.WATCHED.set(block, watched);
    }

    /**
     * In place of renderModel in BlockRendererDispatcher.renderBlock, on the chunk build threads: a
     * marked block passes the model through the mods; the others cost the read of the field.
     */
    static boolean blockModel(
            Object renderer,
            Object world,
            Object model,
            Object state,
            Object pos,
            Object worldRenderer) {
        Object finalModel = model;
        if (GameHooks.SINK != null
                && (boolean) Blocks.WATCHED.get(((IBlockState) state).getBlock())) {
            try {
                Object replacement = GameHooks.SINK.blockModel(world, state, pos, model);
                if (replacement != null) {
                    finalModel = replacement;
                }
            } catch (Throwable e) {
                GameHooks.fail("blockModel", e);
            }
        }
        return ((BlockModelRenderer) renderer)
                .renderModel(
                        (IBlockAccess) world,
                        (IBakedModel) finalModel,
                        (IBlockState) state,
                        (BlockPos) pos,
                        (WorldRenderer) worldRenderer);
    }

    /**
     * In place of ItemModelMesher.getItemModel(stack): the model of every drawn item (screen, hand,
     * ground, frame), which the listener can swap.
     */
    static Object itemModel(Object mesher, Object stack) {
        Object model = ((ItemModelMesher) mesher).getItemModel((ItemStack) stack);
        if (GameHooks.SINK != null && stack != null) {
            try {
                Object m = GameHooks.SINK.itemModel(stack, model);
                if (m != null) {
                    return m;
                }
            } catch (Throwable e) {
                GameHooks.fail("itemModel", e);
            }
        }
        return model;
    }

    /** The armor piece that renderLayer is drawing (only the render thread uses it). */
    private static Object armorStack;

    /**
     * In place of getCurrentArmor in LayerArmorBase.renderLayer: stores the piece for its texture.
     */
    static Object armorStack(Object layer, Object entity, int slot) {
        Object stack = ((LayerArmorBase) layer).getCurrentArmor((EntityLivingBase) entity, slot);
        armorStack = stack;
        return stack;
    }

    /**
     * In place of getArmorResource(item, leggings) in renderLayer: the piece's texture, which the
     * listener can swap — Forge's ForgeHooksClient.getArmorTexture.
     */
    static Object armorTexture(Object layer, Object item, boolean legs) {
        // The game's texture comes from ArmorTextures: the same ResourceLocation, without
        // String.format.
        return armorListener(ArmorTextures.resource(layer, item, legs, null), legs, null);
    }

    /** Likewise, the overlay layer (dyed leather). */
    static Object armorTextureOverlay(Object layer, Object item, boolean legs, Object overlay) {
        return armorListener(
                ArmorTextures.resource(layer, item, legs, (String) overlay),
                legs,
                (String) overlay);
    }

    private static Object armorListener(Object location, boolean legs, String overlay) {
        Object stack = armorStack;
        if (GameHooks.SINK != null && stack != null) {
            try {
                Object t = GameHooks.SINK.armorTexture(stack, legs ? 2 : 1, overlay, location);
                if (t != null) {
                    return t;
                }
            } catch (Throwable e) {
                GameHooks.fail("armorTexture", e);
            }
        }
        return location;
    }

    /**
     * The object a mod stored on this ItemStack (sbModData field, created by the patcher), or null.
     */
    static Object itemData(Object stack) {
        return (Object) ITEM_DATA.get(stack);
    }

    /** Stores a mod's object on this ItemStack. */
    static void setItemData(Object stack, Object value) {
        ITEM_DATA.set(stack, value);
    }

    /**
     * Zeroes the smooth camera filters of the EntityRenderer: whoever turns the smooth camera on
     * for a while (zoom) and then off leaves no leftover movement for the next time.
     */
    static void resetCameraSmoothing() {
        try {
            EntityRenderer r = Minecraft.getMinecraft().entityRenderer;
            if (r != null) {
                r.mouseFilterXAxis.reset();
                r.mouseFilterYAxis.reset();
            }
        } catch (Throwable e) {
            GameHooks.fail("resetCameraSmoothing", e);
        }
    }

    /** Fraction of the current tick, for whoever draws interpolated. */
    static float partialTicks() {
        try {
            return Minecraft.getMinecraft().timer.renderPartialTicks;
        } catch (Throwable e) {
            return 0.0F;
        }
    }
}
