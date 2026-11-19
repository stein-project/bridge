/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import java.util.Locale;

/**
 * Text rules of Stein Sync that need no game: the login marker (stein-sync's docs/protocol.md
 * section 6), the key a server's address is remembered by, and server text made plain for a
 * tooltip. Plain values only, so they are tested without the game.
 */
final class SteinSyncAddress {

    /** The token after the NUL, followed by the client's Stein Sync version. */
    static final String MARKER_PREFIX = "SteinSync";

    /** The longest handshake address a server reads. */
    static final int MAX_HOST_CHARS = 255;

    /** The port a typed address without one goes to. */
    private static final String DEFAULT_PORT = ":25565";

    private SteinSyncAddress() {}

    /**
     * {@code host} with the Stein Sync marker of {@code version} appended ({@code
     * "<host>\0SteinSync1"}), or {@code host} itself when the result would not fit a handshake.
     */
    static String marked(String host, int version) {
        String out = host + '\0' + MARKER_PREFIX + version;
        return out.length() <= MAX_HOST_CHARS ? out : host;
    }

    /**
     * The key a typed server address is remembered by: trimmed, lower case, without the default
     * port, so "Play.Example.org" and "play.example.org:25565" are the same server.
     */
    static String key(String address) {
        if (address == null) {
            return "";
        }
        String k = address.trim().toLowerCase(Locale.ROOT);
        return k.endsWith(DEFAULT_PORT) ? k.substring(0, k.length() - DEFAULT_PORT.length()) : k;
    }

    /**
     * Server text made plain: section-sign formatting codes (the sign and the code after it) and
     * control characters taken out, so a server cannot colour, hide or break the lines of what the
     * client shows in its own words.
     */
    static String plain(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++;
            } else if (!Character.isISOControl(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
