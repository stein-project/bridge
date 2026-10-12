/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import static dev.xavier.stein.gfx.gl.SteinGlOps.TEXTURE;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGfxOptions;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Check of the recorded frame ({@code -Dsteinbridge.gfx.recordFrameCheck}, diagnostic): one frame
 * every N, each command is described when recorded and again when replayed, and at the end of the
 * frame the two lists are compared command by command; at replay, the resources the commands name
 * must still exist.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlFrameCheck {

    private SteinGlFrameCheck() {}

    private static final boolean ENABLED = SteinGfxOptions.RECORD_FRAME_CHECK;

    /**
     * One frame checked every N ({@code -Dsteinbridge.gfx.recordFrameCheck.every}, default 300).
     */
    private static final int EVERY = SteinGlOptions.RECORD_FRAME_CHECK_EVERY;

    /** This frame is being checked. */
    static boolean checking;

    private static final List<String> REC = new ArrayList<>();
    private static final List<String> REP = new ArrayList<>();
    static long checkedFrames;
    static long mismatches;
    static long invalids;
    private static int printed;

    /** When recording is decided: the first frame is already checked. */
    static void start(boolean recordingOn) {
        checking = recordingOn && ENABLED;
    }

    /** After frame number {@code frames}: is the next one checked? */
    static void afterFrame(long frames) {
        checking = ENABLED && frames % EVERY == 0;
    }

    /** The check found a difference or a nonexistent resource. */
    static boolean failed() {
        return mismatches > 0 || invalids > 0;
    }

    private static String describe(int code, long... args) {
        StringBuilder b = new StringBuilder(SteinGlOps.name(code));
        for (long a : args) {
            b.append(' ').append(a);
        }
        return b.toString();
    }

    static void chkRec(int code, long... args) {
        REC.add(describe(code, args));
    }

    static void chkRep(int code, long... args) {
        REP.add(describe(code, args));
    }

    /** Recording a texture binding: the filter it would have at this point of the list. */
    static void recordTexture(int slot, int id, boolean array, int sampler) {
        // the filter the texture would have at this point of the list: the driver's now, with
        // the parameters already recorded
        int[] f = id != 0 ? CHK_FILTER.get(id) : null;
        int min =
                id == 0
                        ? 0
                        : f != null && f[0] != 0
                                ? f[0]
                                : GL45.glGetTextureParameteri(id, GL11.GL_TEXTURE_MIN_FILTER);
        int mag =
                id == 0
                        ? 0
                        : f != null && f[1] != 0
                                ? f[1]
                                : GL45.glGetTextureParameteri(id, GL11.GL_TEXTURE_MAG_FILTER);
        chkRec(TEXTURE, slot, id, array ? 1 : 0, sampler, min, mag);
    }

    /** Replaying a texture binding: the real filter at this point (before binding). */
    static void replayTexture(int slot, int id, int array, int s) {
        // the real filter at this point (before binding): equal to the one
        // expected at recording, if nothing outside the list touched the
        // texture between recording and replay
        boolean live = id != 0 && GL11.glIsTexture(id);
        int min = live ? GL45.glGetTextureParameteri(id, GL11.GL_TEXTURE_MIN_FILTER) : 0;
        int mag = live ? GL45.glGetTextureParameteri(id, GL11.GL_TEXTURE_MAG_FILTER) : 0;
        chkRep(TEXTURE, slot, id, array, s, min, mag);
        checkTexture(id);
        if (s != 0 && !GL33.glIsSampler(s)) {
            invalid("sampler " + s);
        }
    }

    /** Check: recorded filter (not yet replayed) per texture, for what is expected at draw time. */
    private static final Map<Integer, int[]> CHK_FILTER = new HashMap<>();

    static void chkFilter(int tex, int pname, int value) {
        if (pname == GL11.GL_TEXTURE_MIN_FILTER || pname == GL11.GL_TEXTURE_MAG_FILTER) {
            CHK_FILTER
                            .computeIfAbsent(tex, k -> new int[2])[
                            pname == GL11.GL_TEXTURE_MIN_FILTER ? 0 : 1] =
                    value;
        }
    }

    static void clearFilters() {
        CHK_FILTER.clear();
    }

    static void checkBuffer(int h) {
        if (h != 0 && !GL15.glIsBuffer(h)) {
            invalid("buffer " + h);
        }
    }

    static void checkTexture(int id) {
        if (id != 0 && !GL11.glIsTexture(id)) {
            invalid("texture " + id);
        }
    }

    static void invalid(String what) {
        invalids++;
        if (printed < 20) {
            printed++;
            Log.info(
                    "gfx",
                    "recorded-frame check: resource that no longer exists at" + " replay: " + what);
        }
    }

    /** End of a checked frame: recorded x replayed, command by command. */
    static void compareCheck() {
        checkedFrames++;
        int n = Math.max(REC.size(), REP.size());
        int bad = 0;
        for (int k = 0; k < n; k++) {
            String a = k < REC.size() ? REC.get(k) : "(nothing)";
            String b = k < REP.size() ? REP.get(k) : "(nothing)";
            if (!a.equals(b)) {
                bad++;
                if (printed < 20) {
                    printed++;
                    Log.info(
                            "gfx",
                            "recorded-frame check: command "
                                    + k
                                    + ": recorded '"
                                    + a
                                    + "', replayed '"
                                    + b
                                    + "'");
                }
            }
        }
        mismatches += bad;
        Log.info(
                "gfx",
                "recorded-frame check: "
                        + checkedFrames
                        + " frames checked, "
                        + REC.size()
                        + " commands in this one, "
                        + mismatches
                        + " differing and "
                        + invalids
                        + " nonexistent resources in total");
        REC.clear();
        REP.clear();
    }
}
