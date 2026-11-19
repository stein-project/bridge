/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Stein Sync messages of the {@code stein:sync} plugin channel, version 1 (stein-sync's
 * docs/protocol.md): reading the proxy's Hello, HitConfirm, BlockAck, Damaged and CombatSeen, and
 * writing the client's Accept and BlockAction (the Tick has its own writer, {@link SteinSyncTick}).
 * Plain bytes, no game classes, so it is tested without the game.
 *
 * <p>Everything read comes from the network and is checked: a payload over the version 1 limit, a
 * VarInt longer than 5 bytes or negative, a count or a string over its limit, or bytes that are not
 * UTF-8 make the whole message unreadable (null or false) instead of an exception. Trailing bytes
 * are ignored, as the protocol asks (later versions only append fields).
 */
final class SteinSyncCodec {

    static final String CHANNEL = "stein:sync";

    /** The highest protocol version this client speaks. */
    static final int CLIENT_VERSION = 1;

    static final int HELLO = 1;
    static final int ACCEPT = 2;
    static final int HIT_CONFIRM = 3;
    static final int BLOCK_ACTION = 5;
    static final int BLOCK_ACK = 6;
    static final int TICK = 7;
    static final int DAMAGED = 8;
    static final int COMBAT_SEEN = 9;
    static final int ATTACK = 10;

    /** Fence (client to proxy over QUIC, section 7.3): Ticks up to a number left before this. */
    static final int FENCE = 11;

    /** Transport (client to proxy, section 7.6): how this client reached the proxy. */
    static final int TRANSPORT = 12;

    /** Still (proxy to client, section 5.9): the Respawn before it is this proxy's server's. */
    static final int STILL = 13;

    /**
     * Correction (proxy to client, section 5.10, feature {@link #AUTHORITY}): the movement state
     * the proxy's physics reached after one of the client's Ticks.
     */
    static final int CORRECTION = 14;

    /** The Transport message's QUIC outcomes (section 7.6). */
    static final int QUIC_NOT_OFFERED = 0;

    static final int QUIC_CONNECTED = 1;
    static final int QUIC_DISABLED = 2;
    static final int QUIC_UNSUPPORTED = 3;
    static final int QUIC_TIMEOUT = 4;
    static final int QUIC_HANDSHAKE_FAILED = 5;
    static final int QUIC_SKIPPED = 6;

    /** Hit confirmation, attacks in the Tick, Damaged and CombatSeen (sections 3.2, 5.1, 5.6). */
    static final String HITS = "hits";

    /** Block resync, and the client holding block changes until the BlockAck (3.1, 5.3). */
    static final String BLOCKS = "blocks";

    /** One Tick per client tick in place of the vanilla movement packets (5.5). */
    static final String MOVE = "move";

    /**
     * The proxy's authority over the player's movement (section 5.10): it moves the player by its
     * own physics and sends a Correction when the client's Tick differs. Only with {@link #MOVE},
     * and only over QUIC.
     */
    static final String AUTHORITY = "authority";

    /** Damaged causes past the last one this client knows read as {@link #CAUSE_OTHER}. */
    static final int CAUSE_OTHER = 0;

    static final int CAUSE_LAST = 7;

    /**
     * HitConfirm flags: the hit was critical; the hit killed the target. Other bits are ignored.
     */
    static final int FLAG_CRITICAL = 1;

    static final int FLAG_KILLED = 2;

    /** Version 1 limits: payload size, feature count and characters per feature name. */
    static final int MAX_PAYLOAD = 1024;

    static final int MAX_FEATURES = 32;
    static final int MAX_FEATURE_CHARS = 32;

    /** A UTF-8 character takes at most 4 bytes, so a longer string cannot fit its limit. */
    private static final int MAX_FEATURE_BYTES = MAX_FEATURE_CHARS * 4;

    private SteinSyncCodec() {}

    /** A Hello from the proxy: the highest version it speaks and the features it offers. */
    static final class Hello {
        final int version;
        final List<String> features;

        Hello(int version, List<String> features) {
            this.version = version;
            this.features = features;
        }
    }

