/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

// How to run (standalone, outside the game; not part of the Bridge jar). After tools/build.sh:
//   java -cp "build/classes;build/test;libs/*" dev.xavier.stein.gfx.vk.SteinVkSelfTest

package dev.xavier.stein.gfx.vk;

import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinGfxApiTest;
import dev.xavier.stein.gfx.SteinGfxSelfTest;

import java.util.ArrayList;
import java.util.List;

/**
 * The graphics API tests ({@link SteinGfxSelfTest} and {@link SteinGfxApiTest}, the same as for
 * OpenGL) on the Vulkan backend, outside the game: no window, it draws offscreen and reads back the
 * pixels. Exits with 0 if it passed; {@code -Dsteinbridge.gfx.vkNoPush=true} tests the path without
 * push descriptors.
 *
 * <pre>
 * java -cp build/classes;libs/* -Dsteinbridge.gfx.vkValidation=true
 * dev.xavier.stein.gfx.vk.SteinVkSelfTest
 * </pre>
 *
 * (validation needs the Vulkan SDK; without it the test runs without validation.)
 */
public final class SteinVkSelfTest {

    private SteinVkSelfTest() {}

    public static void main(String[] args) {
        SteinVkDevice dev = new SteinVkDevice();
        int code;
        try {
            System.out.println("[gfx] " + dev.caps());
            SteinDevice.use(dev);
            List<String> fails = new ArrayList<>(SteinGfxSelfTest.run());
            fails.addAll(SteinGfxApiTest.run());
            if (fails.isEmpty()) {
                System.out.println("[gfx] vulkan self-test: OK");
                code = 0;
            } else {
                System.out.println(
                        "[gfx] vulkan self-test: FAILED at " + fails.size() + ": " + fails);
                code = 1;
            }
        } catch (Throwable e) {
            System.out.println("[gfx] vulkan self-test: error " + e);
            e.printStackTrace(System.out);
            code = 2;
        } finally {
            SteinDevice.use(null);
            dev.destroy();
        }
        System.exit(code);
    }
}
