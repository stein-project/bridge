/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredGroups.*;
import static dev.xavier.stein.gfx.SteinDeferredKeys.*;
import static dev.xavier.stein.gfx.SteinDeferredLights.*;
import static dev.xavier.stein.gfx.SteinDeferredModel.*;
import static dev.xavier.stein.gfx.SteinDeferredPose.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;
import static dev.xavier.stein.gfx.SteinDeferredSessions.*;
import static dev.xavier.stein.gfx.SteinDeferredStats.*;
import static dev.xavier.stein.gfx.SteinDeferredText.*;
import static dev.xavier.stein.gfx.SteinEntityDeferred.*;
import static dev.xavier.stein.gfx.SteinInstanceWriter.*;
import static dev.xavier.stein.gfx.SteinPassTiming.*;
import static dev.xavier.stein.gfx.SteinSegmentCheck.*;

import dev.xavier.stein.gfx.gl.SteinGlCommands;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * The drawing of {@link SteinEntityDeferred}: at flush, writes the commands of every group and
 * draws them in one pass, with an indirect draw per group (or a sorted instanced draw per part
 * below GL 4.4).
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredFrame {

    // ---- drawing ----

    static long[] sortKeys = new long[1024];

    static void flush() {
        if (pending == 0) {
            return;
        }
        long ft0 = TIMING ? System.nanoTime() : 0L;
        // Texture filters registered in the pass go to the driver first: the applyFilter below
        // starts from what the driver has.
        SteinTessCapture.syncFilters();
        SteinDevice dev = SteinDevice.get();
        long frameAt = prepareFrame(dev);
        // The commands of all groups before the pass: the on-GPU pose needs to know where each of
        // its blocks landed.
        for (int i = 0; i < order.size(); i++) {
            Group g = order.get(i);
            g.cmdAt = indirect && g.n > 0 ? writeCommands(g) : 0;
        }
        boolean poseGpu = SteinPoseGpu.pending();
        if (poseGpu && !SteinPoseGpu.prepare()) {
            clearPoseBlocks();
            poseGpu = false;
        }
        long ft1 = TIMING ? System.nanoTime() : 0L;
        SteinRenderPass rp = SteinRenderPass.keep(passName, dev.gameTarget());
        if (viewportW > 0) {
            rp = rp.viewport(viewportX, viewportY, viewportW, viewportH);
        }
        SteinCommands c = dev.beginPass(rp);
        long ft2 = TIMING ? System.nanoTime() : 0L;
        try {
            drawGroups(c, poseGpu, frameAt);
        } finally {
            finishFlush(c, ft0, ft1, ft2);
        }
    }

    /**
     * Writes the Frame block, uploads the rings (without a persistent buffer) and imports the
     * lightmap; returns the block's offset in the frame ring.
     */
    private static long prepareFrame(SteinDevice dev) {
        long frameAt = frame.reserve(FRAME_BYTES, dev.caps().uniformAlignment);
        if (frameAt == -2) {
            frame.nextRegion();
            frameAt = frame.reserve(FRAME_BYTES, dev.caps().uniformAlignment);
        }
        writeFrame(frame.address(frameAt));
        // Without a persistent buffer, what the CPU wrote is uploaded now (with it, nothing to do).
        frame.commitRing();
        instances.commitRing();
        if (dynVertices != null) {
            dynVertices.commitRing();
        }
        if (lightmap == null || lightmap.handle() != lightmapId) {
            lightmap =
                    dev.importTexture(
                            SteinTexture.Desc.texture2D("lightmap", SteinFormat.RGBA8, 16, 16, 1),
                            lightmapId);
        }
        return frameAt;
    }

    /** Binds what every group shares and draws the groups: by segments, or non-blended first. */
    private static void drawGroups(SteinCommands c, boolean poseGpu, long frameAt) {
        // The current scissor (Panel, scrolling lists: SteinGlTrack.scissor): what was recorded
        // goes out inside it.
        int[] clip = SteinGlTrack.scissor();
        if (clip != null) {
            c.setScissor(clip);
        }
        if (poseGpu) {
            // before any draw (barrier in the dispatch)
            SteinPoseGpu.dispatch(c, instances, commands);
        }
        c.bindVertexBuffer(0, SteinModelInstancer.geometry(), 0);
        passVb0 = SteinModelInstancer.geometry();
        c.bindVertexBuffer(1, instances, 0);
        c.bindUniforms(0, frame, frameAt, FRAME_BYTES);
        c.bindTexture(1, lightmap, linear);
        if (strictOrder) {
            // Strict order in segments: in the order they were opened, each with its pipeline and
            // its texture.
            if (segs > 0) {
                segTo[segs - 1] = segGroup[segs - 1].n;
            }
            if (SEGMENT_CHECK) {
                checkSegments();
            }
            for (int i = 0; i < segs; i++) {
                Group g = segGroup[i];
                if (segTo[i] > segFrom[i] && g.cmdAt >= 0) {
                    drawRange(c, g, segFrom[i], segTo[i] - segFrom[i]);
                }
            }
        } else {
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < order.size(); i++) {
                    Group g = order.get(i);
                    if (skipInPass(g, pass)) {
                        continue;
                    }
                    if (g.cmdAt < 0) {
                        continue;
                    }
                    drawRange(c, g, 0, g.n);
                }
            }
        }
    }

    /** Pass 0 draws the groups without blending, pass 1 those with it. */
    private static boolean skipInPass(Group g, int pass) {
        return g.n == 0 || (strictOrder ? pass == 1 : g.blend != (pass == 1));
    }

    /** Closes the pass and empties the groups, the segments and the pending count. */
    private static void finishFlush(SteinCommands c, long ft0, long ft1, long ft2) {
        Arrays.fill(segGroup, 0, segs, null);
        segs = 0;
        c.end();
        try {
            SteinPoseGpu.afterFlush(instances, commands);
        } finally {
            poseBlocks = 0;
        }
        for (int i = 0; i < order.size(); i++) {
            Group g = order.get(i);
            g.n = 0;
            g.entries = 0;
        }
        order.clear();
        last = null;
        pending = 0;
        flushes++;
        flushSerial++;
        if (TIMING) {
            long ft3 = System.nanoTime();
            flushPrep += ft1 - ft0;
            flushOpen += ft2 - ft1;
            flushRest += ft3 - ft2;
            flushCount++;
        }
    }

    /**
     * The group's polygon offset ({@code id} = index + 1; 0 turns it off). Straight on OpenGL: the
     * pass runs the commands immediately, starts with the offset off, and the glPopAttrib at the
     * end returns the game's. Pending in the API: a setDepthBias in SteinCommands (dynamic state,
     * as in Vulkan).
     */
    static void polygonOffset(int id) {
        // the same calls, through the pass (which notes what was touched and returns the game's at
        // the end, without glPopAttrib in core)
        if (id == 0) {
            SteinGlCommands.polygonOffset(false, 0f, 0f);
            return;
        }
        SteinGlCommands.polygonOffset(true, offsetFactor[id - 1], offsetUnits[id - 1]);
    }

    /**
     * The commands {@code [from, from + count)} of the group (in units of {@code n}; the whole
     * group outside strict order), with its pipeline, vertex buffer, texture and offset.
     */
    static void drawRange(SteinCommands c, Group g, int from, int count) {
        c.bindPipeline(g.pipeline);
        if (g.offset != 0) {
            polygonOffset(g.offset);
        }
        if (g.gameTex) {
            // model part with the game's texture directly, with its parameters
            vb0(c, SteinModelInstancer.geometry());
            c.bindTexture(0, g.gameTexture, null);
            drawGroup(c, g, from, count);
        } else if (!g.dynamic) {
            vb0(c, SteinModelInstancer.geometry());
            c.bindTexture(
                    0,
                    SteinTextureArrays.array(keyClass(g.key)),
                    SteinTextureArrays.sampler(keyClass(g.key)));
            drawGroup(c, g, from, count);
        } else {
            // the game's texture with its parameters (without a sampler of ours), in the filter
            // from when it was recorded
            vb0(
                    c,
                    g.fromText
                            ? SteinTextGpu.arena()
                            : g.fromStatic ? SteinTessCapture.staticGeometry() : dynVertices);
            c.bindTexture(0, g.gameTexture, null);
            int was = g.texture > 0 ? SteinTessCapture.applyFilter(g.texture, g.filter) : 0;
            drawGroup(c, g, from, count);
            SteinTessCapture.restore(g.texture, was);
        }
        if (g.offset != 0) {
            polygonOffset(0);
        }
        draws++;
    }

    /**
     * The buffer of binding 0 in this pass (the last one bound): binding the same one again is just
     * work for the backend.
     */
    static SteinBuffer passVb0;

    /**
     * Binding 0 with {@code b}, if it is not already it: what is bound stays valid until replaced,
     * including across a pipeline switch (contract of {@link SteinCommands}). In strict order in
     * segments, the segments alternate between a few groups of the same buffer (the frame ring).
     */
    static void vb0(SteinCommands c, SteinBuffer b) {
        if (b != passVb0) {
            c.bindVertexBuffer(0, b, 0);
            passVb0 = b;
        }
    }

    static void drawGroup(SteinCommands c, Group g, int from, int count) {
        if (indirect) {
            c.drawIndirect(commands, g.cmdAt + (long) from * COMMAND_BYTES, count);
        } else {
            drawSorted(c, g, from, count);
            c.bindVertexBuffer(1, instances, 0);
        }
    }

    /**
     * Without indirect draw (4.1): the group's instances copied in part order (first vertex) to the
     * {@link SteinDeferredResources#sorted} buffer, and one instanced draw per sequence of the same
     * part — dozens of draws instead of one per part of each player. The order among equal parts is
     * the recording order.
     */
    static void drawSorted(SteinCommands c, Group g, int from, int count) {
        // Without indirect draw there is no on-GPU pose: each triple is a command (the interval
        // counts in triples).
        int n = count;
        if (sortKeys.length < n) {
            sortKeys = new long[Math.max(n, sortKeys.length * 2)];
        }
        int[] cm = g.cmds;
        for (int i = 0; i < n; i++) {
            sortKeys[i] = (long) cm[(from + i) * 3 + 1] << 32 | (from + i);
        }
        Arrays.sort(sortKeys, 0, n);
        long bytes = (long) n * INSTANCE_BYTES;
        long at = sorted.reserve(bytes);
        if (at == -2) {
            sorted.nextRegion();
            at = sorted.reserve(bytes);
        }
        if (at < 0) {
            return;
        }
        long dst = sorted.address(at);
        for (int i = 0; i < n; i++) {
            int k = (int) sortKeys[i];
            MemoryUtil.memCopy(
                    instances.address((long) cm[k * 3 + 2] * INSTANCE_BYTES),
                    dst + (long) i * INSTANCE_BYTES,
                    INSTANCE_BYTES);
        }
        sorted.commit(at, bytes);
        c.bindVertexBuffer(1, sorted, 0);
        int base = (int) (at / INSTANCE_BYTES);
        for (int i = 0; i < n; ) {
            int k = (int) sortKeys[i];
            int first = cm[k * 3 + 1];
            int verts = cm[k * 3];
            int j = i + 1;
            while (j < n
                    && cm[(int) sortKeys[j] * 3 + 1] == first
                    && cm[(int) sortKeys[j] * 3] == verts) {
                j++;
            }
            c.draw(verts, j - i, first, base + i);
            i = j;
        }
    }

    static long writeCommands(Group g) {
        long bytes = (long) g.n * COMMAND_BYTES;
        long at = commands.reserve(bytes);
        if (at == -2) {
            commands.nextRegion();
            at = commands.reserve(bytes);
        }
        if (at < 0) {
            return -1;
        }
        long p = commands.address(at);
        int[] cm = g.cmds;
        int index = (int) (at / COMMAND_BYTES);
        for (int i = 0, k = 0; i < g.entries; i++, k += 3) {
            if (cm[k] == POSE_BLOCK) {
                // on-GPU pose block: the compute writes its commands here
                poseBlockAt[cm[k + 1]] = index;
                p += (long) cm[k + 2] * COMMAND_BYTES;
                index += cm[k + 2];
                continue;
            }
            MemoryUtil.memPutInt(p, cm[k]);
            MemoryUtil.memPutInt(p + 4, 1);
            MemoryUtil.memPutInt(p + 8, cm[k + 1]);
            MemoryUtil.memPutInt(p + 12, cm[k + 2]);
            p += COMMAND_BYTES;
            index++;
        }
        return at;
    }
}
