/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

/**
 * Access to the game's obfuscated classes for the optimizations' initializers.
 *
 * <p>Loads without initializing: looking up methods through a MethodHandle does not need the class
 * to be initialized, and it only gets initialized when the game actually uses it. This way an
 * optimization's initialization does not trigger, at the wrong time, the static initializer of a
 * game class — some call OpenGL or Minecraft.getMinecraft() — and the preflight
 * (test/Preflight.java) can check the lookups without the game running.
 */
public final class Obf {

    private Obf() {}

    public static Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name, false, Obf.class.getClassLoader());
    }
}
