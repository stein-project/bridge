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
}
