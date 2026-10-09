/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.hooks;

import dev.xavier.stein.bridge.core.Opt;

/** The {@code -D} options of the game hooks. */
public final class HookOptions {

    private HookOptions() {}

    /**
     * {@code -Dsteinbridge.hooks.debug=true}: logs every key, mouse and attack event the hooks
     * deliver (default: off).
     */
    static final boolean DEBUG = Opt.flag("steinbridge.hooks.debug");

    /**
     * {@code -Dsteinbridge.diag.noGhost=true}: does not ask the mods whether an entity is a ghost,
     * only for A/B measuring; the old name {@code -Dsteinbridge.diag.semFantasma=true} still works
     * (default: off).
     */
    static final boolean SKIP_GHOST_QUERY =
            Opt.flag("steinbridge.diag.noGhost") || Opt.flag("steinbridge.diag.semFantasma");
}
