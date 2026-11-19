/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.IChatComponent;

/**
 * The login refusal side of {@link GameHooks} (stein-sync's docs/protocol.md section 6.2): a Stein
 * Sync proxy that requires the Stein client refuses an unmarked login with a reason that carries
 * its Stein Sync offer next to the text, and the Stein Sync client joins again at once with the
 * marker (and over QUIC when the offer says so), so the player never sees the refusal. Without a
 * listener both hooks do exactly what the game does.
 */
final class GameHooksLogin {

    /** The longest reason the game reads (its own limit). */
    private static final int REASON_CHARS = 32767;

    private GameHooksLogin() {}

    /**
     * PacketBuffer.readChatComponent in the login S00PacketDisconnect.readPacketData: the game
     * reads the reason as it always does; the listener also sees its JSON text, read again from the
     * same bytes.
     */
    static Object refusalReason(Object buffer) throws java.io.IOException {
        PacketBuffer buf = (PacketBuffer) buffer;
        SteinSyncLoginHooks l = GameHooks.steinSyncLogin;
        int start = buf.readerIndex();
        IChatComponent reason = buf.readChatComponent();
        if (l != null) {
            int end = buf.readerIndex();
            try {
                buf.readerIndex(start);
                l.loginRefused(buf.readStringFromBuffer(REASON_CHARS));
            } catch (Throwable e) {
                GameHooks.fail("steinSyncLogin.loginRefused", e);
            } finally {
                buf.readerIndex(end);
            }
        }
        return reason;
    }

    /**
     * Minecraft.displayGuiScreen in NetHandlerLoginClient.onDisconnect: the "connection failed"
     * screen, or what the listener shows instead (a new join to the same server).
     */
    static void failedScreen(Object mc, Object screen) {
        Object shown = screen;
        SteinSyncLoginHooks l = GameHooks.steinSyncLogin;
        if (l != null) {
            try {
                Object instead = l.loginFailedScreen(screen);
                if (instead instanceof GuiScreen) {
                    shown = instead;
                }
            } catch (Throwable e) {
                GameHooks.fail("steinSyncLogin.loginFailedScreen", e);
            }
        }
        ((Minecraft) mc).displayGuiScreen((GuiScreen) shown);
    }
}
