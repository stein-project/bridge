/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinNameLabels.LABEL_SCALE;
import static dev.xavier.stein.gfx.SteinNameLabels.SNEAK_SCALE;

import dev.xavier.stein.bridge.engine.NameStyle;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

/**
 * Where a name label goes and how big it is, with the {@link NameStyle} (height, size, padding):
 * one place for the expressions that the direct label ({@link SteinNameLabels}, {@link
 * SteinLabelEmit}) and the game path ({@link SteinLabelTail}) must compute to the same floats. With
 * the game's look they are the game's own expressions, unchanged.
 *
 * <p>Pipeline: both (the styled game path also runs on the fixed-function pipeline).
 */
final class SteinLabelGeometry {

    private SteinLabelGeometry() {}

    /** The standing label's height, Render.renderLivingLabel's, plus the style's offset. */
    static float labelTop(double y, float h) {
        float t = (float) y + h + 0.5F;
        return NameStyle.custom() ? t + NameStyle.offset() : t;
    }

    /** The sneaking label's height, RendererLivingEntity.renderName's, plus the style's offset. */
    static float sneakTop(double y, float h, boolean child) {
        float t = (float) y + h + 0.5F - (child ? h / 2.0F : 0.0F);
        return NameStyle.custom() ? t + NameStyle.offset() : t;
    }

    static float labelScale() {
        return NameStyle.custom() ? LABEL_SCALE * NameStyle.scale() : LABEL_SCALE;
    }

    static float sneakScale() {
        return NameStyle.custom() ? SNEAK_SCALE * NameStyle.scale() : SNEAK_SCALE;
    }

    /**
     * How far up the name goes over the scoreboard line (RenderPlayer.renderOffsetLivingLabel);
     * with a style, the larger size and padding too, so the two backgrounds do not overlap.
     */
    static double scoreStep(FontRenderer font) {
        if (!NameStyle.custom()) {
            return (double) ((float) font.FONT_HEIGHT * 1.15F * SNEAK_SCALE);
        }
        return (double)
                (((float) font.FONT_HEIGHT * 1.15F + 2 * NameStyle.paddingY())
                        * SNEAK_SCALE
                        * NameStyle.scale());
    }

    /**
     * The scoreboard line below a player's name (RenderPlayer.renderOffsetLivingLabel, within 10
     * blocks), through the game's scoreboard; null if there is none. Without formatting codes when
     * the style asks, kept per entity in {@code slot} (null: stripped every time).
     */
    static String scoreLine(Entity entity, double d, SteinLabelSlots.Slot slot) {
        if (d >= 100.0) {
            return null;
        }
        Scoreboard board = ((EntityPlayer) entity).getWorldScoreboard();
        ScoreObjective objective = board.getObjectiveInDisplaySlot(2);
        if (objective == null) {
            return null;
        }
        Score score = board.getValueFromObjective(entity.getName(), objective);
        String s = score.getScorePoints() + " " + objective.getDisplayName();
        if (!NameStyle.custom() || !NameStyle.plainText()) {
            return s;
        }
        return slot != null
                ? SteinLabelSlots.plainScore(slot, s)
                : EnumChatFormatting.getTextWithoutFormattingCodes(s);
    }
}
