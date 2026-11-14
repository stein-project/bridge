/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

/**
 * A hover animation: a value that rises towards 1 while the mouse is over the control and falls
 * back to 0 when it leaves (in 140 ms), to be passed through {@link Ui#ease}.
 */
public final class HoverFade {

    private float hover;
    private long last;

    /** Advances the animation to now; {@code over} = the mouse is over the control. */
    public float update(boolean over) {
        long t = Ui.now();
        float step = last == 0L ? 0.0F : Math.min(100L, t - last) / 140.0F;
        last = t;
        hover = over ? Math.min(1.0F, hover + step) : Math.max(0.0F, hover - step);
        return hover;
    }

    /** The current value (0 to 1), not eased. */
    public float value() {
        return hover;
    }
}
