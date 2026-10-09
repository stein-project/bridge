/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.hooks;

import dev.xavier.stein.bridge.engine.TextHook;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.boss.BossStatus;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScoreObjective;

/**
 * Parts of the game's own HUD that the Stein Loader can take over (HUD grid): the action bar (the
 * text above the hotbar), the boss bar and the sidebar scoreboard. Once taken over, the part
 * disappears from the game's drawing and the content stays here for the Loader to draw where the
 * player put it, in the format they chose (or not draw, if they turned it off). Without the Loader,
 * nothing changes.
 *
 * <ul>
 *   <li>Action bar: the body of GuiIngame.setRecordPlaying (patcher rule; the IChatComponent one
 *       and the record one go through it, and so do mods that call it directly) keeps the text;
 *       once taken over, the game's counter stays at 0.
 *   <li>Boss bar: in place of renderBossHealth in renderGameOverlay. The game discounts the bar's
 *       time (BossStatus.statusBarTime) inside the drawing — once taken over, the discount
 *       continues here, otherwise the bar would never disappear.
 *   <li>Sidebar scoreboard: in place of renderScoreboard, which receives the frame's objective (the
 *       player's team one, if any); the objective stays here until the end of the frame's HUD.
 * </ul>
 */
public final class VanillaHud {

    /** Game ticks the action bar stays on screen (the game's). */
    private static final int ACTION_TICKS = 60;

    private static final double TICKS_PER_SECOND = 20.0;

    private static volatile boolean actionBarTakenOver;
    private static volatile boolean bossBarTakenOver;
    private static volatile boolean sidebarTakenOver;
    private static volatile boolean hotbarTakenOver;
    private static volatile boolean statsTakenOver;
    private static volatile boolean experienceTakenOver;
    private static volatile boolean itemNameTakenOver;
    private static volatile boolean chatTakenOver;

    /**
     * Chat moved on the grid, for clicking and hovering over its texts: on-screen box (left, top),
     * the game's box (left, top), grid scale, GUI scale and screen height, in GUI pixels; null with
     * the chat turned off (nothing to click).
     */
    private static volatile float[] chatMouse;

    private static String actionText;
    private static boolean actionPlaying;
    private static long actionSince;
    private static Object objective;

    private VanillaHud() {}

    /** The Loader takes over (or gives back to the game) each part. */
    public static void setTakeover(boolean action, boolean boss, boolean side) {
        actionBarTakenOver = action;
        bossBarTakenOver = boss;
        sidebarTakenOver = side;
    }

    /**
     * The Loader takes over (or gives back to the game) the hotbar, health and hunger, experience,
     * item name and chat.
     */
    public static void setTakeoverParts(
            boolean hotbarPart,
            boolean statsPart,
            boolean experiencePart,
            boolean itemNamePart,
            boolean chatPart) {
        hotbarTakenOver = hotbarPart;
        statsTakenOver = statsPart;
        experienceTakenOver = experiencePart;
        itemNameTakenOver = itemNamePart;
        chatTakenOver = chatPart;
    }

    /** Chat lines drawn: the count of the frame in progress and that of the last complete frame. */
    private static int chatCount;

    private static int chatLines;
    private static float chatAlphaCount;
    private static float chatAlpha;

    /** Dark box behind the chat lines: on, as in the game, until the Loader turns it off. */
    private static boolean chatBackgroundShown = true;

    /**
     * Show or not the dark box behind the chat ("Background" option, on the Panel's Screen page).
     */
    public static void setChatBackground(boolean on) {
        chatBackgroundShown = on;
    }

    /**
     * In place of Gui.drawRect inside GuiNewChat.drawChat. There are three: each line's box, which
     * only has alpha (the RGB is zero), and the chat scrollbar's two, which have color. With the
     * background off, the first is not drawn and the scrollbar's stay — whoever removes the
     * background still wants to see that the chat scrolled.
     */
    public static void chatBackground(int left, int top, int right, int bottom, int color)
            throws Throwable {
        if ((color & 0xFFFFFF) == 0) {
            chatCount++; // a line's box: one more line on screen this frame
            // The game paints the box at half the line's opacity: the most visible line is how
            // much the chat shows.
            chatAlphaCount = Math.max(chatAlphaCount, Math.min(1.0F, (color >>> 24) * 2 / 255.0F));
            if (!chatBackgroundShown) {
                return;
            }
        }
        Gui.drawRect(left, top, right, bottom, color);
    }

