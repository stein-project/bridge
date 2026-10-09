/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

/**
 * The command codes of the recorded frame's list ({@link SteinGlFrame}) and their names.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlOps {

    private SteinGlOps() {}

    static final int PASS_BEGIN = 1;
    static final int PASS_END = 2;
    static final int PIPELINE = 3;
    static final int VERTEX = 4;
    static final int INDEX = 5;
    static final int TEXTURE = 6;
    static final int UNIFORMS = 7;
    static final int STORAGE = 8;
    static final int VIEWPORT = 9;
    static final int SCISSOR = 10;
    static final int DRAW = 11;
    static final int DRAW_INDEXED = 12;
    static final int DRAW_INDIRECT = 13;
    static final int DRAW_INDEXED_INDIRECT = 14;
    static final int DISPATCH = 15;
    static final int POLY_OFFSET = 16;
    static final int TEX_PARAMI = 17;
    static final int TEX_PARAMF = 18;
    static final int UPLOAD = 19;
    static final int COPY_BUFFER = 20;
    static final int COPY_TEXTURE = 21;
    static final int DELETE_BUFFER = 22;
    static final int FENCE = 23;
    static final int GAME_CLEAR = 24;
    static final String[] OP_NAMES = {
        "?",
        "pass",
        "end of pass",
        "pipeline",
        "vertex",
        "index",
        "texture",
        "uniforms",
        "storage",
        "viewport",
        "scissor",
        "draw",
        "indexed draw",
        "indirect",
        "indexed indirect",
        "compute",
        "offset",
        "texture parameter",
        "texture parameter (f)",
        "upload",
        "buffer copy",
        "texture copy",
        "buffer deleted",
        "fence",
        "game clear"
    };

    /** The name of the command, or its number if it has none. */
    static String name(int code) {
        return code < OP_NAMES.length ? OP_NAMES[code] : String.valueOf(code);
    }
}
