/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.FontBatch;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.texture.TextureManager;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Cached text for the deferred entity pass (names above heads, signs): the game's FontRenderer
 * rebuilds each text letter by letter every frame (format codes, width, glyph, texture coordinates)
 * even when the text is the same. Here a text's assembly (FontRenderer.renderStringAtPos) is done
 * once, recording what it produces — the {@link FontBatch} batches: vertices already in the {@link
 * SteinTessCapture} format, the texture and the color of each batch — and the state it leaves the
 * font in; on later occasions only that is repeated, with the current matrix and state (each batch
 * becomes a recorded draw, as before).
 *
 * <p>The key is everything the assembly depends on: the font, the text (with bidi already applied),
 * the shadow, the unicode flag, the starting position (the exact floats: the vertices come out
 * equal to those of assembling again) and the input color. Not cached: text with {@code §k}
 * (obfuscated, re-rolls every frame), {@code §n} and {@code §m} (underline and strikethrough, which
 * draw through the Tessellator), a style already on at the input, Stein's font ({@code TextHook},
 * only in the mods' HUD) and any assembly that did not go entirely through the batch. The text
 * width (getStringWidth, requested by the name and the sign every frame) is kept in the same place,
 * by font, text and unicode flag.
 *
 * <p>How it is stored: one node per (font, text, unicode) in a table of ours (open addressing,
 * mixed hash), with the width and up to {@value #MAX_VARIANTS} assemblies of that text (shadow,
 * position, color). The name asks for the width and then draws twice the same String object: the
 * last node found is kept at hand, and the two assemblies do not consult the table. No
 * access-ordered LinkedHashMap: each use only notes a counter, and the oldest go out in a sweep
 * when the cache passes the limit.
 *
 * <p>Invalidation: a resource reload (the font's widths and pages change) clears everything; the
 * rest is in the key. The least recently used texts go out when the cache passes {@value
 * #MAX_ENTRIES} assemblies, {@value #MAX_NODES} texts or {@value #MAX_BYTES} bytes.
 *
 * <p>In the entity pass, with indirect drawing (4.4+), the repeated assembly does not even copy the
 * vertices: it is uploaded once to the GPU and each repetition becomes just the record and the
 * commands pointing there ({@link SteinTextGpu}; {@code -Dsteinbridge.gfx.gpuText=false} brings
 * back the earlier copy, which remains the fallback for 4.1 and for every refused case).
 *
 * <p>Only acts inside the deferred pass ({@code -Dsteinbridge.gfx=true}); {@code
 * -Dsteinbridge.gfx.textCache=false} turns off just this.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinTextCache {

    public static final boolean REQUESTED = SteinGfxOptions.TEXT_CACHE;

    /** See {@link SteinTextCacheCheck}. */
    static final boolean CHECK = SteinGfxOptions.TEXT_CACHE_CHECK;

    private static final int MAX_ENTRIES = 4096;
    private static final long MAX_BYTES = 16L << 20;
    private static final int MAX_NODES = 8192;

    /**
     * Assemblies per text: the name has two (translucent and opaque background), the sign line one.
     */
    private static final int MAX_VARIANTS = 8;

    private static final int NO_WIDTH = Integer.MIN_VALUE;

    private SteinTextCache() {}

    // ---- table ----

    /**
     * An assembled text: the batches (vertices in our format, texture and color of each) and the
     * font's final state.
     */
    static final class Entry {
        // The key inside the text: shadow and the exact bits of the starting position and input
        // color.
        boolean shadow;
        int x;
        int y;
        int r;
        int g;
        int b;
        int a;
        long used;

        ByteBuffer data;
        int[] counts;
        Object[] locations;
        float[] colors;
        int segments;
        float endX;
        float endY;
        int endTextColor;
        int endStyles;
        float endR;
        float endG;
        float endB;
        float endA;
        long bytes;

        // Names on the GPU (SteinTextGpu): the runs (consecutive batches with the same texture)
        // and the resident span.
        /** Per run: first vertex (relative to the text), vertices, texture and its first batch. */
        int[] runFirst;

        int[] runCount;
        int[] runSegment;
        Object[] runLocation;
        int runs;

        /**
         * Repetitions: goes up to the GPU on the second use (a one-frame text does not occupy the
         * GPU).
         */
        int uses;

        SteinTextGpu.Alloc resident;

        /**
         * The owner text (the {@link SteinNameLabels} direct label notes the use on it, for
         * eviction).
         */
        Node node;

        /** The assembly leaves a color of its own in the GlStateManager (color code at the end). */
        boolean hasEndColor() {
            return endR >= 0f && endG >= 0f && endB >= 0f && endA >= 0f;
        }
    }

    /**
     * The text left the cache: its resident span is freed for the ring (without holding the
     * assembly).
     */
    private static void dropped(Entry e) {
        SteinTextGpu.release(e.resident);
        e.resident = null;
    }

    /** A text (font, text, unicode): its width and its assemblies. */
    private static final class Node {
        final Object font;
        final String text;
        final boolean unicode;
        final int hash;
        int width = NO_WIDTH;
        Entry[] entries;
        int count;
        long used;

        /**
         * False after leaving the table (eviction or cleanup): the node kept in {@link #lastNode}
         * is no longer valid.
         */
        boolean alive = true;

        /** 0 = not seen, 1 = cacheable, 2 = not ({@code §k}, {@code §n}, {@code §m}). */
        byte cacheable;

        boolean cacheable() {
            byte c = cacheable;
            if (c == 0) {
                c = SteinTextCache.cacheable(text) ? (byte) 1 : (byte) 2;
                cacheable = c;
            }
            return c == 1;
        }

        Node(Object font, String text, boolean unicode, int hash) {
            this.font = font;
            this.text = text;
            this.unicode = unicode;
            this.hash = hash;
        }

        Entry find(boolean shadow, int x, int y, int r, int g, int b, int a) {
            Entry[] es = entries;
            for (int i = 0; i < count; i++) {
                Entry e = es[i];
                if (e.x == x
                        && e.y == y
                        && e.shadow == shadow
                        && e.r == r
                        && e.g == g
                        && e.b == b
                        && e.a == a) {
                    return e;
                }
            }
            return null;
        }
    }

    /*
     * Open addressing with linear probing, at most half full. The earlier HashMap hash combined the
     * fields with
     * *31, and the bits of x, y and color (whole floats and 1.0f have the low 16 bits zeroed) only
     * reached the index
     * through the HashMap's fold. Here only the text, the font and the unicode flag make the hash,
     * passed through a
     * mixer (the murmur3 finalizer), and the rest of the key stays inside the node, in a short
     * list. The longest
     * probe appears in the numbers (-Dsteinbridge.gfx.deferredStats=true).
     */
    private static final int INITIAL_TABLE_SIZE = 2048;

    /** Multipliers that mix the text's hash and the font's identity into the table hash. */
    private static final int TEXT_MIX = 0x9E3779B9;

    private static final int FONT_MIX = 0x7FEB352D;

    private static Node[] table = new Node[INITIAL_TABLE_SIZE];
    static int nodes;
    static int entries;
    static long bytes;

    /** Use counter: each node's and assembly's "how long ago", in place of access order. */
    private static long clock;

    // The last node found, by String object: the name asks for the width and draws the same text
    // twice.
    private static String lastText;
    private static Object lastFont;
    private static boolean lastUnicode;
    private static Node lastNode;

    private static int hash(Object font, String text, boolean unicode) {
        int h =
                text.hashCode() * TEXT_MIX
                        + System.identityHashCode(font) * FONT_MIX
                        + (unicode ? 0x632BE5AB : 0);
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    /** The node of this text, or null (with {@code create}, a new one). */
    private static Node node(Object font, String text, boolean unicode, boolean create) {
        Node n = lastNode;
        if (text == lastText
                && font == lastFont
                && unicode == lastUnicode
                && n != null
                && n.alive) {
            return n;
        }
        int h = hash(font, text, unicode);
        Node[] t = table;
        int mask = t.length - 1;
        int i = h & mask;
        while ((n = t[i]) != null) {
            if (n.hash == h
                    && n.font == font
                    && n.unicode == unicode
                    && (n.text == text || n.text.equals(text))) {
                remember(n, text);
                return n;
            }
            i = i + 1 & mask;
        }
        if (!create) {
            return null;
        }
        n = new Node(font, text, unicode, h);
        t[i] = n;
        nodes++;
        if (nodes * 2 > t.length) {
            rehash(t.length * 2);
        }
        remember(n, text);
        return n;
    }

    /**
     * Keeps the node with the String object requested now (may be another object with the same text
     * as the one in the node).
     */
    private static void remember(Node n, String text) {
        lastNode = n;
        lastText = text;
        lastFont = n.font;
        lastUnicode = n.unicode;
    }

    /** Rebuilds the table with the live nodes. */
    private static void rehash(int size) {
        Node[] old = table;
        Node[] t = new Node[size];
        int mask = size - 1;
        for (Node n : old) {
            if (n != null && n.alive) {
                int i = n.hash & mask;
                while (t[i] != null) {
                    i = i + 1 & mask;
                }
                t[i] = n;
            }
        }
        table = t;
    }

    // ---- recording (the FontBatch batches during assembly) ----

    /**
     * Something in the assembly did not go through the batch (immediate mode, Tessellator): the
     * text does not enter the cache.
     */
    public static void markBad() {
        SteinTextRecorder.markBad();
    }

    /**
     * A FontBatch batch during assembly: {@code count} vertices (x, y, z, u, v as floats, in
     * triangles) at {@code addr}, with the texture {@code location} (the game's ResourceLocation)
     * and the current GlStateManager color.
     */
    public static void segment(long addr, int count, Object location, boolean own, int ownTexture) {
        SteinTextRecorder.segment(addr, count, location, own, ownTexture);
    }

    /** The GlStateManager color is not set (the game forgot it, or a negative one). */
    static boolean invalidColor(float r, float g, float b, float a) {
        return r < 0f || g < 0f || b < 0f || a < 0f;
    }

    // ---- FontRenderer.renderStringAtPos (patcher rule, in renderString) ----

    /** A text that does not go through the cache: counted, and drawn by the game as always. */
    private static void drawUncached(FontRenderer font, String s, boolean shadow) {
        SteinTextCacheStats.bypass++;
        font.renderStringAtPos(s, shadow);
    }

    /** In place of the renderStringAtPos(text, shadow) called by renderString. */
    public static void renderStringAtPos(Object font, Object text, boolean shadow)
            throws Throwable {
        String s = (String) text;
        FontRenderer fr = (FontRenderer) font;
        if (!REQUESTED || FontBatch.textRecording || !SteinEntityDeferred.capturingDynamic()) {
            fr.renderStringAtPos(s, shadow);
            return;
        }
        SteinTextCacheStats.report();
        Object key = font;
        if (SteinOwnText.active()) {
            // Stein's font (the mods' HUD): its glyphs are valid per its generation (scale, atlas,
            // option); the text stays in the cache with a key of that generation, and changes key
            // when it changes.
            key = SteinOwnText.ENABLED ? SteinOwnText.key(font) : null;
        }
        if (key == null || anyStyle(fr)) {
            drawUncached(fr, s, shadow);
            return;
        }
        boolean unicode = fr.unicodeFlag;
        // The text's node (the same String object as a moment ago does not even go through the
        // table) already knows whether the text is cacheable: the format-code scan only the first
        // time.
        Node n = node(key, s, unicode, false);
        if (n != null ? !n.cacheable() : !cacheable(s)) {
            drawUncached(fr, s, shadow);
            return;
        }
        float r = SteinGameState.colorR();
        float g = SteinGameState.colorG();
        float b = SteinGameState.colorB();
        float a = SteinGameState.colorA();
        if (invalidColor(r, g, b, a)) {
            drawUncached(fr, s, shadow);
            return;
        }
        int xb = Float.floatToRawIntBits(fr.posX);
        int yb = Float.floatToRawIntBits(fr.posY);
        int rb = Float.floatToRawIntBits(r);
        int gb = Float.floatToRawIntBits(g);
        int bb = Float.floatToRawIntBits(b);
        int ab = Float.floatToRawIntBits(a);
        if (n != null) {
            Entry e = n.find(shadow, xb, yb, rb, gb, bb, ab);
            if (e != null) {
                SteinTextCacheStats.hits++;
                long now = ++clock;
                n.used = now;
                e.used = now;
                if (CHECK) {
                    SteinTextCacheCheck.check(fr, s, shadow, e);
                }
                SteinTextReplay.replay(fr, e);
                return;
            }
        }
        if (n == null && !seenBefore(key, s, shadow, xb, yb, ab)) {
            // First time: assembles without keeping. Text that changes every frame (the FPS,
            // moving coordinates, a clock) never gets kept (neither the direct copy nor the table
            // entry per new text every frame).
            SteinTextCacheStats.firstSight++;
            fr.renderStringAtPos(s, shadow);
            return;
        }
        SteinTextCacheStats.misses++;
        SteinTextRecorder.record(fr, s, shadow, false);
        if (!SteinTextRecorder.bad) {
            if (n == null || !n.alive) {
                n = node(key, s, unicode, true);
            }
            Entry e = new Entry();
            e.shadow = shadow;
            e.x = xb;
            e.y = yb;
            e.r = rb;
            e.g = gb;
            e.b = bb;
            e.a = ab;
            e.node = n;
            store(n, e, fr);
        }
        SteinTextRecorder.releaseLocations();
    }

    private static void store(Node node, Entry e, FontRenderer font) {
        int n = SteinTextRecorder.verts * SteinTessCapture.VERTEX_BYTES;
        e.data = ByteBuffer.allocateDirect(Math.max(n, 1)).order(ByteOrder.nativeOrder());
        MemoryUtil.memCopy(
                MemoryUtil.memAddress(SteinTextRecorder.data), MemoryUtil.memAddress(e.data), n);
        int segments = SteinTextRecorder.segments;
        e.segments = segments;
        e.counts = Arrays.copyOf(SteinTextRecorder.counts, segments);
        e.locations = Arrays.copyOf(SteinTextRecorder.locations, segments);
        e.colors = Arrays.copyOf(SteinTextRecorder.colors, segments * 4);
        e.endX = font.posX;
        e.endY = font.posY;
        e.endTextColor = font.textColor;
        // Bit i: random, bold, italic, underline, strikethrough
        int st = 0;
        if (font.randomStyle) {
            st |= 1;
        }
        if (font.boldStyle) {
            st |= 1 << 1;
        }
        if (font.italicStyle) {
            st |= 1 << 2;
        }
        if (font.underlineStyle) {
            st |= 1 << 3;
        }
        if (font.strikethroughStyle) {
            st |= 1 << 4;
        }
        e.endStyles = st;
        e.endR = SteinGameState.colorR();
        e.endG = SteinGameState.colorG();
        e.endB = SteinGameState.colorB();
        e.endA = SteinGameState.colorA();
        e.bytes = n + 64L + segments * 24L + node.text.length() * 2L;
        SteinTextReplay.buildRuns(e);
        long now = ++clock;
        e.used = now;
        node.used = now;
        if (node.entries == null) {
            node.entries = new Entry[2];
        }
        if (node.count == MAX_VARIANTS) {
            // Text with too many assemblies (position or color changing): the least recently used
            // goes out.
            int old = 0;
            for (int i = 1; i < node.count; i++) {
                if (node.entries[i].used < node.entries[old].used) {
                    old = i;
                }
            }
            bytes -= node.entries[old].bytes;
            dropped(node.entries[old]);
            node.entries[old] = e;
            SteinTextCacheStats.evicted++;
        } else {
            if (node.count == node.entries.length) {
                node.entries = Arrays.copyOf(node.entries, node.count * 2);
            }
            node.entries[node.count++] = e;
            entries++;
        }
        bytes += e.bytes;
        evict();
    }

    /**
     * Over the limit: the least recently used texts (with their assemblies) go out, a quarter at a
     * time, and the table is rebuilt. Rare in steady state (the same set of texts every frame), and
     * the exact order does not matter for drawing: leaving the cache only makes the text be
     * assembled again.
     */
    private static void evict() {
        while (nodes > MAX_NODES || entries > MAX_ENTRIES || bytes > MAX_BYTES) {
            long[] stamps = new long[nodes];
            int k = 0;
            for (Node n : table) {
                if (n != null) {
                    stamps[k++] = n.used;
                }
            }
            Arrays.sort(stamps, 0, k);
            long cutoff = stamps[Math.max(1, k / 4) - 1];
            for (Node n : table) {
                if (n != null && n.used <= cutoff) {
                    n.alive = false;
                    nodes--;
                    entries -= n.count;
                    for (int i = 0; i < n.count; i++) {
                        bytes -= n.entries[i].bytes;
                        dropped(n.entries[i]);
                    }
                    SteinTextCacheStats.evicted += n.count;
                }
            }
            rehash(table.length);
        }
    }

    /** Without §k (re-rolls every frame), §n and §m (draw through the Tessellator). */
    static boolean cacheable(String s) {
        int n = s.length();
        for (int i = 0; i + 1 < n; i++) {
            if (s.charAt(i) == '§') {
                char f = s.charAt(i + 1);
                if (f == 'k' || f == 'K' || f == 'n' || f == 'N' || f == 'm' || f == 'M') {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean anyStyle(FontRenderer font) {
        return font.randomStyle
                || font.boldStyle
                || font.italicStyle
                || font.underlineStyle
                || font.strikethroughStyle;
    }

    // ---- seen once ----

    /**
     * The texts seen once (a hash per slot, without the String): the second encounter keeps the
     * assembly. A collision only makes a new text be kept right on the first time (as before) or
     * wait one more frame.
     */
    private static final int[] SEEN = new int[4096];

    private static boolean seenBefore(Object key, String s, boolean shadow, int x, int y, int a) {
        int h =
                s.hashCode() * TEXT_MIX
                        + System.identityHashCode(key) * FONT_MIX
                        + x * 31
                        + y * 0x632BE5AB
                        + a * 0x2545F491
                        + (shadow ? 0x5BD1E995 : 0);
        h ^= h >>> 15;
        h |= 1; // 0 = empty slot
        int slot = (h >>> 7) & (SEEN.length - 1);
        if (SEEN[slot] == h) {
            return true;
        }
        SEEN[slot] = h;
        return false;
    }

    // ---- FontRenderer.getStringWidth (patcher rule, in the name and the sign) ----

    public static int stringWidth(Object font, Object text) throws Throwable {
        String s = (String) text;
        if (!REQUESTED
                || s == null
                || !SteinEntityDeferred.capturingDynamic()
                || SteinOwnText.active()) {
            return ((FontRenderer) font).getStringWidth(s);
        }
        FontRenderer fr = (FontRenderer) font;
        Node n = node(font, s, fr.unicodeFlag, true);
        n.used = ++clock;
        int w = n.width;
        if (w == NO_WIDTH) {
            w = fr.getStringWidth(s);
            n.width = w;
        }
        evict();
        return w;
    }

    // ---- direct label (SteinNameLabels): the resident assembly of a text, without going through
    // the FontRenderer ----

    /**
     * Goes up on every cleanup: what {@link SteinNameLabels} keeps per entity (width, assemblies)
     * is from before.
     */
    private static int generation;

    static int generation() {
        return generation;
    }

    /**
     * The assembly of {@code s} that renderString would give (no shadow, at position {@code x, y},
     * with input color {@code r, g, b, a} as renderString puts it in the GlStateManager), if it is
     * in the cache and resident on the GPU; otherwise null (the label goes through the game's path,
     * which assembles and uploads).
     */
    static Object residentEntry(
            Object font,
            String s,
            boolean unicode,
            float x,
            float y,
            float r,
            float g,
            float b,
            float a) {
        Node n = node(font, s, unicode, false);
        if (n == null) {
            return null;
        }
        Entry e =
                n.find(
                        false,
                        Float.floatToRawIntBits(x),
                        Float.floatToRawIntBits(y),
                        Float.floatToRawIntBits(r),
                        Float.floatToRawIntBits(g),
                        Float.floatToRawIntBits(b),
                        Float.floatToRawIntBits(a));
        return e != null && resident(e) ? e : null;
    }

    /** The assembly is still in the cache (not evicted) and with the run on the GPU. */
    static boolean resident(Object entry) {
        Entry e = (Entry) entry;
        return e.runs > 0 && e.resident != null && e.resident.valid && e.node.alive;
    }

    static int runs(Object entry) {
        return ((Entry) entry).runs;
    }

    static Object runLocation(Object entry, int r) {
        return ((Entry) entry).runLocation[r];
    }

    /** The first vertex of run {@code r} in the GPU buffer. */
    static int runFirst(Object entry, int r) {
        Entry e = (Entry) entry;
        return e.resident.firstVertex() + e.runFirst[r];
    }

    static int runCount(Object entry, int r) {
        return ((Entry) entry).runCount[r];
    }

    /**
     * The direct label drew the assembly: the use (eviction by the oldest) and the run occupied in
     * this flush.
     */
    static void residentDrawn(Object entry) {
        Entry e = (Entry) entry;
        long now = ++clock;
        e.used = now;
        e.node.used = now;
        SteinTextGpu.drawn(e.resident);
        SteinTextGpuStats.runsDrawn += e.runs;
        SteinTextCacheStats.hits++;
    }

    static TextureManager textureManager(FontRenderer font) {
        return font.renderEngine;
    }

    static boolean unicode(FontRenderer font) {
        return font.unicodeFlag;
    }

    /**
     * renderString would pass the text through bidi (Arabic, Hebrew): the cache key would be the
     * reordered text.
     */
    static boolean bidi(FontRenderer font) {
        return font.bidiFlag;
    }

    /**
     * The font as renderString's last call for the label would leave it: the input color (red,
     * blue, green, alpha of the FontRenderer, in the order they go to the GlStateManager) and what
     * the assembly left (position, text color, styles).
     */
    static void leaveFont(FontRenderer font, Object entry) {
        Entry e = (Entry) entry;
        font.red = Float.intBitsToFloat(e.r);
        font.blue = Float.intBitsToFloat(e.g);
        font.green = Float.intBitsToFloat(e.b);
        font.alpha = Float.intBitsToFloat(e.a);
        SteinTextReplay.restoreEndState(font, e);
    }

    // ---- invalidation ----

    /** After each resource reload (the font's widths and pages redone). */
    public static void clear() {
        generation++;
        for (Node n : table) {
            if (n != null) {
                n.alive = false;
                for (int i = 0; i < n.count; i++) {
                    dropped(n.entries[i]);
                }
            }
        }
        // The runs on the GPU are from the earlier font (new pages and widths): the ring starts
        // empty again.
        if (REQUESTED) {
            SteinTextGpu.reset();
        }
        table = new Node[INITIAL_TABLE_SIZE];
        nodes = 0;
        entries = 0;
        bytes = 0;
        lastNode = null;
        lastText = null;
        lastFont = null;
    }

    /**
     * The longest sequence of occupied slots in the table (the worst case of a lookup), only for
     * the numbers.
     */
    static int longestProbe() {
        int best = 0;
        int run = 0;
        for (Node n : table) {
            run = n == null ? 0 : run + 1;
            best = Math.max(best, run);
        }
        return best;
    }
}
