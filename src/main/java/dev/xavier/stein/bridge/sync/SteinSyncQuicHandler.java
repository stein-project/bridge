/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import dev.xavier.stein.bridge.core.Log;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C17PacketCustomPayload;

/**
 * The game's packets on a QUIC connection (stein-sync's docs/protocol.md sections 7.3 and 7.8), in
 * the connection's pipeline right before the game's packet handler, so it sees every packet the
 * game writes, in order, on the connection's own network thread.
 *
 * <ul>
 *   <li>Every packet after the Accept is counted: the count is each Tick's {@code after}, which
 *       lets the proxy keep a Tick behind the packets the game sent before it.
 *   <li>A Tick leaves the stream: it goes as a datagram (with the Ticks before it, so a lost
 *       datagram is covered by the next), and is not written to the stream.
 *   <li>The stream packets written since the Tick before (an attack, a dig, a placement, a held
 *       item change, a swing) ride in that datagram too, as copies: when one of them is lost on the
 *       stream, the proxy forwards its copy and the Tick does not wait for QUIC to retransmit it. A
 *       keep alive answer and the Fence are not copied: the order does not depend on them.
 *   <li>Before the first stream packet after such a Tick goes a Fence with the Tick's number: the
 *       proxy holds that packet until the Tick is in, so nothing the game sent after a Tick reaches
 *       the server before it.
 * </ul>
 *
 * Everything else passes untouched. Without the Accept (a proxy that negotiated nothing) the
 * connection is a plain byte stream over QUIC: nothing is counted or diverted. No allocation per
 * packet beyond the game's own: the datagram, the copies and the encoding buffer are reused.
 */
final class SteinSyncQuicHandler extends ChannelDuplexHandler {

    private final SteinSyncLink link;

    /** The Accept went out: packets count from here, and Ticks may leave the stream. */
    private boolean counting;

    /** Packets written to the stream after the Accept. */
    private long written;

    /** The number of the last Tick sent as a datagram since the last stream packet, 0 for none. */
    private int fence;

    /** A Tick's bytes, reused (Ticks are written one at a time, on this thread). */
    private final byte[] tick = new byte[SteinSyncTick.MAX_BYTES];

    private final SteinSyncDatagram datagrams = new SteinSyncDatagram();
    private final byte[] datagram = new byte[SteinSyncDatagram.BUFFER];

    /** A stream packet's encoding, for its copy (id and body). */
    private final ByteBuf encoded = Unpooled.buffer(SteinSyncDatagram.MAX_COPY_LEN * 2);

    private final PacketBuffer encodedBuf = new PacketBuffer(encoded);
    private final byte[] copy = new byte[SteinSyncDatagram.MAX_COPY_LEN];

    /** Ticks diverted, for the diagnostic rebind and the end-of-session line. */
    private long ticks;

    /** Ticks that could not leave as datagrams and went on the stream instead. */
    private long onStream;

    /** Stream packets copied into a datagram. */
    private long copies;

    SteinSyncQuicHandler(SteinSyncLink link) {
        this.link = link;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
        if (msg instanceof C17PacketCustomPayload) {
            C17PacketCustomPayload custom = (C17PacketCustomPayload) msg;
            if (SteinSyncCodec.CHANNEL.equals(custom.getChannelName())) {
                PacketBuffer data = custom.getBufferData();
                int len = data.readableBytes();
                int type = len > 0 ? data.getUnsignedByte(data.readerIndex()) : -1;
                if (type == SteinSyncCodec.TICK && counting && len <= tick.length) {
                    data.getBytes(data.readerIndex(), tick, 0, len);
                    int n = datagrams.push(written, tick, len, datagram);
                    if (n > 0 && link.sendDatagram(datagram, n)) {
                        fence = SteinSyncCodec.readVarIntAt(tick, 1, len);
                        ticks++;
                        rebindIfAsked();
                        promise.setSuccess();
                        return;
                    }
                    onStream++;
                }
                if (type == SteinSyncCodec.ACCEPT && !counting) {
                    // The Accept itself is not counted: the proxy counts from the packet after it.
                    ctx.write(msg, promise);
                    counting = true;
                    datagrams.openWindow(0);
                    return;
                }
            }
        }
        if (counting) {
            if (fence != 0) {
                ctx.write(fencePacket(fence));
                written++;
                fence = 0;
            }
            written++;
            if (!(msg instanceof C00PacketKeepAlive)) {
                copy(msg);
            }
        }
        ctx.write(msg, promise);
    }

    /**
     * Copies stream packet number {@link #written} into the open window, as it goes on the wire.
     */
    private void copy(Object msg) {
        if (!(msg instanceof Packet)) {
            datagrams.incomplete();
            return;
        }
        try {
            encoded.clear();
            Integer id =
                    EnumConnectionState.PLAY.getPacketId(
                            EnumPacketDirection.SERVERBOUND, (Packet<?>) msg);
            if (id == null) {
                datagrams.incomplete();
                return;
            }
            encodedBuf.writeVarIntToBuffer(id);
            if (msg instanceof C17PacketCustomPayload) {
                // Its writePacketData would consume the payload the encoder still has to write.
                C17PacketCustomPayload custom = (C17PacketCustomPayload) msg;
                PacketBuffer data = custom.getBufferData();
                encodedBuf.writeString(custom.getChannelName());
                encodedBuf.writeBytes(data, data.readerIndex(), data.readableBytes());
            } else {
                ((Packet<?>) msg).writePacketData(encodedBuf);
            }
            int len = encoded.readableBytes();
            if (len > copy.length) {
                datagrams.incomplete();
                return;
            }
            encoded.getBytes(0, copy, 0, len);
            datagrams.copy(written, copy, len);
            copies++;
        } catch (Exception e) {
            datagrams.incomplete();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        long[] stats = new long[6];
        if (link.stats(stats)) {
            Log.info(
                    "steinsync",
                    "Stein Sync QUIC ("
                            + link.kind()
                            + "): ended; rtt "
                            + stats[0] / 1000
                            + " ms, packets "
                            + stats[1]
                            + " sent, "
                            + stats[2]
                            + " lost; Ticks "
                            + ticks
                            + " as datagrams, "
                            + onStream
                            + " on the stream; "
                            + copies
                            + " stream packets copied");
        }
        link.close();
        encoded.release();
        super.channelInactive(ctx);
    }

    /**
     * The Fence (type 11): the Ticks up to {@code tick} left before what follows. One small packet
     * per stream packet that follows a Tick (an attack, a click, a keep alive answer), not per
     * tick.
     */
    private static C17PacketCustomPayload fencePacket(int tick) {
        return new C17PacketCustomPayload(
                SteinSyncCodec.CHANNEL,
                new PacketBuffer(Unpooled.wrappedBuffer(SteinSyncCodec.writeFence(tick))));
    }

    /** {@code -Dsteinbridge.diag.quicRebindAtSec}: one move to a new local port, for tests. */
    private void rebindIfAsked() {
        int at = SteinSyncOptions.DIAG_QUIC_REBIND_AT_SEC;
        if (at > 0 && ticks == at * 20L) {
            Log.info("steinsync", "Stein Sync QUIC: diagnostic rebind " + link.rebind());
        }
    }
}