    /** Where the chat is on the grid (see {@link #chatMouse}); null when turned off. */
    public static void chatPlacement(float[] placement) {
        chatMouse = placement;
    }

    // ---- patcher rules ----

    /** In place of renderTooltip (the hotbar) in renderGameOverlay. */
    public static void hotbar(Object gui, Object resolution, float partialTicks) throws Throwable {
        if (!hotbarTakenOver) {
            ((GuiIngame) gui).renderTooltip((ScaledResolution) resolution, partialTicks);
        }
    }

    /** In place of renderPlayerStats (health, armor, hunger, air, mount health). */
    public static void playerStats(Object gui, Object resolution) throws Throwable {
        if (!statsTakenOver) {
            ((GuiIngame) gui).renderPlayerStats((ScaledResolution) resolution);
        }
    }

    /** In place of renderHorseJumpBar (the horse jump bar, in place of the experience one). */
    public static void horseJumpBar(Object gui, Object resolution, int x) throws Throwable {
        if (!experienceTakenOver) {
            ((GuiIngame) gui).renderHorseJumpBar((ScaledResolution) resolution, x);
        }
    }

    /** In place of renderExpBar. */
    public static void expBar(Object gui, Object resolution, int x) throws Throwable {
        if (!experienceTakenOver) {
            ((GuiIngame) gui).renderExpBar((ScaledResolution) resolution, x);
        }
    }

    /** In place of renderSelectedItem (the item name above the bar when switching slots). */
    public static void selectedItem(Object gui, Object resolution) throws Throwable {
        if (!itemNameTakenOver) {
            ((GuiIngame) gui).renderSelectedItem((ScaledResolution) resolution);
        }
    }

    /** Did the line wrap in progress count as grid text ({@link #chatSplitBegin})? */
    private static boolean chatSplit;

    /**
     * The grid's chat with the game's own font (its "Font" option): wrapping and click measure by
     * the game's.
     */
    private static boolean chatGameFont;

    public static void setChatGameFont(boolean on) {
        chatGameFont = on;
    }

    /**
     * Before and after GuiNewChat wraps a message into lines (setChatLine): with the chat on the
     * grid, it is drawn in the HUD font, so the wrapping has to measure by it — otherwise the line
     * would overflow the box, or wrap too early.
     */
    public static void chatSplitBegin() {
        chatSplit = chatTakenOver && !chatGameFont;
        if (chatSplit) {
            TextHook.hudDepth++;
        }
    }

    public static void chatSplitEnd() {
        if (chatSplit) {
            chatSplit = false;
            TextHook.hudDepth--;
        }
    }

    /** In place of GuiNewChat.drawChat in renderGameOverlay. */
    public static void chat(Object chatGui, int updateCounter) throws Throwable {
        if (!chatTakenOver) {
            ((GuiNewChat) chatGui).drawChat(updateCounter);
        }
    }

    /**
     * In place of GuiNewChat.getChatComponent (click and hover in GuiChat): with the chat moved,
     * the mouse goes back to where the text would be in the game's place; with it off, nothing.
     */
    public static Object chatComponent(Object chatGui, int mouseX, int mouseY) throws Throwable {
        if (chatTakenOver) {
            float[] m = chatMouse;
            if (m == null) {
                return null;
            }
            float sf = m[5];
            float sh = m[6];
            float gx = mouseX / sf;
            float gy = sh - mouseY / sf;
            float ox = m[2] + (gx - m[0]) / m[4];
            float oy = m[3] + (gy - m[1]) / m[4];
            // The words are found by width: the same font the grid's chat was drawn in.
            int depth = chatGameFont ? 0 : 1;
            TextHook.hudDepth += depth;
            try {
                return ((GuiNewChat) chatGui)
                        .getChatComponent(Math.round(ox * sf), Math.round((sh - oy) * sf));
            } finally {
                TextHook.hudDepth -= depth;
            }
        }
        return ((GuiNewChat) chatGui).getChatComponent(mouseX, mouseY);
    }

    // ---- drawing of the game's parts by the Loader (with the grid position already applied) ----

    public static void drawHotbar(Object gui, Object resolution, float partialTicks)
            throws Throwable {
        ((GuiIngame) gui).renderTooltip((ScaledResolution) resolution, partialTicks);
    }

    /** A hotbar item, without the frame (renderHotbarItem): the grid's "no frame" variation. */
    public static void drawHotbarItem(
            Object gui, int index, int x, int y, float partialTicks, Object player)
            throws Throwable {
        ((GuiIngame) gui).renderHotbarItem(index, x, y, partialTicks, (EntityPlayer) player);
    }

