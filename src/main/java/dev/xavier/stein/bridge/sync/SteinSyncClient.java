/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.LookFix;
import dev.xavier.stein.bridge.engine.SyncCorrection;
import dev.xavier.stein.bridge.engine.SyncReplay;
import dev.xavier.stein.bridge.engine.SyncReplayDriver;
import dev.xavier.stein.bridge.hooks.GameHooks;
import dev.xavier.stein.bridge.hooks.SteinSyncHooks;
import dev.xavier.stein.bridge.net.SyncApplied;

import io.netty.buffer.Unpooled;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovementInput;

/**
 * The client side of the Stein Sync messages a client sends (stein-sync's docs/protocol.md section
 * 5), on the one connection where the proxy negotiated them: the Tick in place of the vanilla
 * movement packets ({@code move}), the Attack message in place of the vanilla attack ({@code move}
 * with {@code hits}), and the BlockAction before each block action with the block changes held
 * until its BlockAck ({@code blocks}).
 *
 * <p>After a Respawn the hooks also wait for the proxy's Still ({@link SteinSync#confirmed}): a
 * server switch behind BungeeCord or Velocity looks like a Respawn on the same connection, and the
 * next server may have no Stein Sync (section 5.9).
 *
 * <p>Installed in the Bridge's {@link GameHooks#steinSync} only once a proxy negotiated one of
 * those features, and every hook first checks that the packet belongs to that connection: on any
 * other connection (a vanilla server, a server without Stein Sync, the next server joined) the game
 * sends exactly what it sends without Stein Sync.
 *
 * <p>What a Tick carries is what the vanilla packets would: the feet position, rotation and ground
 * flag of onUpdateWalkingPlayer; the sneak and sprint state the player last told the server (its
 * own C0B are dropped, the proxy sends them from the flags). What an Attack carries is the rotation
 * and the target position of the pick the click was handled with. Minecraft.runTick picks with
 * partial ticks 1.0 right before it handles the clicks, so that pick tested the target's box at its
 * current position, from the eye at the position of the previous Tick, with the yaw the Bridge's
 * LookFix gives getLook. Game thread only.
 *
 * <p>The confirmation of a server teleport (the C06 NetHandlerPlayClient.handlePlayerPosLook sends)
 * stays vanilla: the server accepts movement again only from a packet with the teleport's exact
 * position, and the proxy lets one such C06 through per teleport it relayed (protocol 5.5). The
 * Bridge has no hook there on purpose.
 */
final class SteinSyncClient implements SteinSyncHooks, BlockHold.Apply {

    static final SteinSyncClient INSTANCE = new SteinSyncClient();

    private static final boolean DEBUG = SteinSyncOptions.DEBUG_STEIN_SYNC;

    /** The NetHandlerPlayClient the mode below is for, and its NetworkManager; null when off. */
    private Object connection;

    private Object network;

    private boolean move;
    private boolean hits;
    private boolean blocks;

    /**
     * The proxy holds the authority over the player's movement (section 5.10, only over QUIC): its
     * Corrections are replayed (the Bridge's SyncReplay).
     */
    private boolean authority;

    /** The Bridge's correction, filled from each Correction message. */
    private final SyncCorrection correction = new SyncCorrection();

    /**
     * Corrections are logged one line per period at most. The proxy sends one per 250 ms at most
     * (protocol 5.10), so every Correction of a well-behaved proxy gets its own line (each one
     * names its Tick); only a flood is folded.
     */
    private final LogLimit correctionLog = new LogLimit(250);

    /** The number of the last Tick sent on this connection (the first is 1). */
    private int tickNumber;

    /** The number of the last BlockAction sent on this connection (the first is 1). */
    private int blockSeq;

    private final SteinSyncTick tick = new SteinSyncTick();
    private final BlockHold hold = new BlockHold();

    /**
     * The WorldClient the held block changes belong to. A Respawn into another dimension
     * (CraftBukkit's world switch included) builds a new WorldClient on the same connection: what
     * was held for the old one must never be written into the new one at the same coordinates.
     */
    private Object holdWorld;

    private final byte[] out = new byte[SteinSyncTick.MAX_BYTES];

    /** Attacks that could not be sent (over four in one tick, or an unknown target). */
    private int lostAttacks;

