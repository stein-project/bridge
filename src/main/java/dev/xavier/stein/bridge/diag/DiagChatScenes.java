/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.entity.EntityPlayerSP;

/**
 * The chat-driven diagnostic scene ({@code steinbridge.diag.chat} / {@code chatFile}): the player
 * sends the listed commands at the listed times; entries starting with {@code #} or {@code !} are
 * local commands handled by {@link DiagLocalCommands} instead.
 */
final class DiagChatScenes {
    private DiagChatScenes() {}

    /**
     * {@code -Dsteinbridge.diag.chat=S:command|S:command...}: at S seconds from startup, the player
     * sends the text in the chat (EntityPlayerSP.sendChatMessage) — /tp, /time, /weather in a world
     * with commands, to set up the scene. run.sh splits JAVA_OPTS on spaces: in the command, '_'
     * becomes a space.
     */
    private static final boolean CHAT_FILE = DiagOptions.CHAT_FILE != null;

    private static final String[] CHAT = chatEntries();

    /**
     * {@code -Dsteinbridge.diag.chatFile=file}: the same S:command entries, one per line — without
     * the Windows command-line limit (32 thousand characters), for long scenes (particles repeating
     * for minutes).
     */
    private static String[] chatEntries() {
        String file = DiagOptions.CHAT_FILE;
        if (file != null) {
            try {
                return java.nio.file.Files.readAllLines(java.nio.file.Paths.get(file)).stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new);
            } catch (java.io.IOException e) {
                Log.info("diag", "chatFile: " + e);
                return new String[0];
            }
        }
        String s = DiagOptions.CHAT;
        return s.isEmpty() ? new String[0] : s.split("\\|");
    }

    private static int chatNext;

    /**
     * On the command line a space separates the options: there "_" becomes a space. In the file,
     * the command goes as it is.
     */
    private static String command(String entry) {
        String text = entry.substring(entry.indexOf(':') + 1);
        return CHAT_FILE ? text : text.replace('_', ' ');
    }

    private static void chat(long elapsed) {
        String entry = CHAT[chatNext];
        int colon = entry.indexOf(':');
        if (elapsed < Long.parseLong(entry.substring(0, colon)) * 1_000_000_000L) {
            return;
        }
        chatNext++;
        String text = command(entry);
        if (text.startsWith("#") || text.startsWith("!")) {
            try {
                DiagLocalCommands.run(text);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.info("diag", "failed on " + text + ": " + e);
            }
            return;
        }
        EntityPlayerSP player = DiagGame.player();
        if (player == null) {
            Log.info(
                    "diag",
                    "chat without a player: "
                            + entry.substring(0, colon + 1)
                            + safe(command(entry)));
            return;
        }
        String command = command(entry);
        player.sendChatMessage(command);
        Log.info("diag", "chat: " + safe(command));
    }

    /**
     * The command as it may go to the log: password ones keep only the name. The run log is an
     * ordinary file, and a scene that joins a server sends "/login <password>" — which has no
     * reason to be recorded anywhere.
     */
    private static String safe(String command) {
        String lower = command.toLowerCase(java.util.Locale.ROOT);
        for (String secret :
                new String[] {
                    "/login ", "/l ", "/register ", "/reg ", "/changepassword ", "/senha "
                }) {
            if (lower.startsWith(secret)) {
                return secret.trim() + " ***";
            }
        }
        return command;
    }

    static void frame() {
        if (chatNext < CHAT.length) {
            try {
                chat(Scenes.elapsed());
            } catch (RuntimeException e) {
                chatNext++;
                Log.info("diag", "failed in the chat: " + e);
            }
        }
    }
}
