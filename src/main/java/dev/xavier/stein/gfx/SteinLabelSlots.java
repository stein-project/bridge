/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

/**
 * Per entity, the last text of its label and the assemblies of the passes: a table of slots (open
 * addressing by the entity's identity) that drops the entities that stopped appearing in the name
 * drain.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinLabelSlots {

    /** Drains without appearing after which a slot is dropped when the table is rebuilt. */
    private static final int STALE_DRAINS = 128;

    /**
     * A label text (the name or the scoreboard line) of the entity: the String, the width and the
     * assemblies of the passes.
     */
    static final class Text {
        String s;
        Object font;
        boolean unicode;
        int gen = -1;
        int width;
        boolean never;

        /**
         * The assemblies: [0] see-through, [1] normal, [2] the sneaking one (another position with
         * deadmau5).
         */
        final Object[] entry = new Object[3];

        /** Font, flag or generation changed: width asked again, assemblies forgotten. */
        void reset(Object font, boolean unicode, int gen, String s) throws Throwable {
            this.font = font;
            this.unicode = unicode;
            this.gen = gen;
            width = SteinTextCache.stringWidth(font, s);
            entry[0] = null;
            entry[1] = null;
            entry[2] = null;
        }
    }

    static final class Slot {
        Object entity;
        long stamp;
        final Text name = new Text();
        final Text score = new Text();
    }

    private static Slot[] slots = new Slot[1024];
    private static int slotCount;
    private static long drains;

    private SteinLabelSlots() {}

    /** Start of a drain: counts it, and now and then drops the slots of entities that left. */
    static void nextDrain() {
        if ((++drains & 255) == 0) {
            rebuild(slots.length);
        }
    }

    /** The open-addressing index of the entity (identity hash, mixed). */
    private static int indexOf(Object entity, int mask) {
        return System.identityHashCode(entity) * 0x9E3779B9 >>> 7 & mask;
    }

    static Slot slot(Object entity) {
        Slot[] t = slots;
        int mask = t.length - 1;
        int i = indexOf(entity, mask);
        Slot s;
        while ((s = t[i]) != null) {
            if (s.entity == entity) {
                s.stamp = drains;
                return s;
            }
            i = i + 1 & mask;
        }
        s = new Slot();
        s.entity = entity;
        s.stamp = drains;
        t[i] = s;
        if (++slotCount * 2 > t.length) {
            rebuild(t.length * 2);
        }
        return s;
    }

    /**
     * Rebuilds the table without the entities that did not appear in the last drain (the strong
     * reference no longer holds).
     */
    private static void rebuild(int size) {
        Slot[] old = slots;
        Slot[] t = new Slot[size];
        int mask = size - 1;
        int n = 0;
        for (Slot s : old) {
            if (s != null && drains - s.stamp < STALE_DRAINS) {
                int i = indexOf(s.entity, mask);
                while (t[i] != null) {
                    i = i + 1 & mask;
                }
                t[i] = s;
                n++;
            }
        }
        slots = t;
        slotCount = n;
    }

    /**
     * The text {@code s} at entry {@code t}: the same object, or an equal one, keeps the width and
     * assemblies.
     */
    static Text text(Text t, String s, Object font, boolean unicode) throws Throwable {
        int gen = SteinTextCache.generation();
        if (t.s != s) {
            if (t.s == null
                    || t.gen != gen
                    || t.font != font
                    || t.unicode != unicode
                    || !t.s.equals(s)) {
                t.reset(font, unicode, gen, s);
                t.never = !SteinTextCache.cacheable(s);
            }
            t.s = s;
        } else if (t.gen != gen || t.font != font || t.unicode != unicode) {
            t.reset(font, unicode, gen, s);
        }
        return t;
    }

    /**
     * The assembly stored at {@code which} (0 see-through, 1 normal, 2 sneaking) at (x, y), with
     * the pass's input color (white; alpha 0x20 for see-through and sneaking), resident; null if
     * not. For each {@code which} the position depends only on the text (width, deadmau5): the
     * stored one is valid as long as the text is the same.
     */
    static Object entry(Text t, int which, float x, float y) {
        if (t.never) {
            return null;
        }
        Object e = t.entry[which];
        if (e == null || !SteinTextCache.resident(e)) {
            e =
                    SteinTextCache.residentEntry(
                            t.font,
                            t.s,
                            t.unicode,
                            x,
                            y,
                            1f,
                            1f,
                            1f,
                            which == 1 ? 1f : SteinNameLabels.ALPHA_THROUGH);
            t.entry[which] = e;
        }
        return e;
    }
}