    /** Attack messages sent for the coming Tick (at most four, as a Tick carries). */
    private int tickAttacks;

    /** The player entity the Ticks are for now (identity only); its life is {@code tick.life}. */
    private Object lifePlayer;

    private SteinSyncClient() {}

    /** The proxy accepted these features on this connection. */
    void start(
            NetHandlerPlayClient handler,
            boolean move,
            boolean hits,
            boolean blocks,
            boolean authority) {
        stop();
        if (!move && !blocks) {
            return;
        }
        connection = handler;
        network = handler.getNetworkManager();
        this.move = move;
        this.hits = hits && move;
        this.blocks = blocks;
        this.authority = authority && move;
        GameHooks.steinSync = this;
        if (move) {
            SteinSyncMovement.enter();
        }
        SyncReplay.setSmooth(!SteinSyncOptions.STEIN_SYNC_SNAP);
        SyncReplay.setActive(this.authority);
    }

    /** The features a paused mode had (section 5.9), restored by {@link #resume}. */
    private boolean pausedMove;

    private boolean pausedHits;
    private boolean pausedBlocks;
    private boolean pausedAuthority;

    /**
     * A Respawn's Still did not come in time (section 5.9): the vanilla packets go out for this
     * player entity, and nothing is held; the connection, the Tick numbering and the life stay, so
     * a late Still can bring the mode back.
     */
    void pause() {
        if (connection == null) {
            return;
        }
        pausedMove = move;
        pausedHits = hits;
        pausedBlocks = blocks;
        pausedAuthority = authority;
        if (move) {
            SteinSyncMovement.leave();
        }
        move = false;
        hits = false;
        blocks = false;
        authority = false;
        SyncReplay.setActive(false);
        tickAttacks = 0;
        tick.clearAttacks();
        hold.clear();
        holdWorld = null;
    }

    /** The late Still came: the paused features again, on the same connection. */
    void resume() {
        if (connection == null) {
            return;
        }
        move = pausedMove;
        hits = pausedHits;
        blocks = pausedBlocks;
        authority = pausedAuthority;
        pausedMove = pausedHits = pausedBlocks = pausedAuthority = false;
        if (move) {
            SteinSyncMovement.enter();
        }
        SyncReplay.setActive(authority);
    }

    /** The connection ended or changed: back to the vanilla packets, nothing held. */
    void stop() {
        if (connection != null && lostAttacks > 0) {
            Log.info(
                    "steinsync",
                    "Stein Sync: "
                            + lostAttacks
                            + " attacks not sent (over four per tick, or an unknown target)");
        }
        String corrections = connection != null ? SyncReplay.summary() : null;
        if (corrections != null) {
            Log.info("steinsync", "Stein Sync authority: " + corrections);
        }
        SyncReplay.setActive(false);
        SyncReplay.resetCounters();
        if (move) {
            SteinSyncMovement.leave();
        }
        connection = null;
        network = null;
        pausedMove = pausedHits = pausedBlocks = pausedAuthority = false;
        move = false;
        hits = false;
        blocks = false;
        authority = false;
        tickNumber = 0;
        blockSeq = 0;
        lostAttacks = 0;
        tickAttacks = 0;
        tick.clearAttacks();
        tick.life = 0;
        lifePlayer = null;
        hold.clear();
        holdWorld = null;
    }

    /**
     * Drops everything held when the client's world is no longer the one it was held for; true when
     * it still is. Identity only: no allocation.
     */
    private boolean sameWorld(Object world) {
        if (world == holdWorld) {
            return true;
        }
        hold.clear();
        holdWorld = world;
        return false;
    }

    /** Is the Tick on for the current connection? */
    boolean moving() {
        return move;
    }

    /** End of each tick: actions without an ack for a second are forgotten. */
    void tickEnd() {
        if (blocks && hold.pending() > 0 && sameWorld(Minecraft.getMinecraft().theWorld)) {
            hold.expire(System.currentTimeMillis(), this);
        }
    }

    /** A BlockAck from the proxy. */
    void blockAck(int seq) {
        if (DEBUG) {
            Log.info(
                    "steinsync",
                    "Stein Sync: BlockAck "
                            + seq
                            + " (t="
                            + System.currentTimeMillis() % 100_000
                            + ")");
        }
        if (blocks && sameWorld(Minecraft.getMinecraft().theWorld)) {
            hold.ack(seq, this);
        }
    }

