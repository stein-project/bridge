/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

/**
 * The frames as flight recorder events ({@link FrameEvent}), apart from {@link FrameTime}: a Java
 * without the jdk.jfr module (the Stein Client's before 0.1.39) cannot load a class that names an Event,
 * there. This one is only touched when the module is present ({@link #PRESENT}).
 *
 * <p>Pipeline: both.
 */
final class FrameRecording {

    private static FrameEvent event = new FrameEvent();

    private FrameRecording() {}

    /** At the end of each frame: closes this frame's event (if a recording asks for it). */
    static void frame(boolean ticked) {
        if (event.isEnabled()) {
            event.tick = ticked;
            event.commit();
            event = new FrameEvent();
            event.begin();
        }
    }
}
