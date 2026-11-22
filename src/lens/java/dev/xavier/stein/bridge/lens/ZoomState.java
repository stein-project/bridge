/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

/**
 * How far in the zoom is at each moment: the amount (0 = no zoom, 1 = all the way to the level)
 * follows the key through the transition curve, and the level follows the wheel. The field of view
 * is divided by {@code level ^ amount}, so each step of the transition looks the same size whether
 * the level is 2x or 30x.
 *
 * <p>Every change of direction starts a new stretch from where the zoom is, so letting go halfway
 * through goes back from there without a jump. Asked several times per frame, it gives the same
 * answer (time only moves forward); no allocation.
 */
final class ZoomState {

    /** Constant speed. */
    static final int LINEAR = 0;

    /** Starts and ends slowly. */
    static final int SMOOTH = 1;

    /** Starts fast and slows down near the end. */
    static final int EASE_OUT = 2;

    /** Starts very fast: most of the way in the first fifth of the time. */
    static final int EASE_OUT_STRONG = 3;

    static final int CURVES = 4;

    /** How fast the level follows the wheel: about 63% of the way in this many seconds. */
    private static final double LEVEL_TIME_CONSTANT_S = 0.08;

    private static final float STRONG_END = 1.0F - (float) Math.exp(-8.0);

    private float amount;
    private float from;
    private float target;
    private long stretchStart;
    private long stretchNanos;

    private float level = 1.0F;
    private float levelTarget = 1.0F;
    private long lastNanos;

    /** Zooming in (or staying zoomed) right now. */
    boolean zoomingIn() {
        return target > 0.0F;
    }

    /** No zoom at all on screen. */
    boolean idle() {
        return target == 0.0F && amount == 0.0F;
    }

    /** The level the zoom is heading to. */
    float levelTarget() {
        return levelTarget;
    }

    /**
     * Starts zooming in (to {@code toLevel}) or out.
     *
     * @param fullMs time for the whole way (a half way takes half of it); 0 = at once
     */
    void aim(boolean in, float toLevel, int fullMs, long now) {
        float t = in ? 1.0F : 0.0F;
        if (in) {
            if (amount == 0.0F) {
                level = toLevel; // from no zoom, the level does not ease: the transition does
            }
            levelTarget = toLevel;
        }
        if (t == target) {
            return;
        }
        from = amount;
        target = t;
        stretchStart = now;
        stretchNanos = (long) (Math.abs(t - from) * fullMs * 1_000_000L);
    }

    /** A new level while zoomed (the wheel, or the other key). */
    void setLevel(float toLevel, boolean smooth) {
        levelTarget = toLevel;
        if (!smooth) {
            level = toLevel;
        }
    }

    /** Goes back to no zoom at once. */
    void reset() {
        amount = from = target = 0.0F;
        level = levelTarget = 1.0F;
    }

    /** Moves time to {@code now}. */
    void update(int curve, long now) {
        if (amount != target) {
            long dt = now - stretchStart;
            float p = stretchNanos <= 0 ? 1.0F : (float) dt / stretchNanos;
            amount = p >= 1.0F ? target : from + (target - from) * ease(curve, p);
        }
        long step = now - lastNanos;
        lastNanos = now;
        if (level != levelTarget) {
            if (step <= 0 || step > 1_000_000_000L) {
                return;
            }
            double keep = Math.exp(-step / (LEVEL_TIME_CONSTANT_S * 1e9));
            level = (float) (levelTarget * Math.pow(level / levelTarget, keep));
            if (Math.abs(level / levelTarget - 1.0F) < 1.0e-3F) {
                level = levelTarget;
            }
        }
    }

    /** What the field of view is divided by now (1 = no zoom). */
    float divisor() {
        if (amount <= 0.0F) {
            return 1.0F;
        }
        return amount >= 1.0F ? level : (float) Math.pow(level, amount);
    }

    /** Progress in time (0 to 1) to progress of the zoom (0 to 1), along one of the curves. */
    static float ease(int curve, float t) {
        if (t <= 0.0F) {
            return 0.0F;
        }
        if (t >= 1.0F) {
            return 1.0F;
        }
        switch (curve) {
            case LINEAR:
                return t;
            case SMOOTH:
                return t * t * (3.0F - 2.0F * t);
            case EASE_OUT_STRONG:
                // exponential, rescaled so that it ends exactly at 1
                return (1.0F - (float) Math.exp(-8.0 * t)) / STRONG_END;
            default:
                float u = 1.0F - t;
                return 1.0F - u * u * u;
        }
    }
}