    /**
     * A HitConfirm, filled in place by {@link #readHitConfirm} so a hit costs no allocation. Only
     * meaningful after a read that returned true.
     */
    static final class HitConfirm {
        int target;
        float damage;
        boolean critical;
        boolean killed;

        /** The Tick of the attack this hit answers (section 5.2), 0 for none or an older proxy. */
        int tick;

        /** The reader over the payload being parsed; kept here so reading allocates nothing. */
        private final Reader in = new Reader(null);
    }

    /** A Damaged (section 5.6), filled in place like {@link HitConfirm}. */
    static final class Damaged {
        /** The attacker's entity id, 0 for none or one this player does not see. */
        int attacker;

        float damage;

        /** 0 other ... 7 magic or effect; an unknown value reads as 0. */
        int cause;

        boolean critical;
        boolean killed;

        private final Reader in = new Reader(null);
    }

    /** A CombatSeen (section 5.6), filled in place like {@link HitConfirm}. */
    static final class CombatSeen {
        int attacker;
        int target;
        boolean critical;
        boolean killed;

        private final Reader in = new Reader(null);
    }

    /**
     * A Correction (section 5.10), filled in place by {@link #readCorrection}: plain values, which
     * the client turns into the Bridge's correction.
     */
    static final class Correction {
        static final int ON_GROUND = 1;
        static final int HIT_WALL = 1 << 1;
        static final int HIT_VERTICAL = 1 << 2;
        static final int IN_WATER = 1 << 3;
        static final int IN_WEB = 1 << 4;
        static final int SPRINTING = 1 << 5;
        static final int FLYING = 1 << 6;

        /** The client's Tick the state follows (its movement included). */
        int tick;

        /** Feet position. */
        double x;

        double y;
        double z;

        /** Velocity, blocks per tick. */
        double motionX;

        double motionY;
        double motionZ;

        /** The bits above. */
        int flags;

        float fallDistance;

        /** Ticks before holding jump jumps again. */
        int jumpCooldown;

        /** Ticks until the sprint ends by itself. */
        int sprintExpiry;

        /** The double-tap window of forward (sprint). */
        int sprintTapWindow;

        /** The double-tap window of jump (flight). */
        int flyTapWindow;

        /** Air control of the next tick. */
        float airControl;

        private final Reader in = new Reader(null);
    }

    /** Largest speed a Correction may carry, per axis (blocks per tick): a bound on garbage. */
    private static final double MAX_SPEED = 100.0;

    /** Largest tick counter a Correction may carry (the sprint's own end starts at 600). */
    private static final int MAX_COUNTER = 1200;

    /** The position bounds of a Tick (section 5.5), which a Correction keeps too. */
    private static final double MAX_HORIZONTAL = 3.2e7;

    private static final double MAX_Y = 4096;

    /**
     * Reads a Correction into {@code out}; false for another type or a malformed one: cut short, a
     * long or negative VarInt, a number that is not finite, a position out of the Tick's bounds, a
     * speed over {@link #MAX_SPEED} or a counter over {@link #MAX_COUNTER}. Trailing bytes are
     * ignored (later versions only append).
     */
    static boolean readCorrection(byte[] data, Correction out) {
        if (data == null || data.length < 1 || data.length > MAX_PAYLOAD) {
            return false;
        }
        Reader in = out.in;
        in.reset(data);
        if (in.u8() != CORRECTION) {
            return false;
        }
        int tick = in.varInt();
        double x = in.float64();
        double y = in.float64();
        double z = in.float64();
        double mx = in.float64();
        double my = in.float64();
        double mz = in.float64();
        int flags = in.u8();
        float fall = Float.intBitsToFloat(in.int32());
        int jump = in.varInt();
        int expiry = in.varInt();
        int sprintWindow = in.varInt();
        int flyWindow = in.varInt();
        float air = Float.intBitsToFloat(in.int32());
        if (in.bad
                || !(Math.abs(x) < MAX_HORIZONTAL)
                || !(Math.abs(z) < MAX_HORIZONTAL)
                || !(Math.abs(y) <= MAX_Y)
                || !(Math.abs(mx) <= MAX_SPEED)
                || !(Math.abs(my) <= MAX_SPEED)
                || !(Math.abs(mz) <= MAX_SPEED)
                || !Float.isFinite(fall)
                || !Float.isFinite(air)
                || jump > MAX_COUNTER
                || expiry > MAX_COUNTER
                || sprintWindow > MAX_COUNTER
                || flyWindow > MAX_COUNTER) {
            return false;
        }
        out.tick = tick;
        out.x = x;
        out.y = y;
        out.z = z;
        out.motionX = mx;
        out.motionY = my;
        out.motionZ = mz;
        out.flags = flags;
        out.fallDistance = fall;
        out.jumpCooldown = jump;
        out.sprintExpiry = expiry;
        out.sprintTapWindow = sprintWindow;
        out.flyTapWindow = flyWindow;
        out.airControl = air;
        return true;
    }

