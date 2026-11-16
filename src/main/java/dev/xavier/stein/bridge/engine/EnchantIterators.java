/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Fix for EnchantmentHelper's shared iterators keeping the last world: the entities and the damage
 * source they were given are let go once the call that used them returns.
 *
 * <p>EnchantmentHelper keeps one static instance of each of its modifiers and fills it in before
 * walking the enchantments: applyThornEnchantments (HurtIterator: user and attacker),
 * applyArthropodEnchantments (DamageIterator: user and target) and getEnchantmentModifierDamage
 * (ModifierDamage: the damage source, which holds the entity behind it). Nothing ever clears them,
 * so the last entities hit held their world, with all its chunks and entities, after leaving a
 * single player world, until the next hit in another one. Here every call of those three goes
 * through this class, which calls the game's method and then clears what it filled in.
 *
 * <p>The client thread (a player's attack) and the integrated server thread both use the same
 * static instances; clearing them on one thread while the other walks them would hand it a null. So
 * the three calls take one lock, and the fields are cleared only when the outermost call on that
 * thread returns (a thorns hit can take damage, which asks for the protection modifier inside it).
 * The result of each call is the game's.
 */
public final class EnchantIterators {

    private static final Object LOCK = new Object();

    /** How many of the three calls are running, nested, on the thread that holds the lock. */
    private static int depth;

    private EnchantIterators() {}

    /** Every EnchantmentHelper.applyThornEnchantments(user, attacker). */
    public static void thorns(Object user, Object attacker) {
        synchronized (LOCK) {
            depth++;
            try {
                EnchantmentHelper.applyThornEnchantments(
                        (EntityLivingBase) user, (Entity) attacker);
            } finally {
                release();
            }
        }
    }

    /** Every EnchantmentHelper.applyArthropodEnchantments(user, target). */
    public static void arthropods(Object user, Object target) {
        synchronized (LOCK) {
            depth++;
            try {
                EnchantmentHelper.applyArthropodEnchantments(
                        (EntityLivingBase) user, (Entity) target);
            } finally {
                release();
            }
        }
    }

    /** Every EnchantmentHelper.getEnchantmentModifierDamage(armor, source). */
    public static int protection(Object armor, Object source) {
        synchronized (LOCK) {
            depth++;
            try {
                return EnchantmentHelper.getEnchantmentModifierDamage(
                        (ItemStack[]) armor, (DamageSource) source);
            } finally {
                release();
            }
        }
    }

    /** Leaving one call: the outermost one clears the iterators' references. */
    private static void release() {
        if (--depth > 0) {
            return;
        }
        for (int i = 0; i < FIELDS.length; i++) {
            try {
                FIELDS[i].set(OWNERS[i], null);
            } catch (IllegalAccessException e) {
                // setAccessible succeeded at start-up, so this does not happen
            }
        }
    }

    /**
     * The reference fields of the three iterators (user, attacker, target, source) and the instance
     * each belongs to. The iterator classes are private to EnchantmentHelper, so their fields are
     * found by reflection, by kind (every non-static reference field) rather than by name, which
     * the obfuscation changes. With no fields found, nothing is cleared and the game runs as
     * before.
     */
    private static final Field[] FIELDS;

    private static final Object[] OWNERS;

    static {
        List<Field> fields = new ArrayList<>();
        List<Object> owners = new ArrayList<>();
        try {
            Object[] iterators = {
                EnchantmentHelper.ENCHANTMENT_ITERATOR_HURT,
                EnchantmentHelper.ENCHANTMENT_ITERATOR_DAMAGE,
                EnchantmentHelper.enchantmentModifierDamage
            };
            for (Object it : iterators) {
                for (Field f : it.getClass().getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive()) {
                        f.setAccessible(true);
                        fields.add(f);
                        owners.add(it);
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.info("enchant", "iterator fields not found, they are not cleared: " + e);
            fields.clear();
            owners.clear();
        }
        FIELDS = fields.toArray(new Field[0]);
        OWNERS = owners.toArray();
    }
}
