/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import org.apache.commons.io.IOUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * Fix for an image the game cannot decode crashing it: TextureUtil.readBufferedImage reports it as
 * the read error it is.
 *
 * <p>ImageIO.read returns null for data it has no reader for (a WebP or JPEG renamed .png, a file
 * cut short in a server's resource pack) and throws runtime exceptions for some broken PNGs. 1.8.9
 * passed the null on, and the texture threw a NullPointerException while loading; TextureManager
 * turns only an IOException into the missing texture, so anything else became a crash report
 * ("Registering texture"). A banner pattern or an entity texture in a bad server pack was enough to
 * close the game on join. Here both cases are an IOException, which every caller already handles:
 * the texture becomes the missing one (with the game's own "Failed to load texture" line), the
 * atlas skips the sprite, a pack without a readable icon shows the default one. {@code
 * -Dsteinbridge.opt.vanillaUnreadableImages=true} brings back 1.8.9's behaviour.
 */
public final class UnreadableImages {

    private UnreadableImages() {}

    private static final boolean VANILLA = RenderFixOptions.VANILLA_UNREADABLE_IMAGES;

    /** The body of TextureUtil.readBufferedImage(stream). */
    public static Object read(Object stream) throws IOException {
        InputStream in = (InputStream) stream;
        try {
            BufferedImage image;
            try {
                image = ImageIO.read(in);
            } catch (RuntimeException e) {
                if (VANILLA) {
                    throw e;
                }
                throw new IOException("broken image: " + e, e);
            }
            if (image == null && !VANILLA) {
                throw new IOException("not an image format the game can read");
            }
            return image;
        } finally {
            IOUtils.closeQuietly(in);
        }
    }
}
