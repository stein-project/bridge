/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.compat;

import static org.lwjgl.system.MemoryUtil.NULL;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * OpenAL lifecycle in the LWJGL 2 shape.
 *
 * <p>LWJGL 2's {@code AL.create()} opened the device, created the context and published the
 * capabilities in a single call. In LWJGL 3 these are separate steps and the state stays with the
 * caller — so we keep it here.
 *
 * <p>The caller is Minecraft's sound library (paulscode), whose call sites the patcher redirects to
 * here.
 */
public final class ALCompat {

    private static long device = NULL;
    private static long context = NULL;
    private static boolean created;

    private ALCompat() {}

    /** Equivalent of LWJGL 2's {@code org.lwjgl.openal.AL.create()}. */
    public static synchronized void create() {
        if (created) {
            return;
        }
        device = ALC10.alcOpenDevice((ByteBuffer) null);
        if (device == NULL) {
            throw new IllegalStateException("Could not open the default OpenAL device");
        }
        ALCCapabilities alcCaps = ALC.createCapabilities(device);
        context = ALC10.alcCreateContext(device, (IntBuffer) null);
        if (context == NULL) {
            ALC10.alcCloseDevice(device);
            device = NULL;
            throw new IllegalStateException("Could not create the OpenAL context");
        }
        ALC10.alcMakeContextCurrent(context);
        AL.createCapabilities(alcCaps);
        created = true;
    }

    /**
     * Equivalent of {@code AL.destroy()}. In LWJGL 3 this method exists but is package-private —
     * calling it from outside yields {@code IllegalAccessError}.
     */
    public static synchronized void destroy() {
        if (!created) {
            return;
        }
        ALC10.alcMakeContextCurrent(NULL);
        if (context != NULL) {
            ALC10.alcDestroyContext(context);
            context = NULL;
        }
        if (device != NULL) {
            ALC10.alcCloseDevice(device);
            device = NULL;
        }
        created = false;
    }

    public static boolean isCreated() {
        return created;
    }
}
