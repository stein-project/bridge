/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.GameSettings;

import java.lang.ref.WeakReference;

/**
 * Client settings sent to the server (C15PacketClientSettings: language, view distance, chat, chat
 * colors and skin parts). Report: in multiplayer, lowering the rain volume on the sounds screen
 * disconnected from the server.
 *
 * <p>The volume screen (GuiScreenOptionsSounds) saves the options every frame while a control is
 * being dragged (the button calls the drag in its own draw), and the game's saveOptions sends the
 * packet again every time, even when identical. In 1.8.9 that was already dozens of packets per
 * second; with Stein's FPS, hundreds — and a server with a packet limit (anticheat) disconnects.
 * Here:
 *
 * <ul>
 *   <li>{@link #sendSettings} (patcher rule on every GameSettings.sendSettingsToServer): the packet
 *       only goes out if something in it changed or if the connection is new — joining a server
 *       always sends, as in the game;
 *   <li>{@link #saveWhileDragging} (rule on the volume control's drag): options.txt is written at
 *       most every 250 ms; what is left pending is written by {@link #frame} after that, and the
 *       final position is not lost.
 * </ul>
 *
 * The log says, every 5 s with activity, how many packets went out and how many identical ones were
 * held back. {@code -Dsteinbridge.opt.vanillaClientSettings=true}: like the game (counts, holds
 * nothing back).
 */
public final class ClientSettings {

    private static final boolean VANILLA = EngineOptions.VANILLA_CLIENT_SETTINGS;
    private static final long SAVE_EVERY_NS = 250_000_000L;
    private static final long REPORT_NS = 5_000_000_000L;

    /** Connection (NetHandlerPlayClient) and contents of the last packet sent. */
    private static WeakReference<Object> lastConnection = new WeakReference<>(null);

    private static String lastKey;

    /** GameSettings whose write was deferred by the drag. */
    private static Object pending;

    private static long lastSave;
    private static int sent;
    private static int held;
    private static int saves;
    private static int deferred;
    private static long lastReport;

    private ClientSettings() {}

    /**
     * In place of each GameSettings.sendSettingsToServer: only sends if something changed or the
     * connection is new.
     */
    public static void sendSettings(Object settingsObject) {
        GameSettings settings = (GameSettings) settingsObject;
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        NetHandlerPlayClient connection = player == null ? null : player.sendQueue;
        if (connection == null) {
            settings.sendSettingsToServer(); // without a player the original sends nothing
            return;
        }
        String key = key(settings);
        if (!VANILLA && connection == lastConnection.get() && key.equals(lastKey)) {
            held++;
            return;
        }
        lastConnection = new WeakReference<>(connection);
        lastKey = key;
        sent++;
        settings.sendSettingsToServer();
    }

    /** What goes in the packet: language, distance, chat, chat colors and the skin parts (mask). */
    private static String key(GameSettings settings) {
        int parts = 0;
        for (Object p : settings.setModelParts) {
            parts |= 1 << ((Enum<?>) p).ordinal();
        }
        return settings.language
                + '|'
                + settings.renderDistanceChunks
                + '|'
                + settings.chatVisibility
                + '|'
                + settings.chatColours
                + '|'
                + parts;
    }

    /**
     * In place of saveOptions during the volume control's drag: options.txt at most every 250 ms.
     */
    public static void saveWhileDragging(Object settings) {
        long now = System.nanoTime();
        if (VANILLA || now - lastSave >= SAVE_EVERY_NS) {
            pending = null;
            save(settings, now);
            return;
        }
        pending = settings;
        deferred++;
    }

    private static void save(Object settings, long now) {
        lastSave = now;
        saves++;
        ((GameSettings) settings).saveOptions();
    }

    /**
     * Display.update: writes what the drag left pending and, from time to time, the count to the
     * log.
     */
    public static void frame() {
        if (pending == null && sent + held + saves + deferred == 0) {
            return;
        }
        long now = System.nanoTime();
        try {
            if (pending != null && now - lastSave >= SAVE_EVERY_NS) {
                Object s = pending;
                pending = null;
                save(s, now);
            }
        } catch (Throwable t) {
            pending = null;
            Log.info("options", "deferred write of options.txt failed: " + t);
        }
        if (lastReport == 0L) {
            lastReport = now;
        } else if (now - lastReport >= REPORT_NS && pending == null) {
            if (held > 0 || deferred > 0 || sent > 1) {
                Log.info(
                        "options",
                        "client settings: "
                                + sent
                                + " sent to the server, "
                                + held
                                + " identical held back | options.txt: "
                                + saves
                                + " writes, "
                                + deferred
                                + " deferred"
                                + (VANILLA ? " (as in the game)" : ""));
            }
            sent = 0;
            held = 0;
            saves = 0;
            deferred = 0;
            lastReport = 0L;
        }
    }
}
