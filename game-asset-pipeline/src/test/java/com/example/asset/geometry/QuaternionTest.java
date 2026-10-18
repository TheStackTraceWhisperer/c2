package com.example.asset.geometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuaternionTest {

    @Test
    @DisplayName("Quaternion identity and normalization")
    void testIdentityAndNormalize() {
        Quaternion id = Quaternion.IDENTITY;
        assertEquals(1.0f, id.length(), 1e-5f);

        Quaternion q = new Quaternion(1.0f, 2.0f, 3.0f, 4.0f);
        Quaternion norm = q.normalize();
        assertEquals(1.0f, norm.length(), 1e-5f);
    }

    @Test
    @DisplayName("SLERP interpolation between orthogonal rotations")
    void testSlerpInterpolation() {
        // Rotation around Y by 0 degrees
        Quaternion q0 = Quaternion.IDENTITY;
        // Rotation around Y by 90 degrees: angle/2 = 45 deg, sin(45) = 0.7071, cos(45) = 0.7071
        float halfSin = (float) Math.sin(Math.PI / 4.0);
        float halfCos = (float) Math.cos(Math.PI / 4.0);
        Quaternion q1 = new Quaternion(0.0f, halfSin, 0.0f, halfCos).normalize();

        // At t = 0.0 -> q0
        Quaternion s0 = Quaternion.slerp(q0, q1, 0.0f);
        assertEquals(q0.w(), s0.w(), 1e-4f);

        // At t = 1.0 -> q1
        Quaternion s1 = Quaternion.slerp(q0, q1, 1.0f);
        assertEquals(q1.w(), s1.w(), 1e-4f);

        // At t = 0.5 -> 45 degrees rotation (halfSin = sin(22.5 deg))
        Quaternion sMid = Quaternion.slerp(q0, q1, 0.5f);
        assertTrue(sMid.y() > 0.0f);
        assertEquals(1.0f, sMid.length(), 1e-4f);
    }

    @Test
    @DisplayName("Convert Quaternion to Matrix4f rotation")
    void testToMatrix4f() {
        Quaternion id = Quaternion.IDENTITY;
        Matrix4f mat = id.toMatrix4f();
        assertNotNull(mat);

        // Identity rotation transforms (1, 2, 3) to (1, 2, 3)
        Vector3f v = new Vector3f(1.0f, 2.0f, 3.0f);
        Vector3f transformed = mat.transform(v);
        assertEquals(1.0f, transformed.x(), 1e-5f);
        assertEquals(2.0f, transformed.y(), 1e-5f);
        assertEquals(3.0f, transformed.z(), 1e-5f);
    }
}
