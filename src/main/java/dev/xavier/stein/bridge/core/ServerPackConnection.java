/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The connection the game gets for a server resource pack download (see {@link ServerPacks}): the
 * same HTTP request, refused when the URL, or any redirect along the way, leads to the player's own
 * machine or network.
 *
 * <p>Without this, a server could point the pack at {@code 127.0.0.1}, the router or any other
 * address only the player can reach, and tell from the "failed" or "loaded" answer whether the
 * service is there. A refused request fails like an unreachable host. Redirects are followed here,
 * one hop at a time, so a public URL cannot bounce the request to a local one; the game only reads
 * the headers it set, the content length and the body, which come from the last hop.
 *
 * <p>Local packs still work when the server itself is local (singleplayer opened to LAN, a server
 * on the same machine or network): the server is then already inside that network.
 */
final class ServerPackConnection extends HttpURLConnection {

    private static final int MAX_REDIRECTS = 8;

    private final Proxy proxy;
    private final boolean localAllowed;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private HttpURLConnection real;

    /** {@code localAllowed}: the server itself is local, so local pack URLs are fine. */
    ServerPackConnection(URL url, Proxy proxy, boolean localAllowed) {
        super(url);
        this.proxy = proxy;
        this.localAllowed = localAllowed;
    }

    @Override
    public void setRequestProperty(String key, String value) {
        headers.put(key, value);
    }

    @Override
    public void connect() throws IOException {
        if (real != null) {
            return;
        }
        URL u = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String protocol = u.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                throw new IOException("server pack: not http(s): " + protocol);
            }
            if (!localAllowed && local(u.getHost())) {
                throw new IOException("server pack: local address refused: " + u.getHost());
            }
            URLConnection c = proxy == null ? u.openConnection() : u.openConnection(proxy);
            HttpURLConnection h = (HttpURLConnection) c;
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(getConnectTimeout());
            h.setReadTimeout(getReadTimeout());
            for (Map.Entry<String, String> e : headers.entrySet()) {
                h.setRequestProperty(e.getKey(), e.getValue());
            }
            int code = h.getResponseCode();
            String location = h.getHeaderField("Location");
            if (code >= 300 && code < 400 && location != null) {
                h.disconnect();
                u = new URL(u, location);
                continue;
            }
            real = h;
            connected = true;
            return;
        }
        throw new IOException("server pack: too many redirects");
    }

    @Override
    public InputStream getInputStream() throws IOException {
        connect();
        return real.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return real == null ? null : real.getErrorStream();
    }

    @Override
    public int getResponseCode() throws IOException {
        connect();
        return real.getResponseCode();
    }

    @Override
    public String getHeaderField(String name) {
        return real == null ? null : real.getHeaderField(name);
    }

    @Override
    public long getContentLengthLong() {
        return real == null ? -1 : real.getContentLengthLong();
    }

    @Override
    public int getContentLength() {
        return real == null ? -1 : real.getContentLength();
    }

    @Override
    public void disconnect() {
        if (real != null) {
            real.disconnect();
        }
    }

    @Override
    public boolean usingProxy() {
        return real != null && real.usingProxy();
    }

    /** True if any address the host resolves to is on this machine or its networks. */
    static boolean local(String host) throws IOException {
        if (host == null || host.isEmpty()) {
            return true;
        }
        for (InetAddress a : InetAddress.getAllByName(host)) {
            if (local(a)) {
                return true;
            }
        }
        return false;
    }

    static boolean local(InetAddress a) {
        if (a.isAnyLocalAddress()
                || a.isLoopbackAddress()
                || a.isLinkLocalAddress()
                || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 16) {
            return (b[0] & 0xfe) == 0xfc; // fc00::/7, unique local
        }
        return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10, carrier NAT
    }
}
