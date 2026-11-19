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
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovementInput;

/**
 * The local player as {@link SyncReplayDriver}'s {@link SyncReplayDriver.Stepper}: its movement
 * state read and written, and one tick run again with the game's own movement code.
 *
 * <p>A replayed tick runs what the game's tick runs for movement, in the same order: the water
 * check of the entity update (with the lava halving of the fall), then EntityPlayerSP's
 * onLivingUpdate (portal and sprint timers, the movement input, the item-use slowdown, the push out
 * of blocks, the sprint rules, then the living update: the jump, the travel with its collisions and
 * block contacts, the fall). The input comes from the ring through {@link SyncReplay}'s hooks on
 * the movement input and the sprint key; the rotation and the item in use are set to the tick's.
 *
 * <p>What the tick does besides moving is held back or put back:
 *
 * <ul>
 *   <li>sounds (steps, swimming, splash, fall) and the splash particles: the hooks of {@link
 *       SyncReplay} drop them while a replay runs;
 *   <li>the portal effect (its sound, closing the open screen): the portal flag is cleared before
 *       each tick and the portal timers are put back after;
 *   <li>local fall damage and stats: the client's player takes no damage on its own and keeps only
 *       the statistics that are its own (none of them movement ones), so the game already drops
 *       them; health, food (peaceful regeneration) and exhaustion (the jump's) are put back anyway;
 *   <li>packets: the only ones the living update can send are the flight toggle and the horse jump,
 *       and a replay never runs while the player can fly or rides;
 *   <li>what is drawn from the state (view bobbing, arm sway, limb swing, the pick-up animation of
 *       the hotbar) is put back, so the picture does not jump; the rotation and the movement input
 *       are put back to what the player has now.
 * </ul>
 *
 * <p>Game thread only. Holds no state between corrections other than the saved fields.
 */
final class SyncReplayPlayer implements SyncReplayDriver.Stepper {

    private EntityPlayerSP p;

    /** The item the player uses now, stood in for a tick that ran with an item in use. */
    private ItemStack heldUse;

    /** Stands in for an item in use when the player uses none now (only its presence counts). */
    private static ItemStack stand;

    // Saved around a replay: what the player has now, put back after.
    private float yaw;
    private float pitch;
    private float forward;
    private float strafe;
    private boolean jump;
    private boolean sneak;
    private float health;
    private int food;
    private float exhaustion;
    private int flyToggleTimer;
    private float timeInPortal;
    private float prevTimeInPortal;
    private boolean inPortal;
    private int timeUntilPortal;
    private int fire;
    private float cameraYaw;
    private float prevCameraYaw;
    private float cameraPitch;
    private float prevCameraPitch;
    private float distanceWalked;
    private float prevDistanceWalked;
    private float armYaw;
    private float armPitch;
    private float prevArmYaw;
    private float prevArmPitch;
    private float limbSwing;
    private float limbSwingAmount;
    private float prevLimbSwingAmount;
    private final int[] animations = new int[36];

