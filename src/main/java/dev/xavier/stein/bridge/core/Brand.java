/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
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
