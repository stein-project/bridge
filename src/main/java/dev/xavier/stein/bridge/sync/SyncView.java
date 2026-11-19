/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import dev.xavier.stein.bridge.core.Log;

/**
 * What the Stein Sync client shows: the server list badge and the hit marker. They are drawn with
 * the Bridge's design system (dev.xavier.stein.ui), which compiles after this code and goes through
 * the Patcher like a mod, so this side reaches it through {@link Screens}, found once by name.
 * Until then, or if it failed, nothing is drawn and the game goes on.
 */
public final class SyncView {

    /** The drawing side, implemented by {@code dev.xavier.stein.ui.SteinSyncScreens}. */
    public interface Screens {
        /** A hit this player made was confirmed: the marker flashes (red for a kill). */
        void hit(boolean killed);

        /** Over the HUD, after the listeners' overlay. */
        void overlay();

        /** A multiplayer server list entry drew itself: the badge of a Stein Sync server. */
        void serverListEntry(
                Object serverData, int x, int y, int listWidth, int mouseX, int mouseY);
    }

    private static final String IMPLEMENTATION = "dev.xavier.stein.ui.SteinSyncScreens";

    private static Screens screens;

    private SyncView() {}

    /** Finds the drawing side (once, from {@link SteinSync#install}). */
    static void load() {
        if (screens != null) {
            return;
        }
        try {
            screens =
                    (Screens)
                            Class.forName(IMPLEMENTATION, true, SyncView.class.getClassLoader())
                                    .getDeclaredConstructor()
                                    .newInstance();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            Log.info("steinsync", "Stein Sync badge and hit marker unavailable: " + e);
        }
    }

    static void hit(boolean killed) {
        Screens s = screens;
        if (s != null) {
            s.hit(killed);
        }
    }

    /** After the HUD (GameHooks overlay). No allocation. */
    public static void overlay() {
        Screens s = screens;
        if (s != null) {
            s.overlay();
        }
    }

    /** After a server list entry drew itself (GameHooks drawListEntry). */
    public static void serverListEntry(
            Object serverData, int x, int y, int listWidth, int mouseX, int mouseY) {
        Screens s = screens;
        if (s != null) {
            s.serverListEntry(serverData, x, y, listWidth, mouseX, mouseY);
        }
    }
}