    // ---- movement (section 5.5) ----

    @Override
    public boolean playerPacket(Object handler, Object packet) {
        if (!move || handler != connection || !SteinSync.confirmed()) {
            return false;
        }
        if (packet instanceof C03PacketPlayer) {
            EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
            // A rider sends its look (C05) and steer input (C0C) from onUpdate: the Tick takes the
            // look's place, before the C0C, which stays vanilla.
            if (packet instanceof C03PacketPlayer.C05PacketPlayerLook
                    && player != null
                    && player.isRiding()) {
                sendTick(player, SteinSyncTick.RIDING);
            }
            return true;
        }
        if (packet instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction.Action a = ((C0BPacketEntityAction) packet).getAction();
            return a == C0BPacketEntityAction.Action.START_SNEAKING
                    || a == C0BPacketEntityAction.Action.STOP_SNEAKING
                    || a == C0BPacketEntityAction.Action.START_SPRINTING
                    || a == C0BPacketEntityAction.Action.STOP_SPRINTING;
        }
        return false;
    }

    @Override
    public void playerWalked() {
        if (!move || !SteinSync.confirmed()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getNetHandler() != connection || mc.thePlayer == null) {
            return;
        }
        // With the camera on another entity the vanilla client sends no movement packet (only its
        // sneak and sprint actions): the Tick still goes out, flagged, so the numbering and the
        // flags stay complete, and the proxy forwards only the actions.
        sendTick(
                mc.thePlayer,
                mc.getRenderViewEntity() == mc.thePlayer ? 0 : SteinSyncTick.CAMERA_AWAY);
    }

