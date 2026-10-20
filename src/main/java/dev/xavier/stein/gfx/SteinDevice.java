/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.gl.SteinGlDevice;

import java.nio.ByteBuffer;

/**
 * Stein's graphics device: creates the resources and opens the render passes. It is the door to
 * Stein's graphics API — the renderer only talks to it, never to OpenGL or Vulkan directly. Each
 * backend is an implementation ({@link SteinGlDevice} today; Vulkan later).
 *
 * <p>Deliberately Vulkan-like: draw state in a closed pipeline ({@link SteinPipeline}), explicit
 * buffers and textures, sampler separate from texture, drawing only inside a pass ({@link
 * SteinRenderPass}), and declared synchronization ({@link SteinFence}). Everything on the game
 * thread (the graphics context's).
 *
 * <p>Pipeline: Stein GFX only.
 */
public abstract class SteinDevice {

    private static SteinDevice current;

    /** The game's device (created on the first call, with the graphics context already current). */
    public static synchronized SteinDevice get() {
        if (current == null) {
            current = new SteinGlDevice();
            Log.info("gfx", String.valueOf(current.caps()));
        }
        return current;
    }

    /**
     * Replaces the current device (the self-test of a backend outside the game: {@code
     * SteinVkSelfTest}). The buffer rings ask the current device for fences.
     */
    public static synchronized void use(SteinDevice device) {
        current = device;
    }

    /** What this device can do. */
    public abstract SteinCaps caps();

    // ---- resources ----

    public abstract SteinBuffer createBuffer(SteinBuffer.Desc desc);

    public abstract SteinTexture createTexture(SteinTexture.Desc desc);

    public abstract SteinSampler createSampler(SteinSampler.Desc desc);

    /**
     * A texture that already exists outside the API (the game's), only to bind and copy: it is not
     * ours, {@code destroy} does not delete it. {@code desc} describes what it is (type, format,
     * size).
     */
    public abstract SteinTexture importTexture(SteinTexture.Desc desc, long handle);

    /**
     * Copies on the GPU a {@code w x h} rectangle of level 0 of {@code src} (layer {@code
     * srcLayer}) to {@code dst} (layer {@code dstLayer}), starting at (0, 0). Same formats.
     * Requires {@link SteinCaps#copyImage}.
     */
    public abstract void copyTexture(
            SteinTexture src, int srcLayer, SteinTexture dst, int dstLayer, int w, int h);

    /**
     * Whether {@link #copyTexture} can copy the game's textures (imported) into ours. False when
     * the driver refuses every way of copying them: whoever uses them then draws with the game's
     * texture itself.
     */
    public boolean copiesGameTextures() {
        return true;
    }

    /**
     * Copies on the GPU {@code bytes} from {@code src} (starting at {@code srcOffset}) to {@code
     * dst} (starting at {@code dstOffset}). Outside a pass; what was requested of the GPU before
     * reads the earlier contents.
     */
    public abstract void copyBuffer(
            SteinBuffer src, long srcOffset, SteinBuffer dst, long dstOffset, long bytes);

    public abstract SteinPipeline createPipeline(SteinPipeline.Desc desc);

    /** Render target with these textures (colors and depth; any of them may be null). */
    public abstract SteinRenderTarget createTarget(SteinTexture[] colors, SteinTexture depth);

    /** The target the game is drawing to now (its framebuffer or the screen). */
    public abstract SteinRenderTarget gameTarget();

    // ---- work ----

    /**
     * Opens a pass: what is recorded in the returned {@link SteinCommands} goes to its target. On
     * OpenGL the commands run immediately, and the game's state is kept here and restored in {@link
     * SteinCommands#end()}.
     */
    public abstract SteinCommands beginPass(SteinRenderPass pass);

    /** A fence at the current point of the GPU queue. */
    public abstract SteinFence fence();

    /**
     * Reads pixels of a color texture (RGBA8) to the CPU: only for tests and capture, never in the
     * frame.
     */
    public abstract void readPixels(
            SteinTexture texture, int x, int y, int w, int h, ByteBuffer out);

    /**
     * Reads {@code out.remaining()} bytes of {@code buffer} starting at {@code offset} to the CPU,
     * with everything already requested of the GPU before it finished (including what a compute
     * wrote): only for tests and checking, never in the frame (it waits for the GPU).
     */
    public abstract void readBuffer(SteinBuffer buffer, long offset, ByteBuffer out);
}
