/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */
package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.SteinBridge;
import dev.xavier.stein.bridge.showcase.VideoSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * The last topic of the video settings: who makes Stein, under which licence, what it is built
 * with, and that it is not a Mojang product. Shown only; the texts are in the {@code .lang} files,
 * the same facts as the Bridge's NOTICE.
 */
final class VideoCredits {

    private VideoCredits() {}

    /** The topics, with the credits at the end. */
    static List<VideoSettings.Section> append(List<VideoSettings.Section> in) {
        List<VideoSettings.Section> out = new ArrayList<>(in);
        List<VideoSettings.Entry> e = new ArrayList<>();
        e.add(
                VideoSettings.Entry.info(
                        () -> VideoScreen.tr("steinui.credits.version"),
                        SteinBridge::version,
                        () -> VideoScreen.tr("steinui.credits.version.hint")));
        e.add(line("author", "X4vieer"));
        e.add(line("license", "MPL 2.0"));
        e.add(line("graphics", "LWJGL 3"));
        e.add(line("fonts", "OFL 1.1"));
        e.add(line("minecraft", "Mojang"));
        out.add(
                new VideoSettings.Section(
                        "credits", () -> VideoScreen.tr("steinui.credits.title"), e));
        return out;
    }

    /** A credit: its name and hint from the {@code .lang} files, its value as given. */
    private static VideoSettings.Entry line(String key, String value) {
        return VideoSettings.Entry.info(
                () -> VideoScreen.tr("steinui.credits." + key),
                () -> value,
                () -> VideoScreen.tr("steinui.credits." + key + ".hint"));
    }
}