    private void sendTick(EntityPlayerSP player, int extraFlags) {
        SteinSyncTick t = tick;
        if (player != lifePlayer) {
            // The game built a new player entity (a Respawn): its Ticks start a new life. The
            // first entity after the Accept is life 0.
            if (lifePlayer != null) {
                t.life = (t.life + 1) & 0xFF;
            }
            lifePlayer = player;
        }
        diagNudge();
        t.x = player.posX;
        t.y = player.getEntityBoundingBox().minY;
        t.z = player.posZ;
        t.yaw = player.rotationYaw;
        t.pitch = player.rotationPitch;
        t.flags = flags(player, player.onGround) | extraFlags;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.keyBindSprint.isKeyDown()) {
            t.flags |= SteinSyncTick.SPRINT_KEY;
        }
        t.view = mc.gameSettings.thirdPersonView;
        t.applied = SyncApplied.applied();
        keys(player);
        if (authority) {
            // The tick about to be sent, kept for the Corrections: its input, what changed the
            // player before it, and its end state (the Tick's position).
            SyncReplay.commit(
                    tickNumber + 1,
                    (extraFlags & (SteinSyncTick.RIDING | SteinSyncTick.CAMERA_AWAY)) == 0);
        }
        send(t);
    }

    /**
     * {@code -Dsteinbridge.diag.syncNudge}: pushes the player once, after the movement of the
     * chosen Tick and before its state is read, as a client off the proxy's path would be.
     */
    private void diagNudge() {
        if (SteinSyncOptions.DIAG_SYNC_NUDGE != 0
                && authority
                && tickNumber + 1 == SteinSyncOptions.DIAG_SYNC_NUDGE_AT) {
            SyncReplay.nudge(SteinSyncOptions.DIAG_SYNC_NUDGE);
            Log.info(
                    "steinsync",
                    "Stein Sync authority test: pushed "
                            + SteinSyncOptions.DIAG_SYNC_NUDGE
                            + " blocks along X at Tick "
                            + (tickNumber + 1));
        }
    }

    // ---- authority (section 5.10) ----

    /** A Correction from the proxy: the tick's state restored and the later ticks run again. */
    void correction(SteinSyncCodec.Correction m) {
        if (!authority || !SteinSync.confirmed()) {
            return;
        }
        SyncCorrection c = correction;
        c.tick = m.tick;
        c.fields = SyncCorrection.ALL;
        c.state.x = m.x;
        c.state.y = m.y;
        c.state.z = m.z;
        c.state.motionX = m.motionX;
        c.state.motionY = m.motionY;
        c.state.motionZ = m.motionZ;
        int f = m.flags;
        c.state.onGround = (f & SteinSyncCodec.Correction.ON_GROUND) != 0;
        c.state.collidedHorizontally = (f & SteinSyncCodec.Correction.HIT_WALL) != 0;
        c.state.collidedVertically = (f & SteinSyncCodec.Correction.HIT_VERTICAL) != 0;
        c.state.inWater = (f & SteinSyncCodec.Correction.IN_WATER) != 0;
        c.state.inWeb = (f & SteinSyncCodec.Correction.IN_WEB) != 0;
        c.state.sprinting = (f & SteinSyncCodec.Correction.SPRINTING) != 0;
        c.state.fallDistance = m.fallDistance;
        c.state.jumpTicks = m.jumpCooldown;
        c.state.sprintingTicksLeft = m.sprintExpiry;
        c.state.sprintToggleTimer = m.sprintTapWindow;
        c.state.jumpMovementFactor = m.airControl;
        // The flight flag and window are not used: a player who can fly is never replayed (the
        // walking code the replay runs again does not fly), and the replay refuses it.
        int outcome = SyncReplay.correct(c);
        int leftOut = DEBUG ? 0 : correctionLog.due();
        if (leftOut >= 0) {
            Log.info(
                    "steinsync",
                    "Stein Sync authority: Correction of Tick "
                            + m.tick
                            + " (now "
                            + tickNumber
                            + "): "
                            + SyncReplayDriver.OUTCOMES[outcome]
                            + (outcome == SyncReplayDriver.OK
                                    ? String.format(", moved %.4f blocks", SyncReplay.lastShift())
                                    : "")
                            + LogLimit.leftOut(leftOut));
        }
    }

    private static int flags(EntityPlayerSP player, boolean onGround) {
        MovementInput input = player.movementInput;
        int f = onGround ? SteinSyncTick.ON_GROUND : 0;
        if (player.serverSneakState) {
            f |= SteinSyncTick.SNEAKING;
        }
        if (player.serverSprintState) {
            f |= SteinSyncTick.SPRINTING;
        }
        if (input != null && input.jump) {
            f |= SteinSyncTick.JUMPING;
        }
        if (player.isUsingItem()) {
            f |= SteinSyncTick.USING_ITEM;
        }
        return f;
    }

    private void keys(EntityPlayerSP player) {
        MovementInput input = player.movementInput;
        tick.forward = input == null ? 0 : SteinSyncTick.key(input.moveForward);
        tick.strafe = input == null ? 0 : SteinSyncTick.key(input.moveStrafe);
    }

    /** Writes the Tick with the next number and sends it (attacks went in Attack messages). */
    private void send(SteinSyncTick t) {
        int length = t.write(++tickNumber, out);
        t.clearAttacks();
        tickAttacks = 0;
        sendPayload(length);
    }

    // ---- attacks (section 5.1) ----

    @Override
    public boolean attackPacket(Object handler, Object packet) {
        if (!hits
                || handler != connection
                || !(packet instanceof C02PacketUseEntity)
                || !SteinSync.confirmed()) {
            return false;
        }
        C02PacketUseEntity c = (C02PacketUseEntity) packet;
        if (c.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        Entity target = world == null ? null : c.getEntityFromWorld(world);
        // In this mode the proxy drops a vanilla attack packet, so an attack that cannot be
        // reported is not sent at all; the click still played out on the client. Reported now, in
        // an Attack message in the C02's place (not in the end-of-tick Tick): a use of the same
        // tick (block-hitting's C08) then reaches the server after the attack, as from a vanilla
        // client, and anticheats that check that order see the vanilla order.
        if (player == null || target == null || tickAttacks == SteinSyncTick.MAX_ATTACKS) {
            lostAttacks++;
            return true;
        }
        tickAttacks++;
        sendPayload(
                SteinSyncTick.writeAttack(
                        tickNumber + 1,
                        target.getEntityId(),
                        LookFix.pickYaw(player),
                        player.rotationPitch,
                        target.posX,
                        target.posY,
                        target.posZ,
                        out));
        if (DEBUG) {
            Log.info(
                    "steinsync",
                    "Stein Sync: attack on "
                            + target.getEntityId()
                            + " sent for Tick "
                            + (tickNumber + 1)
                            + " (pick yaw "
                            + LookFix.pickYaw(player)
                            + ", pitch "
                            + player.rotationPitch
                            + ", seen at "
                            + target.posX
                            + " "
                            + target.posY
                            + " "
                            + target.posZ
                            + ")");
        }
        return true;
    }

    // ---- blocks (section 5.3) ----

    @Override
    public void blockPacket(Object handler, Object packet) {
        if (!blocks || handler != connection || !SteinSync.confirmed()) {
            return;
        }
        long a;
        long b = BlockHold.NONE;
        if (packet instanceof C07PacketPlayerDigging) {
            C07PacketPlayerDigging c = (C07PacketPlayerDigging) packet;
            C07PacketPlayerDigging.Action s = c.getStatus();
            if (s != C07PacketPlayerDigging.Action.START_DESTROY_BLOCK
                    && s != C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK
                    && s != C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK) {
                return;
            }
            a = key(c.getPosition());
        } else if (packet instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement c = (C08PacketPlayerBlockPlacement) packet;
            int face = c.getPlacedBlockDirection();
            BlockPos pos = c.getPosition();
            if (face < 0 || face > 5 || pos == null) {
                return;
            }
            EnumFacing side = EnumFacing.getFront(face);
            a = key(pos);
            b =
                    BlockHold.key(
                            pos.getX() + side.getFrontOffsetX(),
                            pos.getY() + side.getFrontOffsetY(),
                            pos.getZ() + side.getFrontOffsetZ());
        } else {
            return;
        }
        int seq = ++blockSeq;
        sendPayload(SteinSyncCodec.writeBlockAction(seq, out));
        sameWorld(Minecraft.getMinecraft().theWorld);
        hold.begin(seq, a, b, System.currentTimeMillis(), this);
        if (DEBUG) {
            Log.info(
                    "steinsync",
                    "Stein Sync: BlockAction "
                            + seq
                            + " before "
                            + packet.getClass().getSimpleName()
                            + " (t="
                            + System.currentTimeMillis() % 100_000
                            + ")");
        }
    }

    @Override
    public boolean holdBlockChange(Object world, Object pos, Object state) {
        if (!blocks || hold.pending() == 0) {
            return false;
        }
        if (Minecraft.getMinecraft().getNetHandler() != connection || !sameWorld(world)) {
            return false;
        }
        boolean held = hold.hold(key((BlockPos) pos), pos, state);
        if (DEBUG && held) {
            Log.info("steinsync", "Stein Sync: block change at " + pos + " held: " + state);
        }
        return held;
    }

    @Override
    public void apply(Object pos, Object state) {
        WorldClient world = Minecraft.getMinecraft().theWorld;
        // Held for another world: dropped, never written here.
        if (world != null && world == holdWorld) {
            world.invalidateRegionAndSetBlock((BlockPos) pos, (IBlockState) state);
        }
        if (DEBUG) {
            Log.info(
                    "steinsync",
                    "Stein Sync: held block change at "
                            + pos
                            + " applied: "
                            + state
                            + " (t="
                            + System.currentTimeMillis() % 100_000
                            + ")");
        }
    }

    private static long key(BlockPos pos) {
        return BlockHold.key(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Sends {@code out[0..length)} on {@code stein:sync} through the connection's NetworkManager,
     * in order with the game's own packets.
     *
     * <p>The bytes are written into the reused {@link #out}; what is left per Tick is the packet
     * itself: the C17, its PacketBuffer and a heap ByteBuf of the Tick's ~43 bytes. They cannot be
     * reused: the game thread hands the packet to Netty, which encodes it later on its own thread
     * (and reads the ByteBuf by advancing it), so a reused buffer could be rewritten by the next
     * Tick before the previous one is encoded; and a packet class of our own would not be in the
     * game's packet registry. The vanilla client allocates its movement packet every tick too, and
     * still does here (the game builds it before the hook drops it).
     */
    private void sendPayload(int length) {
        ((NetworkManager) network)
                .sendPacket(
                        new C17PacketCustomPayload(
                                SteinSyncCodec.CHANNEL,
                                new PacketBuffer(Unpooled.copiedBuffer(out, 0, length))));
    }
}
