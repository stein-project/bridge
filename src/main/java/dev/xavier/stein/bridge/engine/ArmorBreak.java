/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

/**
 * The break sound and particles of the player's own armor in multiplayer.
 *
 * <p>In 1.8.9 the server plays an item's break sound to everyone around except the player wearing
 * it, and the client never damages its armor itself: a piece broke in a fight without a sound or a
 * puff of fragments, and the player only noticed from the armor bar. From 1.15 the server sends the
 * break as an event to the wearer too. Here, when the server's set-slot packet (handleSetSlot)
 * empties an armor slot (5 to 8 of the player's inventory container) that held armor at its last
 * point of durability, the client plays the game's own renderBrokenItemStack for it, locally. In
 * single player the integrated server's sound is not sent to the player either, so it is the same
 * there. {@code -Dsteinbridge.opt.vanillaArmorBreakSound=true} brings back 1.8.9's behaviour.
 */
public final class ArmorBreak {

    private ArmorBreak() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_ARMOR_BREAK_SOUND;

    /** The armor slots of the player's inventory container (helmet to boots). */
    private static final int FIRST_ARMOR_SLOT = 5;

    private static final int LAST_ARMOR_SLOT = 8;

    /**
     * Before the server's stack goes into the slot (handleSetSlot): the break, if this empties a
     * piece of armor about to break.
     */
    public static void beforeSetSlot(Object containerObject, int slot, Object stack) {
        if (VANILLA || stack != null || slot < FIRST_ARMOR_SLOT || slot > LAST_ARMOR_SLOT) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        Container container = (Container) containerObject;
        if (player == null || container != player.inventoryContainer) {
            return;
        }
        ItemStack old = container.getSlot(slot).getStack();
        if (old != null
                && old.getItem() instanceof ItemArmor
                && old.isItemStackDamageable()
                && old.getItemDamage() > old.getMaxDamage() - 2) {
            player.renderBrokenItemStack(old);
        }
    }
}
