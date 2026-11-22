/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Stein Sync client's own parts without the game: the Tick encoding (SteinSyncTick, read back
 * field by field as the proxy reads it) and the holding of block changes until their BlockAck
 * (BlockHold). Exits 0 when all is well, 1 on failure. Runs on every Bridge build.
 */
public final class SteinSyncClientTest {

    private static int failures;

    public static void main(String[] args) {
        tickWithoutAttacks();
        tickWithAttacks();
        attackMessage();
        keys();
        holdUntilAck();
        holdTimeout();
        holdOverlap();
        holdFull();
        packing();
        datagramWindows();
        modRoute();
        quicTiming();
        try {
            failures += dev.xavier.stein.bridge.net.SteinQuicLibraryTest.run();
        } catch (java.io.IOException e) {
            failures++;
            System.out.println("FAILED native QUIC library: " + e);
        }
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("SteinSyncClientTest: ok");
    }

    private static void tickWithoutAttacks() {
        SteinSyncTick t = new SteinSyncTick();
        t.x = 100.5;
        t.y = 64.0;
        t.z = -3.25;
        t.yaw = -721.5F;
        t.pitch = 12.0F;
        t.flags = SteinSyncTick.ON_GROUND | SteinSyncTick.SPRINTING;
        t.forward = 1;
        t.strafe = -1;
        byte[] out = new byte[SteinSyncTick.MAX_BYTES];
        int n = t.write(300, out);
        check("Tick without attacks is 41 bytes for tick 300", "41", "" + n);
        ByteBuffer in = ByteBuffer.wrap(out, 0, n);
        check("type", "7", "" + in.get());
        check("tick", "300", "" + varInt(in));
        check(
                "x y z",
                "100.5 64.0 -3.25",
                in.getDouble() + " " + in.getDouble() + " " + in.getDouble());
        check("yaw pitch", "-721.5 12.0", in.getFloat() + " " + in.getFloat());
        check("flags", "5", "" + in.get());
        check("keys", "1 -1", in.get() + " " + in.get());
        check("attack count", "0", "" + varInt(in));
        check("life of the first player entity", "0", "" + in.get());
        check("view: first person", "0", "" + in.get());
        check("no applied count when not known", "0", "" + in.remaining());
        t.life = 255;
        t.view = 2;
        t.applied = 300;
        n = t.write(301, out);
        check(
                "life, view, then the applied VarLong",
                "255 2 172 2",
                (out[n - 4] & 0xFF)
                        + " "
                        + out[n - 3]
                        + " "
                        + (out[n - 2] & 0xFF)
                        + " "
                        + out[n - 1]);
        t.applied = Long.MAX_VALUE;
        n = t.write(301, out);
        check("the largest applied count fits", "true", "" + (n <= SteinSyncTick.MAX_BYTES));
        t.life = 0;
        t.view = 0;
        t.applied = -1;
        t.flags = SteinSyncTick.SPRINT_KEY;
        t.write(1, out);
        check("the sprint key is bit 7", "-128", "" + out[1 + 1 + 24 + 8]);
        t.flags = SteinSyncTick.RIDING;
        t.write(1, out);
        check("riding is bit 5", "32", "" + out[1 + 1 + 24 + 8]);
        t.flags = SteinSyncTick.CAMERA_AWAY;
        t.write(1, out);
        check("camera away is bit 6", "64", "" + out[1 + 1 + 24 + 8]);
    }

