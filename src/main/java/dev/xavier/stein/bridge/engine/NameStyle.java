/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.scoreboard.Team;

import java.nio.ByteOrder;

/**
 * The look of the name labels above heads, set by a mod through the Loader API: the background
 * (color, padding, rounded corners, or none), the text (shadow, color, the formatting codes left
 * out), the size and the height, and whether the player's own label shows in third person.
 *
 * <p>It only changes the picture. Who gets a label is still the game's question (canRenderName:
 * sneaking, invisible, teams, the GUI hidden with F1, riding) and so is the distance (64 blocks, 32
 * sneaking); the own label, when asked for, follows the same rules a teammate's would.
 *
 * <p>Starts at the game's look, and while it stays there ({@link #custom} false) every label goes
 * the way it always went: the direct label of Stein GFX ({@code SteinNameLabels}) with its caches,
 * or the game's own code. A custom style goes through the same direct label (Vulkan and OpenGL),
 * through the same game calls when the direct label cannot be used, and through those calls too on
 * the fixed-function pipeline. Every value the labels read per frame is computed here, when the
 * style is set, so the drawing reads fields and allocates nothing.
 *
 * <p>Game thread only: the setters are called between frames, never while the entities draw.
 */
public final class NameStyle {

    /** The game's background: color(0, 0, 0, 0.25F) in the WorldRenderer, 63 in alpha. */
    public static final int VANILLA_BACKGROUND = (int) (0.25F * 255F) << 24;

    /** The largest corner radius, in label pixels (the game's label is 9 pixels high). */
    public static final int MAX_RADIUS = 6;

    /** The largest padding, in label pixels, on each side. */
    public static final int MAX_PADDING = 8;

    private static boolean custom;
    private static int version;
    private static boolean showOwn;

    private static boolean background = true;
    private static int backgroundArgb = VANILLA_BACKGROUND;
    private static int paddingX;
    private static int paddingY;
    private static int radius;
    private static boolean shadow;
    private static int textRgb = 0xFFFFFF;
    private static boolean plainText;
    private static float scale = 1.0F;
    private static float offset;

    // What the labels read: the background color as bytes r, g, b, a in memory order (as the
    // WorldRenderer stores it), the input colors of the text passes as renderString puts them in
    // the
    // GlStateManager, and the shadow's (the game's darkening of the same color).
    private static int backgroundBytes = memoryColor(VANILLA_BACKGROUND);
    private static int backgroundR;
    private static int backgroundG;
    private static int backgroundB;
    private static int backgroundA = VANILLA_BACKGROUND >>> 24;
    private static float textR = 1.0F;
    private static float textG = 1.0F;
    private static float textB = 1.0F;
    private static float shadowR;
    private static float shadowG;
    private static float shadowB;

    private NameStyle() {}

    /**
     * Sets the look of every label. The game's look is {@code (true, VANILLA_BACKGROUND, 0, 0, 0,
     * false, 0xFFFFFF, false, 1, 0)}.
     *
     * @param background false draws no background at all
     * @param backgroundArgb the background color, alpha in the top byte
     * @param paddingX label pixels added to the left and right of the game's background
     * @param paddingY label pixels added above and below it
     * @param radius the corner radius in label pixels (0 = square, as the game)
     * @param shadow the text gets the game's drop shadow (not while sneaking, where the text is
     *     only the faint see-through pass)
     * @param textRgb the text's color where no formatting code gives one (white in the game)
     * @param plainText leaves the formatting codes out, so the whole name is {@code textRgb} (rank
     *     and team colors ignored)
     * @param scale the label's size, 1 = the game's
     * @param offsetBlocks how far above the game's height, in blocks (negative goes down)
     */
    public static void set(
            boolean background,
            int backgroundArgb,
            int paddingX,
            int paddingY,
            int radius,
            boolean shadow,
            int textRgb,
            boolean plainText,
            float scale,
            float offsetBlocks) {
        paddingX = clamp(paddingX, 0, MAX_PADDING);
        paddingY = clamp(paddingY, 0, MAX_PADDING);
        radius = clamp(radius, 0, MAX_RADIUS);
        textRgb &= 0xFFFFFF;
        scale = Float.isFinite(scale) ? Math.max(0.1F, Math.min(4.0F, scale)) : 1.0F;
        offsetBlocks =
                Float.isFinite(offsetBlocks) ? Math.max(-4F, Math.min(4F, offsetBlocks)) : 0F;
        if (background == NameStyle.background
                && backgroundArgb == NameStyle.backgroundArgb
                && paddingX == NameStyle.paddingX
                && paddingY == NameStyle.paddingY
                && radius == NameStyle.radius
                && shadow == NameStyle.shadow
                && textRgb == NameStyle.textRgb
                && plainText == NameStyle.plainText
                && scale == NameStyle.scale
                && offsetBlocks == NameStyle.offset) {
            return;
        }
        NameStyle.background = background;
        NameStyle.backgroundArgb = backgroundArgb;
        NameStyle.paddingX = paddingX;
        NameStyle.paddingY = paddingY;
        NameStyle.radius = radius;
        NameStyle.shadow = shadow;
        NameStyle.textRgb = textRgb;
        NameStyle.plainText = plainText;
        NameStyle.scale = scale;
        NameStyle.offset = offsetBlocks;
        custom =
                !background
                        || backgroundArgb != VANILLA_BACKGROUND
                        || paddingX != 0
                        || paddingY != 0
                        || radius != 0
                        || shadow
                        || textRgb != 0xFFFFFF
                        || plainText
                        || scale != 1.0F
                        || offsetBlocks != 0.0F;
        backgroundBytes = memoryColor(backgroundArgb);
        backgroundR = backgroundArgb >> 16 & 255;
        backgroundG = backgroundArgb >> 8 & 255;
        backgroundB = backgroundArgb & 255;
        backgroundA = backgroundArgb >>> 24;
        // FontRenderer.renderString: the color with full alpha, and the shadow's darkened copy.
        int c = 0xFF000000 | textRgb;
        textR = (float) (c >> 16 & 255) / 255.0F;
        textG = (float) (c >> 8 & 255) / 255.0F;
        textB = (float) (c & 255) / 255.0F;
        int s = (c & 0xFCFCFC) >> 2 | c & 0xFF000000;
        shadowR = (float) (s >> 16 & 255) / 255.0F;
        shadowG = (float) (s >> 8 & 255) / 255.0F;
        shadowB = (float) (s & 255) / 255.0F;
        version++;
    }

