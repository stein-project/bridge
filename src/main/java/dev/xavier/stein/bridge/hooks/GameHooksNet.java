/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.hooks;

import dev.xavier.stein.bridge.core.Log;

import io.netty.buffer.ByteBuf;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundManager;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketThreadUtil;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Session;

/**
 * The player-action and packet side of {@link GameHooks}: attacks, entity interactions, window
 * clicks and slot updates, the missed-swing penalty, plugin payloads, sent chat, chest screens,
 * sounds and the session swap. The bodies of the {@code GameHooks} entry points live here.
 *
 * <p>{@code GameHooks} forces this class to initialise from its own static initializer.
 */
final class GameHooksNet {

    private GameHooksNet() {}

    /** Forces this class to initialise: {@code GameHooks} calls it from its static initializer. */
    static void init() {}

    /**
     * In place of PacketThreadUtil.checkThreadAndEnqueue in handleCustomPayload
     * (S3FPacketCustomPayload). The original hands the packet back to the game thread — on the
     * network thread it throws, and nothing below runs — and, already on the game thread, the
     * listeners receive the channel and a copy of the bytes. The game carries on with the packet as
     * usual (a channel it does not know, it ignores).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void customPayload(Object packet, Object handler, Object listener) {
        PacketThreadUtil.checkThreadAndEnqueue(
                (Packet) packet, (INetHandler) handler, (IThreadListener) listener);
        if (GameHooks.SINK != null) {
            try {
                S3FPacketCustomPayload payload = (S3FPacketCustomPayload) packet;
                String channel = payload.getChannelName();
                ByteBuf buf = payload.getBufferData();
                byte[] data = new byte[buf.readableBytes()];
                // getBytes by index: reads without advancing the cursor, and the game still reads
                // the packet afterwards.
                buf.getBytes(buf.readerIndex(), data);
                GameHooks.SINK.customPayload(channel, data);
            } catch (Throwable e) {
                GameHooks.fail("customPayload", e);
            }
        }
    }

    /**
     * In place of EntityPlayerSP.sendChatMessage(msg) in GuiScreen.sendChatMessage: what the player
     * sends, chat or command, goes through the listeners before going to the server. null sends
     * nothing.
     */
    static void sendChat(Object player, Object message) {
        Object msg = message;
        if (GameHooks.SINK != null) {
            try {
                msg = GameHooks.SINK.sendChat((String) message);
            } catch (Throwable e) {
                GameHooks.fail("sendChat", e);
                msg = message;
            }
            if (msg == null) {
                return;
            }
        }
        ((EntityPlayerSP) player).sendChatMessage((String) msg);
    }

    /**
     * In place of Minecraft.displayGuiScreen(screen) in EntityPlayerSP.displayGUIChest: the chest
     * the server opens becomes a screen — or, if the Loader asked for a virtual container, just the
     * container, with no screen at all.
     */
    static void openChestScreen(Object mc, Object screen) {
        GameHooks.ChestScreenFilter f = GameHooks.chestScreen;
        if (f != null) {
            try {
                if (f.consume(screen)) {
                    return;
                }
            } catch (Throwable e) {
                GameHooks.fail("openChestScreen", e);
            }
        }
        ((Minecraft) mc).displayGuiScreen((GuiScreen) screen);
    }

    /**
     * In place of SoundManager.playSound(sound) in SoundHandler.playSound: every sound goes through
     * the listeners; null silences it.
     */
    static void playSound(Object manager, Object sound) {
        Object s = sound;
        if (GameHooks.SINK != null) {
            try {
                s = GameHooks.SINK.playSound(sound);
            } catch (Throwable e) {
                GameHooks.fail("playSound", e);
                s = sound;
            }
            if (s == null) {
                return;
            }
        }
        ((SoundManager) manager).playSound((ISound) s);
    }

    /** Penalty for missing a swing: on, as in the game, until a mod turns it off. */
    private static boolean missPenalty = true;

    /**
     * The 1.8 penalty for missing a swing. When a click hits nothing (aiming at the sky, at the
     * air, beyond reach), {@code Minecraft.clickMouse} sets {@code leftClickCounter = 10} — ten
     * ticks, half a second — and until the counter reaches zero it returns on the first line:
     * during that time, aiming at someone is useless and the arm does not even swing. Whoever
     * clicks very fast spends half the time inside that window (measured: ~230 ms average wait at
     * 100 clicks per second, 500 ms in the worst case).
     *
     * <p>Turned off, each click counts again immediately. In exchange, every click that misses
     * sends the arm animation to the server — at 100 clicks per second that is 100 packets per
     * second, which draw anticheat's attention.
     */
    static void setMissPenalty(boolean on) {
        missPenalty = on;
    }

    /**
     * Clicks that missed and passed the first line of clickMouse — the penalty counter is only
     * consulted when the click got through. The {@code clickTestAtSec} scene reads from here: it is
     * the exact measure of how many clicks the game accepted, which the arm animation does not give
     * (it only restarts every three ticks).
     */
    private static int missedClicks;

    /** How many clicks missed since the game started. */
    static int missedClicks() {
        return missedClicks;
    }

