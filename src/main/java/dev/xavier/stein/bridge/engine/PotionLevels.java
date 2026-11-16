/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.StatCollector;

/**
 * The level of potion effects above IV, in the inventory and in the potion's tooltip.
 *
 * <p>InventoryEffectRenderer.drawActivePotionEffects only names levels II to IV (amplifier 1 to 3):
 * a Speed V or a Haste X given by a server showed just "Speed". ItemPotion.addInformation asks for
 * "potion.potency.N", which the language files only have up to 3: the tooltip of such a potion read
 * "Strength potion.potency.4". From 1.13 any level shows its numeral. Here both use the game's own
 * enchantment level names (enchantment.level.N, I to X), and the number itself past X, where the
 * game has no name. {@code -Dsteinbridge.opt.vanillaPotionLevels=true} brings back 1.8.9's
 * behaviour.
 */
public final class PotionLevels {

    private PotionLevels() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_POTION_LEVELS;

    /** The colour of the effect's name in drawActivePotionEffects (its duration is grey). */
    private static final int NAME_COLOR = 0xFFFFFF;

    /** The first amplifier the inventory does not name (level V). */
    private static final int FIRST_UNNAMED = 4;

    private static final String POTENCY = "potion.potency.";

    /** The amplifier of the effect drawActivePotionEffects is drawing. */
    private static int amplifier;

    /** In place of PotionEffect.getAmplifier in drawActivePotionEffects: notes the effect's. */
    public static int amplifier(Object effect) {
        amplifier = ((PotionEffect) effect).getAmplifier();
        return amplifier;
    }

    /**
     * In place of FontRenderer.drawStringWithShadow in drawActivePotionEffects: the effect's name
     * gets its level when the game gave it none.
     */
    public static int drawString(Object font, Object text, float x, float y, int color) {
        String s = (String) text;
        if (!VANILLA && color == NAME_COLOR && amplifier >= FIRST_UNNAMED) {
            s = s + " " + level(amplifier + 1);
        }
        return ((FontRenderer) font).drawStringWithShadow(s, x, y, color);
    }

    /**
     * In place of StatCollector.translateToLocal in ItemPotion.addInformation: a potency the
     * language has no name for becomes the level's numeral.
     */
    public static Object translate(Object key) {
        String k = (String) key;
        String s = StatCollector.translateToLocal(k);
        if (VANILLA || !s.equals(k) || !k.startsWith(POTENCY)) {
            return s;
        }
        try {
            return level(Integer.parseInt(k.substring(POTENCY.length())) + 1);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    /** The game's name for a level (I to X), or the number when it has none. */
    private static String level(int level) {
        String key = "enchantment.level." + level;
        String name = I18n.format(key);
        return name.equals(key) ? Integer.toString(level) : name;
    }
}
