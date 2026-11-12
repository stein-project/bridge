/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.showcase.VideoSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Where everything of {@link VideoScreen} is: the panel and its parts, the scroll of the list, and
 * what was drawn where in the last frame ({@link Hit}), so a click finds what it landed on. The
 * drawing ({@link VideoDrawing}) places the pieces and registers them here.
 */
final class VideoLayout {

    /** Something that can be clicked. */
    enum Kind {
        TOPIC,
        ENTRY,
        BACK,
        DAY_NIGHT,
        HIDE,
        VIEW,
        CAL_SLIDER,
        CAL_NEXT,
        CAL_BACK
    }

    static final class Hit {
        Kind kind;
        int x;
        int y;
        int w;
        int h;
        int index;
        VideoSettings.Entry entry;

        /** For a choice: left of this x the click goes back (the left arrow). */
        int backEdge;

        /** Set by {@link VideoLayout#hit}: the click is on the back arrow. */
        boolean back;

        /** For a slider: where its track starts and how wide it is. */
        int trackX;

        int trackW;
    }

    static final int MARGIN = 10;
    static final int HEADER = 26;
    static final int HINT = 36;
    static final int ROW = 16;
    static final int CHIP_H = 13;

    // the panel and its parts, from place()
    int px;
    int py;
    int pw;
    int ph;
    int contentX;
    int contentW;
    int listTop;
    int listBottom;

    /** The first row shown, and how many rows there are in the topic. */
    private int scroll;

    private int rows;

    private final List<Hit> hits = new ArrayList<>();
    private int used;

    /** The panel's width for a screen this wide: a narrow column, most of the view is landscape. */
    int panelWidth(int screenWidth) {
        return Math.max(190, Math.min(240, screenWidth * 3 / 10));
    }

    /**
     * Lays the panel out on the right, {@code dx} away from its place (it slides in and out). The
     * list's top is set by the drawing, under the topics.
     */
    void place(int width, int height, float dx) {
        pw = panelWidth(width);
        px = width - MARGIN - pw + Math.round(dx);
        py = MARGIN;
        ph = height - 2 * MARGIN;
        contentX = px + 12;
        contentW = pw - 24;
        listTop = py + HEADER;
        listBottom = py + ph - HINT;
        used = 0;
    }

    /** Registers something clicked in this frame. */
    Hit add(Kind kind, int x, int y, int w, int h) {
        Hit hit;
        if (used < hits.size()) {
            hit = hits.get(used);
        } else {
            hit = new Hit();
            hits.add(hit);
        }
        used++;
        hit.kind = kind;
        hit.x = x;
        hit.y = y;
        hit.w = w;
        hit.h = h;
        hit.index = 0;
        hit.entry = null;
        hit.backEdge = Integer.MIN_VALUE;
        hit.back = false;
        hit.trackX = 0;
        hit.trackW = 0;
        return hit;
    }

    /** What is under (x, y), last registered first (the controls over the panel). */
    Hit hit(int x, int y) {
        for (int i = used - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (x >= h.x && x < h.x + h.w && y >= h.y && y < h.y + h.h) {
                h.back = x < h.backEdge;
                return h;
            }
        }
        return null;
    }

    int scroll() {
        return scroll;
    }

    /** The topic has {@code n} rows (the drawing tells, each frame); keeps the scroll inside. */
    void rows(int n, int fit) {
        rows = n;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, n - fit)));
    }

    void scroll(int by) {
        scroll = Math.max(0, Math.min(scroll + by, Math.max(0, rows - 1)));
    }

    void resetScroll() {
        scroll = 0;
    }
}