    /**
     * In place of PlayerControllerMP.isNotCreative() inside Minecraft.clickMouse: it is the
     * condition for setting the ten penalty ticks on a miss. With the penalty off we answer as
     * creative answers — it does not set them — and the rest of the method stays the same. Outside
     * clickMouse the game's method stays untouched (the rule is scoped).
     */
    static boolean armMissPenalty(Object controller) {
        missedClicks++;
        return missPenalty && ((PlayerControllerMP) controller).isNotCreative();
    }

    /**
     * In place of PlayerControllerMP.attackEntity(player, target) in Minecraft.clickMouse: the
     * attack goes through the listener, which can cancel it — nothing goes to the server (the arm
     * already swung). Forge's AttackEntityEvent, on the client.
     */
    static void attackEntity(Object controller, Object player, Object target) {
        if (GameHooks.SINK != null && target != null) {
            try {
                boolean cancel = GameHooks.SINK.attackEntity(target);
                if (GameHooks.DEBUG) {
                    Log.info(
                            "hooks",
                            "attack on " + target + (cancel ? ": cancelled" : ": goes on"));
                }
                if (cancel) {
                    return;
                }
            } catch (Throwable e) {
                GameHooks.fail("attackEntity", e);
            }
        }
        ((PlayerControllerMP) controller).attackEntity((EntityPlayer) player, (Entity) target);
    }

    /**
     * In place of the two entity interactions of Minecraft.rightClickMouse
     * (isPlayerRightClickingOnEntity and interactWithEntitySendPacket): the listener can cancel —
     * nothing goes to the server, and the click carries on as if there were no entity in the
     * crosshair (the held item is used). Forge's EntityInteractEvent, on the client.
     */
    static boolean rightClickEntityAt(Object controller, Object player, Object target, Object hit) {
        if (cancelInteract(target)) {
            return false;
        }
        return ((PlayerControllerMP) controller)
                .isPlayerRightClickingOnEntity(
                        (EntityPlayer) player, (Entity) target, (MovingObjectPosition) hit);
    }

    /** See {@link #rightClickEntityAt}. */
    static boolean interactEntity(Object controller, Object player, Object target) {
        if (cancelInteract(target)) {
            return false;
        }
        return ((PlayerControllerMP) controller)
                .interactWithEntitySendPacket((EntityPlayer) player, (Entity) target);
    }

    private static boolean cancelInteract(Object target) {
        if (GameHooks.SINK == null || target == null) {
            return false;
        }
        try {
            return GameHooks.SINK.interactEntity(target);
        } catch (Throwable e) {
            GameHooks.fail("interactEntity", e);
            return false;
        }
    }

    // Window click in progress in the listener: a click requested from inside it (a mod that
    // answers a click with another) goes straight to the game, without going back to the listeners
    // — otherwise it would be endless recursion.
    private static boolean inWindowClick;

    /**
     * In place of PlayerControllerMP.windowClick throughout the game (it is the path of every slot
     * click, including the API's): the listener sees window, slot, button and mode before the
     * server and can cancel. Cancelled, nothing changes and nothing goes to the server; it returns
     * null (the caller in the game does not use the return).
     */
    static Object windowClick(
            Object controller, int windowId, int slot, int button, int mode, Object player) {
        if (GameHooks.SINK != null && !inWindowClick) {
            inWindowClick = true;
            try {
                if (GameHooks.SINK.windowClick(windowId, slot, button, mode)) {
                    return null;
                }
            } catch (Throwable e) {
                GameHooks.fail("windowClick", e);
            } finally {
                inWindowClick = false;
            }
        }
        return ((PlayerControllerMP) controller)
                .windowClick(windowId, slot, button, mode, (EntityPlayer) player);
    }

    /**
     * Swaps the game's session (Minecraft.session, private and final: ave.ae). Only the Loader
     * calls it (api.Account): the mod asks for "offline nick" or "the launcher's", and the token
     * never passes through it. The write is bound by core.GameAccess (the field is final in the
     * game); if the game jar does not have the field, the first call fails with a LinkageError
     * (BootstrapMethodError).
     */
    static void setSession(Object session) {
        Minecraft.getMinecraft().session = (Session) session;
    }

    /**
     * In place of Container.putStackInSlot in handleSetSlot (S2F): applies, and the listener is
     * informed.
     */
    static void setSlot(Object container, int slot, Object stack) {
        ((Container) container).putStackInSlot(slot, (ItemStack) stack);
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.slotChanged(((Container) container).windowId, slot, stack);
            } catch (Throwable e) {
                GameHooks.fail("slotChanged", e);
            }
        }
    }

    /**
     * In place of InventoryPlayer.setItemStack in handleSetSlot (window -1): the item on the
     * cursor.
     */
    static void setCursor(Object inventory, Object stack) {
        ((InventoryPlayer) inventory).setItemStack((ItemStack) stack);
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.slotChanged(-1, -1, stack);
            } catch (Throwable e) {
                GameHooks.fail("slotChanged", e);
            }
        }
    }

    /**
     * In place of Container.putStacksInSlots in handleWindowItems (S30): applies, and the listener
     * is informed.
     */
    static void setWindowItems(Object container, Object stacks) {
        ((Container) container).putStacksInSlots((ItemStack[]) stacks);
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.windowItems(((Container) container).windowId);
            } catch (Throwable e) {
                GameHooks.fail("windowItems", e);
            }
        }
    }
}
