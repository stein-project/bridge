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

/**
 * 4x4 column-major matrix in OpenGL's convention: the field {@code mCR} is column C, row R. Ported
 * from the LWJGL 2 API (removed in LWJGL 3).
 *
 * <p>Only what Minecraft 1.8.9 calls: setIdentity, mul, rotate, transform.
 */
public class Matrix4f extends Matrix {
    public float m00, m01, m02, m03;
    public float m10, m11, m12, m13;
    public float m20, m21, m22, m23;
    public float m30, m31, m32, m33;

    public Matrix4f() {
        setIdentity();
    }

    /**
     * Covariant return: javac automatically generates the bridge method with descriptor {@code
     * ()Lorg/lwjgl/util/vector/Matrix;}, which is what Minecraft's bytecode references.
     */
    @Override
    public Matrix4f setIdentity() {
        m00 = 1;
        m01 = 0;
        m02 = 0;
        m03 = 0;
        m10 = 0;
        m11 = 1;
        m12 = 0;
        m13 = 0;
        m20 = 0;
        m21 = 0;
        m22 = 1;
        m23 = 0;
        m30 = 0;
        m31 = 0;
        m32 = 0;
        m33 = 1;
        return this;
    }

    /** dest = left * right. Safe when dest is the same object as left or right. */
    public static Matrix4f mul(Matrix4f left, Matrix4f right, Matrix4f dest) {
        if (dest == null) {
            dest = new Matrix4f();
        }
        float n00 =
                left.m00 * right.m00
                        + left.m10 * right.m01
                        + left.m20 * right.m02
                        + left.m30 * right.m03;
        float n01 =
                left.m01 * right.m00
                        + left.m11 * right.m01
                        + left.m21 * right.m02
                        + left.m31 * right.m03;
        float n02 =
                left.m02 * right.m00
                        + left.m12 * right.m01
                        + left.m22 * right.m02
                        + left.m32 * right.m03;
        float n03 =
                left.m03 * right.m00
                        + left.m13 * right.m01
                        + left.m23 * right.m02
                        + left.m33 * right.m03;
        float n10 =
                left.m00 * right.m10
                        + left.m10 * right.m11
                        + left.m20 * right.m12
                        + left.m30 * right.m13;
        float n11 =
                left.m01 * right.m10
                        + left.m11 * right.m11
                        + left.m21 * right.m12
                        + left.m31 * right.m13;
        float n12 =
                left.m02 * right.m10
                        + left.m12 * right.m11
                        + left.m22 * right.m12
                        + left.m32 * right.m13;
        float n13 =
                left.m03 * right.m10
                        + left.m13 * right.m11
                        + left.m23 * right.m12
                        + left.m33 * right.m13;
        float n20 =
                left.m00 * right.m20
                        + left.m10 * right.m21
                        + left.m20 * right.m22
                        + left.m30 * right.m23;
        float n21 =
                left.m01 * right.m20
                        + left.m11 * right.m21
                        + left.m21 * right.m22
                        + left.m31 * right.m23;
        float n22 =
                left.m02 * right.m20
                        + left.m12 * right.m21
                        + left.m22 * right.m22
                        + left.m32 * right.m23;
        float n23 =
                left.m03 * right.m20
                        + left.m13 * right.m21
                        + left.m23 * right.m22
                        + left.m33 * right.m23;
        float n30 =
                left.m00 * right.m30
                        + left.m10 * right.m31
                        + left.m20 * right.m32
                        + left.m30 * right.m33;
        float n31 =
                left.m01 * right.m30
                        + left.m11 * right.m31
                        + left.m21 * right.m32
                        + left.m31 * right.m33;
        float n32 =
                left.m02 * right.m30
                        + left.m12 * right.m31
                        + left.m22 * right.m32
                        + left.m32 * right.m33;
        float n33 =
                left.m03 * right.m30
                        + left.m13 * right.m31
                        + left.m23 * right.m32
                        + left.m33 * right.m33;

        dest.m00 = n00;
        dest.m01 = n01;
        dest.m02 = n02;
        dest.m03 = n03;
        dest.m10 = n10;
        dest.m11 = n11;
        dest.m12 = n12;
        dest.m13 = n13;
        dest.m20 = n20;
        dest.m21 = n21;
        dest.m22 = n22;
        dest.m23 = n23;
        dest.m30 = n30;
        dest.m31 = n31;
        dest.m32 = n32;
        dest.m33 = n33;
        return dest;
    }

