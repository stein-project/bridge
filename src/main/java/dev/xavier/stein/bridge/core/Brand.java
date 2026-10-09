/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.core;

/**
 * Client brand, in place of the "vanilla" of ClientBrandRetriever (patcher/redirects-mc.tsv, body
 * mode).
 *
 * <p>Like Forge's "fml,forge": it shows in F3 ("Minecraft 1.8.9 (1.8.9/SteinLoader)"), goes to the
 * server in the MC|Brand packet on joining and makes the crash report say the client is modified.
 */
public final class Brand {

    public static final String NAME = "SteinLoader";

    private Brand() {}

    public static Object clientModName() {
        return NAME;
    }
}
