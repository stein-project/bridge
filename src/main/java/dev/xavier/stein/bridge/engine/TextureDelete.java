/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureUtil;

/**
 * Fix for TextureManager.deleteTexture keeping the texture it deletes: the body removes the entry
 * from the manager's map and then deletes the OpenGL texture.
 *
 * <p>1.8.9's deleteTexture only frees the GL name and leaves the texture object registered under
 * its location. Everything the game deletes this way (server list icons, banner textures that
 * expired, map textures, the Mojang logo of the start-up) stayed on the heap with its pixels (a
 * DynamicTexture keeps an int per pixel) for the rest of the session, and every resource reload
 * (F3+T, changing resource packs) loaded the deleted textures again into new GL names. Removing the
 * entry is what later versions do (MC-98707). The callers in the game (and the Bridge's loading
 * screen) either load a new texture under the location right away or stop using it, and binding a
 * location with no entry loads it from the resources as before.
 */
public final class TextureDelete {

    private TextureDelete() {}

    /** Body of TextureManager.deleteTexture(location). */
    public static void delete(Object manager, Object location) {
        ITextureObject texture = ((TextureManager) manager).mapTextureObjects.remove(location);
        if (texture != null) {
            TextureUtil.deleteTexture(texture.getGlTextureId());
        }
    }
}
