/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import dev.xavier.stein.bridge.hooks.GameHooks;

/**
 * The Stein Sync messages that become events: HitConfirm (the hit marker, then {@link
 * GameHooks#steinSyncHit}), Damaged ({@link GameHooks#steinSyncDamaged}) and CombatSeen ({@link
 * GameHooks#steinSyncCombatSeen}). The game's listeners get them in order on the game thread; the
 * Loader hands them to its mods ({@code onHitConfirmed}, {@code onDamaged}, {@code onCombatSeen}).
 */
final class SteinSyncEvents {

    private SteinSyncEvents() {}

    /** A message {@link SteinSync#read} read, by its type. */
    static void dispatch(int type) {
        switch (type) {
            case SteinSyncCodec.HIT_CONFIRM:
                SteinSyncCodec.HitConfirm hit = SteinSync.HIT;
                SyncView.hit(hit.killed);
                GameHooks.steinSyncHit(hit.target, hit.damage, hit.critical, hit.killed, hit.tick);
                break;
            case SteinSyncCodec.DAMAGED:
                SteinSyncCodec.Damaged d = SteinSync.DAMAGED;
                GameHooks.steinSyncDamaged(d.attacker, d.damage, d.cause, d.critical, d.killed);
                break;
            case SteinSyncCodec.COMBAT_SEEN:
                SteinSyncCodec.CombatSeen s = SteinSync.SEEN;
                GameHooks.steinSyncCombatSeen(s.attacker, s.target, s.critical, s.killed);
                break;
            default:
                break;
        }
    }
}
