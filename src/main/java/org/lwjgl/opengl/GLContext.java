/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package org.lwjgl.opengl;

import dev.xavier.stein.bridge.core.Win;

import java.util.Set;

/**
 * Bridge to the LWJGL 3 capabilities model.
 *
 * <p>In LWJGL 2 the capabilities came from here; in LWJGL 3 they come from {@code
 * GL.getCapabilities()}, which is per thread and requires an active context.
 */
public final class GLContext {

    /**
     * What the game sees with the Vulkan backend (no OpenGL context): OpenGL 3.0, as its objects on
     * the API emulate it (framebuffers, VBOs, the 1.x level). The game's shaders exist only as
     * names there, which is enough: with Stein GFX its post-processing (spectator outline, "Super
     * Secret Settings") is already off ({@code DirectRender}), on OpenGL too.
     */
    private static final Set<String> VULKAN =
            Set.of(
                    "OpenGL11",
                    "OpenGL12",
                    "OpenGL13",
                    "OpenGL14",
                    "OpenGL15",
                    "OpenGL20",
                    "OpenGL21",
                    "OpenGL30",
                    "GL_ARB_shader_objects",
                    "GL_ARB_vertex_shader",
                    "GL_ARB_fragment_shader",
                    "GL_ARB_multitexture",
                    "GL_ARB_texture_env_combine",
                    "GL_ARB_vertex_buffer_object",
                    "GL_ARB_framebuffer_object",
                    "GL_ARB_texture_non_power_of_two",
                    "GL_EXT_blend_func_separate",
                    "GL_EXT_framebuffer_object",
                    "GL_EXT_rescale_normal");

    private static GLCapabilities source;
    private static ContextCapabilities cached;

    private GLContext() {}

    /**
     * Rebuilds if LWJGL 3 returns a different object — this happens when the context is recreated
     * (fullscreen switch, for example), and keeping the old cache would report the capabilities of
     * the dead context.
     */
    public static synchronized ContextCapabilities getCapabilities() {
        if (Win.vulkan) {
            if (cached == null) {
                cached = new ContextCapabilities(VULKAN);
            }
            return cached;
        }
        GLCapabilities now = GL.getCapabilities();
        if (cached == null || source != now) {
            source = now;
            cached = new ContextCapabilities(now);
        }
        return cached;
    }
}
