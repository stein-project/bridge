/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Initializes each optimization/diagnostic class of SteinBridge against the patched jar, without
 * opening the game.
 *
 * <p>The static initializers of these classes look up methods of the obfuscated classes through
 * MethodHandle (name, descriptor, visibility). An error there — a private method looked up with a
 * public lookup, a wrong descriptor — only showed up inside the game, as
 * ExceptionInInitializerError on first use. Here it shows up in seconds.
 *
 * <p>Loading game classes can call OpenGL (TextureUtil creates the "missing" texture in its
 * initializer); without a context, LWJGL aborts the JVM. So a context is created beforehand, in a
 * hidden window.
 *
 * <p><b>Machine without OpenGL</b> (the CI runner has no GPU or driver): the window is not created,
 * and then the check is <b>skipped</b> instead of failing. It needs a machine with a display — and
 * that is where it counts, before sending a build to someone. In CI, the same class of error is
 * covered by the patcher's coverage check, which verifies each diversion against the jar without
 * needing any context.
 *
 * <p>Usage: java -cp "&lt;the game classpath&gt;" test/Preflight.java build/classes
 */
public final class Preflight {

    public static void main(String[] a) throws Exception {
        if (!GLFW.glfwInit()) {
            System.out.println("PREFLIGHT SKIPPED — GLFW did not initialize (no display)");
            return;
        }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long win = GLFW.glfwCreateWindow(64, 64, "preflight", 0L, 0L);
        if (win == 0L) {
            System.out.println("PREFLIGHT SKIPPED — no OpenGL context on this machine");
            return;
        }
        GLFW.glfwMakeContextCurrent(win);
        try {
            GL.createCapabilities();
        } catch (Throwable t) {
            System.out.println("PREFLIGHT SKIPPED — no OpenGL context on this machine (" + t + ")");
            return;
        }

        // One or more class directories: the bridge (build/classes) and the Loader
        // (build/loader-classes).
        List<String> classes = new java.util.ArrayList<>();
        for (String dir : a.length > 0 ? a : new String[] {"build/classes"}) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(root)) {
                s.map(p -> root.relativize(p).toString().replace('\\', '/'))
                        // Named nested classes are included (the MethodHandle "holders" of the
                        // options resolve on first use, inside the game); anonymous ones ($1,
                        // $2...) are not.
                        .filter(
                                n ->
                                        n.endsWith(".class")
                                                && !n.matches(".*\\$\\d+.*")
                                                && (n.startsWith("dev/xavier/stein/bridge/engine/")
                                                        || n.startsWith(
                                                                "dev/xavier/stein/bridge/diag/")
                                                        || n.startsWith(
                                                                "dev/xavier/stein/bridge/hooks/")
                                                        || n.startsWith(
                                                                "dev/xavier/stein/loader/")))
                        .map(n -> n.substring(0, n.length() - 6).replace('/', '.'))
                        .forEach(classes::add);
            }
        }
        java.util.Collections.sort(classes);
        int bad = 0;
        for (String c : classes) {
            try {
                Class.forName(c, true, Preflight.class.getClassLoader());
                System.out.println("  ok    " + c);
            } catch (Throwable t) {
                bad++;
                Throwable root0 = t;
                while (root0.getCause() != null) {
                    root0 = root0.getCause();
                }
                System.out.println("  FAIL  " + c + ": " + root0);
            }
        }
        System.out.println(
                bad == 0
                        ? "PREFLIGHT OK — " + classes.size() + " classes initialize"
                        : bad + " class(es) failed to initialize");
        // Return the context and the window before exiting: the check runs on every build, and
        // leaving an OpenGL context hanging on each run is asking the driver for trouble.
        GLFW.glfwMakeContextCurrent(0L);
        GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();
        System.exit(bad == 0 ? 0 : 1);
    }
}
