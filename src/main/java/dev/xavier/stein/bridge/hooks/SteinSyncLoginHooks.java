/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

/**
 * The listener of the Stein Sync server list and login hooks ({@link GameHooks#steinSyncLogin}),
 * installed by the Bridge's Stein Sync client (bridge.sync.SteinSyncJoin). Without one (Stein Sync
 * turned off with {@code -Dsteinbridge.noSteinSync}) the status JSON is not even searched for the
 * {@code steinSync} field and every handshake goes out exactly as the game writes it.
 */
public interface SteinSyncLoginHooks {

    /**
     * A server list ping of {@code serverData} finished: {@code offer} is what its status said
     * about Stein Sync, or null when it said nothing. On the pinger's network thread.
     *
     * @param serverData the ServerData that was pinged
     * @param offer the Stein Sync offer of its status, or null
     */
    void pinged(Object serverData, SteinSyncOffer offer);

    /**
     * The address a login handshake (C00Handshake with the LOGIN state) is about to carry, as
     * GuiConnecting wrote it: the listener returns it as it is, or with the Stein Sync marker
     * appended for a server whose status offered Stein Sync (stein-sync's docs/protocol.md section
     * 6). On GuiConnecting's connection thread.
     *
     * @param address the host in the handshake
     * @return the host to send instead (the same string when nothing changes)
     */
    String loginAddress(String address);

    /**
     * The address and port a login handshake goes to, as GuiConnecting wrote them (before {@link
     * #loginAddress}): a join without a server entry (a launcher's quick join) is known only by
     * these. On GuiConnecting's connection thread.
     *
     * @param address the host in the handshake
     * @param port its port
     */
    default void loginTarget(String address, int port) {}

    /**
     * The reason of a login Disconnect, as the server's JSON text (a refusal by a Stein Sync proxy
     * carries its Stein Sync offer there, stein-sync's docs/protocol.md section 6.2). On the
     * connection's network thread.
     *
     * @param json the reason as sent
     */
    default void loginRefused(String json) {}

    /**
     * The screen the game shows after a login ended with a Disconnect ("connection failed" with the
     * reason). On the game thread.
     *
     * @param screen the game's screen
     * @return the screen to show instead (a new join), or {@code screen}
     */
    default Object loginFailedScreen(Object screen) {
        return screen;
    }
}
