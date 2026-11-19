/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * The tick-driven scene script of the physics trace ({@code
 * -Dsteinbridge.diag.physicsScript=<file>}): one command per line, run at the start of a client
 * tick, before the state is recorded. Lines:
 *
 * <ul>
 *   <li>{@code cmd <text>}: the player sends the text in the chat (a server command to build the
 *       scene, give an effect, teleport);
 *   <li>{@code wait <n>}: n ticks pass with the keys as they are;
 *   <li>{@code keys <set>}: the keys held from this tick on, letters W A S D, J (jump), N (sneak),
 *       R (sprint), U (use item), L (attack, held: mining), or {@code -} for none;
 *   <li>{@code yaw <degrees>}: the heading, as the mouse would set it between ticks;
 *   <li>{@code pitch <degrees>}: the vertical angle, the same way (aims clicks at blocks and
 *       entities);
 *   <li>{@code slot <0-8>}: the hotbar slot, as a number key would pick it;
 *   <li>{@code attack [n]}: n presses (default 1) of the attack button in this tick: the tick's
 *       click step clicks n times on whatever the crosshair is on (an entity is attacked, a block
 *       is hit, the air is a miss), through the game's own path;
 *   <li>{@code use [n]}: n presses (default 1) of the use button in this tick (right click: use the
 *       item, place a block, interact with an entity);
 *   <li>{@code unload <cx> <cz>}: the client drops that chunk column, as it does when the server
 *       unloads it (the server still thinks it is loaded and does not send it again until the
 *       player leaves and comes back), so a scene can stay next to an unloaded column;
 *   <li>{@code scene <name>}: ends the current scene file; the next {@code dump} starts the new
 *       one;
 *   <li>{@code dump x0 y0 z0 x1 y1 z1}: opens the scene's file with the blocks of that region and
 *       starts recording;
 *   <li>{@code note <text>}: a note in the scene file;
 *   <li>{@code respawn}: presses the death screen's respawn button (a Respawn without a player at
 *       the keyboard, for the Stein Sync respawn checks);
 *   <li>{@code end}: closes the file; the script is over.
 * </ul>
 *
 * <p>Keys are set every tick through KeyBinding, so a focus change of the (background) window does
 * not release them halfway through a scene.
 */
final class PhysicsScript {

    private static final String FILE = System.getProperty("steinbridge.diag.physicsScript");

    /** Ticks a missed click blocks further clicks; a larger counter is the "no focus" value. */
    private static final int MISS_PENALTY = 10;

    private static List<String> lines;
    private static int next;
    private static int waiting;
    private static int held;
    private static String scene = "scene";
    private static boolean over;

    private PhysicsScript() {}

    /** Runs the script's lines due at the start of this tick, then holds the keys. */
    static void tick(EntityPlayerSP p) throws IOException {
        if (FILE == null || over) {
            return;
        }
        if (lines == null) {
            lines = Files.readAllLines(Paths.get(FILE));
            Log.info("diag", "physicsScript: " + lines.size() + " lines");
        }
        if (waiting > 0) {
            waiting--;
        }
        while (waiting == 0 && next < lines.size() && !over) {
            run(p, lines.get(next++).trim());
        }
        if (next >= lines.size()) {
            over = true;
            PhysicsTrace.close();
        }
        hold();
    }

