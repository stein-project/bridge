/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.hooks.GameHooks;
import dev.xavier.stein.bridge.hooks.SteinSyncLoginHooks;
import dev.xavier.stein.bridge.hooks.SteinSyncOffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.I18n;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Stein Sync side of joining a server: which servers offered Stein Sync in the server list, and
 * the marker this client appends to the login handshake of those servers only (stein-sync's
 * docs/protocol.md section 6), so that a server requiring Stein clients can refuse vanilla ones at
 * the login screen.
 *
 * <p>The marker goes only to a server whose status offered Stein Sync: its own list entry's last
 * ping, or else the last ping of any entry with the same address in this session. A server that was
 * never pinged (Direct Connect to an address not in the list, a launcher's quick join) gets no
 * marker and no status ping first: a ping before every join would cost every server, vanilla ones
 * included, an extra connection and a round trip. Such a server, if it requires Stein clients,
 * refuses that first login with its message; the multiplayer list pings its entries again when the
 * player goes back to it. A wrong marker never breaks a join: a Stein Sync proxy strips it, and a
 * server without one treats it as Forge's marker is treated (ignored after the NUL).
 *
 * <p>Installed only when Stein Sync is on ({@code -Dsteinbridge.noSteinSync} leaves the Bridge
 * without a listener: no status is searched, no handshake changed).
 */
final class SteinSyncJoin implements SteinSyncLoginHooks {

    /** Addresses remembered at most: a list never holds this many, a flood cannot grow it. */
    private static final int MAX_REMEMBERED = 512;

    private static final LogLimit MARKED_LOG = new LogLimit(10_000);

    /** The offers of the server list pings, by {@link SteinSyncAddress#key}. */
    private final ConcurrentHashMap<String, SteinSyncOffer> offers = new ConcurrentHashMap<>();

    /**
     * How long after joining again a refusal of the same server shows instead of a second retry: a
     * server that refuses the marked login too (a full server, another rule) must not loop.
     */
    private static final long RETRY_EVERY_MS = 30_000;

    /** The offer in the last login refusal, until the screen that follows it reads it. */
    private volatile SteinSyncOffer refused;

    /** The address of the last login handshake (host:port), for a join without a server entry. */
    private volatile String lastTarget;

    /** The server last joined again after a refusal, and when. */
    private String retriedKey;

    private long retriedAt;

    /** Installs the listener in the Bridge, once, when Stein Sync is on. */
    static void install() {
        if (SteinSync.ENABLED) {
            GameHooks.steinSyncLogin = new SteinSyncJoin();
        }
    }

    @Override
    public void pinged(Object serverData, SteinSyncOffer offer) {
        if (!(serverData instanceof ServerData)) {
            return;
        }
        String key = SteinSyncAddress.key(((ServerData) serverData).serverIP);
        if (offer == null) {
            offers.remove(key);
            return;
        }
        if (offers.size() >= MAX_REMEMBERED && !offers.containsKey(key)) {
            offers.clear();
        }
        offers.put(key, offer);
    }

    /**
     * The Stein Sync offer of the server being joined (the game's current ServerData): the last
     * server list ping of its entry, or else of any entry with the same address in this session;
     * null when there is none or Stein Sync is off.
     */
    static SteinSyncOffer currentOffer() {
        Object listener = GameHooks.steinSyncLogin;
        ServerData server = Minecraft.getMinecraft().getCurrentServerData();
        if (!(listener instanceof SteinSyncJoin) || server == null) {
            return null;
        }
        SteinSyncOffer offer = GameHooks.steinSyncOffer(server);
        return offer != null
                ? offer
                : ((SteinSyncJoin) listener).offers.get(SteinSyncAddress.key(server.serverIP));
    }

    /**
     * The round trip the server list ping measured for the server being joined, in ms; 0 when it
     * was never pinged (a quick join) or the ping failed.
     */
    static long currentPingMs() {
        ServerData server = Minecraft.getMinecraft().getCurrentServerData();
        return server == null ? 0 : Math.max(0, server.pingToServer);
    }

    /** The game's folder (where the Bridge keeps its files). */
    static Path gameDir() {
        File dir = Minecraft.getMinecraft().mcDataDir;
        return (dir != null ? dir : new File(".")).toPath();
    }

    @Override
    public void loginTarget(String address, int port) {
        lastTarget = address == null ? null : address + ":" + port;
    }

    @Override
    public void loginRefused(String json) {
        refused = SteinSyncOffer.fromRefusal(json);
    }

    /**
     * A login ended with a Disconnect. When it was a Stein Sync proxy refusing an unmarked login
     * (stein-sync's docs/protocol.md section 6.2: Direct Connect to a server the list never
     * pinged), its offer becomes this server's and the game joins again at once, marked (and over
     * QUIC if the offer says so): the player never sees the refusal. Once per server per {@link
     * #RETRY_EVERY_MS}; anything else shows the game's screen. A launcher's quick join (no server
     * entry) gets one made from the address it went to.
     */
    @Override
    public Object loginFailedScreen(Object screen) {
        SteinSyncOffer offer = refused;
        refused = null;
        Minecraft mc = Minecraft.getMinecraft();
        ServerData server = mc.getCurrentServerData();
        String target = lastTarget;
        if (server == null && target != null) {
            // A launcher's quick join: no server entry, only the address it went to.
            server = new ServerData(I18n.format("selectServer.defaultName"), target, false);
        }
        if (offer == null || server == null || server.serverIP == null) {
            return screen;
        }
        String key = SteinSyncAddress.key(server.serverIP);
        long now = System.currentTimeMillis();
        if (key.equals(retriedKey) && now - retriedAt < RETRY_EVERY_MS) {
            return screen;
        }
        retriedKey = key;
        retriedAt = now;
        if (offers.size() >= MAX_REMEMBERED && !offers.containsKey(key)) {
            offers.clear();
        }
        offers.put(key, offer);
        Log.info(
                "steinsync",
                "Stein Sync: the server requires it; joining again with the offer it sent");
        return new GuiConnecting(new GuiMultiplayer(new GuiMainMenu()), mc, server);
    }

    @Override
    public String loginAddress(String address) {
        if (address == null) {
            return address;
        }
        SteinSyncOffer offer = currentOffer();
        if (offer == null) {
            return address;
        }
        String marked = SteinSyncAddress.marked(address, SteinSyncCodec.CLIENT_VERSION);
        int leftOut = MARKED_LOG.due();
        if (leftOut >= 0) {
            Log.info(
                    "steinsync",
                    "Stein Sync: login marked for a server that offers it"
                            + (marked.equals(address) ? " (address too long, not marked)" : "")
                            + LogLimit.leftOut(leftOut));
        }
        return marked;
    }
}
