/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.IChatComponent;

/**
 * The chat and screen-keyboard side of {@link GameHooks}: every message entering the chat, each
 * drawn chat row, a key typed on an open screen and a click on a chat text. The bodies of those
 * {@code GameHooks} entry points live here.
 */
final class GameHooksChat {

    private GameHooksChat() {}

    /** A message about to enter the chat (engine.ChatLines.setChatLine). */
    static Object chatLine(Object message, int id) {
        if (GameHooks.SINK == null) {
            return message;
        }
        try {
            return GameHooks.SINK.chatLine(message, id);
        } catch (Throwable e) {
            GameHooks.fail("chatLine", e);
            return message;
        }
    }

    /** A chat row just drawn (engine.ChatView.drawRow), in the chat's coordinates. */
    static void chatRowDrawn(Object message, int x, int y, int width, int alpha) {
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.chatRowDrawn(message, x, y, width, alpha);
            } catch (Throwable e) {
                GameHooks.fail("chatRowDrawn", e);
            }
        }
    }

    /** In place of keyTyped(char, key) in GuiScreen.handleKeyboardInput. */
    static void keyTyped(Object screen, char c, int key) {
        if (GameHooks.SINK != null) {
            try {
                if (GameHooks.SINK.guiKey(screen, c, key)) {
                    return;
                }
            } catch (Throwable e) {
                GameHooks.fail("guiKey", e);
            }
        }
        ((GuiScreen) screen).keyTyped(c, key);
    }

    /** In place of handleComponentClick(component) in GuiChat.mouseClicked. */
    static boolean chatClick(Object screen, Object component) {
        if (component != null && GameHooks.SINK != null) {
            try {
                if (GameHooks.SINK.chatClick(screen, component)) {
                    return true;
                }
            } catch (Throwable e) {
                GameHooks.fail("chatClick", e);
            }
        }
        return ((GuiScreen) screen).handleComponentClick((IChatComponent) component);
    }
}
