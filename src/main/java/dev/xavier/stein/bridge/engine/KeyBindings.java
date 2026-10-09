/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Keys from mods and from the bridge itself on the Controls screen, with the key the player chose.
 *
 * <p>The game reads options.txt inside startGame, before the mods register their keys
 * (afterStartGame). A key registered later stayed at the default on every start — and the next
 * saveOptions wrote the default over the player's choice. Here the key goes into the options list
 * (and the Controls screen) and receives the code saved in options.txt.
 */
public final class KeyBindings {

    private KeyBindings() {}

    /**
     * The game's {@code KeyBinding.unPressAllKeys()}: releases everything it thinks is pressed. It
     * is the same call it makes when opening a screen; the bridge calls it when the window loses
     * focus (see {@code core/Input}).
     */
    public static void unPressAll() {
        try {
            KeyBinding.unPressAllKeys();
        } catch (Throwable e) {
            Log.info("bridge", "could not release the keys: " + e);
        }
    }

    /** Registers a KeyBinding (game type, as Object) after startGame. */
    public static void register(Object bindingObject) {
        try {
            KeyBinding binding = (KeyBinding) bindingObject;
            Minecraft mc = Minecraft.getMinecraft();
            GameSettings settings = mc.gameSettings;
            KeyBinding[] keys = settings.keyBindings;
            for (KeyBinding k : keys) {
                if (k == binding) {
                    return;
                }
            }
            // copyOf keeps the array type (KeyBinding[]).
            KeyBinding[] all = Arrays.copyOf(keys, keys.length + 1);
            all[keys.length] = binding;
            settings.keyBindings = all;

            String prefix = "key_" + binding.getKeyDescription() + ":";
            File options = new File(mc.mcDataDir, "options.txt");
            if (options.isFile()) {
                // ISO-8859-1 never fails on read; the key lines are ASCII.
                for (String line :
                        Files.readAllLines(options.toPath(), StandardCharsets.ISO_8859_1)) {
                    if (line.startsWith(prefix)) {
                        try {
                            binding.setKeyCode(
                                    Integer.parseInt(line.substring(prefix.length()).trim()));
                        } catch (NumberFormatException ignored) {
                            // corrupted line: the default stays
                        }
                    }
                }
            }
            KeyBinding.resetKeyBindingArrayAndHash();
        } catch (Throwable e) {
            Log.info("bridge", "key binding not registered: " + e);
        }
    }
}
