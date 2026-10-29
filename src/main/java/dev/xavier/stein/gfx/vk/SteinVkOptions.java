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

    /**
     * {@code -Dsteinbridge.gfx.vkNoDepthClipControl=true}: acts as if the device had no
     * VK_EXT_depth_clip_control, as older drivers: the game goes to OpenGL (default: off; to test
     * that path).
     */
    static final boolean NO_DEPTH_CLIP_CONTROL = Opt.flag("steinbridge.gfx.vkNoDepthClipControl");

    /**
     * {@code -Dsteinbridge.gfx.vkAllowOldDriver=true}: runs Vulkan on a device without
     * VK_EXT_depth_clip_control anyway, with the shaders' depth remap (default: off).
     */
    static final boolean ALLOW_OLD_DRIVER = Opt.flag("steinbridge.gfx.vkAllowOldDriver");

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

    /**
     * {@code -Dsteinbridge.gfx.vkPipelineStats=true} (diagnostics): every 5 s, how many draw
     * pipelines were compiled on the render thread and how long they took (total and worst): the
     * hitches a new pipeline costs in the middle of a frame (default: off).
     */
    static final boolean PIPELINE_STATS = Opt.flag("steinbridge.gfx.vkPipelineStats");

    /**
     * {@code -Dsteinbridge.gfx.vkEarlyUploads=false}: every texture upload is recorded where it
     * happens, interrupting the open render, instead of going to the start of the submission when
     * nothing recorded before it in the submission touches that texture (the animated textures on
     * every client tick; default: on).
     */
    static final boolean EARLY_UPLOADS = Opt.flag("steinbridge.gfx.vkEarlyUploads", true);

    /**
     * {@code -Dsteinbridge.gfx.vkSwapchainImages=N} (experiment): asks the window for N swapchain
     * images, within what the surface allows; more images give the presentation engine slack, so a
     * present waits less often (default: 0, the surface minimum plus one).
     */
    static final int SWAPCHAIN_IMAGES = Opt.integer("steinbridge.gfx.vkSwapchainImages", 0);

    /**
     * {@code -Dsteinbridge.gfx.vkMailbox=true} (experiment, visible): without VSync, MAILBOX before
     * IMMEDIATE when the driver has it: no tearing, the newest finished frame replaces the one
     * waiting for the refresh (default: off, IMMEDIATE first).
     */
    static final boolean MAILBOX = Opt.flag("steinbridge.gfx.vkMailbox");

    /**
     * {@code -Dsteinbridge.gfx.vkParallelRecord=true} (experiment): the entity pass's bulk writes
     * (the camera-corrected instances of retained tile entities and the indirect commands of the
     * entity groups) run on the pose threads ({@code poseThreads}) at the flush instead of on the
     * render thread, into the same reserved ring bytes; the draws are recorded as before, so the
     * image is the same (default: off; without pose threads it writes on the render thread).
     */
    public static final boolean PARALLEL_RECORD = Opt.flag("steinbridge.gfx.vkParallelRecord");
}
