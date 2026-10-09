/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.core.Win;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiKeyBindingList;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.boss.BossStatus;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * Diagnostic scenes that open a screen of the game (inventory, pause menu, options, server list,
 * the Loader menu), click its buttons, or draw on the HUD (action bar, boss bar), plus the window
 * minimize check.
 *
 * <p>Each {@code ...Frame()} method is called by {@link Scenes#onFrame()} and does nothing unless
 * its {@code -Dsteinbridge.diag.*} option is set; each scene owns its option reads and state.
 */
final class DiagScreenScenes {
    private DiagScreenScenes() {}

    private static boolean inventoryDone;

    /**
     * {@code -Dsteinbridge.diag.pauseAtSec=N}: opens the pause menu (the Esc one) N seconds after
     * startup. Esc is not a game key (it does not go through KeyBinding), so the key scene does not
     * reach this screen.
     */
    private static boolean pauseDone;

    /**
     * {@code -Dsteinbridge.diag.controlsAtSec=N}: opens Options > Controls N seconds after startup
     * — in the world or in the menu — and scrolls the list to the end over a few frames, to check
     * what mods put there (a mod may insert its button on the screen's first draw, after it is
     * opened).
     */
    private static boolean controlsDone;

    private static GuiControls controlsScreen;
    private static int controlsScrollFrames;

    private static void controls() {
        if (!controlsDone) {
            controlsDone = true;
            GameSettings settings = DiagGame.settings();
            GuiScreen options = new GuiOptions(null, settings);
            controlsScreen = new GuiControls(options, settings);
            DiagGame.open(controlsScreen);
            controlsScrollFrames = 30;
            Log.info(
                    "diag",
                    "controls opened (" + (DiagGame.world() == null ? "menu" : "world") + ")");
            return;
        }
        if (DiagGame.currentScreen() != controlsScreen) {
            controlsScrollFrames = 0;
            return;
        }
        GuiKeyBindingList list = controlsScreen.keyBindingList;
        if (list != null) {
            list.scrollBy(100_000);
        }
        controlsScrollFrames--;
    }

    /**
     * {@code -Dsteinbridge.diag.serverListAtSec=N}: opens the server list and, through the GLFW
     * callbacks (Input.injectMouse), gives a single click in the middle of the first server (N+2),
     * away from the icon (a click on the icon connects on purpose); at N+5 says which screen is
     * open — a click only selects; if the connection screen opened, something turned into a double
     * click. {@code -Dsteinbridge.diag.soundReloadAtSec=N}: two resource reloads in a row (as a mod
     * that reloads resources on startup would cause), to check that only one sound system stays
     * alive.
     */
    private static int serverListStep;

    private static boolean soundReloadDone;
    private static GuiMultiplayer serverListGui;
    private static long serverListOpenedAt;
    private static boolean serverInfoLogged;
    private static long lastPingerSample;

    /**
     * {@code -Dsteinbridge.diag.serverListClick=false}: only measures the ping, without the click
     * (real server).
     */
    private static void serverList(long elapsed) throws Exception {
        long t = elapsed - DiagOptions.SERVER_LIST_AT * 1_000_000_000L;
        if (serverListGui != null
                && !serverInfoLogged
                && System.nanoTime() - lastPingerSample > 700_000_000L) {
            // While the description has not arrived: where the ping threads are (the pool's and
            // Netty's).
            lastPingerSample = System.nanoTime();
            long ms = (lastPingerSample - serverListOpenedAt) / 1_000_000L;
            for (Map.Entry<Thread, StackTraceElement[]> en :
                    Thread.getAllStackTraces().entrySet()) {
                String n = en.getKey().getName();
                if (n.startsWith("Server Pinger") || n.startsWith("Netty Client IO")) {
                    StringBuilder b = new StringBuilder();
                    StackTraceElement[] st = en.getValue();
                    for (int i = 0; i < Math.min(8, st.length); i++) {
                        b.append("\n      ").append(st[i]);
                    }
                    Log.info(
                            "diag",
                            "ping, " + ms + " ms: " + n + " (" + en.getKey().getState() + ")" + b);
                }
            }
        }
        if (serverListGui != null && !serverInfoLogged) {
            // Ping response of the first server.
            ServerList list = serverListGui.savedServerList;
            if (list != null && list.countServers() > 0) {
                ServerData data = list.getServerData(0);
                String p = data.populationInfo;
                if (p != null && !p.isEmpty()) {
                    serverInfoLogged = true;
                    Log.info(
                            "diag",
                            "server list: description and players in "
                                    + (System.nanoTime() - serverListOpenedAt) / 1_000_000L
                                    + " ms (ping "
                                    + data.pingToServer
                                    + " ms, players "
                                    + p
                                    + ")");
                }
            }
        }
        if (serverListStep == 0 && t >= 0) {
            serverListStep = 1;
            GuiMultiplayer gui = new GuiMultiplayer(DiagGame.currentScreen());
            DiagGame.open(gui);
            serverListGui = gui;
            serverListOpenedAt = System.nanoTime();
            Log.info("diag", "server list opened");
        } else if (serverListStep == 2 && t >= 5_000_000_000L && !serverInfoLogged) {
            Log.info("diag", "server list: 5 s without the first server's description");
            serverInfoLogged = true;
        }
        if (serverListStep == 1 && t >= 2_000_000_000L && DiagOptions.SERVER_LIST_CLICK) {
            serverListStep = 2;
            // The list starts at y = 32 (GUI) and each server is 36 tall: the middle of the first
            // one is at y = 50.
            int scale = DiagGame.guiScale();
            double x = DiagGame.mc().displayWidth / 2.0;
            double y = 50.0 * scale;
            DiagGame.click(x, y, 0);
            Log.info("diag", "server list: a single click on the first server");
        } else if (serverListStep == 2 && t >= 5_000_000_000L) {
            serverListStep = 3;
            GuiScreen cur = DiagGame.currentScreen();
            Log.info(
                    "diag",
                    "server list: screen 3 s after the click: "
                            + (cur == null ? "none" : cur.getClass().getName()));
        }
    }

    private static void soundReload(long elapsed) throws Exception {
        if (elapsed < DiagOptions.SOUND_RELOAD_AT * 1_000_000_000L) {
            return;
        }
        soundReloadDone = true;
        DiagGame.mc().refreshResources();
        DiagGame.mc().refreshResources();
        Log.info("diag", "two resource reloads in a row");
    }

    /**
     * {@code -Dsteinbridge.diag.videoAtSec=N}: opens Options > Video Settings N seconds after
     * startup (in the world or in the menu), to check what mods put there.
     */
    private static boolean videoDone;

    private static void video() {
        GameSettings settings = DiagGame.settings();
        GuiScreen options = new GuiOptions(null, settings);
        DiagGame.open(new GuiVideoSettings(options, settings));
        Log.info("diag", "video settings opened");
    }

    /**
     * {@code -Dsteinbridge.diag.loaderMenuAtSec=N}: opens the Stein Loader menu over the current
     * screen N seconds after startup.
     */
    private static boolean loaderMenuDone;

    /**
     * {@code -Dsteinbridge.diag.minimizeAtSec=N} (with {@code minimizeMs}, default 3000): minimizes
     * the window and restores it minimizeMs later — a 0x0 framebuffer with a screen open (the menu
     * crash at 1x1).
     */
    private static int minimizeStage;

    private static void loaderMenu() throws ReflectiveOperationException {
        // The Loader screen is reached by reflection: the Bridge does not depend on the Loader.
        Class<?> loader = Class.forName("dev.xavier.stein.loader.Loader");
        Object menu =
                Class.forName("dev.xavier.stein.loader.gui.LoaderScreen")
                        .getConstructor(loader, GuiScreen.class)
                        .newInstance(
                                loader.getMethod("get").invoke(null), DiagGame.currentScreen());
        // -Dsteinbridge.diag.loaderMenuPage=N: opens straight on page N (0 Mods, 1 About); only
        // in the development build.
        int page = DiagOptions.LOADER_MENU_PAGE;
        if (page > 0) {
            Field f = menu.getClass().getDeclaredField("page");
            f.setAccessible(true);
            f.setInt(menu, page);
        }
        // -Dsteinbridge.diag.loaderMenuSelected=N: line N of the mod list selected (its details
        // on the right).
        int selected = DiagOptions.LOADER_MENU_SELECTED;
        if (selected >= 0) {
            Field f = menu.getClass().getDeclaredField("selected");
            f.setAccessible(true);
            f.setInt(menu, selected);
        }
        DiagGame.open((GuiScreen) menu);
        Log.info("diag", "Stein Loader menu opened (page " + page + ")");
    }

    private static void minimize(long elapsed) {
        long at = DiagOptions.MINIMIZE_AT * 1_000_000_000L;
        if (minimizeStage == 0 && elapsed >= at) {
            minimizeStage = 1;
            GLFW.glfwIconifyWindow(Win.handle);
            Log.info("diag", "window minimized");
        } else if (minimizeStage == 1 && elapsed >= at + DiagOptions.MINIMIZE_MS * 1_000_000L) {
            minimizeStage = 2;
            GLFW.glfwRestoreWindow(Win.handle);
            Log.info("diag", "window restored: " + Win.width + "x" + Win.height);
        }
    }

    /**
     * {@code -Dsteinbridge.diag.soundOptionsAtSec=N}: opens the volume screen (Options > Music and
     * Sounds) N seconds after startup, in the world — a check of the client settings sent to the
     * server while a control is being dragged (engine/ClientSettings).
     */
    private static boolean soundOptionsDone;

    private static void soundOptions() {
        if (DiagGame.world() == null) {
            return; // no world yet
        }
        soundOptionsDone = true;
        DiagGame.open(new GuiScreenOptionsSounds(null, DiagGame.settings()));
        Log.info("diag", "volume screen opened");
    }

    /**
     * {@code -Dsteinbridge.diag.clicks=S:ID,S:ID,...}: at S seconds from startup, clicks (left
     * button, at the center) the button with id ID on the open screen, through its mouseClicked —
     * the path of a real click, with the mods' hooks. With videoAtSec and screenshotAtSec, it
     * photographs mods' screens without touching the mouse (hexadecimal ids work: 0x5E1E5).
     */
    private static final long[][] CLICKS = parseClicks(DiagOptions.CLICKS);

    private static int clickNext;

    private static long[][] parseClicks(String s) {
        if (s.isEmpty()) {
            return new long[0][];
        }
        String[] parts = s.split(",");
        long[][] out = new long[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            String[] p = parts[i].split(":");
            out[i] = new long[] {Long.parseLong(p[0].trim()), Long.decode(p[1].trim())};
        }
        return out;
    }

    private static void click(int id) throws IOException {
        GuiScreen screen = DiagGame.currentScreen();
        if (screen == null) {
            Log.info("diag", "click " + id + ": no screen open");
            return;
        }
        for (GuiButton b : screen.buttonList) {
            if (b.id != id) {
                continue;
            }
            int x = b.xPosition + b.width / 2;
            int y = b.yPosition + b.height / 2;
            screen.mouseClicked(x, y, 0);
            Log.info("diag", "click on button " + id + " of " + screen.getClass().getName());
            return;
        }
        Log.info("diag", "click " + id + ": button not found in " + screen.getClass().getName());
    }

    /**
     * {@code -Dsteinbridge.diag.actionBar=S:text|S:text...}: at S seconds, the text on the action
     * bar (GuiIngame.setRecordPlaying, the path of the server's message) — 1.8 has no command for
     * it. '_' becomes a space.
     */
    private static int actionNext;

    private static void actionBar(long elapsed) {
        String entry = DiagOptions.ACTION_BAR[actionNext];
        int colon = entry.indexOf(':');
        if (elapsed < Long.parseLong(entry.substring(0, colon)) * 1_000_000_000L) {
            return;
        }
        actionNext++;
        String text = entry.substring(colon + 1).replace('_', ' ');
        DiagGame.mc().ingameGUI.setRecordPlaying(text, false);
        Log.info("diag", "action bar: " + text);
    }

    /**
     * {@code -Dsteinbridge.diag.boss=S1:S2:name}: from S1 to S2 seconds, the boss bar as a boss on
     * screen shows it (the BossStatus, which the Wither's and Dragon's drawing fills in every
     * frame), with 60% health — the bar without depending on a mob on the server. '_' becomes a
     * space.
     */
    private static boolean bossLogged;

    private static boolean bossFailed;

    private static void boss(long elapsed) {
        if (elapsed < Long.parseLong(DiagOptions.BOSS[0]) * 1_000_000_000L
                || elapsed > Long.parseLong(DiagOptions.BOSS[1]) * 1_000_000_000L) {
            return;
        }
        if (!bossLogged) {
            bossLogged = true;
            Log.info("diag", "boss bar: " + DiagOptions.BOSS[2].replace('_', ' '));
        }
        BossStatus.healthScale = 0.6F;
        BossStatus.statusBarTime = 100;
        BossStatus.bossName = DiagOptions.BOSS[2].replace('_', ' ');
    }

    // ---- per-frame entry points, called by Scenes.onFrame() in that order

    static void soundOptionsFrame() {
        if (DiagOptions.SOUND_OPTIONS_AT >= 0
                && !soundOptionsDone
                && Scenes.elapsed() >= DiagOptions.SOUND_OPTIONS_AT * 1_000_000_000L) {
            try {
                soundOptions();
            } catch (RuntimeException e) {
                soundOptionsDone = true;
                Log.info("diag", "volume screen: " + e);
            }
        }
    }

    static void serverListFrame() {
        if (DiagOptions.SERVER_LIST_AT >= 0 && serverListStep < 3) {
            try {
                serverList(Scenes.elapsed());
            } catch (Exception e) {
                serverListStep = 3;
                Log.info("diag", "failed in the server list: " + e);
            }
        }
    }

    static void soundReloadFrame() {
        if (DiagOptions.SOUND_RELOAD_AT >= 0 && !soundReloadDone) {
            try {
                soundReload(Scenes.elapsed());
            } catch (Exception e) {
                soundReloadDone = true;
                Log.info("diag", "failed in the reloads: " + e);
            }
        }
    }

    static void clicksFrame() {
        while (clickNext < CLICKS.length
                && Scenes.elapsed() >= CLICKS[clickNext][0] * 1_000_000_000L) {
            int id = (int) CLICKS[clickNext++][1];
            try {
                click(id);
            } catch (IOException | RuntimeException e) {
                Log.info("diag", "failed on click " + id + ": " + e);
            }
        }
    }

    static void loaderMenuFrame() {
        if (DiagOptions.LOADER_MENU_AT >= 0
                && !loaderMenuDone
                && Scenes.elapsed() >= DiagOptions.LOADER_MENU_AT * 1_000_000_000L) {
            loaderMenuDone = true;
            try {
                loaderMenu();
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.info("diag", "failed opening the Stein Loader menu: " + e);
            }
        }
    }

    static void minimizeFrame() {
        if (DiagOptions.MINIMIZE_AT >= 0 && minimizeStage < 2) {
            minimize(Scenes.elapsed());
        }
    }

    static void actionBarFrame() {
        if (actionNext < DiagOptions.ACTION_BAR.length) {
            try {
                actionBar(Scenes.elapsed());
            } catch (RuntimeException e) {
                actionNext++;
                Log.info("diag", "failed in the action bar: " + e);
            }
        }
    }

    static void bossFrame() {
        if (DiagOptions.BOSS.length == 3 && !bossFailed) {
            try {
                boss(Scenes.elapsed());
            } catch (RuntimeException e) {
                bossFailed = true;
                Log.info("diag", "failed in the boss bar: " + e);
            }
        }
    }

    static void videoFrame() {
        if (DiagOptions.VIDEO_AT >= 0
                && !videoDone
                && Scenes.elapsed() >= DiagOptions.VIDEO_AT * 1_000_000_000L) {
            videoDone = true;
            try {
                video();
            } catch (RuntimeException e) {
                Log.info("diag", "failed opening the video settings: " + e);
            }
        }
    }

    static void controlsFrame() {
        if (DiagOptions.CONTROLS_AT >= 0
                && (!controlsDone || controlsScrollFrames > 0)
                && Scenes.elapsed() >= DiagOptions.CONTROLS_AT * 1_000_000_000L) {
            try {
                controls();
            } catch (RuntimeException e) {
                controlsScrollFrames = 0;
                Log.info("diag", "failed opening the controls: " + e);
            }
        }
    }

    static void pauseFrame() {
        if (DiagOptions.PAUSE_AT >= 0
                && !pauseDone
                && Scenes.elapsed() >= DiagOptions.PAUSE_AT * 1_000_000_000L) {
            pauseDone = true;
            DiagGame.open(new GuiIngameMenu());
            Log.info("diag", "pause menu opened");
        }
    }

    static void inventoryFrame() {
        if (DiagOptions.INVENTORY_AT < 0 || inventoryDone) {
            return;
        }
        if (Scenes.elapsed() < DiagOptions.INVENTORY_AT * 1_000_000_000L) {
            return;
        }
        inventoryDone = true;
        EntityPlayerSP player = DiagGame.player();
        if (player == null) {
            Log.info("diag", "inventory: no player yet");
            return;
        }
        DiagGame.open(new GuiInventory(player));
        Log.info("diag", "inventory opened");
    }
}
