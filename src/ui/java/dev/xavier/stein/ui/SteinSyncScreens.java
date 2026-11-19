/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.sync.SyncView;

/**
 * The drawing side of the Bridge's Stein Sync client ({@link SyncView}): the hit marker over the
 * HUD and the badge on the multiplayer server list. Created once by name when Stein Sync starts.
 */
public final class SteinSyncScreens implements SyncView.Screens {

    /** Created by {@link SyncView} (by name: this code compiles after the Bridge's). */
    public SteinSyncScreens() {}

    @Override
    public void hit(boolean killed) {
        HitMarker.hit(killed);
    }

    @Override
    public void overlay() {
        HitMarker.draw();
    }

    @Override
    public void serverListEntry(
            Object serverData, int x, int y, int listWidth, int mouseX, int mouseY) {
        SteinSyncBadge.draw(serverData, x, y, listWidth, mouseX, mouseY);
    }
}
