/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */
package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.core.Opt;

/**
 * The options of Stein's screens and fonts. They keep the {@code steinloader.*} keys they had when
 * the design system lived in the Stein Loader, so a player's launch arguments keep working.
 */
public final class UiOptions {

    /**
     * {@code -Dsteinloader.vanillaFont=true}: Stein's screens use Minecraft's font instead of
     * Stein's (default: off).
     */
    public static final boolean VANILLA_FONT = Opt.flag("steinloader.vanillaFont");

    /**
     * {@code -Dsteinloader.hudWeight=SemiBold|Bold|ExtraBold}: the weight of the HUD font, for
     * comparison (default {@code Bold}).
     */
    public static final String HUD_WEIGHT = Opt.text("steinloader.hudWeight", "Bold");

    /**
     * {@code -Dsteinloader.hudFontSize=N}: the body size of the HUD font, for comparison (default
     * 8.6).
     */
    public static final float HUD_FONT_SIZE = (float) Opt.decimal("steinloader.hudFontSize", 8.6);

    /**
     * {@code -Dsteinbridge.diag.hdrStep=N} (diagnostics): the video settings open straight into
     * step N (0-2) of the HDR calibration, even where HDR cannot be shown (the steps' layout;
     * default: unset).
     */
    public static final int DIAG_HDR_STEP = Opt.integer("steinbridge.diag.hdrStep", -1);

    private UiOptions() {}
}
