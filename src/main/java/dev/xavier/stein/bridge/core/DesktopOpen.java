/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

import dev.xavier.stein.bridge.engine.GuiFixOptions;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Opening a link or a folder in the operating system, off the game thread.
 *
 * <p>LWJGL 2's Sys.openURL, which 1.8.9 used for links and the resource pack folder, tried xdg-open
 * and then a list of browsers on Linux. Stein's shim only had Java's Desktop, which on Linux needs
 * the GTK desktop integration: on KDE, on Wayland sessions and on minimal window managers it fails
 * or does nothing, so "Open resource pack folder", chat links and the Loader's buttons did nothing.
 * Here Linux runs {@code xdg-open} first (every desktop's opener, which picks the player's own
 * browser and file manager), and Desktop only when that fails; the other systems keep Desktop. It
 * all happens on a daemon thread: Desktop can take seconds to answer the first time (AWT starting
 * up), and the game window froze meanwhile. {@code -Dsteinbridge.opt.vanillaOpenFolder=true} uses
 * Desktop alone, as the shim did.
 */
public final class DesktopOpen {

    private DesktopOpen() {}

    /** Whether the game runs on Linux (or another X11/Wayland Unix, where xdg-open lives). */
    public static final boolean XDG =
            !GuiFixOptions.VANILLA_OPEN_FOLDER && isXdgSystem(System.getProperty("os.name", ""));

    /** How long xdg-open may take to hand the target to the desktop. */
    private static final long XDG_WAIT_SECONDS = 10L;

    private static boolean isXdgSystem(String os) {
        String n = os.toLowerCase(java.util.Locale.ROOT);
        return n.contains("linux") || n.contains("bsd");
    }

    /**
     * Opens a link (http, https, file:) or a folder path, on a daemon thread; true once the attempt
     * has started (as LWJGL 2's openURL, which also did not wait for the browser).
     */
    public static boolean open(String target) {
        if (target == null || target.isEmpty()) {
            return false;
        }
        Thread t =
                new Thread(
                        () -> {
                            if (!xdgOpen(target)) {
                                desktop(target);
                            }
                        },
                        "stein-open");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * Runs {@code xdg-open target} and waits for it to hand the target over; false when not on
     * Linux, when xdg-open is missing or when it reports a failure. Call off the game thread.
     */
    public static boolean xdgOpen(String target) {
        if (!XDG) {
            return false;
        }
        try {
            Process p =
                    new ProcessBuilder("xdg-open", target)
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
            if (!p.waitFor(XDG_WAIT_SECONDS, TimeUnit.SECONDS)) {
                return true; // still running: it is the opener itself staying around
            }
            return p.exitValue() == 0;
        } catch (Exception e) { // no xdg-open on this system: Desktop's turn
            Log.info("bridge", "xdg-open failed for " + target + ": " + e);
            return false;
        }
    }

    private static void desktop(String target) {
        try {
            if (!Desktop.isDesktopSupported()) {
                Log.info("bridge", "no way to open " + target + " on this system");
                return;
            }
            Desktop d = Desktop.getDesktop();
            File f = new File(target);
            if (!target.contains("://") && !target.startsWith("file:") && f.exists()) {
                d.open(f);
            } else {
                d.browse(new URI(target));
            }
        } catch (Throwable e) { // never let a failed launch reach the game
            Log.info("bridge", "could not open " + target + ": " + e);
        }
    }
}
