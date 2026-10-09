/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package org.lwjgl.opengl;

/**
 * Bridge to the LWJGL 3 capabilities model.
 *
 * <p>In LWJGL 2 the capabilities came from here; in LWJGL 3 they come from {@code
 * GL.getCapabilities()}, which is per thread and requires an active context.
 */
public final class GLContext {

    private static GLCapabilities source;
    private static ContextCapabilities cached;

    private GLContext() {}

    /**
     * Rebuilds if LWJGL 3 returns a different object — this happens when the context is recreated
     * (fullscreen switch, for example), and keeping the old cache would report the capabilities of
     * the dead context.
     */
    public static synchronized ContextCapabilities getCapabilities() {
        GLCapabilities now = GL.getCapabilities();
        if (cached == null || source != now) {
            source = now;
            cached = new ContextCapabilities(now);
        }
        return cached;
    }
}
