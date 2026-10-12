/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

import org.lwjgl.opengl.GL11;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is read from a game VertexFormat (offsets, -1 = absent, and the format bits), cached per
 * format object, for the Tessellator capture.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinCaptureFormat {
    private SteinCaptureFormat() {}

    int stride;
    int pos = -1;
    int uv = -1;
    int color = -1;
    int normal = -1;
    int light = -1;
    int bits;

    /**
     * The particle and rain format (position, texture, color, light; 28 bytes): direct copy, no
     * detours.
     */
    boolean particle;

    /**
     * Only position (first), texture and color: quads through {@link
     * SteinVertexConverter#convertQuads}.
     */
    boolean quadFast;

    boolean ok;

    private static final Map<VertexFormat, SteinCaptureFormat> FORMATS = new IdentityHashMap<>();
    private static VertexFormat lastFormat;
    private static SteinCaptureFormat lastFmt;

    static SteinCaptureFormat of(VertexFormat format) {
        if (format == lastFormat) {
            return lastFmt;
        }
        SteinCaptureFormat f = FORMATS.get(format);
        if (f == null) {
            f = describe(format);
            FORMATS.put(format, f);
        }
        lastFormat = format;
        lastFmt = f;
        return f;
    }

    private static SteinCaptureFormat describe(VertexFormat format) {
        SteinCaptureFormat f = new SteinCaptureFormat();
        f.stride = format.getNextOffset();
        List<VertexFormatElement> els = format.getElements();
        boolean ok = true;
        for (int i = 0; i < els.size(); i++) {
            VertexFormatElement e = els.get(i);
            int usage = e.getUsage().ordinal();
            int type = e.getType().getGlConstant();
            int count = e.getElementCount();
            int index = e.getIndex();
            int off = format.getOffset(i);
            switch (usage) {
                case 0 -> { // position
                    ok &= type == GL11.GL_FLOAT && count == 3;
                    f.pos = off;
                }
                case 1 -> { // normal
                    ok &= type == GL11.GL_BYTE && count == 3;
                    f.normal = off;
                    f.bits |= SteinTessCapture.HAS_NORMAL;
                }
                case 2 -> { // color
                    ok &= type == GL11.GL_UNSIGNED_BYTE && count == 4;
                    f.color = off;
                    f.bits |= SteinTessCapture.HAS_COLOR;
                }
                case 3 -> { // texture: index 0 is unit 0's, index 1 is the lightmap
                    if (index == 0) {
                        ok &= type == GL11.GL_FLOAT && count == 2;
                        f.uv = off;
                        f.bits |= SteinTessCapture.HAS_UV;
                    } else if (index == 1) {
                        ok &= type == GL11.GL_SHORT && count == 2;
                        f.light = off;
                        f.bits |= SteinTessCapture.HAS_LIGHT;
                    } else {
                        ok = false;
                    }
                }
                case 6 -> {
                    // padding
                }
                default -> ok = false; // matrix, weight: nothing in the game uses them on entities
            }
        }
        f.ok = ok && f.pos >= 0 && f.stride > 0;
        f.particle =
                f.ok
                        && f.stride == 28
                        && f.pos == 0
                        && f.uv == 12
                        && f.color == 20
                        && f.light == 24
                        && f.normal < 0;
        f.quadFast = f.ok && f.pos == 0 && f.normal < 0 && f.light < 0 && f.stride >= 12;
        return f;
    }
}
