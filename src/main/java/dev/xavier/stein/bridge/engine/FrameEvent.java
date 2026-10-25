/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * One frame of the game in a Java Flight Recorder recording (diagnostic): its start, its end and
 * whether it ran a game tick, so the CPU samples of slow frames can be told apart from the others.
 * Costs nothing unless a recording enables {@code dev.xavier.stein.Frame}.
 *
 * <p>Pipeline: both.
 */
@Name("dev.xavier.stein.Frame")
@Label("Stein frame")
@Category("Stein")
@StackTrace(false)
final class FrameEvent extends Event {

    @Label("Game tick in the frame")
    boolean tick;
}