    /** Takes the game's player for one replay and saves what is put back after. */
    boolean begin() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || !walking(mc, player)) {
            return false;
        }
        p = player;
        heldUse = player.itemInUse;
        yaw = player.rotationYaw;
        pitch = player.rotationPitch;
        MovementInput in = player.movementInput;
        if (in != null) {
            forward = in.moveForward;
            strafe = in.moveStrafe;
            jump = in.jump;
            sneak = in.sneak;
        }
        health = player.getHealth();
        food = player.getFoodStats().getFoodLevel();
        exhaustion = player.getFoodStats().foodExhaustionLevel;
        flyToggleTimer = player.flyToggleTimer;
        timeInPortal = player.timeInPortal;
        prevTimeInPortal = player.prevTimeInPortal;
        inPortal = player.inPortal;
        timeUntilPortal = player.timeUntilPortal;
        fire = player.fire;
        cameraYaw = player.cameraYaw;
        prevCameraYaw = player.prevCameraYaw;
        cameraPitch = player.cameraPitch;
        prevCameraPitch = player.prevCameraPitch;
        distanceWalked = player.distanceWalkedModified;
        prevDistanceWalked = player.prevDistanceWalkedModified;
        armYaw = player.renderArmYaw;
        armPitch = player.renderArmPitch;
        prevArmYaw = player.prevRenderArmYaw;
        prevArmPitch = player.prevRenderArmPitch;
        limbSwing = player.limbSwing;
        limbSwingAmount = player.limbSwingAmount;
        prevLimbSwingAmount = player.prevLimbSwingAmount;
        ItemStack[] inv = player.inventory.mainInventory;
        for (int i = 0; i < animations.length && i < inv.length; i++) {
            animations[i] = inv[i] == null ? 0 : inv[i].animationsToGo;
        }
        return true;
    }

    /** Puts back what {@link #begin} saved. */
    void end() {
        EntityPlayerSP player = p;
        if (player == null) {
            return;
        }
        player.itemInUse = heldUse;
        player.rotationYaw = yaw;
        player.rotationPitch = pitch;
        MovementInput in = player.movementInput;
        if (in != null) {
            in.moveForward = forward;
            in.moveStrafe = strafe;
            in.jump = jump;
            in.sneak = sneak;
        }
        if (player.getHealth() != health) {
            player.setHealth(health);
        }
        if (player.getFoodStats().getFoodLevel() != food) {
            player.getFoodStats().setFoodLevel(food);
        }
        player.getFoodStats().foodExhaustionLevel = exhaustion;
        player.flyToggleTimer = flyToggleTimer;
        player.timeInPortal = timeInPortal;
        player.prevTimeInPortal = prevTimeInPortal;
        player.inPortal = inPortal;
        player.timeUntilPortal = timeUntilPortal;
        player.fire = fire;
        player.cameraYaw = cameraYaw;
        player.prevCameraYaw = prevCameraYaw;
        player.cameraPitch = cameraPitch;
        player.prevCameraPitch = prevCameraPitch;
        player.distanceWalkedModified = distanceWalked;
        player.prevDistanceWalkedModified = prevDistanceWalked;
        player.renderArmYaw = armYaw;
        player.renderArmPitch = armPitch;
        player.prevRenderArmYaw = prevArmYaw;
        player.prevRenderArmPitch = prevArmPitch;
        player.limbSwing = limbSwing;
        player.limbSwingAmount = limbSwingAmount;
        player.prevLimbSwingAmount = prevLimbSwingAmount;
        ItemStack[] inv = player.inventory.mainInventory;
        for (int i = 0; i < animations.length && i < inv.length; i++) {
            if (inv[i] != null) {
                inv[i].animationsToGo = animations[i];
            }
        }
        p = null;
        heldUse = null;
    }

    /**
     * Whether the player moves with the walking code the replay runs: alive, not riding, not able
     * to fly (creative, spectator: the flight toggle would send a packet), not asleep, the camera
     * on it.
     */
    static boolean walking(Minecraft mc, EntityPlayerSP player) {
        // A dead player does not move (and its health is not part of the recorded state).
        return player.getHealth() > 0.0F
                && !player.isRiding()
                && !player.capabilities.allowFlying
                && !player.capabilities.isFlying
                && !player.isSpectator()
                && !player.isPlayerSleeping()
                && mc.getRenderViewEntity() == player
                && player.worldObj == mc.theWorld;
    }

    @Override
    public boolean ready() {
        return p != null;
    }

    @Override
    public void restore(SyncState s, boolean sprintChanged) {
        EntityPlayerSP player = p;
        player.setEntityBoundingBox(
                new AxisAlignedBB(s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ));
        player.posX = s.x;
        player.posY = s.y;
        player.posZ = s.z;
        player.motionX = s.motionX;
        player.motionY = s.motionY;
        player.motionZ = s.motionZ;
        player.onGround = s.onGround;
        player.isCollidedHorizontally = s.collidedHorizontally;
        player.isCollidedVertically = s.collidedVertically;
        player.isCollided = s.collidedHorizontally || s.collidedVertically;
        player.inWater = s.inWater;
        player.isInWeb = s.inWeb;
        // setSprinting also sets the sprint speed modifier the next tick's speed comes from.
        if (player.isSprinting() != s.sprinting) {
            player.setSprinting(s.sprinting);
        }
        player.sprintingTicksLeft = s.sprintingTicksLeft;
        player.isAirBorne = s.airBorne;
        player.fallDistance = s.fallDistance;
        player.jumpTicks = s.jumpTicks;
        player.sprintToggleTimer = s.sprintToggleTimer;
        player.jumpMovementFactor = s.jumpMovementFactor;
        // The game sets the next tick's ground speed from the movement speed at the end of a tick;
        // a correction that turned the sprint on or off changed that speed's sprint modifier.
        player.setAIMoveSpeed(
                sprintChanged
                        ? (float)
                                player.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                                        .getAttributeValue()
                        : s.aiMoveSpeed);
        player.distanceWalkedOnStepModified = s.distanceWalkedOnStep;
        player.nextStepDistance = s.nextStepDistance;
    }

    @Override
    public void changes(SyncRing.Entry e) {
        applyChanges(p, e);
    }

    /** Applies the between-ticks changes of {@code e} to the player, in the order they happen. */
    static void applyChanges(EntityPlayerSP player, SyncRing.Entry e) {
        if (e.positionSet) {
            SyncState s = e.prePosition;
            player.setEntityBoundingBox(
                    new AxisAlignedBB(s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ));
            player.posX = s.x;
            player.posY = s.y;
            player.posZ = s.z;
        }
        if (e.velocitySet) {
            player.motionX = e.velocityX;
            player.motionY = e.velocityY;
            player.motionZ = e.velocityZ;
        }
        for (int i = 0; i < e.hits; i++) {
            player.motionX *= SyncReplay.HIT_SLOWDOWN;
            player.motionZ *= SyncReplay.HIT_SLOWDOWN;
            player.setSprinting(false);
        }
        player.motionX += e.addX;
        player.motionY += e.addY;
        player.motionZ += e.addZ;
        if (e.sprintSet >= 0 && player.isSprinting() != (e.sprintSet == 1)) {
            player.setSprinting(e.sprintSet == 1);
        }
    }

    @Override
    public void step(SyncRing.Entry e) {
        EntityPlayerSP player = p;
        player.rotationYaw = e.yaw;
        player.rotationPitch = e.pitch;
        player.itemInUse = e.usingItem ? (heldUse != null ? heldUse : stand()) : null;
        player.inPortal = false;
        SyncReplay.stepping(e);
        try {
            // The movement part of the entity update, then the living update, as the tick runs
            // them.
            player.handleWaterMovement();
            if (player.isInLava()) {
                player.fallDistance *= LAVA_FALL;
            }
            player.onLivingUpdate();
        } finally {
            SyncReplay.stepping(null);
        }
    }

    /** The entity update halves the fall distance in lava each tick. */
    private static final float LAVA_FALL = 0.5F;

    private static ItemStack stand() {
        if (stand == null) {
            stand = new ItemStack(Items.bow);
        }
        return stand;
    }

    @Override
    public void capture(SyncState out) {
        read(p, out);
    }

    /** Reads {@code player}'s movement state into {@code out}. No allocation. */
    static void read(EntityPlayerSP player, SyncState out) {
        AxisAlignedBB box = player.getEntityBoundingBox();
        out.x = player.posX;
        out.y = player.posY;
        out.z = player.posZ;
        out.minX = box.minX;
        out.minY = box.minY;
        out.minZ = box.minZ;
        out.maxX = box.maxX;
        out.maxY = box.maxY;
        out.maxZ = box.maxZ;
        out.motionX = player.motionX;
        out.motionY = player.motionY;
        out.motionZ = player.motionZ;
        out.onGround = player.onGround;
        out.collidedHorizontally = player.isCollidedHorizontally;
        out.collidedVertically = player.isCollidedVertically;
        out.inWater = player.inWater;
        out.inWeb = player.isInWeb;
        out.sprinting = player.isSprinting();
        out.airBorne = player.isAirBorne;
        out.fallDistance = player.fallDistance;
        out.jumpTicks = player.jumpTicks;
        out.sprintingTicksLeft = player.sprintingTicksLeft;
        out.sprintToggleTimer = player.sprintToggleTimer;
        out.jumpMovementFactor = player.jumpMovementFactor;
        out.aiMoveSpeed = player.getAIMoveSpeed();
        out.distanceWalkedOnStep = player.distanceWalkedOnStepModified;
        out.nextStepDistance = player.nextStepDistance;
    }
}
