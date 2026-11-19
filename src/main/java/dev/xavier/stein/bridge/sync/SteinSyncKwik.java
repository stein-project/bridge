/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import dev.xavier.stein.bridge.core.Log;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;

import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.Statistics;
import tech.kwik.core.impl.QuicClientConnectionImpl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.X509TrustManager;

/**
 * A QUIC connection to a Stein Sync proxy with Kwik (pure Java, LGPL, loaded from its own jars next
 * to the Bridge): the default transport of stein-sync's docs/protocol.md section 7. No native
 * library to build, sign and ship per platform, and the connection lives on the Java 25 the game
 * already runs on.
 *
 * <p>The game's Netty 4.0 is not touched: its connection is a Netty LocalChannel to a {@link
 * LocalServerChannel} of this link, inside the JVM (no loopback port another process could take).
 * Bytes the game writes go to the QUIC stream; bytes the stream delivers go to the game, from a
 * reader thread of this link. The certificate is pinned by its SHA-256, as the server list entry
 * announced it; the TLS 1.3 handshake proves the server holds its key.
 */
final class SteinSyncKwik implements SteinSyncLink {

    /** The application protocol of the transport (section 7.2). */
    private static final String ALPN = "stein-sync/1";

    /**
     * The TLS server name (section 7.2): the proxy's certificate is made for it, whatever address
     * the player typed. The pin decides the trust; the name only has to match for Kwik's TLS.
     */
    private static final String SERVER_NAME = "stein-sync";

    /** The game's own read timeout: the idle limit of the connection. */
    private static final Duration IDLE = Duration.ofSeconds(30);

    /** Seconds between pings when nothing else goes out, so NAT mappings stay up (7.4). */
    private static final int KEEP_ALIVE_SECONDS = 5;

    /** Stream bytes read per hand-over to the game. */
    private static final int READ_CHUNK = 16 * 1024;

    /**
     * How long the game has to open its local channel to a new link: past that the link was never
     * used (the connection attempt went another way) and is closed, not left pinging.
     */
    private static final long GAME_OPEN_SECONDS = 15;

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private final QuicClientConnection conn;
    private final QuicStream stream;
    private final OutputStream toServer;
    private final LocalAddress address;
    private final Channel server;

    /**
     * This link's own event loop thread: a write to a stalled QUIC stream blocks it, and must not
     * hold up the next link (a rejoin while the old proxy is gone).
     */
    private final EventLoopGroup loop = new LocalEventLoopGroup(1);

    private volatile Channel game;
    private volatile boolean closed;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong datagrams = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    /**
     * Connects to the proxy at {@code ip} on the first of {@code ports} that completes the
     * handshake with the pinned certificate, within {@code attemptMs} each.
     *
     * @param outcome receives, on failure, {@link SteinSyncCodec#QUIC_TIMEOUT} or {@link
     *     SteinSyncCodec#QUIC_HANDSHAKE_FAILED}
     * @return the link, or null
     */
    static SteinSyncKwik connect(
            InetAddress ip, int[] ports, byte[] pin, int attemptMs, int[] outcome) {
        outcome[0] = SteinSyncCodec.QUIC_TIMEOUT;
        for (int port : ports) {
            QuicClientConnection conn = null;
            try {
                conn =
                        QuicClientConnection.newBuilder()
                                .host(SERVER_NAME)
                                // The address to send to, apart from the name above.
                                .proxy(ip.getHostAddress())
                                .port(port)
                                .applicationProtocol(ALPN)
                                .connectTimeout(Duration.ofMillis(attemptMs))
                                .maxIdleTimeout(IDLE)
                                .customTrustManager(new Pinned(pin))
                                .enableDatagramExtension()
                                .build();
                conn.connect();
                if (!conn.canSendDatagram()) {
                    // Ticks need datagrams: without them this is no better than TCP.
                    conn.close();
                    outcome[0] = SteinSyncCodec.QUIC_HANDSHAKE_FAILED;
                    continue;
                }
                conn.keepAlive(KEEP_ALIVE_SECONDS);
                QuicStream stream = conn.createStream(true);
                return new SteinSyncKwik(conn, stream);
            } catch (IOException | RuntimeException e) {
                if (conn != null) {
                    conn.close();
                }
                boolean timeout =
                        e instanceof SocketTimeoutException
                                || String.valueOf(e.getMessage())
                                        .toLowerCase()
                                        .contains("timed out");
                if (!timeout) {
                    outcome[0] = SteinSyncCodec.QUIC_HANDSHAKE_FAILED;
                }
                Log.info("steinsync", "Stein Sync QUIC (Kwik): port " + port + " failed: " + e);
            }
        }
        return null;
    }

