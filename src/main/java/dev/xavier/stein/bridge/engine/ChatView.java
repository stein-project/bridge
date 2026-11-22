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
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * How the chat's rows are shown: a filter (only the messages that match, for a search), the peek
 * (the open chat's view without the chat screen), a highlight behind a message and the per-row
 * drawing hook ({@code HookSink.chatRowDrawn}), and which message is under the mouse.
 *
 * <p>The rows reach GuiNewChat's drawChat, getChatComponent and scroll through {@link #size} and
 * {@link #get} (patcher/redirects-mods.tsv, scoped to those three): with no filter they are the
 * game's own list, so nothing changes. The row text goes through {@link #drawRow}.
 */
public final class ChatView {

    private ChatView() {}

    /** Show the chat as if it were open (all rows, the open height), without the chat screen. */
    static volatile boolean peek;

    private static Predicate<Object> filter;

    /** The rows that pass the filter, rebuilt when the rows or the filter change. */
    private static final ArrayList<ChatLine> shown = new ArrayList<>();

    private static int shownVersion = -1;
    private static int shownSourceSize = -1;

    /** The row drawChat fetched last: the one whose text is drawn next. */
    private static ChatLine drawing;

    /** Where the mouse was over the chat at the last {@link #hovered}, in chat units. */
    private static int hoveredColumn = -1;

    /** Turns the peek on or off. */
    public static void setPeek(boolean on) {
        peek = on;
    }

    /**
     * Shows only the messages {@code test} accepts (it receives each message's IChatComponent);
     * null shows them all again. Scrolling goes back to the bottom.
     */
    public static void setFilter(Predicate<Object> test) {
        filter = test;
        shownVersion = -1;
        GuiNewChat chat = chat();
        if (chat != null) {
            chat.resetScroll();
        }
    }

    private static GuiNewChat chat() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.ingameGUI == null ? null : mc.ingameGUI.getChatGUI();
    }

    // ---- patcher rules (drawChat, getChatComponent, scroll) ----

    /** In place of drawnChatLines.size(): the filtered rows' count with a filter. */
    public static int size(Object list) {
        List<?> rows = (List<?>) list;
        return filter == null ? rows.size() : view(rows).size();
    }

    /** In place of drawnChatLines.get(i) in getChatComponent and scroll. */
    public static Object get(Object list, int index) {
        List<?> rows = (List<?>) list;
        return filter == null ? rows.get(index) : view(rows).get(index);
    }

    /** In place of drawnChatLines.get(i) in drawChat: also remembers the row being drawn. */
    public static Object getDrawn(Object list, int index) {
        Object row = get(list, index);
        drawing = row instanceof ChatLine line ? line : null;
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<ChatLine> view(List<?> rows) {
        if (shownVersion != ChatLines.version || shownSourceSize != rows.size()) {
            shownVersion = ChatLines.version;
            shownSourceSize = rows.size();
            shown.clear();
            Predicate<Object> test = filter;
            Object last = null;
            boolean lastPass = false;
            for (ChatLine row : (List<ChatLine>) rows) {
                if (row == null) {
                    continue;
                }
                Object message = ChatLines.messageOf(row);
                if (message != last) { // the rows of a message are next to each other
                    last = message;
                    lastPass = test.test(message);
                }
                if (lastPass) {
                    shown.add(row);
                }
            }
        }
        return shown;
    }

    /**
     * In place of the row's drawStringWithShadow in drawChat: the highlight behind it, the text,
     * then the listeners' drawing for that row, in the chat's own coordinates.
     */
    public static int drawRow(Object fontObject, Object text, float x, float y, int color) {
        FontRenderer font = (FontRenderer) fontObject;
        ChatLine row = drawing;
        int alpha = color >>> 24;
        int width = boxWidth();
        int tint = row == null ? 0 : ChatLines.colorOf(row);
        if (tint != 0) {
            int a = (tint >>> 24) * alpha / 255;
            Gui.drawRect(
                    (int) x, (int) y - 1, (int) x + width, (int) y + 8, a << 24 | tint & 0xFFFFFF);
            GlStateManager.enableBlend(); // drawRect leaves blending off; the text needs it
        }
        int end = font.drawStringWithShadow((String) text, x, y, color);
        if (row != null) {
            GameHooks.chatRowDrawn(ChatLines.messageOf(row), (int) x, (int) y, width, alpha);
        }
        return end;
    }

    /** The width of a row's dark box, in chat units (drawChat's {@code l + 4}). */
    public static int boxWidth() {
        GuiNewChat chat = chat();
        return chat == null
                ? 0
                : MathHelper.ceiling_float_int(chat.getChatWidth() / chat.getChatScale()) + 4;
    }

    // ---- what is under the mouse ----

    /**
     * The message under the mouse in the open chat (IChatComponent), or null; afterwards {@link
     * #hoveredColumn} is where across the row the mouse is. Works out the rows as getChatComponent
     * does, with the chat moved on the grid too, but anywhere across the row and its right margin.
     */
    public static Object hovered() {
        hoveredColumn = -1;
        GuiNewChat chat = chat();
        if (chat == null || !chat.getChatOpen()) {
            return null;
        }
        long at = VanillaHud.chatMouseAt(Mouse.getX(), Mouse.getY());
        if (at == VanillaHud.NO_CHAT) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int scale = new ScaledResolution(mc).getScaleFactor();
        float f = chat.getChatScale();
        int column = MathHelper.floor_float(((int) (at >> 32) / scale - 3) / f);
        int y = MathHelper.floor_float(((int) at / scale - 27) / f);
        List<ChatLine> rows = chat.drawnChatLines;
        int lines = Math.min(chat.getLineCount(), size(rows));
        if (column < 0 || y < 0 || column > boxWidth() + 16) {
            return null;
        }
        int line = y / mc.fontRendererObj.FONT_HEIGHT;
        if (line >= lines) {
            return null;
        }
        int index = line + chat.scrollPos;
        if (index >= size(rows)) {
            return null;
        }
        Object row = get(rows, index);
        if (!(row instanceof ChatLine r)) {
            return null;
        }
        hoveredColumn = column;
        return ChatLines.messageOf(r);
    }

    /** Where across the row the mouse was at the last {@link #hovered} (chat units), or -1. */
    public static int hoveredColumn() {
        return hoveredColumn;
    }
}
