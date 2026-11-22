/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.NameStyle;

import org.lwjgl.system.MemoryUtil;

/**
 * The template of each kind of direct label (standing, sneaking): the group keys of each pass and
 * the matrix-less record, learned from the first name of the kind drawn by the game's own path.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinLabelTemplates {

    static final class Template {
        boolean valid;

        /** The {@link NameStyle} version it was learned with (the passes depend on the style). */
        int style;

        long bgKey;

        /**
         * The keys of the text passes without the texture (standing: see-through and normal;
         * sneaking: the single one).
         */
        final long[] textBase = new long[2];

        final long record = MemoryUtil.nmemAlloc(SteinEntityDeferred.RECORD_BYTES);
    }

    static final Template[] templates = {new Template(), new Template()};

    private SteinLabelTemplates() {}

    /** The templates are valid again only after a name goes through the game and teaches them. */
    static void invalidate() {
        templates[SteinNameLabels.LABEL].valid = false;
        templates[SteinNameLabels.SNEAK].valid = false;
    }

    /**
     * The template of kind {@code kind} from the {@code n} commitTexts recorded for a name drawn by
     * the game: standing, background plus two passes per label (one or two labels, with the
     * scoreboard line); sneaking, background and one pass. A style without a background has only
     * the passes, and a new label starts where the see-through pass comes back; a style's shadow is
     * part of the normal pass (same state). Otherwise (something of the name through another path,
     * another format), nothing changes.
     */
    static void learn(int kind, int n) {
        int passes = kind == SteinNameLabels.SNEAK ? 1 : 2;
        boolean bare = NameStyle.custom() && !NameStyle.background();
        if (n < (bare ? 0 : 1) + passes) {
            return;
        }
        long bg = -1L;
        long[] base = {-1L, -1L};
        long rec0 = SteinEntityDeferred.probeRecord(0);
        int phase = -1;
        long prevBase = 0L;
        for (int i = 0; i < n; i++) {
            long key = SteinEntityDeferred.probeKey(i);
            if (!sameTemplate(rec0, SteinEntityDeferred.probeRecord(i))) {
                return;
            }
            if (SteinEntityDeferred.probeBackground(i)) {
                if (bare) {
                    return;
                }
                if (phase >= 0 && phase != passes) {
                    return; // previous label incomplete
                }
                if (bg != -1L && bg != key) {
                    return;
                }
                bg = key;
                phase = 0;
                continue;
            }
            if (phase < 0) {
                if (!bare) {
                    return; // text before the background
                }
                phase = 0;
            }
            long b = SteinEntityDeferred.textKeyBase(key);
            if (phase == 0 || b != prevBase) {
                if (bare && phase == passes) {
                    phase = 0; // the next label (the name after the scoreboard line)
                }
                phase++;
                if (phase > passes) {
                    return;
                }
                if (base[phase - 1] == -1L) {
                    base[phase - 1] = b;
                } else if (base[phase - 1] != b) {
                    return;
                }
            }
            prevBase = b;
        }
        if (phase != passes || kind == SteinNameLabels.LABEL && base[0] == base[1]) {
            return;
        }
        Template t = templates[kind];
        t.style = NameStyle.version();
        t.bgKey = bg;
        t.textBase[0] = base[0];
        t.textBase[1] = base[1];
        MemoryUtil.memCopy(rec0, t.record, SteinEntityDeferred.RECORD_BYTES);
        t.valid = true;
        SteinLabelStats.learned++;
    }

    /**
     * The records are from the same template: everything equal except the matrix (0..47) and the
     * lightmap (96..103).
     */
    private static boolean sameTemplate(long a, long b) {
        for (int i = 48; i < SteinEntityDeferred.RECORD_BYTES; i += 8) {
            if (i == SteinEntityDeferred.RECORD_LIGHT) {
                continue;
            }
            if (MemoryUtil.memGetLong(a + i) != MemoryUtil.memGetLong(b + i)) {
                return false;
            }
        }
        return true;
    }
}
