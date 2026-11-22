/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.IChatComponent;

/**
 * The chat screen's input field for the Loader: read and set what is typed, send it the way Enter
 * does (optionally keeping the chat open), and run a chat text's click event without the {@code
 * HookSink.chatClick} hook (after the player confirmed it).
 */
public final class ChatInput {

    private ChatInput() {}

    private static GuiChat open() {
        return Minecraft.getMinecraft().currentScreen instanceof GuiChat chat ? chat : null;
    }

    /** What is typed in the open chat; "" with no chat open. */
    public static String text() {
        GuiChat chat = open();
        return chat == null ? "" : chat.inputField.getText();
    }

    /** Replaces what is typed in the open chat (nothing with no chat open). */
    public static void setText(String text) {
        GuiChat chat = open();
        if (chat != null) {
            chat.inputField.setText(text == null ? "" : text);
        }
    }

    /**
     * Sends what is typed, as Enter does (GuiChat.keyTyped: it goes into the up-arrow history and
     * through the send hooks). With keepOpen the chat stays open with the field empty and the
     * history cursor back at the end; otherwise it closes, as in the game.
     */
    public static void submit(boolean keepOpen) {
        GuiChat chat = open();
        if (chat == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        String text = chat.inputField.getText().trim();
        if (!text.isEmpty()) {
            chat.sendChatMessage(text);
        }
        if (!keepOpen) {
            mc.displayGuiScreen(null);
            return;
        }
        if (mc.currentScreen == chat) {
            chat.inputField.setText("");
            chat.sentHistoryCursor = mc.ingameGUI.getChatGUI().getSentMessages().size();
            chat.historyBuffer = "";
        }
    }

    /**
     * Runs a chat text's click event (GuiScreen.handleComponentClick) on {@code screen}, without
     * asking the listeners again.
     *
     * @return the game's answer: true if there was something to run
     */
    public static boolean click(Object screen, Object component) {
        if (!(screen instanceof GuiScreen s) || !(component instanceof IChatComponent c)) {
            return false;
        }
        return s.handleComponentClick(c);
    }
}
