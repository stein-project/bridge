/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

/**
 * Which pixels of an item texture count as empty when the game builds the item's 3D model
 * (ItemModelGenerator: the edges that give a held item its thickness go around the empty pixels).
 *
 * <p>The original counts only alpha 0. Some resource packs paint the background of their item
 * textures with alpha 1 instead (invisible: the item's alpha test already drops anything under
 * 0.1), and with them the whole square counts as solid: the item gets edges only around the image's
 * border and shows up flat in the hand. The clients those packs are made on clear every pixel with
 * alpha up to 16 when the atlas is built, so they come out with edges there; here the same pixels
 * count as empty, only for the edges — the texture stays as it is.
 *
 * <p>Nothing visible changes for any other texture: a pixel with alpha up to 16 was never drawn on
 * an item, so the only difference is the edges around it.
 *
 * <p>The patcher replaces the body of ItemModelGenerator's isTransparent with {@link #empty}
 * (patcher/redirects-mc.tsv). {@code -Dsteinbridge.opt.vanillaItemEdges=true} keeps the original
 * test.
 *
 * <p>Pipeline: both.
 */
public final class ItemEdges {

    /** The highest alpha that still counts as empty. */
    private static final int EMPTY_ALPHA = 16;

    private static final int LIMIT = EngineOptions.VANILLA_ITEM_EDGES ? 0 : EMPTY_ALPHA;

    private ItemEdges() {}

    /**
     * Replacement body of ItemModelGenerator.isTransparent(pixels, x, y, width, height): outside
     * the image counts as empty, as in the original.
     */
    public static boolean empty(Object self, Object pixels, int x, int y, int width, int height) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return true;
        }
        return (((int[]) pixels)[y * width + x] >>> 24) <= LIMIT;
    }
}
