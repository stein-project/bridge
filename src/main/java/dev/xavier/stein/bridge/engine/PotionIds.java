/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

/**
 * Fix for an entity effect packet with a potion id the client does not have: the effect is left out
 * instead of crashing the game on the next tick.
 *
 * <p>NetHandlerPlayClient.handleEntityEffect (S1DPacketEntityEffect) adds a PotionEffect for any id
 * the server sends, from -128 to 127. On the next tick PotionEffect.onUpdate reads {@code
 * Potion.potionTypes[id]}: an id past the array's 32 slots throws ArrayIndexOutOfBoundsException
 * and an empty slot (id 0 and ids 24 to 31 in 1.8.9) a NullPointerException, and the game closes
 * with "Ticking entity"; the inventory's effect icons crash the same way. A newer server through a
 * protocol translator sends such ids for effects 1.8.9 does not know (luck, levitation,
 * glowing...). Here the patcher sends the addPotionEffect call of handleEntityEffect through {@link
 * #add}, which drops an effect whose potion does not exist and says so in the log; every known
 * effect is added exactly as before.
 */
public final class PotionIds {

    private static final RepeatLog LOG = new RepeatLog("potions", 10);

    private PotionIds() {}

    /** EntityLivingBase.addPotionEffect(effect) in NetHandlerPlayClient.handleEntityEffect. */
    public static void add(Object entity, Object effect) {
        PotionEffect e = (PotionEffect) effect;
        int id = e.getPotionID();
        if (id < 0 || id >= Potion.potionTypes.length || Potion.potionTypes[id] == null) {
            LOG.note("effect id " + id + " from the server is unknown to 1.8.9: left out");
            return;
        }
        ((EntityLivingBase) entity).addPotionEffect(e);
    }
}
