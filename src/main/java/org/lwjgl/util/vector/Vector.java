/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
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
