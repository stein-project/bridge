/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import static dev.xavier.stein.gfx.gl.SteinGlOpList.ints;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.longs;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.objs;
import static dev.xavier.stein.gfx.gl.SteinGlOps.*;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinRenderPass;

/**
 * Diagnostics of the gap before the translucent pass: the label of each replayed command (for the
 * per-command GPU timing, {@link SteinGlOpTiming}) and the dump of one frame's list.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlFrameDump {

    private SteinGlFrameDump() {}

    /**
     * {@code -Dsteinbridge.gfx.recordFrameDump=N}: on frame N (counted from startup) each replayed
     * command is printed, in order, with what matters about it (pass, upload size, texture and
     * parameter...).
     */
    static final long DUMP_AT = SteinGlOptions.RECORD_FRAME_DUMP;

    static boolean dumping;
    static final StringBuilder DUMP = new StringBuilder();

    /** After frame number {@code frames}: is the next one the dumped one? */
    static void afterFrame(long frames) {
        dumping = frames == DUMP_AT;
    }

    /** Header of a replayed submission in the dump. */
    static void begin(long submit) {
        DUMP.append("[gfx] frame list, submission ").append(submit).append(":\n");
    }

    /** End of the frame: the dump goes out. */
    static void print() {
        for (String line : DUMP.toString().split("\n")) {
            Log.info("gfx", line.substring("[gfx] ".length()));
        }
        DUMP.setLength(0);
    }

    static String size(long bytes) {
        return bytes <= 4096
                ? "up to 4 KB"
                : bytes <= 65536 ? "up to 64 KB" : bytes <= 1 << 20 ? "up to 1 MB" : "over 1 MB";
    }

    /**
     * The label of the already decoded command ({@code ip}/{@code lp} right after its arguments).
     */
    static String opLabel(int code, int ip, int lp) {
        return switch (code) {
            case PASS_BEGIN -> "pass (open and clear)";
            case PASS_END -> "end of pass (restore)";
            case UPLOAD -> "upload " + size(longs[lp - 2]);
            case COPY_BUFFER -> "buffer copy " + size(longs[lp - 1]);
            case TEX_PARAMI, TEX_PARAMF ->
                    "texture parameter 0x" + Integer.toHexString(ints[ip - 2]);
            default -> SteinGlOps.name(code);
        };
    }

    /** One line of the list ({@code ip}/{@code lp}/{@code op} at the start of the arguments). */
    static void dumpOp(int code, int ip, int lp, int op) {
        DUMP.append("[gfx]   ").append(SteinGlOps.name(code));
        switch (code) {
            case PASS_BEGIN ->
                    DUMP.append(" '")
                            .append(((SteinRenderPass) objs[op]).name)
                            .append("' fbo ")
                            .append(ints[ip]);
            case PIPELINE ->
                    DUMP.append(' ')
                            .append(
                                    objs[op] == null
                                            ? "null"
                                            : ((SteinPipeline) objs[op]).desc().name);
            case VERTEX ->
                    DUMP.append(" binding ")
                            .append(ints[ip])
                            .append(" buffer ")
                            .append(ints[ip + 1])
                            .append(" +")
                            .append(longs[lp]);
            case TEXTURE ->
                    DUMP.append(" slot ")
                            .append(ints[ip])
                            .append(" texture ")
                            .append(ints[ip + 1])
                            .append(ints[ip + 2] != 0 ? " (array)" : "")
                            .append(" sampler ")
                            .append(ints[ip + 3]);
            case DRAW_INDIRECT, DRAW_INDEXED_INDIRECT ->
                    DUMP.append(" buffer ")
                            .append(ints[ip])
                            .append(" +")
                            .append(longs[lp])
                            .append(" x")
                            .append(ints[ip + 1]);
            case TEX_PARAMI, TEX_PARAMF ->
                    DUMP.append(" texture ")
                            .append(ints[ip])
                            .append(" 0x")
                            .append(Integer.toHexString(ints[ip + 1]))
                            .append(" = 0x")
                            .append(Integer.toHexString(ints[ip + 2]));
            case UPLOAD ->
                    DUMP.append(" buffer ")
                            .append(ints[ip])
                            .append(" +")
                            .append(longs[lp])
                            .append(' ')
                            .append(longs[lp + 2])
                            .append(" bytes")
                            .append(longs[lp + 3] != 0 ? " (mapped)" : "");
            case COPY_BUFFER ->
                    DUMP.append(' ')
                            .append(ints[ip])
                            .append(" -> ")
                            .append(ints[ip + 1])
                            .append(' ')
                            .append(longs[lp + 2])
                            .append(" bytes");
            case GAME_CLEAR ->
                    DUMP.append(" mask 0x")
                            .append(Integer.toHexString(ints[ip]))
                            .append(" fbo ")
                            .append(ints[ip + 1]);
            default -> {}
        }
        DUMP.append('\n');
    }
}
