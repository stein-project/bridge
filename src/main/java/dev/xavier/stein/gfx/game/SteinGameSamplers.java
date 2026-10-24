/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.game;

import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinSampler;

import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;

/**
 * The samplers of the game's textures: the OpenGL parameters (min and mag filter, wrap) become an
 * API sampler, one per combination, shared by every texture that has it. The game uses a handful
 * (pixel by pixel, linear, the atlas's mipmaps, clamped or repeating), so the cache stays tiny.
 *
 * <p>Pipeline: Stein GFX only (the game's textures in the Vulkan backend).
 */
final class SteinGameSamplers {

    private static final Map<Integer, SteinSampler> CACHE = new HashMap<>();

    private SteinGameSamplers() {}

    /**
     * The sampler for these OpenGL parameters; {@code mipmapped} says whether the texture has more
     * than one level (with one, a mipmap filter samples level 0, as in OpenGL).
     */
    static SteinSampler get(int min, int mag, int wrapS, int wrapT, boolean mipmapped) {
        SteinSampler.Mip mip = mipmapped ? mip(min) : SteinSampler.Mip.NONE;
        SteinSampler.Filter minF =
                linear(min) ? SteinSampler.Filter.LINEAR : SteinSampler.Filter.NEAREST;
        SteinSampler.Filter magF =
                mag == GL11.GL_LINEAR ? SteinSampler.Filter.LINEAR : SteinSampler.Filter.NEAREST;
        SteinSampler.Wrap u = wrap(wrapS);
        SteinSampler.Wrap v = wrap(wrapT);
        int key =
                minF.ordinal()
                        | magF.ordinal() << 1
                        | mip.ordinal() << 2
                        | u.ordinal() << 4
                        | v.ordinal() << 5;
        SteinSampler s = CACHE.get(key);
        if (s == null) {
            s =
                    SteinDevice.get()
                            .createSampler(new SteinSampler.Desc(minF, magF, mip, u, v, 1f, false));
            CACHE.put(key, s);
        }
        return s;
    }

    private static boolean linear(int min) {
        return min == GL11.GL_LINEAR
                || min == GL11.GL_LINEAR_MIPMAP_NEAREST
                || min == GL11.GL_LINEAR_MIPMAP_LINEAR;
    }

    private static SteinSampler.Mip mip(int min) {
        return switch (min) {
            case GL11.GL_NEAREST_MIPMAP_NEAREST, GL11.GL_LINEAR_MIPMAP_NEAREST ->
                    SteinSampler.Mip.NEAREST;
            case GL11.GL_NEAREST_MIPMAP_LINEAR, GL11.GL_LINEAR_MIPMAP_LINEAR ->
                    SteinSampler.Mip.LINEAR;
            default -> SteinSampler.Mip.NONE;
        };
    }

    /**
     * GL_REPEAT repeats; GL_CLAMP and GL_CLAMP_TO_EDGE clamp to the edge (the core has no
     * GL_CLAMP).
     */
    private static SteinSampler.Wrap wrap(int w) {
        return w == GL11.GL_REPEAT ? SteinSampler.Wrap.REPEAT : SteinSampler.Wrap.CLAMP;
    }
}
