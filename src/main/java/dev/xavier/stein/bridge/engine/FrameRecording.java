/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

/**
 * The frames as flight recorder events ({@link FrameEvent}), apart from {@link FrameTime}: the Java
 * the Stein Client ships has no jdk.jfr module, and a class that only names an Event fails to load
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
