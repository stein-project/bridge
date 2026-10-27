/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

import dev.xavier.stein.bridge.SteinBridge;
import dev.xavier.stein.bridge.bench.BenchZones;
import dev.xavier.stein.bridge.engine.FrameTime;
import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinGfxOptions;
import dev.xavier.stein.gfx.game.SteinGameGl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiOverlayDebug;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.Slot;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.IChatComponent;

import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The screens and the HUD side of {@link GameHooks}: the overlay, the open screen, chat, the F3
 * lines, the main menu branding, the Tab list and the inventory's effects list. The bodies of the
 * {@code GameHooks} entry points live here.
 *
 * <p>{@code GameHooks} forces this class to initialise from its own static initializer.
 */
final class GameHooksHud {

    /** Fixed lines on the right side of F3: Java, memory (2), blank, CPU, blank, screen, GL (2). */
    private static final int DEBUG_SYSTEM_LINES = 9;

    /** Height of a line of the main menu branding, in GUI pixels. */
    private static final int MENU_LINE_HEIGHT = 10;

    private GameHooksHud() {}

    /** Forces this class to initialise: {@code GameHooks} calls it from its static initializer. */
    static void init() {}

    /**
     * In place of GuiIngame.renderGameOverlay(pt) in updateCameraAndRender. With the HUD hidden
     * only the 2D projection is set up (EntityRenderer.setupOverlayRendering, which in 1.8.9 is
     * what renderGameOverlay itself calls): without it, a screen opened afterwards would draw with
     * the world projection, and would not show up.
     */
    static void gameOverlay(Object gui, float partialTicks) {
        if (!GameHooks.hideHud) {
            ((GuiIngame) gui).renderGameOverlay(partialTicks);
            return;
        }
        Minecraft.getMinecraft().entityRenderer.setupOverlayRendering();
    }

    /** After GuiIngame.renderGameOverlay (avo.a), in updateCameraAndRender. */
    static void overlay() {
        if (GameHooks.hideHud) {
            VanillaHud.endFrame();
            return;
        }
        if (GameHooks.SINK != null) {
            BenchZones.begin(BenchZones.MODS_HUD);
            try {
                GameHooks.SINK.overlay(GameHooksRender.partialTicks());
            } catch (Throwable e) {
                GameHooks.fail("overlay", e);
            }
            BenchZones.end(BenchZones.MODS_HUD);
        }
        VanillaHud.endFrame(); // the side scoreboard's objective belongs to this frame only
    }

    /** In place of currentScreen.drawScreen(x, y, pt) in updateCameraAndRender. */
    static void drawScreen(Object screen, int mouseX, int mouseY, float partialTicks) {
        if (GameHooks.SINK != null) {
            BenchZones.begin(BenchZones.MODS_SCREEN);
            try {
                GameHooks.SINK.beforeDrawScreen(screen, mouseX, mouseY, partialTicks);
            } catch (Throwable e) {
                GameHooks.fail("beforeDrawScreen", e);
            }
            BenchZones.end(BenchZones.MODS_SCREEN);
        }
        ((GuiScreen) screen).drawScreen(mouseX, mouseY, partialTicks);
        if (GameHooks.SINK != null) {
            // the zone sums both halves of the frame's screen events
            BenchZones.begin(BenchZones.MODS_SCREEN);
            try {
                GameHooks.SINK.afterDrawScreen(screen, mouseX, mouseY, partialTicks);
            } catch (Throwable e) {
                GameHooks.fail("afterDrawScreen", e);
            }
            BenchZones.end(BenchZones.MODS_SCREEN);
        }
    }

    /** In place of handleMouseInput() in GuiScreen.handleInput: the listener can keep the event. */
    static void handleMouseInput(Object screen) {
        if (GameHooks.SINK != null) {
            try {
                if (GameHooks.SINK.guiMouseInput(screen)) {
                    return;
                }
            } catch (Throwable e) {
                GameHooks.fail("guiMouseInput", e);
            }
        }
        ((GuiScreen) screen).handleMouseInput();
    }

    /** In place of printChatMessage(msg) in handleChat (S02PacketChat, types 0 and 1). */
    static void printChat(Object chatGui, Object message) {
        Object msg = message;
        if (GameHooks.SINK != null) {
            try {
                msg = GameHooks.SINK.chat(message);
            } catch (Throwable e) {
                GameHooks.fail("chat", e);
                msg = message;
            }
            if (msg == null) {
                return;
            }
        }
        ((GuiNewChat) chatGui).printChatMessage((IChatComponent) msg);
    }