    public static void drawPlayerStats(Object gui, Object resolution) throws Throwable {
        ((GuiIngame) gui).renderPlayerStats((ScaledResolution) resolution);
    }

    public static void drawExperience(Object gui, Object resolution, int x, boolean jumpBar)
            throws Throwable {
        if (jumpBar) {
            ((GuiIngame) gui).renderHorseJumpBar((ScaledResolution) resolution, x);
        } else {
            ((GuiIngame) gui).renderExpBar((ScaledResolution) resolution, x);
        }
    }

    public static void drawSelectedItem(Object gui, Object resolution) throws Throwable {
        ((GuiIngame) gui).renderSelectedItem((ScaledResolution) resolution);
    }

    public static void drawChat(Object chatGui, int updateCounter) throws Throwable {
        chatCount = 0;
        chatAlphaCount = 0.0F;
        try {
            ((GuiNewChat) chatGui).drawChat(updateCounter);
        } finally {
            chatLines = chatCount;
            chatAlpha = chatAlphaCount;
        }
    }

    /** How much the chat's most visible line showed in the last frame (0 to 1). */
    public static float chatAlphaShown() {
        return chatAlpha;
    }

    /**
     * Lines the chat drew in the last frame (0 when empty, or with the messages already erased).
     */
    public static int chatLinesShown() {
        return chatLines;
    }

    /** Ticks the item name stays on screen. */
    public static int highlightTicks(Object gui) {
        return ((GuiIngame) gui).remainingHighlightTicks;
    }

    /** The item of the name on screen (ItemStack), or null. */
    public static Object highlightStack(Object gui) {
        return ((GuiIngame) gui).highlightingItemStack;
    }

    /**
     * Body of GuiIngame.setRecordPlaying(text, record): like the game's, but with the part taken
     * over the game does not draw.
     */
    public static void setRecordPlaying(Object gui, Object text, boolean playing) {
        actionText = (String) text;
        actionPlaying = playing;
        actionSince = System.nanoTime();
        GuiIngame ingame = (GuiIngame) gui;
        ingame.recordPlaying = (String) text;
        ingame.recordPlayingUpFor = actionBarTakenOver ? 0 : ACTION_TICKS;
        ingame.recordIsPlaying = playing;
    }

    /** In place of renderBossHealth() in renderGameOverlay. */
    public static void boss(Object gui) throws Throwable {
        if (!bossBarTakenOver) {
            ((GuiIngame) gui).renderBossHealth();
            return;
        }
        // The discount that the game's drawing would make.
        int time = BossStatus.statusBarTime;
        if (BossStatus.bossName != null && time > 0) {
            BossStatus.statusBarTime = time - 1;
        }
    }

    /** In place of renderScoreboard(objective, resolution) in renderGameOverlay. */
    public static void scoreboard(Object gui, Object obj, Object resolution) throws Throwable {
        objective = obj;
        if (!sidebarTakenOver) {
            ((GuiIngame) gui).renderScoreboard((ScoreObjective) obj, (ScaledResolution) resolution);
        }
    }

    /** End of the frame's HUD (GameHooks.overlay): the objective is only for this frame. */
    static void endFrame() {
        objective = null;
    }

    // ---- for the Loader ----

    /** Action bar text still on screen, or null. */
    public static String actionText() {
        return actionText != null && actionSeconds() < ACTION_TICKS / TICKS_PER_SECOND
                ? actionText
                : null;
    }

    /** Action bar opacity (0 to 1), as in the game: fades out in the last second. */
    public static float actionAlpha() {
        double left = ACTION_TICKS / TICKS_PER_SECOND - actionSeconds();
        return (float) Math.max(0.0, Math.min(1.0, left));
    }

    /** Record text playing (the game paints it rainbow). */
    public static boolean actionPlaying() {
        return actionPlaying;
    }

    /** Seconds since the last action bar. */
    public static double actionSeconds() {
        return (System.nanoTime() - actionSince) / 1e9;
    }

    /** Boss name with the bar on screen, or null. */
    public static String bossName() {
        String name = BossStatus.bossName;
        return name != null && BossStatus.statusBarTime > 0 ? name : null;
    }

    /** Boss health (0 to 1). */
    public static float bossHealth() {
        return BossStatus.healthScale;
    }

    /** Sidebar scoreboard objective of this frame (ScoreObjective), or null. */
    public static Object sidebarObjective() {
        return objective;
    }
}
