/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Ping and connect without waiting for reverse DNS. Mojang's Netty (com.mojang:netty, first on the
 * classpath, as in 1.8.9.json) checks the server against the block list on connect: the IP
 * (getHostAddress) and the name (getHostName). With a server given by IP, the game's InetAddress
 * has no name and getHostName does a reverse DNS lookup — 4.5 s on an IP with no reverse name
 * (measured on Java 8 and 25), on every ping of the server list and every time you join a server.
 *
 * <p>Patcher rules (redirects-opt.tsv): the InetAddress.getByName of the ping (OldServerPinger) and
 * of the connection (GuiConnecting) goes through {@link #getByName}, which returns the same address
 * already carrying the typed name. Mojang's check still happens: the IP and the name, which is now
 * the typed one (on an IP with no reverse name, getHostName used to return the IP itself after the
 * wait).
 */
public final class NetAddress {

    private NetAddress() {}

    /** In place of InetAddress.getByName(host): the same address, with the typed name kept. */
    public static Object getByName(Object host) throws UnknownHostException {
        String h = (String) host;
        if (h == null || h.isEmpty()) {
            return InetAddress.getByName(h);
        }
        InetAddress a = InetAddress.getByName(h);
        return InetAddress.getByAddress(h, a.getAddress());
    }
}
