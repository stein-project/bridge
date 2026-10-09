/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.vk;

import dev.xavier.stein.gfx.SteinFence;

/**
 * Vulkan fence: a value of the device's timeline semaphore. The value is the one of the submission
 * still being recorded (everything requested so far goes in it or an earlier one); the GPU has
 * passed this point when the counter reaches it.
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkFence extends SteinFence {

    private final SteinVkDevice device;
    private final long value;
    private boolean passed;

    SteinVkFence(SteinVkDevice device, long value) {
        this.device = device;
        this.value = value;
    }

    @Override
    public boolean done() {
        if (!passed) {
            // query only (without submitting): like OpenGL's glGetSynci, whoever submits is the
            // frame handoff or waitFor
            passed = device.completed() >= value;
        }
        return passed;
    }

    @Override
    public boolean waitFor(long nanos) {
        if (!passed) {
            passed = device.waitValue(value, nanos);
        }
        return passed;
    }

    @Override
    public void destroy() {
        // nothing to release: the semaphore belongs to the device
    }
}
