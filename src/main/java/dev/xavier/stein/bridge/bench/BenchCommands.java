/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.TaskPost;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandResultStats;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.Vec3;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The benchmark's work on the integrated server's thread: the game's own commands, run by a sender
 * of their own that prints nothing (no chat, no log line: on one machine the server thread spent 5
 * s writing one command's line to a console nobody read, while the task queue's lock was held), and
 * other tasks of the benchmark's (a teleport, the biome search). Everything is posted through
 * {@link TaskPost}, so the render thread never waits on the server's queue, and counted, so a part
 * can wait until the server has done what it asked ({@link #mark}, {@link #ran}).
 */
final class BenchCommands {

    private BenchCommands() {}

    /** Ids of the tasks posted and not finished yet. */
    private static final ConcurrentSkipListSet<Long> UNFINISHED = new ConcurrentSkipListSet<>();

    private static final AtomicLong POSTED = new AtomicLong();

    /** Commands that said something back (only errors do, feedback being off), and the first. */
    private static final AtomicInteger FAILED = new AtomicInteger();

    private static volatile String firstFailure;

    /** Runs the commands on the server's thread, in one task, silently. */
    static void run(Minecraft mc, String... commands) {
        MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        long id = begin();
        TaskPost.toServer(
                server,
                (Runnable)
                        () -> {
                            try {
                                execute(server, commands, 0, commands.length);
                            } finally {
                                UNFINISHED.remove(id);
                            }
                        });
    }

    /**
     * Runs the commands {@code perTick} at a time, one batch per server tick (each batch posts the
     * next), so a long list (the block gallery) never holds the server in one task.
     */
    static void runSpread(Minecraft mc, int perTick, String... commands) {
        MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        long id = begin();
        batch(server, id, commands, 0, perTick);
    }

    private static void batch(
            MinecraftServer server, long id, String[] commands, int from, int perTick) {
        TaskPost.toServer(
                server,
                (Runnable)
                        () -> {
                            int to = Math.min(commands.length, from + perTick);
                            try {
                                execute(server, commands, from, to);
                            } finally {
                                if (to < commands.length) {
                                    batch(server, id, commands, to, perTick);
                                } else {
                                    UNFINISHED.remove(id);
                                }
                            }
                        });
    }

    /** Runs {@code work} on the server's thread, counted like the commands. */
    static void onServer(Minecraft mc, Runnable work) {
        MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        long id = begin();
        TaskPost.toServer(
                server,
                (Runnable)
                        () -> {
                            try {
                                work.run();
                            } catch (RuntimeException e) {
                                // the task queue would swallow it
                                Log.error("bench", "server task failed", e);
                            } finally {
                                UNFINISHED.remove(id);
                            }
                        });
    }

    private static long begin() {
        long id = POSTED.incrementAndGet();
        UNFINISHED.add(id);
        return id;
    }

    private static void execute(MinecraftServer server, String[] commands, int from, int to) {
        quiet(server);
        ICommandSender sender = new Silent(server);
        for (int i = from; i < to; i++) {
            try {
                server.getCommandManager().executeCommand(sender, commands[i]);
            } catch (RuntimeException e) {
                Log.warn("bench", "command failed: " + commands[i] + ": " + e);
            }
        }
    }

    /**
     * The game rules that would still make a command print: the server logs every command of a
     * sender other than itself while logAdminCommands is on. Set before the first command (the
     * gamerule commands of the preparation would be printed themselves).
     */
    private static void quiet(MinecraftServer server) {
        if (server.worldServers == null || server.worldServers.length == 0) {
            return;
        }
        GameRules rules = server.worldServers[0].getGameRules();
        if (rules.getBoolean("logAdminCommands") || rules.getBoolean("sendCommandFeedback")) {
            rules.setOrCreateGameRule("logAdminCommands", "false");
            rules.setOrCreateGameRule("sendCommandFeedback", "false");
        }
    }

    /**
     * A mark of the tasks posted until now: {@link #ran} says when the server has run them all. The
     * render thread no longer waits on the server's queue, so a part must not start measuring while
     * the server is still building its scenery.
     */
    static long mark() {
        return POSTED.get();
    }

    static boolean ran(long mark) {
        Long first = UNFINISHED.isEmpty() ? null : UNFINISHED.first();
        return first == null || first > mark;
    }

    /** How many commands answered with an error so far (and the first error), for the events. */
    static String failures() {
        int n = FAILED.get();
        return n == 0 ? "none" : n + " (first: " + firstFailure + ")";
    }

    /**
     * The server's permissions and place, without its voice: what a command says back goes nowhere
     * (feedback is off, so only errors would come here; they are counted).
     */
    private static final class Silent implements ICommandSender {

        private final MinecraftServer server;

        Silent(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public String getName() {
            return "Stein benchmark";
        }

        @Override
        public IChatComponent getDisplayName() {
            return new ChatComponentText(getName());
        }

        @Override
        public void addChatMessage(IChatComponent component) {
            if (FAILED.getAndIncrement() == 0) {
                firstFailure = component.getUnformattedText();
            }
        }

        @Override
        public boolean canCommandSenderUseCommand(int permLevel, String commandName) {
            return true;
        }

        @Override
        public BlockPos getPosition() {
            return server.getPosition();
        }

        @Override
        public Vec3 getPositionVector() {
            return server.getPositionVector();
        }

        @Override
        public World getEntityWorld() {
            return server.getEntityWorld();
        }

        @Override
        public Entity getCommandSenderEntity() {
            return null;
        }

        @Override
        public boolean sendCommandFeedback() {
            return false;
        }

        @Override
        public void setCommandStat(CommandResultStats.Type type, int amount) {}
    }
}
