/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.util.IChatComponent;

/**
 * Fix for the sign update message in the chat: "Unable to locate sign at x, y, z" goes to the log
 * instead of the player's chat.
 *
 * <p>NetHandlerPlayClient.handleUpdateSign (S33PacketUpdateSign) writes that line in the chat for
 * every sign update whose sign the client does not have loaded. Servers send sign text with the
 * chunk and again when it changes, so standing at the edge of a sign-heavy area (a lobby, a shop
 * street) filled the chat with these lines. It is a debugging message for the developer, not for
 * the player: Mojang removed it from the chat in 1.9. The patcher sends the addChatMessage call of
 * handleUpdateSign here; nothing else in the handler changes.
 */
public final class SignChat {

    private static final RepeatLog LOG = new RepeatLog("signs", 10);

    private SignChat() {}

    /** EntityPlayerSP.addChatMessage(message) in NetHandlerPlayClient.handleUpdateSign. */
    public static void unknownSign(Object player, Object message) {
        LOG.note(((IChatComponent) message).getUnformattedText());
    }
}
