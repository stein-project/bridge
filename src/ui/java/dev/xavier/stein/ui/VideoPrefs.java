/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */
package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * What the video settings remember between sessions, in {@code config/stein-video.properties}: for
 * now, whether the player's own view stays behind the menu instead of the landscape. Read once, on
 * the first opening (the game folder is known only once the game runs).
 */
final class VideoPrefs {

    private static final String FILE = "config/stein-video.properties";
    private static final String OWN_VIEW = "ownView";

    private static Properties props;

    private VideoPrefs() {}

    /** The player's own view behind the menu instead of the landscape. */
    static boolean ownView() {
        return Boolean.parseBoolean(props().getProperty(OWN_VIEW, "false"));
    }

    static void setOwnView(boolean on) {
        props().setProperty(OWN_VIEW, Boolean.toString(on));
        store();
    }

    private static Properties props() {
        if (props == null) {
            props = new Properties();
            File f = file();
            if (f.isFile()) {
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    props.load(in);
                } catch (IOException e) {
                    Log.warn("ui", "could not read " + f + ": " + e);
                }
            }
        }
        return props;
    }

    private static void store() {
        File f = file();
        try {
            Files.createDirectories(f.getParentFile().toPath());
            try (OutputStream out = Files.newOutputStream(f.toPath())) {
                props.store(out, "Stein video settings screen");
            }
        } catch (IOException e) {
            Log.warn("ui", "could not save " + f + ": " + e);
        }
    }

    private static File file() {
        return new File(Minecraft.getMinecraft().mcDataDir, FILE);
    }
}
