/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.boss.EntityDragonPart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Physics trace recorder ({@code -Dsteinbridge.diag.physicsTrace=<dir>}): writes, for every client
 * tick, the local player's state right before the tick (with the inputs the tick will read) and
 * right after it, plus a dump of the blocks around the scene. Stein Sync's clean-room physics
 * (stein-sync {@code crates/stein-physics}) replays these files tick by tick to prove it moves the
 * player bit for bit as the client does; the format is in stein-sync {@code docs/physics-trace.md}.
 *
 * <p>It records behaviour only (numbers the game produced), never the game's code. Called from the
 * two hooks around each {@code Minecraft.runTick} (redirects-mods.tsv); without the property both
 * return at once on a static final flag, so a normal game pays nothing. Two more rules pass through
 * here always, because a redirected call cannot be switched off at run time: the player's attack
 * ({@link #attackTarget}) and the server's block action ({@link #blockEvent}); without the property
 * each forwards the call after one test of the same flag. The scenes are driven by {@link
 * PhysicsScript}.
 */
public final class PhysicsTrace {

    /** The directory the scene files go to; null when the recorder is off. */
    private static final String DIR = System.getProperty("steinbridge.diag.physicsTrace");

    /** True only with {@code -Dsteinbridge.diag.physicsTrace}. */
    static final boolean ENABLED = DIR != null;

    private static BufferedWriter out;
    private static long tick;

    /** The scene's block region, watched for changes; null while no scene file is open. */
    private static PhysicsTraceBlocks blocks;

    /** True between the before-record of a tick and its after-record. */
    private static boolean inTick;

    /** Attacks the player made inside the current tick (written in its after-record). */
    private static int attacks;

    private PhysicsTrace() {}

    /** Before each runTick: drives the script, then records the state the tick starts from. */
    public static void tickBegin() {
        if (!ENABLED) {
            return;
        }
        try {
            EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
            if (p == null) {
                return;
            }
            PhysicsScript.tick(p);
            if (out != null) {
                blocks.diff(p.worldObj, out, tick, 'b');
                out.write(pre(p));
                out.write('\n');
                inTick = true;
                attacks = 0;
            }
        } catch (Throwable e) {
            fail(e);
        }
    }

    /** After each runTick: records the state the tick produced. */
    public static void tickEnd() {
        if (!ENABLED || out == null) {
            return;
        }
        try {
            EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
            if (p != null) {
                blocks.diff(p.worldObj, out, tick, 'a');
                out.write(post(p));
                out.write('\n');
                tick++;
            }
            inTick = false;
        } catch (Throwable e) {
            fail(e);
        }
    }

    private static void fail(Throwable e) {
        Log.info("diag", "physicsTrace: " + e);
        close();
    }

    /** Opens the file of a scene and writes its header and the block dump of the given region. */
    static void open(String scene, int x0, int y0, int z0, int x1, int y1, int z1)
            throws IOException {
        close();
        Path dir = Paths.get(DIR);
        Files.createDirectories(dir);
        out = Files.newBufferedWriter(dir.resolve(scene + ".trace"), StandardCharsets.US_ASCII);
        tick = 0;
        inTick = false;
        out.write(
                "H 2 sin="
                        + Long.toHexString(sinHash())
                        + " java="
                        + System.getProperty("java.version"));
        out.write('\n');
        out.write("S " + scene);
        out.write('\n');
        blocks = new PhysicsTraceBlocks(x0, y0, z0, x1, y1, z1);
        blocks.dump(Minecraft.getMinecraft().theWorld, out);
        Log.info("diag", "physicsTrace: recording " + scene);
    }

    /** Ends the current scene file. */
    static void close() {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException e) {
            Log.info("diag", "physicsTrace: " + e);
        }
        out = null;
        blocks = null;
        inTick = false;
    }

    /** A note line in the current scene file (what the script did at this point). */
    static void note(String text) throws IOException {
        if (out != null) {
            out.write("N " + tick + " " + text);
            out.write('\n');
        }
    }

    /**
     * FNV-1a over the little-endian bytes of every entry of the running game's sine table, so the
     * replay can check its own table against this JVM's (physics spec Q1).
     */
    private static long sinHash() {
        long h = 0xcbf29ce484222325L;
        for (float v : MathHelper.SIN_TABLE) {
            int b = Float.floatToRawIntBits(v);
            for (int i = 0; i < 4; i++) {
                h ^= (b >>> (8 * i)) & 0xff;
                h *= 0x100000001b3L;
            }
        }
        return h;
    }

    private static String d(double v) {
        return Long.toHexString(Double.doubleToRawLongBits(v));
    }

    private static String f(float v) {
        return Integer.toHexString(Float.floatToRawIntBits(v));
    }

    private static void box(StringBuilder b, EntityPlayerSP p) {
        AxisAlignedBB bb = p.getEntityBoundingBox();
        b.append(' ')
                .append(d(bb.minX))
                .append(' ')
                .append(d(bb.minY))
                .append(' ')
                .append(d(bb.minZ));
        b.append(' ')
                .append(d(bb.maxX))
                .append(' ')
                .append(d(bb.maxY))
                .append(' ')
                .append(d(bb.maxZ));
        b.append(' ')
                .append(d(p.motionX))
                .append(' ')
                .append(d(p.motionY))
                .append(' ')
                .append(d(p.motionZ));
    }

    /** Flags shared by both records, as a hex bit set (docs/physics-trace.md). */
    private static int flags(EntityPlayerSP p) {
        int fl = 0;
        fl |= p.onGround ? 1 : 0;
        fl |= p.isCollidedHorizontally ? 2 : 0;
        fl |= p.isCollidedVertically ? 4 : 0;
        fl |= p.inWater ? 8 : 0;
        fl |= p.isInWeb ? 16 : 0;
        fl |= p.isSprinting() ? 32 : 0;
        fl |= p.capabilities.isFlying ? 64 : 0;
        fl |= p.capabilities.allowFlying ? 128 : 0;
        fl |= p.isSpectator() ? 256 : 0;
        fl |= p.isPotionActive(Potion.blindness) ? 512 : 0;
        fl |= p.movementInput.sneak ? 1024 : 0;
        fl |= p.movementInput.jump ? 2048 : 0;
        fl |= p.isUsingItem() ? 4096 : 0;
        return fl;
    }

    /** Counters and accumulators shared by both records. */
    private static void counters(StringBuilder b, EntityPlayerSP p) {
        b.append(' ').append(f(p.fallDistance));
        b.append(' ').append(p.jumpTicks);
        b.append(' ').append(p.sprintingTicksLeft);
        b.append(' ').append(p.sprintToggleTimer);
        b.append(' ').append(f(p.jumpMovementFactor));
        b.append(' ').append(f(p.movementInput.moveForward));
        b.append(' ').append(f(p.movementInput.moveStrafe));
        IAttributeInstance a = p.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        b.append(' ').append(d(a.getAttributeValue()));
    }

    /**
     * Keys the coming tick reads, as a bit set: forward, back, left, right, jump, sneak, sprint,
     * use, attack.
     */
    private static int keys() {
        GameSettings g = Minecraft.getMinecraft().gameSettings;
        int k = 0;
        k |= g.keyBindForward.isKeyDown() ? 1 : 0;
        k |= g.keyBindBack.isKeyDown() ? 2 : 0;
        k |= g.keyBindLeft.isKeyDown() ? 4 : 0;
        k |= g.keyBindRight.isKeyDown() ? 8 : 0;
        k |= g.keyBindJump.isKeyDown() ? 16 : 0;
        k |= g.keyBindSneak.isKeyDown() ? 32 : 0;
        k |= g.keyBindSprint.isKeyDown() ? 64 : 0;
        k |= g.keyBindUseItem.isKeyDown() ? 128 : 0;
        k |= g.keyBindAttack.isKeyDown() ? 256 : 0;
        return k;
    }

    /** The record before a tick: inputs and the whole state the tick starts from. */
    private static String pre(EntityPlayerSP p) {
        StringBuilder b = new StringBuilder(512);
        b.append("P ").append(tick).append(' ').append(Integer.toHexString(keys()));
        b.append(' ').append(f(p.rotationYaw));
        box(b, p);
        b.append(' ').append(Integer.toHexString(flags(p)));
        counters(b, p);
        // The truncating column test of the gravity rule (spec 18).
        BlockPos col = new BlockPos((int) p.posX, 0, (int) p.posZ);
        b.append(' ').append(p.worldObj.getChunkFromBlockCoords(col).isEmpty() ? 0 : 1);
        b.append(' ').append(p.getFoodStats().getFoodLevel());
        PotionEffect jump = p.getActivePotionEffect(Potion.jump);
        b.append(' ').append(jump == null ? -1 : jump.getAmplifier());
        b.append(' ').append(EnchantmentHelper.getDepthStriderModifier(p));
        b.append(' ').append(f(p.capabilities.getFlySpeed()));
        IAttributeInstance a = p.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        b.append(' ').append(d(a.getBaseValue()));
        StringBuilder mods = new StringBuilder();
        int n = 0;
        for (int op = 0; op < 3; op++) {
            for (AttributeModifier m : a.getModifiersByOperation(op)) {
                mods.append(' ').append(m.getID().toString().replace("-", ""));
                mods.append(':').append(d(m.getAmount())).append(':').append(m.getOperation());
                n++;
            }
        }
        b.append(' ').append(n).append(mods);
        b.append(' ').append(f(p.rotationPitch));
        b.append(' ').append(p.inventory.currentItem);
        b.append(' ').append(Minecraft.getMinecraft().leftClickCounter);
        columns(b, p);
        return b.toString();
    }

    /** The record after a tick: the state it produced. */
    private static String post(EntityPlayerSP p) {
        StringBuilder b = new StringBuilder(384);
        b.append("T ").append(tick);
        box(b, p);
        b.append(' ').append(Integer.toHexString(flags(p)));
        counters(b, p);
        b.append(' ').append(attacks);
        return b.toString();
    }

    /**
     * The loaded state of the 3 x 3 chunk columns around the player's floored position: "cx cz
     * mask", where cx and cz are the floored column's chunk coordinates and bit (dz + 1) * 3 + (dx
     * + 1) of the hex mask is set when the column at (cx + dx, cz + dz) is loaded. With the
     * position, the replay can tell which column a truncating (not flooring) test reads at negative
     * coordinates, and whether a column the box touches was there at all.
     */
    private static void columns(StringBuilder b, EntityPlayerSP p) {
        int cx = MathHelper.floor_double(p.posX) >> 4;
        int cz = MathHelper.floor_double(p.posZ) >> 4;
        int mask = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!p.worldObj.getChunkFromChunkCoords(cx + dx, cz + dz).isEmpty()) {
                    mask |= 1 << ((dz + 1) * 3 + dx + 1);
                }
            }
        }
        b.append(' ').append(cx).append(' ').append(cz).append(' ');
        b.append(Integer.toHexString(mask));
    }

    /**
     * EntityPlayer.attackTargetEntityWithCurrentItem as PlayerControllerMP.attackEntity calls it
     * (redirects-mods.tsv): with the recorder on, writes an "A tick entity_id kind" record and,
     * inside a tick, counts it for the tick's after-record; then attacks as the game would. The
     * record's place among the others says whether it fell inside a tick (between P and T).
     *
     * @param player the attacking player (EntityPlayer)
     * @param target the attacked entity (Entity)
     */
    public static void attackTarget(Object player, Object target) {
        if (ENABLED && out != null) {
            try {
                Entity e = (Entity) target;
                out.write("A " + tick + ' ' + e.getEntityId() + ' ' + kind(e));
                out.write('\n');
                if (inTick) {
                    attacks++;
                }
            } catch (Throwable t) {
                fail(t);
            }
        }
        ((EntityPlayer) player).attackTargetEntityWithCurrentItem((Entity) target);
    }

    /**
     * The entity's kind as the trace writes it: the game's registered entity name ("Pig", "Boat",
     * "MinecartRideable", "EnderCrystal", "Fireball"...), "Player" for players, "DragonPart" for
     * the ender dragon's parts, "-" for anything else unregistered.
     */
    private static String kind(Entity e) {
        if (e instanceof EntityPlayer) {
            return "Player";
        }
        if (e instanceof EntityDragonPart) {
            return "DragonPart";
        }
        String name = EntityList.getEntityString(e);
        return name == null ? "-" : name;
    }

    /**
     * World.addBlockEvent as NetHandlerPlayClient.handleBlockAction calls it for the server's block
     * action (redirects-mods.tsv): with the recorder on, writes a "K tick x y z block_id event
     * param" record (a piston extending or retracting, a chest lid, a note block); then hands the
     * action to the world as the game would. It runs on the game thread, between two ticks.
     *
     * @param world the client world (World)
     * @param pos the block's position (BlockPos)
     * @param block the block the action is for (Block)
     * @param event the action's first byte (for pistons, 0 extend and 1 retract)
     * @param param the action's second byte (for pistons, the facing)
     */
    public static void blockEvent(Object world, Object pos, Object block, int event, int param) {
        if (ENABLED && out != null) {
            try {
                BlockPos at = (BlockPos) pos;
                out.write(
                        "K "
                                + tick
                                + ' '
                                + at.getX()
                                + ' '
                                + at.getY()
                                + ' '
                                + at.getZ()
                                + ' '
                                + Block.getIdFromBlock((Block) block)
                                + ' '
                                + event
                                + ' '
                                + param);
                out.write('\n');
            } catch (Throwable t) {
                fail(t);
            }
        }
        ((World) world).addBlockEvent((BlockPos) pos, (Block) block, event, param);
    }
}
