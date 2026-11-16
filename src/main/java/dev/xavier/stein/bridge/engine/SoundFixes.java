/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;

import paulscode.sound.SoundSystem;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fixes in the game's SoundManager: the pause menu's pause and resume, and the warning of an
 * unknown sound.
 *
 * <ul>
 *   <li>pauseAllSounds paused every source still in the playing list and resumeAllSounds played
 *       every one of them again, including those that had already ended and were only waiting for
 *       the next update to be removed: closing the pause menu in single player replayed, from the
 *       start, a door, a hit or an explosion heard just before (fixed in 1.14). Here the pause only
 *       pauses a source that is playing and remembers it, and the resume only plays the ones it
 *       remembers. {@code -Dsteinbridge.opt.vanillaPauseSounds=true} brings back 1.8.9's behaviour.
 *   <li>playSound writes "Unable to play unknown soundEvent" every time a sound the client does not
 *       have plays; with a newer server through ViaVersion that is several lines per second, which
 *       buried the rest of the log. Here each sound warns once. {@code
 *       -Dsteinbridge.opt.vanillaSoundWarnings=true} warns every time.
 * </ul>
 */
public final class SoundFixes {

    private SoundFixes() {}

    private static final boolean VANILLA_PAUSE = GuiFixOptions.VANILLA_PAUSE_SOUNDS;
    private static final boolean VANILLA_WARNINGS = GuiFixOptions.VANILLA_SOUND_WARNINGS;

    /**
     * More paused sources than the sound system can ever hold at once: past this, the set only
     * holds ids of sources that were removed during a pause (leaving the world from the menu).
     */
    private static final int MAX_PAUSED = 256;

    /** Ids of the sources the pause menu paused, on the game thread. */
    private static final Set<String> PAUSED = new HashSet<>();

    /** The sounds already warned about, per warning (the format string). */
    private static final Map<Object, Set<Object>> WARNED = new HashMap<>();

    /** In place of the sound system's pause(id) in SoundManager.pauseAllSounds. */
    public static void pause(Object system, Object id) {
        SoundSystem sounds = (SoundSystem) system;
        String source = (String) id;
        if (VANILLA_PAUSE) {
            sounds.pause(source);
            return;
        }
        if (!sounds.playing(source)) {
            return; // already ended (or never started): nothing to bring back
        }
        if (PAUSED.size() >= MAX_PAUSED) {
            PAUSED.clear();
        }
        PAUSED.add(source);
        sounds.pause(source);
    }

    /** In place of the sound system's play(id) in SoundManager.resumeAllSounds. */
    public static void resume(Object system, Object id) {
        String source = (String) id;
        if (VANILLA_PAUSE || PAUSED.remove(source)) {
            ((SoundSystem) system).play(source);
        }
    }

    /** In place of the logger's warn in SoundManager.playSound: once per sound and warning. */
    public static void warn(Object logger, Object marker, Object format, Object args) {
        Object[] a = (Object[]) args;
        if (!VANILLA_WARNINGS && a != null && a.length > 0) {
            Set<Object> seen = WARNED.get(format);
            if (seen == null) {
                seen = new HashSet<>();
                WARNED.put(format, seen);
            }
            if (!seen.add(a[0])) {
                return;
            }
        }
        ((Logger) logger).warn((Marker) marker, (String) format, a);
    }
}
