/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Each part's picture for the report: read back from the game's screenshot, measured (how bright
 * and how varied it is: a frame that came out black, white or of a single colour did not draw) and
 * made small (a JPEG at most 960 pixels wide) for the zip. The full-size file is deleted. Pixels
 * are read and scaled by hand, without Java2D drawing, so nothing else of the desktop toolkit wakes
 * up next to the game's window.
 */
final class BenchShots {

    private BenchShots() {}

    /** A measured picture: mean and spread of the brightness (0-255), size, and the JPEG. */
    record Shot(double mean, double stdev, int width, int height, byte[] jpeg) {}

    private static final int MAX_WIDTH = 960;

    /** Reads, measures and shrinks the picture, then deletes the file; null if there is none. */
    static Shot take(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
                return null;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            double sum = 0;
            double sq = 0;
            long n = 0;
            for (int y = 0; y < h; y += 4) {
                for (int x = 0; x < w; x += 4) {
                    int rgb = img.getRGB(x, y);
                    double l =
                            0.2126 * ((rgb >> 16) & 0xFF)
                                    + 0.7152 * ((rgb >> 8) & 0xFF)
                                    + 0.0722 * (rgb & 0xFF);
                    sum += l;
                    sq += l * l;
                    n++;
                }
            }
            double mean = n == 0 ? 0 : sum / n;
            double stdev = n == 0 ? 0 : Math.sqrt(Math.max(0, sq / n - mean * mean));
            int step = Math.max(1, (w + MAX_WIDTH - 1) / MAX_WIDTH);
            BufferedImage small = new BufferedImage(w / step, h / step, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < small.getHeight(); y++) {
                for (int x = 0; x < small.getWidth(); x++) {
                    small.setRGB(x, y, img.getRGB(x * step, y * step));
                }
            }
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(256 * 1024);
            ImageIO.write(small, "jpg", jpeg);
            return new Shot(mean, stdev, w, h, jpeg.toByteArray());
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            f.delete();
        }
    }
}
