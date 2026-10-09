/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

import org.lwjgl.util.glu.GLU;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;
import org.lwjgl.util.vector.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Numerical validation of the math ported from LWJGL 2. */
public class SanityTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testRotate90AroundZ();
        testMulIdentity();
        testUnProjectRoundTrip();
        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** A 90-degree rotation around Z must map (1,0,0) -> (0,1,0). */
    private static void testRotate90AroundZ() {
        Matrix4f r =
                Matrix4f.rotate((float) Math.PI / 2f, new Vector3f(0, 0, 1), new Matrix4f(), null);
        Vector4f out = Matrix4f.transform(r, new Vector4f(1, 0, 0, 1), null);
        check("rotate90Z.x", 0f, out.x);
        check("rotate90Z.y", 1f, out.y);
        check("rotate90Z.z", 0f, out.z);
        check("rotate90Z.w", 1f, out.w);
    }

    /** M * I must return M unchanged. */
    private static void testMulIdentity() {
        Matrix4f m = Matrix4f.rotate(0.7f, new Vector3f(0.3f, 0.5f, 0.81f), new Matrix4f(), null);
        Matrix4f res = Matrix4f.mul(m, new Matrix4f(), null);
        check("mulI.m00", m.m00, res.m00);
        check("mulI.m12", m.m12, res.m12);
        check("mulI.m21", m.m21, res.m21);
        check("mulI.m33", m.m33, res.m33);
    }

    /**
     * Manually projects a point to window coordinates and checks that gluUnProject recovers the
     * original point. Exercises multiply + invert.
     */
    private static void testUnProjectRoundTrip() {
        float[] proj = perspective(70f, 16f / 9f, 0.05f, 1000f);
        float[] model = new float[16];
        Matrix4f mv = Matrix4f.rotate(0.4f, new Vector3f(0, 1, 0), new Matrix4f(), null);
        mv.m30 = 2f;
        mv.m31 = -1f;
        mv.m32 = -12f; // translation
        store(mv, model);

        int[] vp = {0, 0, 1920, 1080};
        float ox = 1.5f, oy = 0.75f, oz = -3.25f;

        // clip = proj * model * obj
        float[] pm = mulArr(proj, model);
        float[] clip = new float[4];
        for (int row = 0; row < 4; row++) {
            clip[row] = pm[row] * ox + pm[4 + row] * oy + pm[8 + row] * oz + pm[12 + row];
        }
        float winX = vp[0] + vp[2] * (clip[0] / clip[3] + 1f) / 2f;
        float winY = vp[1] + vp[3] * (clip[1] / clip[3] + 1f) / 2f;
        float winZ = (clip[2] / clip[3] + 1f) / 2f;

        FloatBuffer fbModel = buf(model);
        FloatBuffer fbProj = buf(proj);
        IntBuffer ibVp = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asIntBuffer();
        ibVp.put(vp).flip();
        FloatBuffer objPos =
                ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).asFloatBuffer();

        boolean ok = GLU.gluUnProject(winX, winY, winZ, fbModel, fbProj, ibVp, objPos);
        if (!ok) {
            System.out.println("FAIL   gluUnProject returned false");
            failures++;
            return;
        }
        check("unProject.x", ox, objPos.get(0));
        check("unProject.y", oy, objPos.get(1));
        check("unProject.z", oz, objPos.get(2));

        // The input buffers must not have been consumed.
        check("model.position unchanged", 0f, fbModel.position());
        check("viewport.position unchanged", 0f, ibVp.position());
    }

    private static float[] perspective(float fovyDeg, float aspect, float zNear, float zFar) {
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovyDeg) / 2.0));
        float[] m = new float[16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (zFar + zNear) / (zNear - zFar);
        m[11] = -1f;
        m[14] = 2f * zFar * zNear / (zNear - zFar);
        return m;
    }

    private static float[] mulArr(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                r[c * 4 + row] =
                        a[row] * b[c * 4]
                                + a[4 + row] * b[c * 4 + 1]
                                + a[8 + row] * b[c * 4 + 2]
                                + a[12 + row] * b[c * 4 + 3];
            }
        }
        return r;
    }

    private static void store(Matrix4f m, float[] a) {
        a[0] = m.m00;
        a[1] = m.m01;
        a[2] = m.m02;
        a[3] = m.m03;
        a[4] = m.m10;
        a[5] = m.m11;
        a[6] = m.m12;
        a[7] = m.m13;
        a[8] = m.m20;
        a[9] = m.m21;
        a[10] = m.m22;
        a[11] = m.m23;
        a[12] = m.m30;
        a[13] = m.m31;
        a[14] = m.m32;
        a[15] = m.m33;
    }

    private static FloatBuffer buf(float[] a) {
        FloatBuffer b =
                ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a).flip();
        return b;
    }

    private static void check(String label, float expected, float actual) {
        if (Math.abs(expected - actual) > 1e-3f) {
            System.out.printf("FAIL   %-26s expected %.5f  got %.5f%n", label, expected, actual);
            failures++;
        } else {
            System.out.printf("ok     %-26s %.5f%n", label, actual);
        }
    }
}
