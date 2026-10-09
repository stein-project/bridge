/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package org.lwjgl.util.vector;

import java.io.Serializable;

/** Base of the LWJGL 2 vector hierarchy. Removed in LWJGL 3. */
public abstract class Vector implements Serializable {
    public final float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    public abstract float lengthSquared();

    public abstract Vector scale(float scale);

    public final Vector normalise() {
        float len = length();
        if (len != 0.0f) {
            return scale(1.0f / len);
        }
        throw new IllegalStateException("Zero length vector");
    }
}
