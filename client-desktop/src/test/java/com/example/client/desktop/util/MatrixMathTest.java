package com.example.client.desktop.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatrixMathTest {

    private static final float EPSILON = 1e-4f;

    @Test
    void testPerspectiveMatrix() {
        float[] p = MatrixMath.perspective(60.0f, 16.0f / 9.0f, 0.1f, 1000.0f);
        assertEquals(16, p.length);

        // Aspect and fov should produce positive scaling on X and Y
        assertTrue(p[0] > 0.0f);
        assertTrue(p[5] > 0.0f);
        assertEquals(-1.0f, p[11], EPSILON); // Standard OpenGL perspective term
        assertEquals(0.0f, p[15], EPSILON);
    }

    @Test
    void testLookAtMatrixLookingAlongNegativeZ() {
        // Eye at (0, 0, 10), looking at (0, 0, 0), up is (0, 1, 0)
        float[] v = MatrixMath.lookAt(0f, 0f, 10f, 0f, 0f, 0f, 0f, 1f, 0f);
        assertEquals(16, v.length);

        // Right vector should be along +X
        assertEquals(1.0f, v[0], EPSILON);
        // Up vector should be along +Y
        assertEquals(1.0f, v[5], EPSILON);
        // Forward (look direction) is -Z, so -forward is +Z
        assertEquals(1.0f, v[10], EPSILON);
        // Translation z offset
        assertEquals(-10.0f, v[14], EPSILON);
    }
}
