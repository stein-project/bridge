/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 *
 * Parts of this file (the matrix and vector arithmetic of the LWJGL 2 API) are taken from LWJGL 2:
 * Copyright (c) 2002-2008 LWJGL Project. All rights reserved. Used under the BSD 3-clause licence;
 * the full text, conditions and disclaimer are in licenses/LWJGL2-LICENSE.txt.
 */

package org.lwjgl.util.vector;

public class Vector3f extends Vector implements ReadableVector3f {
    public float x, y, z;

    public Vector3f() {}

    public Vector3f(float x, float y, float z) {
        set(x, y, z);
    }

    public Vector3f(ReadableVector3f src) {
        set(src.getX(), src.getY(), src.getZ());
    }

    public void set(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public float getX() {
        return x;
    }

    @Override
    public float getY() {
        return y;
    }

    @Override
    public float getZ() {
        return z;
    }

    @Override
    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    @Override
    public Vector scale(float scale) {
        x *= scale;
        y *= scale;
        z *= scale;
        return this;
    }

    public static Vector3f add(Vector3f left, Vector3f right, Vector3f dest) {
        if (dest == null) {
            return new Vector3f(left.x + right.x, left.y + right.y, left.z + right.z);
        }
        dest.set(left.x + right.x, left.y + right.y, left.z + right.z);
        return dest;
    }

    public static Vector3f sub(Vector3f left, Vector3f right, Vector3f dest) {
        if (dest == null) {
            return new Vector3f(left.x - right.x, left.y - right.y, left.z - right.z);
        }
        dest.set(left.x - right.x, left.y - right.y, left.z - right.z);
        return dest;
    }

    public static Vector3f cross(Vector3f left, Vector3f right, Vector3f dest) {
        if (dest == null) {
            dest = new Vector3f();
        }
        dest.set(
                left.y * right.z - left.z * right.y,
                right.x * left.z - right.z * left.x,
                left.x * right.y - left.y * right.x);
        return dest;
    }

    public static float dot(Vector3f left, Vector3f right) {
        return left.x * right.x + left.y * right.y + left.z * right.z;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof Vector3f o && x == o.x && y == o.y && z == o.z;
    }

    @Override
    public int hashCode() {
        return Float.floatToIntBits(x) * 31 * 31
                + Float.floatToIntBits(y) * 31
                + Float.floatToIntBits(z);
    }

    @Override
    public String toString() {
        return "Vector3f[" + x + ", " + y + ", " + z + "]";
    }
}
