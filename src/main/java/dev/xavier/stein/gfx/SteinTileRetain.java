/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinTileEntry.DYN;
import static dev.xavier.stein.gfx.SteinTileEntry.INST;
import static dev.xavier.stein.gfx.SteinTileEntry.PART;
import static dev.xavier.stein.gfx.SteinTileKey.SIGN;
import static dev.xavier.stein.gfx.SteinTileKey.keyBuf;
import static dev.xavier.stein.gfx.SteinTileKey.keyN;
import static dev.xavier.stein.gfx.SteinTileKey.refBuf;
import static dev.xavier.stein.gfx.SteinTileKey.refN;
import static dev.xavier.stein.gfx.SteinTileState.*;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.bridge.engine.SignText;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Retained tile entities (new renderer, entity pass): when what a block draws has not changed since
 * the previous frame, the game's renderer (TileEntityRendererDispatcher, bhc) does not run again —
 * the instances recorded last time are re-emitted into the ring, with the matrix corrected for the
 * current camera. In a crowd with chests and signs, renderTile was ~9% of the render thread,
 * rebuilding the game's model tree every frame for blocks that were standing still.
 *
 * <p>Allow-list: only the game's own renderers that read nothing but what goes into the key (see
 * {@link SteinTileKey#key}):
 *
 * <ul>
 *   <li>chest (bhe): metadata, block, type (normal/trapped), the four neighbours and each one's
 *       interpolated lid angle (prevLidAngle + (lidAngle - prevLidAngle) * partialTicks, the same
 *       arithmetic in float). A chest with metadata 0 (the renderer fixes the orientation in the
 *       world) is left to the game. The "Christmas" texture is fixed in the renderer.
 *   <li>ender chest (bhg): metadata and the interpolated lid angle.
 *   <li>sign (bhj): block (standing or on a wall), metadata, the four text components by identity
 *       (the game replaces the component, it does not edit it: which is what SignText already uses)
 *       and the line being edited (the "> <"). Text with a scrambled code (§k: a glyph drawn at
 *       random every frame) is left to the game. The font goes into the global key.
 *   <li>skull (bhk): metadata (face), type and rotation. A player head with an owner is left to the
 *       game (the skin arrives later, through the SkinManager, without changing anything in the
 *       block).
 * </ul>
 *
 * Excluded: banner (sways with world time), enchanting table (book), beacon (beam), spawner
 * (spins), End portal (animated texture generation), moving piston, the crack of a block being
 * broken (stage >= 0), and any block whose class or renderer is not exactly the game's (mods
 * register their own).
 *
 * <p>Besides the block's key, the entry only holds with the same GlStateManager state on entering
 * the block (bound textures, light, blend, depth, colour, current normal... see {@link
 * SteinTileState#snapshot}), with the same block light (lightmap coord from world.getCombinedLight,
 * which bhc.a sets before the renderer) and with the geometry and textures in the same place (layer
 * in the array, filter, display list). The state the renderer leaves behind on exit (bound texture,
 * colour, rescale normal, culling, current normal...) is stored as a difference and reapplied
 * through the GlStateManager's own methods: after the retained block the game is in the same state
 * it would otherwise be in. A difference in a field with no setter here = the block is left to the
 * game while the key does not change.
 *
 * <p>The matrix: the renderer draws with M = V * T(block - camera) * L, with L fixed for the same
 * key. Recorded with camera (V0, S0), re-emitted with (V1, S1): M1 = V1 * T(S0 - S1) * inverse(V0)
 * * M0, a 3x4 correction per frame (in double), the same for all instances recorded in the same
 * frame. Same camera: exact copy, bit for bit. Different camera: the float rounding changes in the
 * last bit (far below the depth buffer's resolution).
 *
 * <p>Invalidated by: a different key or entry state (block replaced, broken, lid moving, new text,
 * light), a resource reload (SignText generation), a texture deleted by the game (an id can come
 * back for another one), a changed font or unicode turned on, a texture layer in the array that was
 * reused, a changed texture filter (mipmap), a display list with another size; blocks that do not
 * show up for 64 passes (chunk unloaded, out of view) are released.
 *
 * <p>{@code -Dsteinbridge.gfx.retainTiles=false}: today's path (the game's renderer every frame,
 * recorded). {@code -Dsteinbridge.gfx.retainTilesCheck=true}: every {@code retainTilesCheckEvery}
 * passes (default 30) the blocks that would be retained also run the game's renderer; what is
 * generated is compared with what is retained (instances, groups, vertices, exit state) and the
 * divergences are counted and printed with {@code [gfx]}. The drawing in that frame is the game's.
 * {@code -Dsteinbridge.gfx.retainTilesStats=true}: only the numbers. {@code
 * -Dsteinbridge.gfx.retainTilesReplayCheck=true}: every re-emission is also computed the plain way
 * ({@link SteinTileReplayCheck}).
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinTileRetain {

    static final boolean ENABLED = SteinEntityDeferred.REQUESTED && SteinGfxOptions.RETAIN_TILES;
    static final boolean CHECK = ENABLED && SteinGfxOptions.RETAIN_TILES_CHECK;
    static final int CHECK_EVERY = Math.max(1, SteinGfxOptions.RETAIN_TILES_CHECK_EVERY);
    private static final boolean STATS = CHECK || SteinGfxOptions.RETAIN_TILES_STATS;

    /** Passes without showing up until the entry is released. */
    private static final int MAX_IDLE = 64; // must be a power of two (passBegin masks with it)

    private static final int MAX_ENTRIES = 16384;

    private SteinTileRetain() {}

    private static final Map<Object, SteinTileEntry> entries = new IdentityHashMap<>();

    // ---- recording (SteinEntityDeferred notes each instance while this is on) ----

    /** Recording a block for retention: SteinEntityDeferred notes each instance here. */
    static boolean recording;

    /**
     * Something in the block being recorded went out immediately (or outside what retention
     * covers).
     */
    private static boolean tainted;

    private static int recordedCount;
    private static byte[] recordedType = new byte[64];
    private static long[] recordedGroupKey = new long[64];
    private static int[] recordedVerts = new int[64];
    private static int[] recordedRef = new int[64];
    private static int[] recordedTex = new int[64];
    private static int[] recordedCheck = new int[64];
    private static float[] recordedData = new float[64 * INST];
    private static boolean recordedLightmap;
    private static long recordedVertexAddr;
    private static long recordedVertexCap;
    private static long recordedVertexUsed;
    private static long scratchAddr;
    private static long scratchCap;

    /**
     * Something in the block being recorded does not enter retention (immediate drawing, fixed
     * geometry, glow).
     */
    static void taint() {
        if (recording) {
            tainted = true;
        }
    }

    /**
     * Our own memory for {@code bytes} of vertices of a dynamic draw of the block being recorded.
     */
    static long scratch(long bytes) {
        if (bytes > scratchCap) {
            long cap = Math.max(bytes, Math.max(64 * 1024, scratchCap * 2));
            scratchAddr = SteinNativeBuffer.grow(scratchAddr, cap);
            scratchCap = cap;
        }
        // Zeroed: SteinTessCapture.font writes only position and texture (bytes 0..19) and leaves
        // colour, normal and light as they were (unused fields in that format). In the ring that is
        // harmless garbage, but here it would turn into a different retained vertex on every
        // recording (and different from the cached text, which has those bytes zeroed): the check
        // reported "vertices" on signs.
        MemoryUtil.memSet(scratchAddr, 0, bytes);
        return scratchAddr;
    }

    static long scratchAddress() {
        return scratchAddr;
    }

    private static int slot() {
        if (recordedCount == recordedType.length) {
            int n = recordedCount * 2;
            recordedType = Arrays.copyOf(recordedType, n);
            recordedGroupKey = Arrays.copyOf(recordedGroupKey, n);
            recordedVerts = Arrays.copyOf(recordedVerts, n);
            recordedRef = Arrays.copyOf(recordedRef, n);
            recordedTex = Arrays.copyOf(recordedTex, n);
            recordedCheck = Arrays.copyOf(recordedCheck, n);
            recordedData = Arrays.copyOf(recordedData, n * INST);
        }
        return recordedCount++;
    }

    /**
     * A recorded model part: group, display list, texture and layer (to check later), the instance.
     */
    static void notePart(
            long gkey, int list, int verts, int tex, int packed, boolean lightmap, float[] inst) {
        int i = slot();
        recordedType[i] = PART;
        recordedGroupKey[i] = gkey;
        recordedVerts[i] = verts;
        recordedRef[i] = list;
        recordedTex[i] = tex;
        recordedCheck[i] = packed;
        recordedLightmap |= lightmap;
        System.arraycopy(inst, 0, recordedData, i * INST, INST);
    }

    /**
     * A recorded dynamic draw: group, the vertices (copied from {@code src}), texture and filter,
     * the instance.
     */
    static void noteDynamic(
            long gkey, int verts, long src, int tex, int filter, boolean lightmap, float[] inst) {
        int i = slot();
        long bytes = (long) verts * SteinTessCapture.VERTEX_BYTES;
        if (recordedVertexUsed + bytes > recordedVertexCap) {
            long cap =
                    Math.max(
                            recordedVertexUsed + bytes, Math.max(64 * 1024, recordedVertexCap * 2));
            recordedVertexAddr = SteinNativeBuffer.grow(recordedVertexAddr, cap);
            recordedVertexCap = cap;
        }
        MemoryUtil.memCopy(src, recordedVertexAddr + recordedVertexUsed, bytes);
        recordedType[i] = DYN;
        recordedGroupKey[i] = gkey;
        recordedVerts[i] = verts;
        recordedRef[i] = (int) recordedVertexUsed;
        recordedTex[i] = tex;
        recordedCheck[i] = filter;
        recordedLightmap |= lightmap;
        System.arraycopy(inst, 0, recordedData, i * INST, INST);
        recordedVertexUsed += bytes;
    }

    // ---- the current block (in place of bhc.a(akw, F, I) inside renderEntities) ----

    /**
     * Predicted state: in renderEntities' block loop nothing runs between one block and the next,
     * so after a retained block (state = the recorded exit) or a recorded one (state = the exit
     * read) the next one's entry is that exit plus the start of bhc.a (the lightmap coordinates of
     * its light, white colour and, if the colour changed, the white last vertex colour). That way
     * the whole GlStateManager state is read once per sequence, not per block. Any block left to
     * the game or outside the key breaks the sequence (reads again). The check compares the
     * prediction with the reading. {@code -Dsteinbridge.gfx.retainTilesPredict=false}: always
     * reads.
     */
    private static final boolean PREDICT = SteinGfxOptions.RETAIN_TILES_PREDICT;

    private static final int[] chain = new int[STATE_FIELDS];
    private static boolean chainValid;
    private static final int[] predictedCheckState = new int[STATE_FIELDS];
    private static long predictions;
    private static long predictMisses;

    /**
     * The entry predicted from the previous block's exit and the start of bhc.a with light {@code
     * light}.
     */
    private static void predict(int[] s, int light) {
        System.arraycopy(chain, 0, s, 0, STATE_FIELDS);
        s[LMU] = bits(lightmapU(light));
        s[LMV] = bits(lightmapV(light));
        int one = bits(1f);
        // GlStateManager.color(1, 1, 1, 1) only goes to the driver (and notes the last colour) if
        // the stored colour was different.
        if (s[CR] != one || s[CG] != one || s[CB] != one || s[CA] != one) {
            s[CR] = one;
            s[CG] = one;
            s[CB] = one;
            s[CA] = one;
            s[LCKNOWN] = 1;
            s[LR] = one;
            s[LG] = one;
            s[LB] = one;
            s[LA] = one;
        }
    }

    private static final int[] entryState = new int[STATE_FIELDS];
    private static final int[] exitState = new int[STATE_FIELDS];
    private static final float[] viewNow = new float[16];

    private static long pass;

    /** Start of the entity pass. */
    static void passBegin() {
        if (!ENABLED) {
            return;
        }
        pass++;
        chainValid = false;
        if ((pass & (MAX_IDLE - 1)) == 0 || entries.size() > MAX_ENTRIES) {
            sweep(entries.size() > MAX_ENTRIES ? Long.MAX_VALUE : pass - MAX_IDLE);
        }
        stats();
        if (SteinTileReplayCheck.ENABLED) {
            SteinTileReplayCheck.report();
        }
    }

    /** Releases the entries that have not shown up since before pass {@code before}. */
    private static void sweep(long before) {
        for (Iterator<SteinTileEntry> it = entries.values().iterator(); it.hasNext(); ) {
            SteinTileEntry e = it.next();
            if (e.lastPass < before) {
                e.free();
                it.remove();
                dropped++;
            }
        }
    }

    private static long globalPass = -1;
    private static int gGeneration;
    private static long gDeletes;
    private static FontRenderer globalFont;
    private static boolean globalUnicode;

    /** Once per pass: resource reload, deleted texture, changed font = everything again. */
    private static void globalCheck(TileEntityRendererDispatcher dispatcher) {
        if (globalPass == pass) {
            return;
        }
        globalPass = pass;
        int gen = SignText.generation();
        long deletes = SteinTextureArrays.deletes();
        FontRenderer font = dispatcher.getFontRenderer();
        boolean unicode = font != null && font.unicodeFlag;
        if (gen != gGeneration
                || deletes != gDeletes
                || font != globalFont
                || unicode != globalUnicode) {
            if (!entries.isEmpty()) {
                sweep(Long.MAX_VALUE);
                invalidations++;
            }
            gGeneration = gen;
            gDeletes = deletes;
            globalFont = font;
            globalUnicode = unicode;
        }
    }

    /**
     * In place of bhc.a(akw, F, I) (through {@link SteinEntityDeferred#renderTile}), recording the
     * entity pass.
     */
    static void render(Object dispatcherObject, Object te, float partial, int stage) {
        TileEntityRendererDispatcher dispatcher = (TileEntityRendererDispatcher) dispatcherObject;
        int kind = stage < 0 ? SteinTileKey.kindOf(dispatcher, te) : 0;
        if (kind == 0) {
            plain++;
            chainValid = false;
            SteinEntityDeferred.renderTileNow(dispatcher, te, partial, stage);
            return;
        }
        // The start of bhc.a: only within the distance; the block's light in the lightmap and the
        // white colour.
        TileEntity tile = (TileEntity) te;
        double ex = dispatcher.entityX;
        double ey = dispatcher.entityY;
        double ez = dispatcher.entityZ;
        if (!(tile.getDistanceSq(ex, ey, ez) < tile.getMaxRenderDistanceSquared())) {
            return;
        }
        World world = dispatcher.worldObj;
        BlockPos pos = tile.getPos();
        int light = world.getCombinedLight(pos, 0);
        SteinGameState.setLightmap(lightmapU(light), lightmapV(light));
        GlStateManager.color(1f, 1f, 1f, 1f);
        globalCheck(dispatcher);
        boolean chained = chainValid;
        // From here on the state is only known where it is marked again.
        chainValid = false;
        if (!SteinTileKey.key(kind, te, partial, light)) {
            leftToGame(dispatcher, te, partial, stage);
            return;
        }
        float[] v = GLMatrix.current();
        captureEntryState(chained, light);
        if (entryState[TEXMAT] != 0 || v[3] != 0f || v[7] != 0f || v[11] != 0f || v[15] != 1f) {
            leftToGame(dispatcher, te, partial, stage);
            return;
        }
        SteinTileEntry e = entries.get(te);
        boolean same = e != null && e.matches(entryState);
        if (same) {
            e.lastPass = pass;
            if (e.noRetain) {
                notRetained++;
                SteinEntityDeferred.renderTileNow(dispatcher, te, partial, stage);
                return;
            }
        } else if (kind == SIGN && SteinTileKey.signObfuscated()) {
            // Scrambled text: left to the game; the entry only marks this, so the check is not
            // repeated every frame.
            leftToGame(dispatcher, te, partial, stage);
            storeStub(te, e);
            return;
        }
        boolean usable = same && !e.stub && valid(e);
        boolean check = CHECK && usable && pass % CHECK_EVERY == 0;
        if (usable && !check && tryReplay(e)) {
            return;
        }
        recordAndStore(dispatcher, te, partial, stage, kind, e, same, check, v);
    }

    /** The block is not retained: the game's renderer runs, counted as outside the key. */
    private static void leftToGame(Object dispatcher, Object te, float partial, int stage) {
        uncacheable++;
        SteinEntityDeferred.renderTileNow(dispatcher, te, partial, stage);
    }

    /**
     * The entry state of the block: predicted from the previous block's exit when the sequence is
     * unbroken, read otherwise (and, in the check, both, comparing them).
     */
    private static void captureEntryState(boolean chained, int light) {
        if (PREDICT && chained) {
            predict(entryState, light);
            if (CHECK) {
                snapshot(predictedCheckState);
                predictions++;
                if (!Arrays.equals(entryState, predictedCheckState)) {
                    predictMisses++;
                    if (SteinTileCheck.printed < 20) {
                        SteinTileCheck.printed++;
                        Log.info(
                                "gfx",
                                "retention: DIVERGENCE in the predicted state ("
                                        + SteinTileCheck.firstDiff(entryState, predictedCheckState)
                                        + ")");
                    }
                    System.arraycopy(predictedCheckState, 0, entryState, 0, STATE_FIELDS);
                }
            }
        } else {
            snapshot(entryState);
        }
    }

    /** Replaces the block's entry with a marker that leaves it to the game while the key holds. */
    private static void storeStub(Object te, SteinTileEntry e) {
        SteinTileEntry stub = new SteinTileEntry();
        stub.key = Arrays.copyOf(keyBuf, keyN);
        stub.refs = Arrays.copyOf(refBuf, refN);
        stub.sig = entryState.clone();
        stub.lastPass = pass;
        stub.stub = true;
        stub.noRetain = true;
        if (e != null) {
            e.free();
        }
        entries.put(te, stub);
    }

    /** Re-emits the retained block; false = it did not fit or has no usable correction. */
    private static boolean tryReplay(SteinTileEntry e) {
        double[] fix = SteinCameraCorrection.correction(e, pass);
        if (fix != SteinCameraCorrection.NO_INVERSE && replay(e, fix)) {
            hits++;
            // After the retained block the state is exactly the recorded exit: the next block
            // predicts it.
            System.arraycopy(e.exit, 0, chain, 0, STATE_FIELDS);
            chainValid = true;
            return true;
        }
        return false;
    }

    /**
     * Runs the game's renderer recording what it draws, and stores the result in the block's entry.
     */
    private static void recordAndStore(
            Object dispatcher,
            Object te,
            float partial,
            int stage,
            int kind,
            SteinTileEntry e,
            boolean same,
            boolean check,
            float[] v) {
        // Records (the game's renderer, as today) and stores.
        System.arraycopy(v, 0, viewNow, 0, 16);
        double sx = TileEntityRendererDispatcher.staticPlayerX;
        double sy = TileEntityRendererDispatcher.staticPlayerY;
        double sz = TileEntityRendererDispatcher.staticPlayerZ;
        recordedCount = 0;
        recordedVertexUsed = 0;
        recordedLightmap = false;
        tainted = false;
        recording = true;
        try {
            SteinEntityDeferred.renderTileNow(dispatcher, te, partial, stage);
        } finally {
            recording = false;
        }
        records++;
        SteinTileEntry fresh = new SteinTileEntry();
        fresh.key = Arrays.copyOf(keyBuf, keyN);
        fresh.refs = Arrays.copyOf(refBuf, refN);
        fresh.sig = entryState.clone();
        fresh.lastPass = pass;
        System.arraycopy(viewNow, 0, fresh.view, 0, 16);
        fresh.sx = sx;
        fresh.sy = sy;
        fresh.sz = sz;
        if (tainted) {
            // Something went out immediately (the first time for a model, which compiles the
            // display list through the game's path): tries again next frame; three times in a row
            // with the same key, it is left to the game.
            taints++;
            fresh.stub = true;
            fresh.taints = same ? e.taints + 1 : 1;
            fresh.noRetain = fresh.taints >= 3;
        } else {
            snapshot(exitState);
            System.arraycopy(exitState, 0, chain, 0, STATE_FIELDS);
            chainValid = true;
            fill(fresh);
            if (exitState[TEXMAT] != 0
                    || !Arrays.equals(viewNow, GLMatrix.current())
                    || !exitDiff(fresh)) {
                // Exit that is not reapplied: left to the game while the key does not change.
                fresh.noRetain = true;
                unsupported++;
            } else if (check) {
                SteinTileCheck.compare(kind, e, fresh, pass);
            }
        }
        if (e != null) {
            e.free();
        }
        entries.put(te, fresh);
    }

    /** Copies the recorded data into the entry. */
    private static void fill(SteinTileEntry e) {
        int n = recordedCount;
        e.n = n;
        e.type = Arrays.copyOf(recordedType, n);
        e.gkey = Arrays.copyOf(recordedGroupKey, n);
        e.verts = Arrays.copyOf(recordedVerts, n);
        e.ref = Arrays.copyOf(recordedRef, n);
        e.tex = Arrays.copyOf(recordedTex, n);
        e.chk = Arrays.copyOf(recordedCheck, n);
        e.data = Arrays.copyOf(recordedData, n * INST);
        if (n > 0) {
            e.iblob = MemoryUtil.nmemAlloc((long) n * INST * 4);
            for (int i = 0; i < n * INST; i++) {
                MemoryUtil.memPutFloat(e.iblob + i * 4L, e.data[i]);
            }
        }
        for (int i = 0; i < n; i++) {
            if (e.type[i] == DYN) {
                e.dynVerts += e.verts[i];
            }
        }
        e.lightmap = recordedLightmap;
        if (recordedVertexUsed > 0) {
            e.vblob = MemoryUtil.nmemAlloc(recordedVertexUsed);
            e.vbytes = recordedVertexUsed;
            MemoryUtil.memCopy(recordedVertexAddr, e.vblob, recordedVertexUsed);
        }
    }

    /** The differences between the renderer's entry and exit; false = some has no setter. */
    private static boolean exitDiff(SteinTileEntry e) {
        int[] d = new int[STATE_FIELDS];
        int n = 0;
        for (int i = 0; i < STATE_FIELDS; i++) {
            if (exitState[i] != entryState[i]) {
                if (!settable(i, exitState)) {
                    return false;
                }
                d[n++] = i;
            }
        }
        e.exit = exitState.clone();
        e.diff = Arrays.copyOf(d, n);
        return true;
    }

    /** Do the recorded geometry and textures remain in the same place? */
    private static boolean valid(SteinTileEntry e) {
        for (int i = 0; i < e.n; i++) {
            int tex = e.tex[i];
            if (e.type[i] == PART) {
                if (SteinModelInstancer.vertexCount(e.ref[i]) != e.verts[i]) {
                    return false;
                }
                int now = tex < 0 ? SteinTextureArrays.anyClass() : SteinTextureArrays.layer(tex);
                if (now != e.chk[i]) {
                    return false;
                }
            } else if (tex > 0 && SteinTessCapture.filterCode(tex) != e.chk[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Re-emits the instances and reapplies the exit state; false = it did not fit (records again).
     */
    private static boolean replay(SteinTileEntry e, double[] fix) {
        if (e.lightmap) {
            SteinEntityDeferred.retainedLightmap();
        }
        if (e.n > 0 && e.groupsEpoch != SteinEntityDeferred.groupEpoch()) {
            // Looking up a group may create another and release the idle ones (including one
            // already looked up here): repeats until the epoch stays still during the lookup.
            if (e.groups == null) {
                e.groups = new Object[e.n];
            }
            int epoch;
            do {
                epoch = SteinEntityDeferred.groupEpoch();
                for (int i = 0; i < e.n; i++) {
                    e.groups[i] = SteinEntityDeferred.groupFor(e.gkey[i]);
                }
            } while (epoch != SteinEntityDeferred.groupEpoch());
            e.groupsEpoch = epoch;
        }
        if (!SteinEntityDeferred.emitRetainedBatch(
                e.groups, e.n, e.type, e.verts, e.ref, e.iblob, e.data, fix, e.vblob, e.dynVerts)) {
            // Bigger than a ring region (does not happen with these blocks): records through the
            // game.
            return false;
        }
        applyExit(e.exit, e.diff);
        return true;
    }

    // ---- numbers ----

    private static long hits;
    private static long records;
    private static long plain;
    private static long uncacheable;
    private static long notRetained;
    private static long taints;
    private static long unsupported;
    private static long dropped;
    private static long invalidations;
    private static long statsPasses;
    private static long statsAt = System.nanoTime();

    private static void stats() {
        if (!STATS) {
            return;
        }
        statsPasses++;
        long now = System.nanoTime();
        if (now - statsAt < 5_000_000_000L) {
            return;
        }
        statsAt = now;
        long p = Math.max(1, statsPasses);
        Log.info(
                "gfx",
                "retention per pass: "
                        + hits / p
                        + " retained, "
                        + records / p
                        + " recorded, "
                        + notRetained / p
                        + " by the game (exit without a setter: "
                        + unsupported
                        + "), "
                        + uncacheable / p
                        + " outside the key, "
                        + plain / p
                        + " of other kinds; "
                        + entries.size()
                        + " entries, "
                        + taints
                        + " with immediate drawing, "
                        + dropped
                        + " released, "
                        + invalidations
                        + " global invalidations"
                        + (CHECK
                                ? "; checked "
                                        + SteinTileCheck.checked
                                        + ", divergences "
                                        + SteinTileCheck.divergent
                                        + "; predicted state checked "
                                        + predictions
                                        + ", divergences "
                                        + predictMisses
                                : ""));
        hits = 0;
        records = 0;
        notRetained = 0;
        uncacheable = 0;
        plain = 0;
        statsPasses = 0;
    }
}