    /** Back to the game's look (the own label too). */
    public static void reset() {
        set(true, VANILLA_BACKGROUND, 0, 0, 0, false, 0xFFFFFF, false, 1.0F, 0.0F);
        showOwn = false;
    }

    /** The player's own label in third person (the game never draws it). */
    public static void setShowOwn(boolean on) {
        showOwn = on;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** ARGB to the bytes r, g, b, a in memory order, as an int read in the native order. */
    private static int memoryColor(int argb) {
        int r = argb >> 16 & 255;
        int g = argb >> 8 & 255;
        int b = argb & 255;
        int a = argb >>> 24;
        return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                ? a << 24 | b << 16 | g << 8 | r
                : r << 24 | g << 16 | b << 8 | a;
    }

    // ---- what the labels read ----

    /** Anything other than the game's look. */
    public static boolean custom() {
        return custom;
    }

    /** Goes up on every change: what was kept for the earlier style (backgrounds, texts) is old. */
    public static int version() {
        return version;
    }

    public static boolean background() {
        return background;
    }

    public static int backgroundBytes() {
        return backgroundBytes;
    }

    public static int backgroundR() {
        return backgroundR;
    }

    public static int backgroundG() {
        return backgroundG;
    }

    public static int backgroundB() {
        return backgroundB;
    }

    public static int backgroundA() {
        return backgroundA;
    }

    public static int paddingX() {
        return paddingX;
    }

    public static int paddingY() {
        return paddingY;
    }

    public static int radius() {
        return radius;
    }

    public static boolean shadow() {
        return shadow;
    }

    public static boolean plainText() {
        return plainText;
    }

    public static float textR() {
        return textR;
    }

    public static float textG() {
        return textG;
    }

    public static float textB() {
        return textB;
    }

    public static float shadowR() {
        return shadowR;
    }

    public static float shadowG() {
        return shadowG;
    }

    public static float shadowB() {
        return shadowB;
    }

    /** The text's color for the FontRenderer, with alpha {@code alpha} (0x20 see-through). */
    public static int textColor(int alpha) {
        return alpha << 24 | textRgb;
    }

    public static float scale() {
        return scale;
    }

    public static float offset() {
        return offset;
    }

    // ---- the own label ----

    /**
     * Whether the player's own label shows (asked only when the game said no): in third person, in
     * the world (not the inventory's player, drawn with the shadow turned off), with the GUI on,
     * not invisible, not ridden, and the team's rule read as a teammate would see it (never, or
     * hidden for the own team: no label).
     */
    static boolean ownShown(Entity entity) {
        if (!showOwn) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        RenderManager rm = mc.getRenderManager();
        if (entity != rm.livingPlayer
                || entity != mc.thePlayer
                || mc.gameSettings.thirdPersonView == 0
                || !rm.isRenderShadow()
                || !Minecraft.isGuiEnabled()
                || entity.isInvisibleToPlayer(mc.thePlayer)
                || entity.riddenByEntity != null) {
            return false;
        }
        Team team = ((EntityLivingBase) entity).getTeam();
        if (team == null) {
            return true;
        }
        Team.EnumVisible v = team.getNameTagVisibility();
        return v != Team.EnumVisible.NEVER && v != Team.EnumVisible.HIDE_FOR_OWN_TEAM;
    }
}
