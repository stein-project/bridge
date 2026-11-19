/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Stein Sync readers (Hello, HitConfirm, Damaged, CombatSeen, BlockAck) and the Accept and
 * BlockAction writers (SteinSyncCodec), without the game: well-formed messages, and hostile ones
 * that must come back unreadable (never an exception). Exits 0 when all is well, 1 on failure. Runs
 * on every Bridge build.
 */
public final class SteinSyncTest {

    private static int failures;

    public static void main(String[] args) {
        hello();
        hostileHello();
        accept();
        hitConfirm();
        hostileHitConfirm();
        damaged();
        combatSeen();
        blockAck();
        loginMarker();
        plainText();
        logLimit();
        quicMessages();
        still();
        correction();
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("SteinSyncTest: ok");
    }

    /** The Correction layout of section 5.10, as the proxy writes it, and hostile ones. */
    private static void correction() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(80);
        b.put((byte) SteinSyncCodec.CORRECTION).put((byte) 0xAC).put((byte) 0x02);
        b.putDouble(10.5).putDouble(64.0).putDouble(-3.25);
        b.putDouble(0.1).putDouble(-0.0784).putDouble(0.0);
        b.put((byte) (SteinSyncCodec.Correction.ON_GROUND | SteinSyncCodec.Correction.SPRINTING));
        b.putFloat(1.5F);
        b.put((byte) 9).put((byte) 0xD8).put((byte) 0x04).put((byte) 0).put((byte) 0);
        b.putFloat(0.026F);
        byte[] data = java.util.Arrays.copyOf(b.array(), b.position());
        check("Correction length (proxy's test layout)", "65", "" + data.length);
        SteinSyncCodec.Correction c = new SteinSyncCodec.Correction();
        check("Correction read", "true", "" + SteinSyncCodec.readCorrection(data, c));
        check(
                "Correction fields",
                "300 10.5 64.0 -3.25 0.1 -0.0784 0.0 33 1.5 9 600 0 0 0.026",
                c.tick
                        + " "
                        + c.x
                        + " "
                        + c.y
                        + " "
                        + c.z
                        + " "
                        + c.motionX
                        + " "
                        + c.motionY
                        + " "
                        + c.motionZ
                        + " "
                        + c.flags
                        + " "
                        + c.fallDistance
                        + " "
                        + c.jumpCooldown
                        + " "
                        + c.sprintExpiry
                        + " "
                        + c.sprintTapWindow
                        + " "
                        + c.flyTapWindow
                        + " "
                        + c.airControl);
        byte[] longer = java.util.Arrays.copyOf(data, data.length + 3);
        check("trailing bytes ignored", "true", "" + SteinSyncCodec.readCorrection(longer, c));
        boolean cut = false;
        for (int n = 1; n < data.length; n++) {
            cut |= SteinSyncCodec.readCorrection(java.util.Arrays.copyOf(data, n), c);
        }
        check("every cut Correction refused", "false", "" + cut);
        byte[] nan = data.clone();
        java.nio.ByteBuffer.wrap(nan).putDouble(3, Double.NaN);
        check("a NaN position refused", "false", "" + SteinSyncCodec.readCorrection(nan, c));
        byte[] far = data.clone();
        java.nio.ByteBuffer.wrap(far).putDouble(3, 4.0e7);
        check(
                "a position out of bounds refused",
                "false",
                "" + SteinSyncCodec.readCorrection(far, c));
        byte[] fast = data.clone();
        java.nio.ByteBuffer.wrap(fast).putDouble(3 + 32, 500.0);
        check(
                "a speed out of bounds refused",
                "false",
                "" + SteinSyncCodec.readCorrection(fast, c));
        byte[] counter = data.clone();
        counter[3 + 48 + 1 + 4 + 1] = (byte) 0xB1; // 1201 as a VarInt: 0xB1 0x09
        counter[3 + 48 + 1 + 4 + 2] = (byte) 0x09;
        check(
                "a counter over the bound refused",
                "false",
                "" + SteinSyncCodec.readCorrection(counter, c));
        check("another type refused", "false", "" + SteinSyncCodec.readCorrection(bytes(13), c));
    }

    private static void loginMarker() {
        check(
                "marker after a NUL, with the version",
                "play.example.org|SteinSync1",
                SteinSyncAddress.marked("play.example.org", 1).replace('\0', '|'));
        String longest = "a".repeat(SteinSyncAddress.MAX_HOST_CHARS - 11);
        check(
                "a marked address that fits is marked",
                "" + SteinSyncAddress.MAX_HOST_CHARS,
                "" + SteinSyncAddress.marked(longest, 1).length());
        String tooLong = longest + "a";
        check(
                "an address with no room left is sent as typed",
                tooLong,
                SteinSyncAddress.marked(tooLong, 1));
        check(
                "the same server under two spellings",
                "play.example.org play.example.org [::1]",
                SteinSyncAddress.key(" Play.Example.org ")
                        + " "
                        + SteinSyncAddress.key("play.example.org:25565")
                        + " "
                        + SteinSyncAddress.key("[::1]:25565"));
        check("another port is another server", "h:25566", SteinSyncAddress.key("h:25566"));
        check("no address", "", SteinSyncAddress.key(null));
    }

    /** The QUIC transport's messages (stein-sync's docs/protocol.md sections 7.3 and 7.6). */
    private static void quicMessages() {
        check("Fence 300", "[11, -84, 2]", Arrays.toString(SteinSyncCodec.writeFence(300)));
        check(
                "Transport over TCP after a UDP timeout of 1500 ms",
                "[12, 0, 4, -36, 11]",
                Arrays.toString(
                        SteinSyncCodec.writeTransport(0, SteinSyncCodec.QUIC_TIMEOUT, 1500)));
        byte[] tick = {7, (byte) 0xAC, 2, 0};
        check("a Tick's number", "300", "" + SteinSyncCodec.readVarIntAt(tick, 1, tick.length));
        check("cut short", "0", "" + SteinSyncCodec.readVarIntAt(tick, 1, 2));
        byte[] tooLong = {7, -1, -1, -1, -1, -1, 1};
        check("over 5 bytes", "0", "" + SteinSyncCodec.readVarIntAt(tooLong, 1, tooLong.length));
    }

    private static void plainText() {
        check(
                "formatting codes and control characters out",
                "hits, moveblocks",
                SteinSyncAddress.plain("\u00a7chits\u00a7r, move\nblocks\u0007"));
        check("a trailing section sign", "x", SteinSyncAddress.plain("x\u00a7"));
        check("plain text untouched", "ação, hits", SteinSyncAddress.plain("ação, hits"));
    }

    private static void logLimit() {
        LogLimit limit = new LogLimit(10_000);
        String got =
                limit.due(0)
                        + " "
                        + limit.due(1)
                        + " "
                        + limit.due(9_999)
                        + " "
                        + limit.due(10_000)
                        + " "
                        + limit.due(10_001);
        check("one line per period, with the count left out", "0 -1 -1 2 -1", got);
        check("suffix", " (2 more since the last line)", LogLimit.leftOut(2));
        check("no suffix", "", LogLimit.leftOut(0));
    }

    private static void hello() {
        SteinSyncCodec.Hello h = SteinSyncCodec.readHello(bytes(1, 1, 0));
        check("version 1, no features", "1 []", h.version + " " + h.features);
        h = SteinSyncCodec.readHello(concat(bytes(1, 1, 2), str("ack"), str("hits")));
        check("two features", "1 [ack, hits]", h.version + " " + h.features);
        h = SteinSyncCodec.readHello(concat(bytes(1, 0xAC, 0x02, 1), str("ação")));
        check("VarInt version and UTF-8 name", "300 [ação]", h.version + " " + h.features);
        h = SteinSyncCodec.readHello(bytes(1, 1, 0, 9, 9, 9));
        check("trailing bytes ignored", "1 []", h.version + " " + h.features);
        h = SteinSyncCodec.readHello(concat(bytes(1, 1, 1), str("")));
        check("empty name kept", "1 []", h.version + " " + h.features);
        check("agreed with a newer proxy", "1", "" + SteinSyncCodec.agreedVersion(7));
        check("agreed with version 1", "1", "" + SteinSyncCodec.agreedVersion(1));
        check(
                "known features turned on, in the proxy order",
                "[hits, blocks, move]",
                ""
                        + SteinSyncCodec.supported(
                                Arrays.asList("ack", "hits", "blocks", "move"), false));
        check(
                "move alone",
                "[move]",
                "" + SteinSyncCodec.supported(Arrays.asList("move", "Move", "moves"), false));
        check(
                "hits once, even offered twice",
                "[hits]",
                "" + SteinSyncCodec.supported(Arrays.asList("hits", "hits"), false));
        check(
                "nothing known offered",
                "[]",
                "" + SteinSyncCodec.supported(Arrays.asList("ack", "HITS"), false));
        check(
                "authority over QUIC with move",
                "[move, authority]",
                "" + SteinSyncCodec.supported(Arrays.asList("move", "authority"), true));
        check(
                "authority never over TCP",
                "[move]",
                "" + SteinSyncCodec.supported(Arrays.asList("move", "authority"), false));
        check(
                "authority never without move",
                "[hits]",
                "" + SteinSyncCodec.supported(Arrays.asList("authority", "hits"), true));
    }

    private static void hostileHello() {
        unreadable("null", null);
        unreadable("empty", new byte[0]);
        unreadable("only the type", bytes(1));
        unreadable("another message type", bytes(2, 1, 0));
        unreadable("an unknown message type", bytes(77, 1, 0));
        unreadable("version 0", bytes(1, 0, 0));
        unreadable("negative version", bytes(1, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F, 0));
        unreadable("VarInt of 6 bytes", bytes(1, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01, 0));
        unreadable("VarInt cut short", bytes(1, 0x80));
        unreadable("no feature count", bytes(1, 1));
        unreadable("33 features", bytes(1, 1, 33));
        unreadable("negative count", bytes(1, 1, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F));
        unreadable("fewer features than counted", concat(bytes(1, 1, 2), str("ack")));
        unreadable("string past the end", bytes(1, 1, 1, 10, 'a', 'b'));
        unreadable("string of 129 bytes", concat(bytes(1, 1, 1, 0x81, 0x01), filled(129)));
        unreadable("string of 33 characters", concat(bytes(1, 1, 1), str("a".repeat(33))));
        unreadable("not UTF-8", bytes(1, 1, 1, 2, 0xC3, 0x28));
        unreadable("lone surrogate in UTF-8", bytes(1, 1, 1, 3, 0xED, 0xA0, 0x80));
        byte[] big = new byte[SteinSyncCodec.MAX_PAYLOAD + 1];
        big[0] = 1;
        big[1] = 1;
        unreadable("over 1024 bytes", big);
        byte[] limit = new byte[SteinSyncCodec.MAX_PAYLOAD];
        limit[0] = 1;
        limit[1] = 1;
        check("1024 bytes is fine", "true", "" + (SteinSyncCodec.readHello(limit) != null));
        // Every truncation of a good Hello is unreadable, never a throw.
        byte[] good = concat(bytes(1, 1, 2), str("ack"), str("hits"));
        for (int n = 0; n < good.length; n++) {
            try {
                SteinSyncCodec.Hello h = SteinSyncCodec.readHello(Arrays.copyOf(good, n));
                if (h != null) {
                    failures++;
                    System.out.println("FAILED truncated to " + n + " bytes was read");
                }
            } catch (RuntimeException e) {
                failures++;
                System.out.println("FAILED truncated to " + n + " bytes threw " + e);
            }
        }
        // And random bytes after the type never throw.
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < 20000; i++) {
            byte[] noise = new byte[1 + random.nextInt(64)];
            random.nextBytes(noise);
            noise[0] = 1;
            try {
                SteinSyncCodec.readHello(noise);
            } catch (RuntimeException e) {
                failures++;
                System.out.println("FAILED noise " + Arrays.toString(noise) + " threw " + e);
                break;
            }
        }
    }

    private static void accept() {
        check(
                "Accept v1 without features",
                "[2, 1, 0]",
                Arrays.toString(SteinSyncCodec.writeAccept(1, Collections.emptyList())));
        check(
                "Accept with features",
                Arrays.toString(concat(bytes(2, 0xAC, 0x02, 2), str("ack"), str("ação"))),
                Arrays.toString(SteinSyncCodec.writeAccept(300, Arrays.asList("ack", "ação"))));
        rejected("version 0", 0, Collections.emptyList());
        rejected("negative version", -1, Collections.emptyList());
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            many.add("f" + i);
        }
        rejected("33 features", 1, many);
        rejected("33-character name", 1, Collections.singletonList("a".repeat(33)));
        rejected("empty name", 1, Collections.singletonList(""));
        // What the client writes, a proxy reading it as a Hello-shaped message gets back.
        byte[] a = SteinSyncCodec.writeAccept(1, Arrays.asList("ack", "hits"));
        a[0] = (byte) SteinSyncCodec.HELLO;
        SteinSyncCodec.Hello back = SteinSyncCodec.readHello(a);
        check("Accept round trip", "1 [ack, hits]", back.version + " " + back.features);
    }

    private static void hitConfirm() {
        SteinSyncCodec.HitConfirm h = new SteinSyncCodec.HitConfirm();
        hit("plain hit", "42 4.5 false false", concat(bytes(3, 42), f32(4.5F), bytes(0)), h);
        hit("critical", "42 6.75 true false", concat(bytes(3, 42), f32(6.75F), bytes(1)), h);
        hit("kill", "300 20.0 false true", concat(bytes(3, 0xAC, 0x02), f32(20F), bytes(2)), h);
        hit("critical kill", "7 0.0 true true", concat(bytes(3, 7), f32(0F), bytes(3)), h);
        hit(
                "unknown flag bits ignored",
                "7 1.0 false true",
                concat(bytes(3, 7), f32(1F), bytes(0xFE)),
                h);
        hit(
                "trailing bytes ignored",
                "7 1.0 false false",
                concat(bytes(3, 7), f32(1F), bytes(0, 9, 9)),
                h);
        hit(
                "negative entity id (5-byte VarInt)",
                "-1 2.0 false false",
                concat(bytes(3, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F), f32(2F), bytes(0)),
                h);
        hit(
                "largest float",
                "1 " + Float.MAX_VALUE + " false true",
                concat(bytes(3, 1), f32(Float.MAX_VALUE), bytes(2)),
                h);
        SteinSyncCodec.readHitConfirm(concat(bytes(3, 7), f32(1F), bytes(0, 0xAC, 0x02)), h);
        check("appended tick", "300", "" + h.tick);
        SteinSyncCodec.readHitConfirm(concat(bytes(3, 7), f32(1F), bytes(0)), h);
        check("no tick from an older proxy", "0", "" + h.tick);
        SteinSyncCodec.readHitConfirm(concat(bytes(3, 7), f32(1F), bytes(0, 0x80)), h);
        check("tick cut short reads as 0, hit kept", "7 0", h.target + " " + h.tick);
        // The holder is reused: a good read after a bad one is whole.
        SteinSyncCodec.readHitConfirm(bytes(3, 1), h);
        hit("after a bad read", "5 3.0 true false", concat(bytes(3, 5), f32(3F), bytes(1)), h);
    }

    private static void hostileHitConfirm() {
        noHit("null", null);
        noHit("empty", new byte[0]);
        noHit("only the type", bytes(3));
        noHit("a Hello", bytes(1, 1, 0));
        noHit("an Accept", bytes(2, 1, 0));
        noHit("an unknown type", concat(bytes(9, 1), f32(1F), bytes(0)));
        noHit("no damage", bytes(3, 1));
        noHit("damage cut short", bytes(3, 1, 0x40, 0x00, 0x00));
        noHit("no flags", concat(bytes(3, 1), f32(1F)));
        noHit("VarInt of 6 bytes", concat(bytes(3, 0x80, 0x80, 0x80, 0x80, 0x80, 1), f32(1F)));
        noHit("VarInt cut short", bytes(3, 0x80));
        noHit("NaN damage", concat(bytes(3, 1), f32(Float.NaN), bytes(0)));
        noHit("other NaN bits", concat(bytes(3, 1, 0x7F, 0xC0, 0x12, 0x34), bytes(0)));
        noHit("infinite damage", concat(bytes(3, 1), f32(Float.POSITIVE_INFINITY), bytes(0)));
        noHit("negative damage", concat(bytes(3, 1), f32(-1F), bytes(0)));
        noHit("negative infinity", concat(bytes(3, 1), f32(Float.NEGATIVE_INFINITY), bytes(0)));
        byte[] big = new byte[SteinSyncCodec.MAX_PAYLOAD + 1];
        big[0] = 3;
        noHit("over 1024 bytes", big);
        byte[] good = concat(bytes(3, 0xAC, 0x02), f32(5F), bytes(3));
        SteinSyncCodec.HitConfirm h = new SteinSyncCodec.HitConfirm();
        for (int n = 0; n < good.length; n++) {
            try {
                if (SteinSyncCodec.readHitConfirm(Arrays.copyOf(good, n), h)) {
                    failures++;
                    System.out.println("FAILED HitConfirm truncated to " + n + " bytes was read");
                }
            } catch (RuntimeException e) {
                failures++;
                System.out.println("FAILED HitConfirm truncated to " + n + " bytes threw " + e);
            }
        }
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 20000; i++) {
            byte[] noise = new byte[1 + random.nextInt(16)];
            random.nextBytes(noise);
            noise[0] = 3;
            try {
                if (SteinSyncCodec.readHitConfirm(noise, h)
                        && (!(h.damage >= 0F) || Float.isInfinite(h.damage))) {
                    failures++;
                    System.out.println("FAILED noise read a bad damage: " + h.damage);
                    break;
                }
            } catch (RuntimeException e) {
                failures++;
                System.out.println("FAILED noise " + Arrays.toString(noise) + " threw " + e);
                break;
            }
        }
    }

    private static void damaged() {
        SteinSyncCodec.Damaged d = new SteinSyncCodec.Damaged();
        check(
                "Damaged by a visible attacker",
                "true 42 3.5 1 true false",
                damagedText(concat(bytes(8, 42), f32(3.5F), bytes(1, 1)), d));
        check(
                "Damaged with no attacker, killed",
                "true 0 20.0 6 false true",
                damagedText(concat(bytes(8, 0), f32(20F), bytes(6, 2)), d));
        check(
                "unknown cause reads as other",
                "true 0 1.0 0 false false",
                damagedText(concat(bytes(8, 0), f32(1F), bytes(200, 0, 9)), d));
        check("Damaged cut short", "false", damagedText(concat(bytes(8, 1), f32(1F)), d));
        check(
                "Damaged with NaN damage",
                "false",
                damagedText(concat(bytes(8, 1), f32(Float.NaN), bytes(0, 0)), d));
        check("HitConfirm is not a Damaged", "false", damagedText(bytes(3, 1, 0, 0, 0, 0, 0), d));
        noise(8, 12, data -> SteinSyncCodec.readDamaged(data, d));
    }

    private static String damagedText(byte[] data, SteinSyncCodec.Damaged d) {
        if (!SteinSyncCodec.readDamaged(data, d)) {
            return "false";
        }
        return "true "
                + d.attacker
                + " "
                + d.damage
                + " "
                + d.cause
                + " "
                + d.critical
                + " "
                + d.killed;
    }

    private static void combatSeen() {
        SteinSyncCodec.CombatSeen c = new SteinSyncCodec.CombatSeen();
        boolean ok = SteinSyncCodec.readCombatSeen(bytes(9, 5, 0xAC, 0x02, 3), c);
        check(
                "CombatSeen",
                "true 5 300 true true",
                ok + " " + c.attacker + " " + c.target + " " + c.critical + " " + c.killed);
        check("CombatSeen cut short", "false", "" + SteinSyncCodec.readCombatSeen(bytes(9, 5), c));
        check("not a CombatSeen", "false", "" + SteinSyncCodec.readCombatSeen(bytes(8, 5, 6), c));
        noise(9, 8, data -> SteinSyncCodec.readCombatSeen(data, c));
    }

    private static void blockAck() {
        SteinSyncCodec.BlockAck a = new SteinSyncCodec.BlockAck();
        boolean ok = SteinSyncCodec.readBlockAck(bytes(6, 0xAC, 0x02), a);
        check("BlockAck", "true 300", ok + " " + a.seq);
        check(
                "negative seq",
                "false",
                "" + SteinSyncCodec.readBlockAck(bytes(6, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F), a));
        check("no seq", "false", "" + SteinSyncCodec.readBlockAck(bytes(6), a));
        byte[] out = new byte[8];
        int n = SteinSyncCodec.writeBlockAction(300, out);
        check("BlockAction", "[5, -84, 2]", Arrays.toString(Arrays.copyOf(out, n)));
        n = SteinSyncCodec.writeBlockAction(Integer.MAX_VALUE, out);
        check("largest BlockAction", "6", "" + n);
        noise(6, 8, data -> SteinSyncCodec.readBlockAck(data, a));
    }

    /** Random payloads of one type never throw. */
    private static void noise(int type, int maxLength, java.util.function.Predicate<byte[]> read) {
        java.util.Random random = new java.util.Random(type);
        for (int i = 0; i < 20000; i++) {
            byte[] noise = new byte[1 + random.nextInt(maxLength)];
            random.nextBytes(noise);
            noise[0] = (byte) type;
            try {
                read.test(noise);
            } catch (RuntimeException e) {
                failures++;
                System.out.println("FAILED type " + type + " noise threw " + e);
                return;
            }
        }
    }

    private static void hit(
            String name, String expected, byte[] data, SteinSyncCodec.HitConfirm h) {
        try {
            if (!SteinSyncCodec.readHitConfirm(data, h)) {
                failures++;
                System.out.println("FAILED " + name + ": unreadable");
                return;
            }
        } catch (RuntimeException e) {
            failures++;
            System.out.println("FAILED " + name + ": threw " + e);
            return;
        }
        check(name, expected, h.target + " " + h.damage + " " + h.critical + " " + h.killed);
    }

    private static void noHit(String name, byte[] data) {
        try {
            if (SteinSyncCodec.readHitConfirm(data, new SteinSyncCodec.HitConfirm())) {
                failures++;
                System.out.println("FAILED " + name + ": read");
            }
        } catch (RuntimeException e) {
            failures++;
            System.out.println("FAILED " + name + ": threw " + e);
        }
    }

    /** The protocol's float: its IEEE 754 bits, big-endian. */
    private static byte[] f32(float f) {
        int v = Float.floatToRawIntBits(f);
        return bytes(v >>> 24, v >>> 16 & 0xFF, v >>> 8 & 0xFF, v & 0xFF);
    }

    private static void unreadable(String name, byte[] data) {
        try {
            SteinSyncCodec.Hello h = SteinSyncCodec.readHello(data);
            if (h != null) {
                failures++;
                System.out.println("FAILED " + name + ": read as " + h.version + " " + h.features);
            }
        } catch (RuntimeException e) {
            failures++;
            System.out.println("FAILED " + name + ": threw " + e);
        }
    }

    private static void rejected(String name, int version, List<String> features) {
        try {
            SteinSyncCodec.writeAccept(version, features);
            failures++;
            System.out.println("FAILED " + name + ": written");
        } catch (IllegalArgumentException expected) {
            // what it should do
        }
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    private static byte[] filled(int n) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) 'a');
        return b;
    }

    /** A protocol string: VarInt byte length (under 128 here) and UTF-8. */
    private static byte[] str(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        return concat(bytes(utf8.length), utf8);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    /**
     * Section 5.9: a Respawn's new player entity waits for its Still, and ends the mode without.
     */
    private static void still() {
        SteinSyncStill still = new SteinSyncStill();
        Object first = new Object();
        Object respawned = new Object();
        still.confirm(first);
        check("the Hello's entity is confirmed", "true", "" + still.confirmed(first));
        check("a confirmed tick goes on", "false", "" + still.tick(first));
        check("a new entity waits", "false", "" + still.confirmed(respawned));
        still.confirm(respawned);
        check("its Still confirms it", "true", "" + still.confirmed(respawned));
        Object switched = new Object();
        StringBuilder ends = new StringBuilder();
        for (int i = 1; i <= SteinSyncStill.DEADLINE_TICKS; i++) {
            if (still.tick(i == 5 ? null : switched)) {
                ends.append(i).append(' ');
            }
        }
        check("no Still: ends once the deadline passes (null not counted)", "", ends.toString());
        check("one more tick ends it", "true", "" + still.tick(switched));
        still.clear();
        check("cleared: nothing confirmed", "false", "" + still.confirmed(first));
        check("cleared: never ends by itself", "false", "" + still.tick(null));
    }

    private static void check(String name, String expected, String got) {
        if (!expected.equals(got)) {
            failures++;
            System.out.println("FAILED " + name + ": expected " + expected + ", got " + got);
        }
    }
}