    /** A BlockAck (section 5.3), filled in place. */
    static final class BlockAck {
        int seq;

        private final Reader in = new Reader(null);
    }

    /**
     * The Hello in a {@code stein:sync} payload, or null when the payload is not a Hello (another
     * message type, which a client ignores) or is malformed.
     */
    static Hello readHello(byte[] data) {
        if (data == null || data.length < 1 || data.length > MAX_PAYLOAD) {
            return null;
        }
        Reader in = new Reader(data);
        if (in.u8() != HELLO) {
            return null;
        }
        int version = in.varInt();
        int count = in.varInt();
        if (in.bad || version < 1 || count < 0 || count > MAX_FEATURES) {
            return null;
        }
        List<String> features = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String f = in.string();
            if (in.bad) {
                return null;
            }
            features.add(f);
        }
        return new Hello(version, Collections.unmodifiableList(features));
    }

    /**
     * Reads a HitConfirm into {@code out}. False, with {@code out} left in an unspecified state,
     * when the payload is another message type or is malformed: over the payload limit, cut short,
     * a VarInt longer than 5 bytes, or a damage that is not a finite number of at least 0. The
     * target is any int (entity ids are plain VarInts in the game's protocol, negative ones
     * included); flag bits other than critical and killed are ignored, and so are trailing bytes,
     * since later versions only append.
     */
    static boolean readHitConfirm(byte[] data, HitConfirm out) {
        if (data == null || data.length < 1 || data.length > MAX_PAYLOAD) {
            return false;
        }
        Reader in = out.in;
        in.reset(data);
        if (in.u8() != HIT_CONFIRM) {
            return false;
        }
        int target = in.anyVarInt();
        float damage = Float.intBitsToFloat(in.int32());
        int flags = in.u8();
        if (in.bad || !(damage >= 0.0F) || damage == Float.POSITIVE_INFINITY) {
            return false;
        }
        out.target = target;
        out.damage = damage;
        out.critical = (flags & FLAG_CRITICAL) != 0;
        out.killed = (flags & FLAG_KILLED) != 0;
        out.tick = optionalTick(in);
        return true;
    }

    /**
     * The appended {@code tick} of a HitConfirm: 0 when a proxy older than section 5.2 left it out,
     * and also when it is cut short or negative, since a hit is worth showing without it.
     */
    private static int optionalTick(Reader in) {
        if (!in.more()) {
            return 0;
        }
        int tick = in.varInt();
        if (in.bad) {
            in.bad = false;
            return 0;
        }
        return tick;
    }

    /**
     * Reads a Damaged into {@code out}; false for another type or a malformed one (as {@link
     * #readHitConfirm}: cut short, a long VarInt, a damage that is not finite and at least 0).
     */
    static boolean readDamaged(byte[] data, Damaged out) {
        if (data == null || data.length < 1 || data.length > MAX_PAYLOAD) {
            return false;
        }
        Reader in = out.in;
        in.reset(data);
        if (in.u8() != DAMAGED) {
            return false;
        }
        int attacker = in.anyVarInt();
        float damage = Float.intBitsToFloat(in.int32());
        int cause = in.u8();
        int flags = in.u8();
        if (in.bad || !(damage >= 0.0F) || damage == Float.POSITIVE_INFINITY) {
            return false;
        }
        out.attacker = attacker;
        out.damage = damage;
        out.cause = cause <= CAUSE_LAST ? cause : CAUSE_OTHER;
        out.critical = (flags & FLAG_CRITICAL) != 0;
        out.killed = (flags & FLAG_KILLED) != 0;
        return true;
    }

    /** Reads a CombatSeen into {@code out}; false for another type or a malformed one. */
    static boolean readCombatSeen(byte[] data, CombatSeen out) {
        if (data == null || data.length < 1 || data.length > MAX_PAYLOAD) {
            return false;
        }
        Reader in = out.in;
        in.reset(data);
        if (in.u8() != COMBAT_SEEN) {
            return false;
        }
        int attacker = in.anyVarInt();
        int target = in.anyVarInt();
        int flags = in.u8();
        if (in.bad) {
            return false;
        }
        out.attacker = attacker;
        out.target = target;
        out.critical = (flags & FLAG_CRITICAL) != 0;
        out.killed = (flags & FLAG_KILLED) != 0;
        return true;
    }

    /** Reads a BlockAck into {@code out}; false for another type, a malformed or negative seq. */
    static boolean readBlockAck(byte[] data, BlockAck out) {
        if (data == null || data.length < 1 || data.length > MAX_PAYLOAD) {
            return false;
        }
        Reader in = out.in;
        in.reset(data);
        if (in.u8() != BLOCK_ACK) {
            return false;
        }
        int seq = in.varInt();
        if (in.bad) {
            return false;
        }
        out.seq = seq;
        return true;
    }

    /**
     * Writes a BlockAction into {@code out} (at least 6 bytes) and returns its length.
     *
     * @param seq the action's number, at least 1
     */
    static int writeBlockAction(int seq, byte[] out) {
        out[0] = (byte) BLOCK_ACTION;
        return putVarInt(out, 1, seq);
    }

    /** Writes the protocol's VarInt at {@code pos}; returns the position after it. */
    /**
     * The Transport message (type 12, section 7.6).
     *
     * @param transport 0 TCP, 1 QUIC
     * @param outcome one of the {@code QUIC_*} values
     * @param millis how long the QUIC attempt took
     */
    static byte[] writeTransport(int transport, int outcome, int millis) {
        byte[] out = new byte[3 + 5];
        out[0] = (byte) TRANSPORT;
        out[1] = (byte) transport;
        out[2] = (byte) outcome;
        return Arrays.copyOf(out, putVarInt(out, 3, Math.max(0, millis)));
    }

    /** The Fence message (type 11, section 7.3) for Tick {@code tick}. */
    static byte[] writeFence(int tick) {
        byte[] out = new byte[1 + 5];
        out[0] = (byte) FENCE;
        return Arrays.copyOf(out, putVarInt(out, 1, tick));
    }

    /**
     * The VarInt at {@code pos} of the first {@code len} bytes of {@code in} (a Tick's number at
     * 1), or 0 when it is cut short or longer than 5 bytes.
     */
    static int readVarIntAt(byte[] in, int pos, int len) {
        int value = 0;
        for (int i = pos, shift = 0; i < len && shift < 35; i++, shift += 7) {
            int b = in[i] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        return 0;
    }

    static int putVarInt(byte[] out, int pos, int value) {
        int v = value;
        int p = pos;
        while ((v & ~0x7F) != 0) {
            out[p++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[p++] = (byte) v;
        return p;
    }

    /**
     * The version both sides use: the lower of the proxy's and this client's.
     *
     * @param proxyVersion the Hello's version (at least 1)
     */
    static int agreedVersion(int proxyVersion) {
        return Math.min(proxyVersion, CLIENT_VERSION);
    }

    /**
     * The offered features this client turns on: the ones it knows ({@code hits}, {@code blocks},
     * {@code move}, and {@code authority} with {@code move} on a QUIC connection), in the proxy's
     * order, each once.
     *
     * @param quic whether this connection reached the proxy over QUIC (section 7): the proxy's
     *     authority over movement runs only there
     */
    static List<String> supported(List<String> offered, boolean quic) {
        List<String> on = new ArrayList<>(4);
        boolean move = offered.contains(MOVE);
        for (String f : offered) {
            boolean known =
                    HITS.equals(f)
                            || BLOCKS.equals(f)
                            || MOVE.equals(f)
                            || (AUTHORITY.equals(f) && move && quic);
            if (known && !on.contains(f)) {
                on.add(f);
            }
        }
        return on.isEmpty() ? Collections.<String>emptyList() : Collections.unmodifiableList(on);
    }

    /**
     * The Accept payload.
     *
     * @throws IllegalArgumentException for a version below 1 or features over the limits: what the
     *     client sends is its own, and must never be a message the proxy rejects
     */
    static byte[] writeAccept(int version, List<String> features) {
        if (version < 1 || features.size() > MAX_FEATURES) {
            throw new IllegalArgumentException("Accept out of the protocol's limits");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        out.write(ACCEPT);
        writeVarInt(out, version);
        writeVarInt(out, features.size());
        for (String f : features) {
            if (f.isEmpty() || f.length() > MAX_FEATURE_CHARS) {
                throw new IllegalArgumentException("feature name out of the limits: " + f);
            }
            byte[] b = f.getBytes(StandardCharsets.UTF_8);
            writeVarInt(out, b.length);
            out.write(b, 0, b.length);
        }
        if (out.size() > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Accept over " + MAX_PAYLOAD + " bytes");
        }
        return out.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    /** A bounded reader: any read past the end or over a limit sets {@link #bad} instead. */
    private static final class Reader {
        private byte[] data;
        private int pos;
        boolean bad;

        Reader(byte[] data) {
            this.data = data;
        }

        /** Are there bytes left (an appended field)? */
        boolean more() {
            return pos < data.length;
        }

        /** Starts over on another payload, so one reader serves every HitConfirm. */
        void reset(byte[] payload) {
            data = payload;
            pos = 0;
            bad = false;
        }

        /** A big-endian double. */
        double float64() {
            long hi = int32() & 0xFFFFFFFFL;
            long lo = int32() & 0xFFFFFFFFL;
            return Double.longBitsToDouble(hi << 32 | lo);
        }

        /** A big-endian 32-bit int (the protocol's float is its bits). */
        int int32() {
            if (data.length - pos < 4) {
                bad = true;
                return 0;
            }
            int v =
                    (data[pos] & 0xFF) << 24
                            | (data[pos + 1] & 0xFF) << 16
                            | (data[pos + 2] & 0xFF) << 8
                            | data[pos + 3] & 0xFF;
            pos += 4;
            return v;
        }

        /** The protocol's VarInt with its whole int range: at most 5 bytes. */
        int anyVarInt() {
            int value = 0;
            for (int i = 0; i < 5; i++) {
                int b = u8();
                if (bad) {
                    return 0;
                }
                value |= (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            bad = true;
            return 0;
        }

        int u8() {
            if (pos >= data.length) {
                bad = true;
                return -1;
            }
            return data[pos++] & 0xFF;
        }

        /** The protocol's VarInt for a count, length or version: a negative value is bad. */
        int varInt() {
            int value = anyVarInt();
            if (bad || value < 0) {
                bad = true;
                return -1;
            }
            return value;
        }

        /** A feature name: VarInt byte length, strict UTF-8, at most 32 characters. */
        String string() {
            int length = varInt();
            if (bad || length > MAX_FEATURE_BYTES || length > data.length - pos) {
                bad = true;
                return null;
            }
            CharsetDecoder utf8 =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
            String s;
            try {
                CharBuffer chars = utf8.decode(ByteBuffer.wrap(data, pos, length));
                s = chars.toString();
            } catch (CharacterCodingException e) {
                bad = true;
                return null;
            }
            pos += length;
            if (s.length() > MAX_FEATURE_CHARS) {
                bad = true;
                return null;
            }
            return s;
        }
    }
}
