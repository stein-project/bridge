/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.hooks.GameHooks;
import dev.xavier.stein.bridge.hooks.VanillaHud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;

import java.util.List;

/**
 * The chat's message store (GuiNewChat's chatLines and drawnChatLines), with what the game does not
 * keep: when each message arrived, a highlight color, and which message each drawn row came from.
 *
 * <p>The bodies of setChatLine, refreshChat and getChatOpen are replaced here (patcher/redirects-
 * mc.tsv). Without a listener everything is the game's, except two fixes:
 *
 * <ul>
 *   <li>refreshChat (a change of chat width, scale or language) rewrapped each message through
 *       setChatLine, which first deletes the newest message with the same id: a message a server or
 *       a mod replaces in place (id other than 0) lost its older copies, or itself, on every
 *       refresh. Here a refresh only rewraps.
 *   <li>The 100-message cap is the game's default, and a mod may raise it ({@link #setLimit}).
 * </ul>
 *
 * <p>The listeners see every message before it enters ({@code HookSink.chatLine}), wherever it came
 * from (server, the game, a mod), and may change it or keep it out. Everything here runs on the
 * game thread.
 */
public final class ChatLines {

    private ChatLines() {}

    /** The game's cap on messages and rows. */
    public static final int VANILLA_LIMIT = 100;

    /** The most a mod may keep: past this, rewrapping on a resize starts to show. */
    public static final int MAX_LIMIT = 5000;

    /** Ticks a row stays on the closed chat (GuiNewChat.drawChat's 200). */
    private static final int SHOWN_TICKS = 200;

    /** A message in chatLines, with when it arrived and its highlight. */
    static final class Message extends ChatLine {
        final long time;
        int color;

        Message(int counter, IChatComponent component, int id, long time) {
            super(counter, component, id);
            this.time = time;
        }
    }

    /** A drawn row (a wrapped part of a message), with the message it belongs to. */
    static final class Row extends ChatLine {
        final ChatLine message;

        Row(int counter, IChatComponent part, int id, ChatLine message) {
            super(counter, part, id);
            this.message = message;
        }
    }

    private static int limit = VANILLA_LIMIT;

    /** Goes up on every change to the rows: the filtered view rebuilds on a new value. */
    static int version;

    /** A highlight asked for a message that has not entered yet (from inside the hook). */
    private static Object pendingColorFor;

    private static int pendingColor;

