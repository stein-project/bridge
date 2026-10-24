/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import dev.xavier.stein.bridge.core.Opt;

/**
 * The system properties read by the Vulkan backend of Stein GFX (experimental).
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
public final class SteinVkOptions {

    private SteinVkOptions() {}

    /**
     * {@code -Dsteinbridge.gfx.vkValidation=true}: turns on the Vulkan SDK validation layers
     * (default: off).
     */
    static final boolean VALIDATION = Opt.flag("steinbridge.gfx.vkValidation");

    /** {@code -Dsteinbridge.gfx.vkNoPush=true}: does not use push descriptors (default: off). */
    static final boolean NO_PUSH = Opt.flag("steinbridge.gfx.vkNoPush");

    /**
     * {@code -Dsteinbridge.gfx.vkDevice=N}: chooses the physical device by driver order (default:
     * -1, automatic).
     */
    static final int DEVICE = Opt.integer("steinbridge.gfx.vkDevice", -1);

    /**
     * {@code -Dsteinbridge.gfx.vkGameCopy=false} (diagnostics): the game's textures are not copied
     * into the renderer's texture arrays; entities and the HUD draw with the game's texture itself
     * (default: copied).
     */
    static final boolean GAME_COPY = Opt.flag("steinbridge.gfx.vkGameCopy", true);

    /**
     * {@code -Dsteinbridge.gfx.vkDebugDrawsAtSec=N} (diagnostics): logs every draw of one frame, N
     * seconds after startup — pass, pipeline state, textures and the start of the uniforms
     * (default: off).
     */
    static final int DEBUG_DRAWS_AT_SEC = Opt.integer("steinbridge.gfx.vkDebugDrawsAtSec", -1);

    /**
     * {@code -Dsteinbridge.gfx.vkSyncEachPass=true} (diagnostics): submits and waits for the GPU at
     * the end of every pass, so nothing the CPU writes afterwards can reach a pass already recorded
     * (default: off).
     */
    static final boolean SYNC_EACH_PASS = Opt.flag("steinbridge.gfx.vkSyncEachPass");
}
