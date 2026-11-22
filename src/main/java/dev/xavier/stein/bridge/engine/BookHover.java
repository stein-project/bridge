/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.IChatComponent;

/**
 * Fix for a book's hover text being covered: the tooltip of a text component under the cursor is
 * drawn after the book's buttons.
 *
 * <p>GuiScreenBook.drawScreen drew the page, then the hover of the component under the cursor
 * (handleComponentHover), and only then called GuiScreen.drawScreen, which draws the buttons: the
 * page arrows and Done were drawn over the tooltip, cutting it where they meet. Here the hover is
 * held until the buttons are drawn, then drawn as the game draws it, last, like every other
 * screen's tooltips. {@code -Dsteinbridge.opt.vanillaBookHover=true} brings back 1.8.9's order.
 */
public final class BookHover {

    private BookHover() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_BOOK_HOVER;

    private static GuiScreen screen;
    private static IChatComponent component;
    private static int hoverX;
    private static int hoverY;

    /** handleComponentHover(component, x, y) in GuiScreenBook.drawScreen. */
    public static void hover(Object book, Object hovered, int x, int y) {
        if (VANILLA) {
            ((GuiScreen) book).handleComponentHover((IChatComponent) hovered, x, y);
            return;
        }
        screen = (GuiScreen) book;
        component = (IChatComponent) hovered;
        hoverX = x;
        hoverY = y;
    }

    /** After GuiScreen.drawScreen (the buttons) in GuiScreenBook.drawScreen. */
    public static void afterButtons() {
        GuiScreen s = screen;
        if (s == null) {
            return;
        }
        IChatComponent c = component;
        screen = null;
        component = null;
        s.handleComponentHover(c, hoverX, hoverY);
    }
}
