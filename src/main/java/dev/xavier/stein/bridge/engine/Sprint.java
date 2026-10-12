/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovementInput;

/**
 * Sprinting in all directions (through the Loader API {@code Gameplay.setSprintAllDirections}). The
 * game only lets you sprint forwards: in EntityPlayerSP.onLivingUpdate, sprinting drops when
 * forward movement falls below 0.8. Before super.onLivingUpdate (which moves the player; patcher
 * rule), with the option on and the sprint key pressed, sprinting comes back in any direction —
 * with the game's other conditions: hunger above 6 (or flight allowed), not using an item, no
 * blindness, not sneaking and not hitting a wall sideways. The server receives sprinting the usual
 * way (it only changes state when sprinting starts or stops).
 */
public final class Sprint {

    /** Potion.blindness. */
    private static final int BLINDNESS = 15;

    private static final float MIN_INPUT = 0.01F;
    private static final double HIT_SLOWDOWN = 0.6;
    private static final int MIN_FOOD_LEVEL = 6;

    private static volatile boolean allDirections;
    private static boolean broken;

    /** Sprinting goes through the hit (off by default). */
    private static volatile boolean onHit;

    /** With {@link #onHit}: the hit does not brake either (the 0.6 of the speed). */
    private static volatile boolean noSlow;

    private Sprint() {}

    /**
     * Sprinting survives the hit (through the Loader API {@code Gameplay.setSprintOnHit}).
     *
     * <p>1.8 drops sprinting on every hit that knocks back ({@code setSprinting(false)} at the end
     * of {@code attackTargetEntityWithCurrentItem}) — this is the "sprint reset", which makes the
     * next hit come out without the sprint knockback until you sprint again. The mod's toggle
     * sprint only holds the key, so sprinting comes back on the next tick; with this option it does
     * not even drop.
     *
     * <p><b>On a server, think twice.</b> The client tells the server when it starts and when it
     * stops sprinting; by not stopping, the server sees hit after hit with the player sprinting,
     * which is what anticheat looks for. That is why the option is born off and warns when turned
     * on, like No Hit Delay.
     */
    public static void setOnHit(boolean on) {
        onHit = on;
    }

    /**
     * Sub-option of sprinting through the hit: the hit that knocks back does not brake either. 1.8
     * multiplies the player's horizontal speed by 0.6 right before dropping sprinting, in the same
     * stretch of the hit; here, at that same point (the hit's setSprinting(false)), the speed goes
     * back to what it was. Only applies with sprinting on. On a server it is even easier to notice
     * than sprinting: born off.
     */
    public static void setNoSlow(boolean on) {
        noSlow = on;
        if (on) {
            Log.info("sprint", "unbraked hit on");
        }
    }

    /**
     * In place of the {@code setSprinting} inside the hit (patcher rule, only at that call site).
     *
     * <p>With the option on, the turning off that comes from the hit does not happen; everything
     * else passes the same — including turning on, and including turning off from anywhere else in
     * the game.
     */
    public static void sprintOnHit(Object player, boolean value) {
        if (onHit && !value && player instanceof EntityPlayerSP) {
            if (noSlow) {
                // The hit has just done motionX *= 0.6 and motionZ *= 0.6 (the two lines before
                // this call).
                Entity e = (Entity) player;
                e.motionX = e.motionX / HIT_SLOWDOWN;
                e.motionZ = e.motionZ / HIT_SLOWDOWN;
            }
            return;
        }
        ((EntityPlayer) player).setSprinting(value);
    }

    /** Turns it on or off (through the Loader API {@code Gameplay}). */
    public static void setAllDirections(boolean on) {
        allDirections = on;
    }

    /**
     * {@code -Dsteinbridge.diag.sprintLog=true}: every second, the ground speed and whether it is
     * sprinting (with or without the option).
     */
    private static final boolean LOG = EngineOptions.SPRINT_LOG;

    private static int logTicks;
    private static int logSprinting;
    private static double logDistance;
    private static double logLastX = Double.NaN;
    private static double logLastZ;

    /**
     * Before super.onLivingUpdate in EntityPlayerSP.onLivingUpdate (patcher rule): on every player
     * tick.
     */
    public static void beforeLivingUpdate() {
        if (allDirections && !broken) {
            sprintAll();
        }
        if (LOG) {
            log(); // after the decision: the state with which the player will move this tick
        }
    }

    private static void sprintAll() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            EntityPlayerSP p = mc.thePlayer;
            if (p == null || p.isSprinting()) {
                return;
            }
            MovementInput input = p.movementInput;
            float forward = input.moveForward;
            float strafe = input.moveStrafe;
            if (Math.abs(forward) < MIN_INPUT && Math.abs(strafe) < MIN_INPUT) {
                return;
            }
            if (!mc.gameSettings.keyBindSprint.isKeyDown()) {
                return;
            }
            boolean fed =
                    p.getFoodStats().getFoodLevel() > MIN_FOOD_LEVEL || p.capabilities.allowFlying;
            if (!fed
                    || p.isUsingItem()
                    || p.isPotionActive(BLINDNESS)
                    || p.isSneaking()
                    || p.isCollidedHorizontally) {
                return;
            }
            p.setSprinting(true);
        } catch (Throwable t) {
            broken = true;
            Log.info("sprint", "sprinting in all directions off: " + t);
        }
    }

    private static void log() {
        try {
            EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
            if (p == null) {
                return;
            }
            // Before this tick's movement: the position minus the previous tick's = what it
            // walked in it (the game's lastTickPos was already set equal to the position at the
            // start of the tick).
            double x = p.posX;
            double z = p.posZ;
            if (!Double.isNaN(logLastX)) {
                double dx = x - logLastX;
                double dz = z - logLastZ;
                logDistance += Math.min(2.0, Math.sqrt(dx * dx + dz * dz)); // /tp does not count
            }
            logLastX = x;
            logLastZ = z;
            if (p.isSprinting()) {
                logSprinting++;
            }
            if (++logTicks >= 20) {
                MovementInput input = p.movementInput;
                Log.info(
                        "diag",
                        "sprint: %.3f blocks/tick, sprinting %d/20, forward %.1f strafe"
                                + " %.1f, all directions %s",
                        logDistance / logTicks,
                        logSprinting,
                        input.moveForward,
                        input.moveStrafe,
                        allDirections ? "on" : "off");
                logTicks = 0;
                logSprinting = 0;
                logDistance = 0.0;
            }
        } catch (Throwable t) {
            // diagnostics only
        }
    }
}
