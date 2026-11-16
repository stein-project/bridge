/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

/**
 * Keeps the session's access token out of the logs.
 *
 * <p>1.8.9's Minecraft constructor logs "(Session ID is token:&lt;access token&gt;:&lt;uuid&gt;)",
 * and that line lands in latest.log, in the launcher's game.log and in Stein's session log — the
 * very files players send along with a report (a tester's log of 15/11/2026 carried their token).
 * Anyone holding it can join servers as that account until it expires. The constructor's only call
 * to Session.getSessionID, the one that builds the line, now gets a placeholder (rule in
 * redirects-mc.tsv); the session itself, and what the game sends to Mojang and to servers, is
 * untouched.
 */
public final class TokenLog {
    private TokenLog() {}

    /** In place of session.getSessionID() in the Minecraft constructor's log line. */
    public static Object hidden(Object session) {
        return "token:<hidden>";
    }
}