    private static void tickWithAttacks() {
        SteinSyncTick t = new SteinSyncTick();
        for (int i = 0; i < SteinSyncTick.MAX_ATTACKS; i++) {
            check("attack " + i + " fits", "true", "" + t.addAttack(1000 + i, 90F, -5F, i, 65, -i));
        }
        check("a fifth attack does not", "false", "" + t.addAttack(1, 0F, 0F, 0, 0, 0));
        byte[] out = new byte[SteinSyncTick.MAX_BYTES];
        int n = t.write(Integer.MAX_VALUE, out);
        check("longest Tick fits its buffer", "true", "" + (n <= SteinSyncTick.MAX_BYTES));
        ByteBuffer in = ByteBuffer.wrap(out, 0, n);
        in.get();
        check("largest tick number", "" + Integer.MAX_VALUE, "" + varInt(in));
        in.position(in.position() + 24 + 8 + 3);
        check("four attacks", "4", "" + varInt(in));
        List<String> attacks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            attacks.add(
                    varInt(in)
                            + " "
                            + in.getFloat()
                            + " "
                            + in.getFloat()
                            + " "
                            + in.getDouble()
                            + " "
                            + in.getDouble()
                            + " "
                            + in.getDouble());
        }
        check(
                "attacks in order",
                "[1000 90.0 -5.0 0.0 65.0 0.0, 1001 90.0 -5.0 1.0 65.0 -1.0,"
                        + " 1002 90.0 -5.0 2.0 65.0 -2.0, 1003 90.0 -5.0 3.0 65.0 -3.0]",
                attacks.toString());
        check("the life after the attacks", "0", "" + in.get());
        check("the view after it", "0", "" + in.get());
        check("nothing after the view", "0", "" + in.remaining());
        t.clearAttacks();
        n = t.write(2, out);
        check("attacks go with one Tick only", "0", "" + out[n - 3]);
        check("negative target id is a 5-byte VarInt", "true", "" + t.addAttack(-1, 0, 0, 0, 0, 0));
        n = t.write(2, out);
        check("Tick with a negative target", "" + (38 + 5 + 8 + 24 + 2), "" + n);
    }

    private static void attackMessage() {
        byte[] out = new byte[SteinSyncTick.MAX_BYTES];
        int n = SteinSyncTick.writeAttack(300, 1000, 12.5F, -3F, 1, 65, -7.5, out);
        // type, tick 300 (2-byte VarInt), target 1000 (2 bytes), two floats, three doubles.
        check("attack length", "" + (1 + 2 + 2 + 8 + 24), "" + n);
        ByteBuffer in = ByteBuffer.wrap(out, 0, n);
        check("attack type", "10", "" + in.get());
        check(
                "attack fields",
                "300 1000 12.5 -3.0 1.0 65.0 -7.5",
                varInt(in)
                        + " "
                        + varInt(in)
                        + " "
                        + in.getFloat()
                        + " "
                        + in.getFloat()
                        + " "
                        + in.getDouble()
                        + " "
                        + in.getDouble()
                        + " "
                        + in.getDouble());
        n = SteinSyncTick.writeAttack(Integer.MAX_VALUE, -1, 0, 0, 0, 0, 0, out);
        check("longest attack fits", "true", "" + (n <= SteinSyncTick.MAX_ATTACK_BYTES));
    }

    private static void keys() {
        check(
                "keys from the movement input (sneaking scales it by 0.3)",
                "1 -1 0 1 -1",
                SteinSyncTick.key(1F)
                        + " "
                        + SteinSyncTick.key(-1F)
                        + " "
                        + SteinSyncTick.key(0F)
                        + " "
                        + SteinSyncTick.key(0.3F)
                        + " "
                        + SteinSyncTick.key(-0.3F));
    }

    /** What a hold applied, in order. */
    private static final class Applied implements BlockHold.Apply {
        final List<String> log = new ArrayList<>();

        @Override
        public void apply(Object pos, Object state) {
            log.add(pos + "=" + state);
        }
    }

    private static void holdUntilAck() {
        BlockHold h = new BlockHold();
        Applied a = new Applied();
        long dug = BlockHold.key(1, 64, 1);
        long other = BlockHold.key(2, 64, 1);
        check("no action, nothing held", "false", "" + h.hold(dug, "dug", "air"));
        h.begin(1, dug, BlockHold.NONE, 0, a);
        check("covered position held", "true", "" + h.hold(dug, "dug", "stone"));
        check("newest state kept", "true", "" + h.hold(dug, "dug", "air"));
        check("other position applied", "false", "" + h.hold(other, "other", "dirt"));
        check("one held", "1", "" + h.held());
        h.ack(0, a);
        check("another action's ack releases nothing", "[]", a.log.toString());
        h.ack(1, a);
        check("ack applies the newest state", "[dug=air]", a.log.toString());
        check("nothing pending after the ack", "0 0", h.pending() + " " + h.held());
    }

    private static void holdTimeout() {
        BlockHold h = new BlockHold();
        Applied a = new Applied();
        long clicked = BlockHold.key(5, 70, -5);
        long placed = BlockHold.key(5, 71, -5);
        h.begin(1, clicked, placed, 1000, a);
        h.hold(placed, "placed", "air");
        h.expire(1999, a);
        check("still held before a second", "[]", a.log.toString());
        h.expire(2000, a);
        check("applied after a second without an ack", "[placed=air]", a.log.toString());
        check("action forgotten", "0", "" + h.pending());
        h.ack(1, a);
        check("a late ack changes nothing", "[placed=air]", a.log.toString());
    }

    private static void holdOverlap() {
        BlockHold h = new BlockHold();
        Applied a = new Applied();
        long p = BlockHold.key(0, 10, 0);
        h.begin(1, p, BlockHold.NONE, 0, a);
        h.begin(2, p, BlockHold.NONE, 0, a);
        h.hold(p, "p", "stone");
        h.ack(1, a);
        check("still covered by the second action", "[]", a.log.toString());
        h.ack(2, a);
        check("applied when no action covers it", "[p=stone]", a.log.toString());
        // Acks can come out of order (an action without a Barrier is acked at once): an ack for a
        // later action leaves an earlier one waiting for its own.
        h.begin(3, p, BlockHold.NONE, 0, a);
        h.begin(4, BlockHold.key(9, 9, 9), BlockHold.NONE, 0, a);
        h.hold(p, "p", "air");
        h.ack(4, a);
        check("ack 4 leaves action 3 held", "[p=stone] 1", a.log + " " + h.pending());
        h.ack(3, a);
        check("ack 3 settles it", "[p=stone, p=air] 0", a.log + " " + h.pending());
        h.begin(5, p, BlockHold.NONE, 0, a);
        h.hold(p, "p", "dirt");
        h.clear();
        check("a new connection drops everything", "0 0", h.pending() + " " + h.held());
        h.ack(5, a);
        check("nothing applied after clear", "[p=stone, p=air]", a.log.toString());
    }

    private static void holdFull() {
        BlockHold h = new BlockHold();
        Applied a = new Applied();
        for (int i = 0; i < BlockHold.MAX_ACTIONS; i++) {
            h.begin(i + 1, BlockHold.key(i, 1, 0), BlockHold.NONE, 0, a);
        }
        h.hold(BlockHold.key(0, 1, 0), "first", "air");
        h.begin(100, BlockHold.key(99, 1, 0), BlockHold.NONE, 0, a);
        check(
                "a full table forgets the oldest action and applies its change",
                "[first=air] 16",
                a.log + " " + h.pending());
        BlockHold big = new BlockHold();
        Applied b = new Applied();
        big.begin(1, BlockHold.key(0, 0, 0), BlockHold.NONE, 0, b);
        for (int i = 0; i < BlockHold.MAX_HELD; i++) {
            big.hold(BlockHold.key(0, 0, 0), "same", "s" + i);
        }
        check("one position takes one slot", "1", "" + big.held());
    }

    /** The Tick datagram with copies: the same bytes the proxy's reader and the native ring use. */
    private static void datagramWindows() {
        SteinSyncDatagram d = new SteinSyncDatagram();
        byte[] out = new byte[SteinSyncDatagram.BUFFER];
        d.openWindow(0);
        d.copy(1, new byte[] {0x0A}, 1);
        int n = d.push(2, new byte[] {7, 1}, 2, out);
        check(
                "first datagram, one swing copied",
                "[2, 1, 2, 2, 7, 1, 0, 1, 1, 1, 10]",
                Arrays.toString(Arrays.copyOf(out, n)));
        // A window too large to keep goes as incomplete; the next one starts after Tick 1's after.
        for (int i = 3; i < 30; i++) {
            d.copy(i, new byte[40], 40);
        }
        n = d.push(29, new byte[] {7, 2}, 2, out);
        check("an overflowing window is incomplete", "2 -128", out[n - 2] + " " + out[n - 1]);
        check("two entries now", "2", "" + out[1]);
        // Windows of big copies: the datagram stays under its limit, newest window kept.
        byte[] big = new byte[70];
        for (int t = 0; t < 4; t++) {
            for (int i = 1; i <= 4; i++) {
                d.copy(100 + t * 10 + i, big, big.length);
            }
            n = d.push(110 + t * 10, new byte[] {7, (byte) (3 + t)}, 2, out);
        }
        check("bounded", "true", "" + (n <= SteinSyncDatagram.MAX_DATAGRAM_LEN));
        check("three entries", "3", "" + out[1]);
        check("refuses an empty Tick", "0", "" + d.push(1, new byte[0], 0, out));
    }

    private static void packing() {
        check(
                "packed like the protocol's Position",
                Long.toHexString((1L << 38) | (64L << 26) | 1L),
                Long.toHexString(BlockHold.key(1, 64, 1)));
        check(
                "negative coordinates",
                Long.toHexString(-1L & ~(0xFFFL << 26) | (5L << 26)),
                Long.toHexString(BlockHold.key(-1, 5, -1)));
        check(
                "NONE is no reachable block",
                "false",
                "" + (BlockHold.key(-30_000_000, 0, 0) == BlockHold.NONE));
    }

    private static int varInt(ByteBuffer in) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.get() & 0xFF;
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                break;
            }
        }
        return value;
    }

    /** A mod that registered a route diverts a join only when it gives an address (SOCKS on). */
    private static void modRoute() {
        Object socks = "socks:1080";
        check(
                "Connect loaded, SOCKS off: a join goes over QUIC",
                "" + SteinSyncRoute.QUIC,
                "" + SteinSyncRoute.choose(null, true));
        check(
                "SOCKS on: the join takes the mod's route",
                "" + SteinSyncRoute.MOD,
                "" + SteinSyncRoute.choose(socks, true));
        check(
                "SOCKS on: a server list ping takes it too",
                "" + SteinSyncRoute.MOD,
                "" + SteinSyncRoute.choose(socks, false));
        check(
                "no route, a ping: the game's own connection",
                "" + SteinSyncRoute.DIRECT,
                "" + SteinSyncRoute.choose(null, false));
    }

    /** How long a QUIC join waits, from the server list ping (protocol.md 7.6). */
    private static void quicTiming() {
        SteinSyncConnect.Timing none = SteinSyncConnect.Timing.fromPing(0);
        check(
                "no ping: 1.2 s to answer, no hint",
                "1200 6000 1200 0",
                none.answerMs + " " + none.limitMs + " " + none.kwikMs + " " + none.rttHintMs);
        SteinSyncConnect.Timing near = SteinSyncConnect.Timing.fromPing(40);
        check(
                "a near server keeps the floors",
                "1200 4000 1280 40",
                near.answerMs + " " + near.limitMs + " " + near.kwikMs + " " + near.rttHintMs);
        SteinSyncConnect.Timing far = SteinSyncConnect.Timing.fromPing(800);
        check(
                "800 ms: four round trips to answer, eight to finish",
                "3700 8400 5300 800",
                far.answerMs + " " + far.limitMs + " " + far.kwikMs + " " + far.rttHintMs);
        SteinSyncConnect.Timing worst = SteinSyncConnect.Timing.fromPing(3000);
        check(
                "the waits are capped",
                "5000 12000 10000",
                worst.answerMs + " " + worst.limitMs + " " + worst.kwikMs);
        check(
                "a failed native connect: silence, slowness and a bad pin are told apart",
                SteinSyncCodec.QUIC_TIMEOUT
                        + " "
                        + SteinSyncCodec.QUIC_SLOW
                        + " "
                        + SteinSyncCodec.QUIC_HANDSHAKE_FAILED
                        + " "
                        + SteinSyncCodec.QUIC_UNSUPPORTED,
                SteinSyncConnect.outcomeOf(dev.xavier.stein.bridge.net.SteinQuic.TIMEOUT)
                        + " "
                        + SteinSyncConnect.outcomeOf(dev.xavier.stein.bridge.net.SteinQuic.SLOW)
                        + " "
                        + SteinSyncConnect.outcomeOf(
                                dev.xavier.stein.bridge.net.SteinQuic.HANDSHAKE_FAILED)
                        + " "
                        + SteinSyncConnect.outcomeOf(
                                dev.xavier.stein.bridge.net.SteinQuic.LOCAL_ERROR));
    }

    private static void check(String name, String expected, String got) {
        if (!expected.equals(got)) {
            failures++;
            System.out.println("FAILED " + name + ": expected " + expected + ", got " + got);
        }
    }
}