    private SteinSyncKwik(QuicClientConnection conn, QuicStream stream) throws IOException {
        this.conn = conn;
        this.stream = stream;
        this.toServer = stream.getOutputStream();
        this.address = new LocalAddress("stein-quic-" + NEXT_ID.getAndIncrement());
        try {
            this.server =
                    new ServerBootstrap()
                            .group(loop)
                            .channel(LocalServerChannel.class)
                            .childHandler(
                                    new ChannelInitializer<LocalChannel>() {
                                        @Override
                                        protected void initChannel(LocalChannel ch) {
                                            ch.pipeline().addLast(new ToServer());
                                        }
                                    })
                            .bind(address)
                            .sync()
                            .channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            conn.close();
            loop.shutdownGracefully();
            throw new IOException("interrupted while binding the local channel", e);
        } catch (RuntimeException e) {
            // Any other bind failure (a ChannelException): the loop's thread is not a daemon and
            // was already started, so it must not be left behind with the connection.
            conn.close();
            loop.shutdownGracefully();
            throw new IOException("could not bind the local channel", e);
        }
        loop.schedule(
                () -> {
                    if (game == null) {
                        close();
                    }
                },
                GAME_OPEN_SECONDS,
                TimeUnit.SECONDS);
    }

    @Override
    public Object gameAddress() {
        return address;
    }

    @Override
    public boolean sendDatagram(byte[] datagram, int len) {
        if (closed) {
            return false;
        }
        try {
            // Kwik's only datagram call takes a byte[] of the exact length and keeps it until the
            // packet goes out, while the caller reuses its buffer: one copy per datagram is the
            // least this API allows (quinn's takes one too).
            conn.sendDatagram(Arrays.copyOf(datagram, len));
            datagrams.incrementAndGet();
            return true;
        } catch (RuntimeException e) {
            refused.incrementAndGet();
            return false;
        }
    }

    @Override
    public boolean stats(long[] out) {
        if (out.length < 6) {
            return false;
        }
        Statistics s = conn.getStats();
        out[0] = s.smoothedRtt() * 1000L;
        out[1] = s.packetsSent();
        out[2] = s.lostPackets();
        out[3] = datagrams.get();
        out[4] = refused.get();
        out[5] = closed || !conn.isConnected() ? 1 : 0;
        return true;
    }

    @Override
    public boolean rebind() {
        if (!(conn instanceof QuicClientConnectionImpl)) {
            return false;
        }
        ((QuicClientConnectionImpl) conn).changeAddress();
        return true;
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        closed = true;
        conn.close();
        server.close();
        Channel g = game;
        if (g != null) {
            g.close();
        }
        loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    @Override
    public String kind() {
        return "kwik";
    }

    /**
     * The game's bytes, from its local channel to the QUIC stream; and the reader the other way.
     */
    private final class ToServer extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            game = ctx.channel();
            Thread reader = new Thread(this::readServer, "Stein Sync QUIC reader");
            reader.setDaemon(true);
            reader.start();
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                // On this link's own event loop thread: a blocking write here waits for QUIC's flow
                // control only, and holds up nothing but this connection's uplink.
                buf.readBytes(toServer, buf.readableBytes());
                toServer.flush();
            } catch (IOException e) {
                ctx.close();
            } finally {
                buf.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            SteinSyncKwik.this.close();
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }

        /** The stream's bytes to the game, until either side ends. */
        private void readServer() {
            byte[] chunk = new byte[READ_CHUNK];
            try (InputStream in = stream.getInputStream()) {
                for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                    Channel g = game;
                    if (g == null || !g.isActive()) {
                        break;
                    }
                    if (n > 0) {
                        // The channel's allocator (pooled when the game's Netty pools), not a
                        // fresh heap buffer per read.
                        g.writeAndFlush(g.alloc().buffer(n).writeBytes(chunk, 0, n));
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    Log.info("steinsync", "Stein Sync QUIC (Kwik): stream ended: " + e);
                }
            } finally {
                Channel g = game;
                if (g != null) {
                    g.close();
                }
            }
        }
    }

    /** Trusts exactly the certificate whose SHA-256 the server list entry announced. */
    private static final class Pinned implements X509TrustManager {
        private final byte[] pin;

        Pinned(byte[] pin) {
            this.pin = pin.clone();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("no certificate");
            }
            try {
                byte[] got = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                if (!MessageDigest.isEqual(got, pin)) {
                    throw new CertificateException("not the pinned certificate");
                }
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new CertificateException(e);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
