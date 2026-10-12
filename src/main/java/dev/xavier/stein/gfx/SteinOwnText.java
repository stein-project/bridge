/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.TextHook;

import java.util.HashMap;
import java.util.Map;

/**
 * Stein's own font (the mods' HUD) inside the text cache ({@link SteinTextCache}): the cache key
 * that follows the font's generation, and the atlas texture as a batch's "place".
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinOwnText {

    /**
     * {@code -Dsteinbridge.gfx.ownTextCache=false}: the text in Stein's font (the mods' HUD) out of
     * the cache, assembled letter by letter every frame, as before.
     */
    static final boolean ENABLED = SteinGfxOptions.OWN_TEXT_CACHE;

    /**
     * Stein's font atlas texture as a batch's "place" (in place of the game font's
     * ResourceLocation). One per id, so that consecutive batches of the same texture become the
     * same run (comparison by identity).
     */
    static final class Texture {
        private static final Map<Integer, Texture> BY_ID = new HashMap<>();
        final int id;

        private Texture(int id) {
            this.id = id;
        }

        static Texture of(int id) {
            return BY_ID.computeIfAbsent(id, Texture::new);
        }
    }

    private static Object key;
    private static Object keyFont;
    private static Object keySource;
    private static int keyGeneration;

    private SteinOwnText() {}

    /**
     * The cache key for text in Stein's font: one object per (game font, Stein font, its
     * generation). An atlas glyph does not move while the generation is valid
     * (TextHook.Source#generation): the assembly kept with this key is the one the font would give
     * now. New generation, new key; the old one's texts go out by eviction.
     */
    static Object key(Object font) {
        TextHook.Source s = TextHook.source;
        if (s == null) {
            return null;
        }
        int gen = s.generation();
        if (key == null || font != keyFont || s != keySource || gen != keyGeneration) {
            key = new Object();
            keyFont = font;
            keySource = s;
            keyGeneration = gen;
        }
        return key;
    }

    /**
     * The place of the batch and of Stein's font (repeated by FontBatch.bindOwn, with its
     * blending).
     */
    static boolean isOwn(Object location) {
        return location instanceof Texture;
    }

    /** Stein's font answers (mods' HUD). */
    static boolean active() {
        return TextHook.source != null && TextHook.hudDepth > 0 && TextHook.gameFontDepth == 0;
    }
}
