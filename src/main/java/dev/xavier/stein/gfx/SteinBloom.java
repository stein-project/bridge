/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

/**
 * The bloom levels of the post-processing ({@link SteinPostFx}): a chain of half-size images going
 * down from the copy of the scene (only its bright part enters the first), then going up, each
 * level adding the one below spread by a tent. The top of the way up, at half the screen's size, is
 * what the composite adds to the scene.
 *
 * <p>Up to {@link #MAX_LEVELS} levels, fewer when the screen is small (none below 8 pixels). The
 * images are RGBA16F: the sums of the way up pass 1.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinBloom {

    static final int MAX_LEVELS = 5;

    private static final int MIN_SIZE = 8;

    /** Down: {@code down[0]} is half the screen. Up: {@code up[i]} has {@code down[i]}'s size. */
    private final SteinRenderTarget[] down = new SteinRenderTarget[MAX_LEVELS];

    private final SteinRenderTarget[] up = new SteinRenderTarget[MAX_LEVELS];
    private int levels;
    private int width;
    private int height;

    /** The levels for a {@code w x h} screen (rebuilt only when that changes). */
    void size(SteinDevice dev, int w, int h) {
        if (w == width && h == height) {
            return;
        }
        destroy();
        width = w;
        height = h;
        int lw = w;
        int lh = h;
        levels = 0;
        while (levels < MAX_LEVELS && lw / 2 >= MIN_SIZE && lh / 2 >= MIN_SIZE) {
            lw /= 2;
            lh /= 2;
            down[levels] = target(dev, "bloom down " + levels, lw, lh);
            // (the smallest level's is never drawn: the way up starts from its down as it is)
            up[levels] = target(dev, "bloom up " + levels, lw, lh);
            levels++;
        }
    }

    private static SteinRenderTarget target(SteinDevice dev, String name, int w, int h) {
        SteinTexture t =
                dev.createTexture(SteinTexture.Desc.target(name, SteinFormat.RGBA16F, w, h));
        return dev.createTarget(new SteinTexture[] {t}, null);
    }

    int levels() {
        return levels;
    }

    SteinRenderTarget down(int i) {
        return down[i];
    }

    SteinRenderTarget up(int i) {
        return up[i];
    }

    /** The finished bloom (half the screen); null without levels. */
    SteinTexture result() {
        if (levels == 0) {
            return null;
        }
        return levels == 1 ? down[0].color(0) : up[0].color(0);
    }

    void destroy() {
        for (int i = 0; i < MAX_LEVELS; i++) {
            destroy(down[i]);
            destroy(up[i]);
            down[i] = null;
            up[i] = null;
        }
        levels = 0;
        width = 0;
        height = 0;
    }

    private static void destroy(SteinRenderTarget t) {
        if (t != null) {
            t.color(0).destroy();
            t.destroy();
        }
    }
}
