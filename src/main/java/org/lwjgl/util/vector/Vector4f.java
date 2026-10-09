/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package org.lwjgl.util.vector;

public class Vector4f extends Vector {
    public float x, y, z, w;

    public Vector4f() {}

    public Vector4f(float x, float y, float z, float w) {
        set(x, y, z, w);
    }

    public void set(float x, float y, float z, float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    @Override
    public float lengthSquared() {
        return x * x + y * y + z * z + w * w;
    }

    @Override
    public Vector scale(float s) {
        x *= s;
        y *= s;
        z *= s;
        w *= s;
        return this;
    }

    @Override
    public String toString() {
        return "Vector4f[" + x + ", " + y + ", " + z + ", " + w + "]";
    }
}
