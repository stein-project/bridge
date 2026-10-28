/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredKeys.*;
import static dev.xavier.stein.gfx.SteinDeferredModel.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;
import static dev.xavier.stein.gfx.SteinDeferredSessions.*;
import static dev.xavier.stein.gfx.SteinDeferredStats.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The groups of the deferred entities ({@link SteinEntityDeferred}): draws with the same key (same
 * pipeline and resources), their commands, and the segments of the strict-order passes.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredGroups {

    // ---- groups ----

    /**
     * A group: same key = same pipeline and same resources. The key (long): in the low 32 bits the
     * state (array texture class, blend, depth, face) and the dynamic-geometry bits; in the upper
     * ones, only for dynamic, the game's texture and its filter when it was recorded.
     */
    static final class Group {
        final long key;
        final boolean blend;
        final boolean dynamic;
        final boolean fromStatic;
        final boolean fromText;
        final boolean gameTex;
        final int texture;
        final int filter;

        /** Polygon offset: index + 1 in the table, 0 = none. */
        final int offset;

        SteinPipeline pipeline;
        SteinTexture gameTexture;
        int[] cmds = new int[3 * 256];

        /** Commands (what the draw counts). */
        int n;

        /**
         * Triples in cmds: one command, or a pose block on the GPU ({@link
         * SteinDeferredPose#POSE_BLOCK}) with several.
         */
        int entries;

        /**
         * Where this flush's commands ended up in the ring ({@link
         * SteinDeferredFrame#writeCommands}); -1 = did not fit.
         */
        long cmdAt;

        /**
         * In this flush's {@link #order}: set on entering it, even with no command yet ({@link
         * #enlist}), so a group never enters twice.
         */
        boolean listed;

        /**
         * This flush's batched commands ({@link SteinEntityBatch}): where they are in the command
         * ring and how many; 0 = drawn command by command.
         */
        long batchAt;

        int batchN;

        Group(long key) {
            this.key = key;
            // A logical operation reads the destination like a blend: it goes out with the
            // blended groups, after what it covers.
            this.blend = keyBlend((int) key) || (key & KEY_LOGIC) != 0;
            this.dynamic = (key & KEY_DYNAMIC) != 0;
            this.fromStatic = (key & KEY_STATIC) != 0;
            this.fromText = (key & KEY_TEXT) != 0;
            this.gameTex = (key & KEY_GAMETEX) != 0;
            this.texture = keyTexture(key);
            this.filter = keyFilter(key);
            this.offset = (int) (key >>> KEY_OFFSET_SHIFT & 3);
        }
    }

    static final Map<Long, Group> groups = new HashMap<>();

    /** The groups with something recorded, in the order they appeared. */
    static final List<Group> order = new ArrayList<>();

    static Group last;
    static int pending;

    /** Groups per slot (mixed hash of the key): the hot path of group() without the HashMap. */
    static final Group[] groupCache = new Group[1024];

    static int groupSlot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 54); // the top 10 bits: 1024 slots
    }

    static Group group(long key) {
        Group g = last;
        if (g != null && g.key == key) {
            return g;
        }
        // The keys alternate (body, armor, glint; name background and text; on the HUD,
        // background, text, icons, items): a direct cache by the key's hash before the HashMap,
        // which would allocate a Long per draw.
        int slot = groupSlot(key);
        Group c = groupCache[slot];
        if (c != null && c.key == key) {
            g = c;
        } else {
            g = groups.get(key);
            if (g == null) {
                g = createGroup(key);
            }
            groupCache[slot] = g;
        }
        if (strictOrder && g != last) {
            // Strict order in segments: the group can come back after another without flushing;
            // each segment (the consecutive commands of a group) goes out in the order it was
            // opened, within the same pass.
            openSegment(g);
        }
        if (!g.listed) {
            order.add(g);
            g.listed = true;
        }
        last = g;
        return g;
    }

    /**
     * Puts the group of {@code key} in this flush's order now, still empty, without becoming the
     * current group: the groups without blending draw in the order they entered, so a group
     * enlisted first draws first ({@link SteinPlayerPoseOrder}: the armor before the body it
     * covers, so the GPU's early depth test rejects the body's hidden pixels). Outside strict order
     * only (there, the segments set the order).
     */
    static void enlist(long key) {
        Group g = (Group) groupFor(key);
        if (!g.listed) {
            order.add(g);
            g.listed = true;
        }
    }

    // ---- strict order in segments (HUD, screens, sky, hand...) ----

    /**
     * The segments of the flush in progress, in order: the group and the command interval (in units
     * of {@code n}).
     */
    static Group[] segGroup = new Group[64];

    static int[] segFrom = new int[64];
    static int[] segTo = new int[64];
    static int segs;

    /** Closes the open segment and opens one of group {@code g}, from its next command. */
    static void openSegment(Group g) {
        if (segs > 0) {
            segTo[segs - 1] = segGroup[segs - 1].n;
            if (segTo[segs - 1] == segFrom[segs - 1]) {
                // Empty segment (the group sought has no command afterwards): reuses the slot.
                segs--;
            }
        }
        if (segs == segGroup.length) {
            int n = segs * 2;
            segGroup = Arrays.copyOf(segGroup, n);
            segFrom = Arrays.copyOf(segFrom, n);
            segTo = Arrays.copyOf(segTo, n);
        }
        segGroup[segs] = g;
        segFrom[segs] = g.n;
        segTo[segs] = -1;
        segs++;
    }

    static Group createGroup(long key) {
        if (groups.size() >= 4096) {
            // Game textures that come and go (skins, maps): do not accumulate forever.
            dropIdleGroups();
        }
        Group g = new Group(key);
        g.pipeline = pipeline(key);
        if ((g.dynamic || g.gameTex) && g.texture > 0) {
            g.gameTexture =
                    SteinDevice.get()
                            .importTexture(
                                    SteinTexture.Desc.texture2D(
                                            "game " + g.texture, SteinFormat.RGBA8, 1, 1, 1),
                                    g.texture);
        }
        groups.put(key, g);
        return g;
    }

    /**
     * Goes up every time groups leave the map: whoever keeps the group object (retention) looks it
     * up again.
     */
    static int groupEpoch;

    static int groupEpoch() {
        return groupEpoch;
    }

    /**
     * The group for the key (created if needed), without entering the frame's order: for retention
     * to store.
     */
    static Object groupFor(long key) {
        // The direct cache only ever holds groups taken from the map under their own key and is
        // emptied together with it (dropIdleGroups), so a hit is exactly the map's answer, without
        // boxing a Long: retained tiles look their groups up again after every drop.
        int slot = groupSlot(key);
        Group c = groupCache[slot];
        if (c != null && c.key == key) {
            if (SteinTileReplayCheck.ENABLED) {
                SteinTileReplayCheck.group(c == groups.get(key));
            }
            return c;
        }
        Group g = groups.get(key);
        if (g == null) {
            g = createGroup(key);
        }
        groupCache[slot] = g;
        return g;
    }

    /**
     * Drops the groups with nothing pending (the pipeline stays in the cache; the group comes back
     * on the next use).
     */
    static void dropIdleGroups() {
        groupEpoch++;
        Arrays.fill(groupCache, null);
        groups.values().removeIf(x -> x.n == 0);
        last = null;
    }

    static void command(int verts, int first, int instance) {
        command(group(curKey), verts, first, instance);
        parts++;
    }

    static void command(Group g, int verts, int first, int instance) {
        if (g.entries * 3 + 3 > g.cmds.length) {
            g.cmds = Arrays.copyOf(g.cmds, g.cmds.length * 2);
        }
        int k = g.entries * 3;
        g.cmds[k] = verts;
        g.cmds[k + 1] = first;
        g.cmds[k + 2] = instance;
        g.entries++;
        g.n++;
        pending++;
    }
}
