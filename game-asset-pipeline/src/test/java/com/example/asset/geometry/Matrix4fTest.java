package com.example.asset.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Matrix4fTest {

    private static final float EPSILON = 1e-5f;

    @Test
    void testIdentityTransform() {
        Matrix4f mat = new Matrix4f();
        Vector3f p = new Vector3f(10.0f, 20.0f, 30.0f);
        Vector3f result = mat.transform(p);
        assertEquals(p, result);
    }

    @Test
    void testTranslation() {
        Matrix4f mat = Matrix4f.translation(5.0f, -2.0f, 3.0f);
        Vector3f p = new Vector3f(1.0f, 2.0f, 3.0f);
        Vector3f result = mat.transform(p);

        assertEquals(6.0f, result.x(), EPSILON);
        assertEquals(0.0f, result.y(), EPSILON);
        assertEquals(6.0f, result.z(), EPSILON);

        // Direction vector should not be translated
        Vector3f dir = mat.transformDirection(p);
        assertEquals(p, dir);
    }

    @Test
    void testTruncateTo3x3AndTranspose() {
        Matrix4f mat = new Matrix4f();
        mat.set(0, 1.0f); mat.set(4, 2.0f); mat.set(8, 3.0f);
        mat.set(1, 4.0f); mat.set(5, 5.0f); mat.set(9, 6.0f);
        mat.set(2, 7.0f); mat.set(6, 8.0f); mat.set(10, 9.0f);

        Matrix3f mat3 = mat.truncateTo3x3();
        assertEquals(2.0f, mat3.get(3)); // column 1, row 0
        assertEquals(4.0f, mat3.get(1)); // column 0, row 1

        Matrix3f transposed = mat3.transpose();
        assertEquals(4.0f, transposed.get(3));
        assertEquals(2.0f, transposed.get(1));
    }

    @Test
    void testInvertTranslationAndMultiplication() {
        Matrix4f t = Matrix4f.translation(3.0f, -7.0f, 12.0f);
        Matrix4f invT = t.invert();

        Matrix4f product = t.multiply(invT);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                float expected = (r == c) ? 1.0f : 0.0f;
                assertEquals(expected, product.get(c * 4 + r), EPSILON);
            }
        }

        Vector3f p = new Vector3f(1.0f, 2.0f, 3.0f);
        Vector3f moved = t.transform(p);
        Vector3f restored = invT.transform(moved);
        assertEquals(p.x(), restored.x(), EPSILON);
        assertEquals(p.y(), restored.y(), EPSILON);
        assertEquals(p.z(), restored.z(), EPSILON);
    }
}
