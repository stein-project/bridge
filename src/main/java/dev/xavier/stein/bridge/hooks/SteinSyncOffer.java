/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a server's status response says about Stein Sync: the {@code "steinSync"} object a Stein
 * Sync proxy adds to the server list ping ({@code {"version": 1, "features": []}}), and its QUIC
 * transport when it has one ({@code "quic": {"ports": [25565], "cert": "sha256:..."}}, stein-sync's
 * docs/protocol.md section 7.1).
 *
 * <p>It is only what the server claims (any server can write the field): the server list shows it,
 * nothing is turned on by it. Immutable, so the ping thread can hand it to the game thread through
 * a plain field.
 */
public final class SteinSyncOffer {

    /** The status field's name, and the quoted form looked for before parsing anything. */
    static final String FIELD = "steinSync";

    static final String QUOTED_FIELD = "\"" + FIELD + "\"";

    /** The most feature names kept, and their longest length: the in-game Hello's limits. */
    static final int MAX_FEATURES = 32;

    static final int MAX_FEATURE_CHARS = 32;

    /** The highest Stein Sync protocol version the server speaks (at least 1). */
    public final int version;

    /** The features the server offers, unmodifiable; names a client does not know are ignored. */
    public final List<String> features;

    /** The most QUIC ports kept: a client tries at most this many. */
    static final int MAX_QUIC_PORTS = 4;

    /**
     * The UDP ports of the server's QUIC transport, in the order to try them; null when the status
     * announced none (or a malformed one). Do not modify.
     */
    public final int[] quicPorts;

    /**
     * The SHA-256 of the server's QUIC certificate (32 bytes), the pin of the connection; null with
     * {@link #quicPorts}. Do not modify.
     */
    public final byte[] quicPin;

    private SteinSyncOffer(int version, List<String> features, int[] quicPorts, byte[] quicPin) {
        this.version = version;
        this.features = features;
        this.quicPorts = quicPin == null ? null : quicPorts;
        this.quicPin = quicPorts == null ? null : quicPin;
    }

    /**
     * The offer in a status JSON, or null when there is none or it is malformed: {@code version}
     * must be an integer of at least 1; {@code features}, when it is an array, keeps its strings of
     * 1 to 32 characters (at most 32 of them) and drops anything else.
     */
    static SteinSyncOffer fromStatus(String json) {
        if (json == null || !json.contains(QUOTED_FIELD)) {
            return null;
        }
        JsonElement root;
        try {
            root = new JsonParser().parse(json);
        } catch (RuntimeException e) {
            return null;
        }
        if (!root.isJsonObject()) {
            return null;
        }
        return from(root.getAsJsonObject().get(FIELD));
    }

    /**
     * The offer a Stein Sync proxy put in the reason of a login it refused (stein-sync's
     * docs/protocol.md section 6.2: the same object as its status, next to the text), or null.
     *
     * @param json the reason as the server sent it
     */
    public static SteinSyncOffer fromRefusal(String json) {
        return fromStatus(json);
    }

    /** The offer in the {@code "steinSync"} element itself, or null (see {@link #fromStatus}). */
    static SteinSyncOffer from(JsonElement field) {
        if (field == null || !field.isJsonObject()) {
            return null;
        }
        JsonObject o = field.getAsJsonObject();
        int version = positiveInt(o.get("version"));
        if (version < 1) {
            return null;
        }
        JsonElement quic = o.get("quic");
        int[] ports = null;
        byte[] pin = null;
        if (quic != null && quic.isJsonObject()) {
            ports = ports(quic.getAsJsonObject().get("ports"));
            pin = pin(quic.getAsJsonObject().get("cert"));
        }
        return new SteinSyncOffer(version, features(o.get("features")), ports, pin);
    }

    /** The integer value of a JSON number, or 0 for anything that is not a whole positive int. */
    private static int positiveInt(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !((JsonPrimitive) e).isNumber()) {
            return 0;
        }
        double d = e.getAsDouble();
        return d >= 1 && d <= Integer.MAX_VALUE && d == Math.rint(d) ? (int) d : 0;
    }

    /** 1 to 4 ports of 1..65535, or null. */
    private static int[] ports(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        int[] out = new int[Math.min(a.size(), MAX_QUIC_PORTS)];
        for (int i = 0; i < out.length; i++) {
            int port = positiveInt(a.get(i));
            if (port < 1 || port > 65535) {
                return null;
            }
            out[i] = port;
        }
        return out.length == 0 ? null : out;
    }

    /** {@code "sha256:"} and 64 hex digits as 32 bytes, or null. */
    static byte[] pin(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !((JsonPrimitive) e).isString()) {
            return null;
        }
        String text = e.getAsString();
        if (!text.startsWith("sha256:") || text.length() != 7 + 64) {
            return null;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            int hi = Character.digit(text.charAt(7 + 2 * i), 16);
            int lo = Character.digit(text.charAt(8 + 2 * i), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    private static List<String> features(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray a = e.getAsJsonArray();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.size() && out.size() < MAX_FEATURES; i++) {
            JsonElement f = a.get(i);
            if (f.isJsonPrimitive() && ((JsonPrimitive) f).isString()) {
                String name = f.getAsString();
                if (!name.isEmpty() && name.length() <= MAX_FEATURE_CHARS) {
                    out.add(name);
                }
            }
        }
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }
}