    /** dest = src rotated by {@code angle} radians around {@code axis}. */
    public static Matrix4f rotate(float angle, Vector3f axis, Matrix4f src, Matrix4f dest) {
        if (dest == null) {
            dest = new Matrix4f();
        }
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float omc = 1.0f - c;

        float xy = axis.x * axis.y;
        float yz = axis.y * axis.z;
        float xz = axis.x * axis.z;
        float xs = axis.x * s;
        float ys = axis.y * s;
        float zs = axis.z * s;

        float f00 = axis.x * axis.x * omc + c;
        float f01 = xy * omc + zs;
        float f02 = xz * omc - ys;
        float f10 = xy * omc - zs;
        float f11 = axis.y * axis.y * omc + c;
        float f12 = yz * omc + xs;
        float f20 = xz * omc + ys;
        float f21 = yz * omc - xs;
        float f22 = axis.z * axis.z * omc + c;

        // Columns 0 and 1 go to temporaries because dest may be src itself.
        float t00 = src.m00 * f00 + src.m10 * f01 + src.m20 * f02;
        float t01 = src.m01 * f00 + src.m11 * f01 + src.m21 * f02;
        float t02 = src.m02 * f00 + src.m12 * f01 + src.m22 * f02;
        float t03 = src.m03 * f00 + src.m13 * f01 + src.m23 * f02;
        float t10 = src.m00 * f10 + src.m10 * f11 + src.m20 * f12;
        float t11 = src.m01 * f10 + src.m11 * f11 + src.m21 * f12;
        float t12 = src.m02 * f10 + src.m12 * f11 + src.m22 * f12;
        float t13 = src.m03 * f10 + src.m13 * f11 + src.m23 * f12;

        dest.m20 = src.m00 * f20 + src.m10 * f21 + src.m20 * f22;
        dest.m21 = src.m01 * f20 + src.m11 * f21 + src.m21 * f22;
        dest.m22 = src.m02 * f20 + src.m12 * f21 + src.m22 * f22;
        dest.m23 = src.m03 * f20 + src.m13 * f21 + src.m23 * f22;

        dest.m00 = t00;
        dest.m01 = t01;
        dest.m02 = t02;
        dest.m03 = t03;
        dest.m10 = t10;
        dest.m11 = t11;
        dest.m12 = t12;
        dest.m13 = t13;
        return dest;
    }

    /** dest = left * right, treating right as a column vector. */
    public static Vector4f transform(Matrix4f left, Vector4f right, Vector4f dest) {
        if (dest == null) {
            dest = new Vector4f();
        }
        float x = left.m00 * right.x + left.m10 * right.y + left.m20 * right.z + left.m30 * right.w;
        float y = left.m01 * right.x + left.m11 * right.y + left.m21 * right.z + left.m31 * right.w;
        float z = left.m02 * right.x + left.m12 * right.y + left.m22 * right.z + left.m32 * right.w;
        float w = left.m03 * right.x + left.m13 * right.y + left.m23 * right.z + left.m33 * right.w;
        dest.set(x, y, z, w);
        return dest;
    }

    @Override
    public String toString() {
        return "Matrix4f["
                + m00
                + " "
                + m10
                + " "
                + m20
                + " "
                + m30
                + "\n"
                + "          "
                + m01
                + " "
                + m11
                + " "
                + m21
                + " "
                + m31
                + "\n"
                + "          "
                + m02
                + " "
                + m12
                + " "
                + m22
                + " "
                + m32
                + "\n"
                + "          "
                + m03
                + " "
                + m13
                + " "
                + m23
                + " "
                + m33
                + "]";
    }
}
