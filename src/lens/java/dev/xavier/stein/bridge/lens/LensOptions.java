/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import dev.xavier.stein.bridge.core.Opt;

/** The {@code -D} options of the Lens package. */
final class LensOptions {

    private LensOptions() {}

    /**
     * {@code -Dsteinlens.config=file}: reads another configuration file instead of
     * config/steinlens.json (default: unset).
     */
    static final String CONFIG = Opt.text("steinlens.config", null);

    /**
     * {@code -Dsteinlens.diag.presetAtSec=S:P}: test, applies graphics slider position P after S
     * seconds of play (default: unset).
     */
    static final String DIAG_PRESET_AT_SEC = Opt.text("steinlens.diag.presetAtSec", null);

    /**
     * {@code -Dsteinlens.customItems=true|false}: overrides the file's custom items setting without
     * saving (default: unset, the file decides).
     */
    static final String CUSTOM_ITEMS = Opt.text("steinlens.customItems", null);

    /**
     * {@code -Dsteinlens.connectedTextures=true|false}: overrides the file's connected textures
     * setting without saving (default: unset, the file decides).
     */
    static final String CONNECTED_TEXTURES = Opt.text("steinlens.connectedTextures", null);

    /**
     * {@code -Dsteinlens.bloom=0|1|2}: overrides the file's bloom (off, low, high) without saving
     * (default: unset, the file decides).
     */
    static final int BLOOM = Opt.integer("steinlens.bloom", -1);

    /**
     * {@code -Dsteinlens.smoothEdges=true|false}: overrides the file's edge smoothing setting
     * without saving (default: unset, the file decides).
     */
    static final String SMOOTH_EDGES = Opt.text("steinlens.smoothEdges", null);

    /**
     * {@code -Dsteinlens.hdr=true|false}: overrides the file's HDR output setting without saving
     * (default: unset, the file decides).
     */
    static final String HDR = Opt.text("steinlens.hdr", null);

    /**
     * {@code -Dsteinlens.debug=true}: zoom state (every 2 s and on every change) and custom items
     * lookups, recomputed choices and enchantment version steps (every 2 s) in the log (default:
     * off).
     */
    static final boolean DEBUG = Opt.flag("steinlens.debug");
}
