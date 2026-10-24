/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkGetPipelineCacheData;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The Vulkan pipeline cache on disk: the compiled pipelines of the last session, read when the
 * device starts and written when the game closes, so a new session does not compile them again (the
 * stutter of the first frames in a world). The driver checks the data's header (vendor, device,
 * driver version) itself and starts empty when it is not its own.
 *
 * <p>Pipeline: Stein GFX only (the Vulkan backend).
 */
final class SteinVkPipelineCacheFile {

    private SteinVkPipelineCacheFile() {}

    /** The saved data (to free with MemoryUtil.memFree), or null. */
    static ByteBuffer read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(file);
            ByteBuffer b = MemoryUtil.memAlloc(data.length);
            b.put(data).flip();
            return b;
        } catch (IOException e) {
            Log.warn("gfx", "vulkan: could not read the pipeline cache: " + e);
            return null;
        }
    }

    /** Writes the cache's data to {@code file} (through a temporary file, then a move). */
    static void save(VkDevice device, long cache, Path file) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(device, cache, size, null) != VK_SUCCESS
                    || size.get(0) == 0L) {
                return;
            }
            ByteBuffer data = MemoryUtil.memAlloc((int) size.get(0));
            try {
                if (vkGetPipelineCacheData(device, cache, size, data) != VK_SUCCESS) {
                    return;
                }
                byte[] bytes = new byte[(int) size.get(0)];
                data.get(0, bytes);
                Path dir = file.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, bytes);
                Files.move(
                        tmp,
                        file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Log.warn("gfx", "vulkan: could not save the pipeline cache: " + e);
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }
}