    /**
     * In place of getDebugInfoRight() in GuiOverlayDebug.renderDebugInfoRight: the SteinBridge (and
     * the listener) come after the system lines and before the targeted block's, where Forge puts
     * its own.
     */
    @SuppressWarnings("unchecked")
    static Object debugInfoRight(Object overlay) {
        List<String> lines = ((GuiOverlayDebug) overlay).getDebugInfoRight();
        if (SteinGfxOptions.VULKAN) {
            // the game's version line is GL_VERSION, which with Vulkan starts with an OpenGL
            // version for mods that read it as a number ("3.0 (Vulkan 1.3.0)"): F3 shows only the
            // API in use
            int i = lines.indexOf(SteinGameGl.glGetString(GL11.GL_VERSION));
            if (i >= 0) {
                lines.set(i, SteinDevice.get().caps().backend);
            }
        }
        List<String> ours = new ArrayList<>();
        ours.add("");
        ours.add(SteinBridge.NAME + " v" + SteinBridge.VERSION);
        ours.add(SteinGfxOptions.describe());
        ours.add(FrameTime.describe());
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.debugInfoRight(ours);
            } catch (Throwable e) {
                GameHooks.fail("debugInfoRight", e);
            }
        }
        lines.addAll(Math.min(DEBUG_SYSTEM_LINES, lines.size()), ours);
        return lines;
    }

    /**
     * In place of the drawString calls in GuiMainMenu.drawScreen: the "Minecraft 1.8.9" line (x =
     * 2, at the foot of the screen) becomes the version block in the bottom left corner, like
     * Forge's — the game, the SteinBridge and whatever the listener adds —, from top to bottom up
     * to where it was. The others (copyright) stay the same.
     */
    static void mainMenuString(Object gui, Object font, Object text, int x, int y, int color) {
        if (x != 2 || !(text instanceof String s) || !s.startsWith("Minecraft")) {
            ((Gui) gui).drawString((FontRenderer) font, (String) text, x, y, color);
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add(s);
        lines.add(SteinBridge.NAME + " v" + SteinBridge.VERSION);
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.mainMenuBranding(lines);
            } catch (Throwable e) {
                GameHooks.fail("mainMenuBranding", e);
            }
        }
        int top = y - MENU_LINE_HEIGHT * (lines.size() - 1);
        for (int i = 0; i < lines.size(); i++) {
            ((Gui) gui)
                    .drawString(
                            (FontRenderer) font,
                            lines.get(i),
                            x,
                            top + MENU_LINE_HEIGHT * i,
                            color);
        }
    }

    /**
     * In place of initGui() in GuiScreen.setWorldAndResolution: after it, the listener can add
     * buttons to the screen, as in Forge's InitGuiEvent.Post. Applies to every screen, including
     * when the window is resized.
     */
    @SuppressWarnings("unchecked")
    static void initGui(Object screen) {
        GuiScreen guiScreen = (GuiScreen) screen;
        guiScreen.initGui();
        if (GameHooks.SINK != null) {
            try {
                Object buttons = guiScreen.buttonList;
                GameHooks.SINK.guiInit(screen, (List<Object>) buttons);
            } catch (Throwable e) {
                GameHooks.fail("guiInit", e);
            }
        }
    }

    /**
     * In place of actionPerformed(button) in GuiScreen.mouseClicked: the listener sees the click
     * before the screen and can keep it (the buttons it placed itself), as in Forge's
     * ActionPerformedEvent.Pre.
     */
    static void actionPerformed(Object screen, Object button) {
        if (GameHooks.SINK != null) {
            try {
                if (GameHooks.SINK.guiAction(screen, button)) {
                    return;
                }
            } catch (Throwable e) {
                GameHooks.fail("guiAction", e);
            }
        }
        ((GuiScreen) screen).actionPerformed((GuiButton) button);
    }

    private static volatile boolean hideInventoryEffects;

    /** Removes the inventory's effects list (and the screen's sideways shift). */
    static void setInventoryEffectsHidden(boolean hide) {
        hideInventoryEffects = hide;
    }

    /**
     * In place of getActivePotionEffects() in InventoryEffectRenderer (updateActivePotionEffects):
     * empty list = centered inventory without the effects list beside it.
     */
    static Object inventoryEffects(Object player) {
        if (hideInventoryEffects) {
            return Collections.emptyList();
        }
        return ((EntityLivingBase) player).getActivePotionEffects();
    }

    /**
     * The number of the slot under the mouse on the open inventory screen, or -1 (no inventory
     * screen, outside the slots).
     */
    static int hoveredSlot(Object screen) {
        if (!(screen instanceof GuiContainer container)) {
            return -1;
        }
        Slot slot = container.theSlot;
        return slot == null ? -1 : slot.slotNumber;
    }

    /**
     * In place of GuiPlayerTabOverlay.renderPlayerlist, inside renderGameOverlay: whoever listens
     * can draw the Tab list its own way (the Stein HUD). With nobody drawing, the game's goes out
     * as usual.
     */
    static void playerList(Object overlay, int width, Object scoreboard, Object objective) {
        if (GameHooks.SINK != null) {
            try {
                if (GameHooks.SINK.playerList(overlay, width, scoreboard, objective)) {
                    return;
                }
            } catch (Throwable e) {
                GameHooks.fail("playerList", e);
            }
        }
        ((GuiPlayerTabOverlay) overlay)
                .renderPlayerlist(width, (Scoreboard) scoreboard, (ScoreObjective) objective);
    }

    /**
     * In place of getPlayerName(info) in GuiPlayerTabOverlay.renderPlayerlist: the name in the Tab,
     * which the listener can change.
     */
    static Object playerListName(Object overlay, Object info) {
        String name = ((GuiPlayerTabOverlay) overlay).getPlayerName((NetworkPlayerInfo) info);
        if (GameHooks.SINK != null) {
            try {
                String n = GameHooks.SINK.playerListName(info, name);
                if (n != null) {
                    return n;
                }
            } catch (Throwable e) {
                GameHooks.fail("playerListName", e);
            }
        }
        return name;
    }
}
