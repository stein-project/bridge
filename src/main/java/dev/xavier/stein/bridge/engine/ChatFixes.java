/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.MathHelper;

import java.util.Iterator;
import java.util.List;

/**
 * Fixes in the game's chat box (GuiNewChat).
 *
 * <ul>
 *   <li>getChatComponent takes a row as on the chat while it is below {@code (FONT_HEIGHT + 1)}
 *       times the line count, a tenth more than the lines drawn: a click or hover a little above
 *       the open chat's top line reached the hidden lines above it and ran their click event (a
 *       command or a link the player never saw). Here a row past the lines on show finds nothing,
 *       as in later versions. {@code -Dsteinbridge.opt.vanillaChatClick=true} brings back 1.8.9's
 *       behaviour.
 *   <li>deleteChatLine read the id of every line without a null check: a null line (put there by a
 *       mod or a broken message) made each later message with an id (the ones a server or a mod
 *       replaces in place) throw, and the game crashed. Here a null line is passed over.
 * </ul>
 */
public final class ChatFixes {

    private ChatFixes() {}

    private static final boolean VANILLA_CLICK = GuiFixOptions.VANILLA_CHAT_CLICK;

    /**
     * The game's getChatComponent, except above the lines on show. The rows are worked out exactly
     * as the game does.
     */
    public static Object component(Object chatObject, int mouseX, int mouseY) {
        GuiNewChat chat = (GuiNewChat) chatObject;
        if (!VANILLA_CLICK && chat.getChatOpen()) {
            Minecraft mc = Minecraft.getMinecraft();
            int scale = new ScaledResolution(mc).getScaleFactor();
            int row = MathHelper.floor_float((mouseY / scale - 27) / chat.getChatScale());
            int lines = Math.min(chat.getLineCount(), chat.drawnChatLines.size());
            if (row >= 0 && row / mc.fontRendererObj.FONT_HEIGHT >= lines) {
                return null;
            }
        }
        return chat.getChatComponent(mouseX, mouseY);
    }

    /** The body of GuiNewChat.deleteChatLine, the same but past null lines. */
    public static void deleteChatLine(Object chatObject, int id) {
        GuiNewChat chat = (GuiNewChat) chatObject;
        removeAll(chat.drawnChatLines, id);
        List<ChatLine> lines = chat.chatLines;
        for (Iterator<ChatLine> it = lines.iterator(); it.hasNext(); ) {
            ChatLine line = it.next();
            if (line != null && line.getChatLineID() == id) {
                it.remove();
                break; // the game removes only the newest full message with that id
            }
        }
    }

    private static void removeAll(List<ChatLine> lines, int id) {
        for (Iterator<ChatLine> it = lines.iterator(); it.hasNext(); ) {
            ChatLine line = it.next();
            if (line != null && line.getChatLineID() == id) {
                it.remove();
            }
        }
    }
}