    private static GuiNewChat chat() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.ingameGUI == null ? null : mc.ingameGUI.getChatGUI();
    }

    /** Something outside this class changed the rows (ChatFixes.deleteChatLine). */
    static void changed() {
        version++;
    }

    // ---- patcher bodies ----

    /** The body of GuiNewChat.setChatLine. */
    public static void setChatLine(
            Object chatObject, Object componentObject, int id, int counter, boolean displayOnly) {
        GuiNewChat chat = (GuiNewChat) chatObject;
        IChatComponent component = (IChatComponent) componentObject;
        Message message = null;
        if (!displayOnly) {
            Object changed = GameHooks.chatLine(component, id);
            if (!(changed instanceof IChatComponent c)) {
                return; // kept out by a listener
            }
            component = c;
            message = new Message(counter, component, id, System.currentTimeMillis());
            if (pendingColorFor == component) {
                message.color = pendingColor;
                pendingColorFor = null;
            }
        }
        if (id != 0) {
            chat.deleteChatLine(id);
        }
        addRows(chat, component, id, counter, message);
        if (message != null) {
            chat.chatLines.add(0, message);
            trim(chat.chatLines);
        }
        version++;
    }

    /** The body of GuiNewChat.refreshChat: every message rewrapped, none deleted. */
    public static void refreshChat(Object chatObject) {
        GuiNewChat chat = (GuiNewChat) chatObject;
        chat.drawnChatLines.clear();
        chat.resetScroll();
        List<ChatLine> lines = chat.chatLines;
        for (int i = lines.size() - 1; i >= 0; i--) {
            ChatLine line = lines.get(i);
            if (line != null) {
                addRows(
                        chat,
                        line.getChatComponent(),
                        line.getChatLineID(),
                        line.getUpdatedCounter(),
                        line);
            }
        }
        version++;
    }

    /** The body of GuiNewChat.getChatOpen: the chat screen, or the peek. */
    public static boolean chatOpen(Object chatObject) {
        return ChatView.peek || Minecraft.getMinecraft().currentScreen instanceof GuiChat;
    }

    /** The game's wrapping of a message into rows, newest first, each knowing its message. */
    private static void addRows(
            GuiNewChat chat, IChatComponent component, int id, int counter, ChatLine message) {
        int width = MathHelper.floor_float(chat.getChatWidth() / chat.getChatScale());
        List<IChatComponent> parts;
        VanillaHud.chatSplitBegin();
        try {
            parts =
                    GuiUtilRenderComponents.splitText(
                            component,
                            width,
                            Minecraft.getMinecraft().fontRendererObj,
                            false,
                            false);
        } finally {
            VanillaHud.chatSplitEnd();
        }
        boolean open = chat.getChatOpen();
        for (IChatComponent part : parts) {
            if (open && chat.scrollPos > 0) {
                chat.isScrolled = true;
                chat.scroll(1);
            }
            chat.drawnChatLines.add(0, new Row(counter, part, id, message));
        }
        trim(chat.drawnChatLines);
    }

    private static void trim(List<ChatLine> lines) {
        while (lines.size() > limit) {
            lines.remove(lines.size() - 1);
        }
    }

    // ---- for the Loader ----

    /**
     * How many messages the chat keeps (and rows it can scroll through): {@link #VANILLA_LIMIT} up
     * to {@link #MAX_LIMIT}. Lowering it drops the oldest at the next message.
     */
    public static void setLimit(int messages) {
        limit = Math.max(VANILLA_LIMIT, Math.min(MAX_LIMIT, messages));
    }

    /** Messages in the chat. */
    public static int count() {
        GuiNewChat chat = chat();
        return chat == null ? 0 : chat.chatLines.size();
    }

    /** The message at {@code index} (0 is the newest; IChatComponent), or null. */
    public static Object message(int index) {
        ChatLine line = line(index);
        return line == null ? null : line.getChatComponent();
    }

    /** When the message at {@code index} arrived (System.currentTimeMillis), 0 if unknown. */
    public static long time(int index) {
        return line(index) instanceof Message m ? m.time : 0L;
    }

    private static ChatLine line(int index) {
        GuiNewChat chat = chat();
        if (chat == null || index < 0 || index >= chat.chatLines.size()) {
            return null;
        }
        return chat.chatLines.get(index);
    }

    /** The message whose component is {@code component} itself (not an equal one), or null. */
    static ChatLine find(GuiNewChat chat, Object component) {
        for (ChatLine line : chat.chatLines) {
            if (line != null && line.getChatComponent() == component) {
                return line;
            }
        }
        return null;
    }

    /** When the message arrived, 0 if unknown. */
    public static long timeOf(Object component) {
        GuiNewChat chat = chat();
        return chat != null && find(chat, component) instanceof Message m ? m.time : 0L;
    }

    /**
     * Takes a message out of the chat, with its rows: {@code component} is the message itself, as
     * {@link #message} or the hook gave it.
     *
     * @return false if it is no longer in the chat
     */
    public static boolean remove(Object component) {
        GuiNewChat chat = chat();
        ChatLine line = chat == null ? null : find(chat, component);
        if (line == null) {
            return false;
        }
        chat.chatLines.remove(line);
        boolean orphans = false;
        List<ChatLine> rows = chat.drawnChatLines;
        for (int i = rows.size() - 1; i >= 0; i--) {
            ChatLine r = rows.get(i);
            if (r instanceof Row row) {
                if (row.message == line) {
                    rows.remove(i);
                }
            } else {
                orphans = true; // a row put there by someone else: its message is unknown
            }
        }
        if (orphans) {
            chat.refreshChat();
        }
        if (chat.scrollPos > Math.max(0, rows.size() - chat.getLineCount())) {
            chat.resetScroll();
        }
        version++;
        return true;
    }

    /**
     * Adds a message as one already read: it goes in as the newest, but does not appear on the
     * closed chat (only when the chat is open), and does not pass through the listeners.
     *
     * @param time when it arrived (System.currentTimeMillis), for whoever shows the time
     */
    public static void addOld(Object component, long time) {
        GuiNewChat chat = chat();
        if (chat == null || !(component instanceof IChatComponent c)) {
            return;
        }
        int counter = Minecraft.getMinecraft().ingameGUI.getUpdateCounter() - SHOWN_TICKS;
        Message message = new Message(counter, c, 0, time);
        addRows(chat, c, 0, counter, message);
        chat.chatLines.add(0, message);
        trim(chat.chatLines);
        version++;
    }

    /** Empties the chat (messages and rows); what the player typed (the up arrow) stays. */
    public static void clear() {
        GuiNewChat chat = chat();
        if (chat != null) {
            chat.drawnChatLines.clear();
            chat.chatLines.clear();
            chat.resetScroll();
            version++;
        }
    }

    /**
     * A color behind a message's rows (ARGB, the alpha is how strong; 0 removes it). From inside
     * the hook, for the message being returned, it applies as the message enters.
     */
    public static void setColor(Object component, int argb) {
        GuiNewChat chat = chat();
        if (chat != null && find(chat, component) instanceof Message m) {
            m.color = argb;
            return;
        }
        pendingColorFor = component;
        pendingColor = argb;
    }

    /** The highlight of a row's message, 0 if none. */
    static int colorOf(ChatLine row) {
        ChatLine m = row instanceof Row r ? r.message : row;
        return m instanceof Message message ? message.color : 0;
    }

    /** The message a row belongs to (IChatComponent): the row itself if it is not known. */
    static Object messageOf(ChatLine row) {
        ChatLine m = row instanceof Row r ? r.message : null;
        return m != null ? m.getChatComponent() : row.getChatComponent();
    }
}
