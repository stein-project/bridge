/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

/**
 * Fix for the main menu's panorama texture leak: every GuiMainMenu shares one 256x256 background
 * texture instead of creating and registering a new one on each initGui.
 *
 * <p>GuiMainMenu.initGui runs every time the menu opens or the window is resized, and each run made
 * a new DynamicTexture(256, 256) (a GL texture and 256 KB of pixels on the heap) and registered it
 * as a new "dynamic/background_N" location that nothing ever deleted: going back to the menu or
 * resizing the window a few hundred times held hundreds of MB. The texture is only a scratch
 * target: the panorama is copied into it from the screen and blurred every frame before it is
 * drawn, so one texture serves every menu and the picture is the same. The patcher sends the
 * texture's allocation and its getDynamicTextureLocation in initGui here; when the shared texture
 * is no longer registered (someone deleted it), a new one is made and registered as the game did.
 */
public final class MenuBackground {

    private static DynamicTexture texture;
    private static ResourceLocation location;

    private MenuBackground() {}

    /** new DynamicTexture(width, height) in GuiMainMenu.initGui. */
    public static Object texture(int width, int height) {
        if (texture == null || texture.getTextureData().length != width * height || !registered()) {
            texture = new DynamicTexture(width, height);
            location = null;
        }
        return texture;
    }

    /** TextureManager.getDynamicTextureLocation(name, texture) in GuiMainMenu.initGui. */
    public static Object location(Object manager, Object name, Object dynamic) {
        TextureManager textures = (TextureManager) manager;
        if (dynamic == texture && location != null && registered()) {
            return location;
        }
        ResourceLocation l =
                textures.getDynamicTextureLocation((String) name, (DynamicTexture) dynamic);
        if (dynamic == texture) {
            location = l;
        }
        return l;
    }

    /** The shared texture is still the one registered under the shared location. */
    private static boolean registered() {
        return location != null
                && Minecraft.getMinecraft().getTextureManager().getTexture(location) == texture;
    }
}
