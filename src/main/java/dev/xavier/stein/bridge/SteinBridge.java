/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge;

/** Name and version of SteinBridge, for the F3 screen and the Stein Loader menu. */
public final class SteinBridge {

    public static final String NAME = "Stein Bridge";
    public static final String VERSION = "0.1.26";
    public static final String VENDOR = "X4vieer";

    private SteinBridge() {}

    /**
     * The version of the Bridge that is running. {@link #VERSION} is a constant, and javac copies
     * constants into the classes compiled against them: read through it, the Loader would see the
     * version it was built with, not the one installed.
     */
    public static String version() {
        return VERSION;
    }
}
