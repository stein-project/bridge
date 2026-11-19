/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

/**
 * One QUIC connection to a Stein Sync proxy (stein-sync's docs/protocol.md section 7), whichever
 * transport runs it: {@link SteinSyncKwik} (Kwik, in Java: the default) or {@link
 * SteinSyncNativeLink} (the Bridge's native library, kept for comparison). The game's own Netty
 * connection reaches it through {@link #gameAddress}; the Ticks leave as datagrams through {@link
 * #sendDatagram}.
 */
interface SteinSyncLink {

    /**
     * Where the game's connection goes: a loopback InetSocketAddress (native) or a Netty
     * LocalAddress inside this JVM (Kwik).
     */
    Object gameAddress();

    /**
     * Sends one datagram ({@code len} bytes of {@code datagram}, which the caller may reuse once
     * this returns). Never blocks.
     *
     * @return false when it was not sent (the caller sends the Tick on the stream instead)
     */
    boolean sendDatagram(byte[] datagram, int len);

    /**
     * The counters into {@code out} (6 values): round trip (microseconds), QUIC packets sent, QUIC
     * packets lost, datagrams sent, datagrams refused, 1 if closed.
     *
     * @return false when there are none
     */
    boolean stats(long[] out);

    /** Moves the connection to a new local UDP port, as an address change would (diagnostics). */
    boolean rebind();

    /** Closes the connection (safe to call twice). */
    void close();

    /** The transport's name for the log: {@code kwik} or {@code native}. */
    String kind();
}
