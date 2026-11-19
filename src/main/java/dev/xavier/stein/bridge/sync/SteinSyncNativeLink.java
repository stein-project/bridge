/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import dev.xavier.stein.bridge.net.SteinQuic;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * A QUIC connection through the Bridge's native library ({@link SteinQuic}, quinn behind a loopback
 * port): the transport before Kwik, kept so the two can be compared on the bench ({@code
 * -Dsteinbridge.quicTransport=native}).
 */
final class SteinSyncNativeLink implements SteinSyncLink {

    private final long handle;
    private final int localPort;

    SteinSyncNativeLink(long handle, int localPort) {
        this.handle = handle;
        this.localPort = localPort;
    }

    @Override
    public Object gameAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort);
    }

    @Override
    public boolean sendDatagram(byte[] datagram, int len) {
        return SteinQuic.sendDatagram(handle, datagram, len);
    }

    @Override
    public boolean stats(long[] out) {
        return SteinQuic.stats(handle, out);
    }

    @Override
    public boolean rebind() {
        return SteinQuic.rebind(handle);
    }

    @Override
    public void close() {
        SteinQuic.close(handle);
    }

    @Override
    public String kind() {
        return "native";
    }
}