    private static void run(EntityPlayerSP p, String line) throws IOException {
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        int sp = line.indexOf(' ');
        String op = sp < 0 ? line : line.substring(0, sp);
        String arg = sp < 0 ? "" : line.substring(sp + 1).trim();
        switch (op) {
            case "cmd" -> {
                p.sendChatMessage(arg);
                PhysicsTrace.note("cmd " + arg);
            }
            case "wait" -> waiting = Integer.parseInt(arg);
            case "keys" -> {
                held = parseKeys(arg);
                PhysicsTrace.note("keys " + arg);
            }
            case "yaw" -> {
                p.rotationYaw = Float.parseFloat(arg);
                p.prevRotationYaw = p.rotationYaw;
                PhysicsTrace.note("yaw " + arg);
            }
            case "pitch" -> {
                p.rotationPitch = Float.parseFloat(arg);
                p.prevRotationPitch = p.rotationPitch;
                PhysicsTrace.note("pitch " + arg);
            }
            case "slot" -> {
                p.inventory.currentItem = Integer.parseInt(arg);
                PhysicsTrace.note("slot " + arg);
            }
            case "attack" -> {
                Minecraft mc = Minecraft.getMinecraft();
                // A window that never had focus keeps the click counter at its large "no focus"
                // value and every click is ignored; a real player's window has focus. The
                // 10-tick miss penalty itself stays.
                if (mc.leftClickCounter > MISS_PENALTY) {
                    mc.leftClickCounter = 0;
                }
                press(mc.gameSettings.keyBindAttack, arg);
                PhysicsTrace.note("attack " + presses(arg));
            }
            case "use" -> {
                press(Minecraft.getMinecraft().gameSettings.keyBindUseItem, arg);
                PhysicsTrace.note("use " + presses(arg));
            }
            case "unload" -> {
                String[] a = arg.split("\\s+");
                int cx = Integer.parseInt(a[0]);
                int cz = Integer.parseInt(a[1]);
                // What the client does with an empty full-column update from the server.
                Minecraft.getMinecraft().theWorld.doPreChunk(cx, cz, false);
                PhysicsTrace.note("unload " + cx + " " + cz);
            }
            case "scene" -> {
                PhysicsTrace.close();
                scene = arg;
            }
            case "dump" -> {
                String[] a = arg.split("\\s+");
                int[] c = new int[6];
                for (int i = 0; i < 6; i++) {
                    c[i] = Integer.parseInt(a[i]);
                }
                PhysicsTrace.open(scene, c[0], c[1], c[2], c[3], c[4], c[5]);
            }
            case "note" -> PhysicsTrace.note(arg);
            case "respawn" -> {
                // What the death screen's button does.
                p.respawnPlayer();
                Minecraft.getMinecraft().displayGuiScreen(null);
                PhysicsTrace.note("respawn");
            }
            case "end" -> {
                held = 0;
                over = true;
                PhysicsTrace.close();
                Log.info("diag", "physicsScript: end");
            }
            default -> Log.info("diag", "physicsScript: unknown line " + line);
        }
    }

    /** The number of presses an {@code attack} or {@code use} line asks for (1 when omitted). */
    private static int presses(String arg) {
        return arg.isEmpty() ? 1 : Integer.parseInt(arg);
    }

    /**
     * Counts presses of a button for this tick's click step, as the mouse handler does for each
     * button-down event; the held state of the button is left to {@link #hold}.
     */
    private static void press(KeyBinding key, String arg) {
        for (int i = presses(arg); i > 0; i--) {
            KeyBinding.onTick(key.getKeyCode());
        }
    }

    private static int parseKeys(String s) {
        int k = 0;
        for (char c : s.toCharArray()) {
            k |=
                    switch (c) {
                        case 'W' -> 1;
                        case 'S' -> 2;
                        case 'A' -> 4;
                        case 'D' -> 8;
                        case 'J' -> 16;
                        case 'N' -> 32;
                        case 'R' -> 64;
                        case 'U' -> 128;
                        case 'L' -> 256;
                        default -> 0;
                    };
        }
        return k;
    }

    /** Presses exactly the keys of the current set. */
    private static void hold() {
        GameSettings g = Minecraft.getMinecraft().gameSettings;
        KeyBinding[] order = {
            g.keyBindForward,
            g.keyBindBack,
            g.keyBindLeft,
            g.keyBindRight,
            g.keyBindJump,
            g.keyBindSneak,
            g.keyBindSprint,
            g.keyBindUseItem,
            g.keyBindAttack
        };
        for (int i = 0; i < order.length; i++) {
            KeyBinding.setKeyBindState(order[i].getKeyCode(), (held & (1 << i)) != 0);
        }
    }
}
