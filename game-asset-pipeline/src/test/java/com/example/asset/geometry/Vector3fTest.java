package com.example.asset.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Vector3fTest {

    private static final float EPSILON = 1e-5f;

    @Test
    void testBasicArithmetic() {
        Vector3f a = new Vector3f(1.0f, 2.0f, 3.0f);
        Vector3f b = new Vector3f(4.0f, 5.0f, 6.0f);

        Vector3f sum = a.add(b);
        assertEquals(5.0f, sum.x(), EPSILON);
        assertEquals(7.0f, sum.y(), EPSILON);
        assertEquals(9.0f, sum.z(), EPSILON);

        Vector3f diff = a.sub(b);
        assertEquals(-3.0f, diff.x(), EPSILON);
        assertEquals(-3.0f, diff.y(), EPSILON);
        assertEquals(-3.0f, diff.z(), EPSILON);

        Vector3f scaled = a.mul(2.5f);
        assertEquals(2.5f, scaled.x(), EPSILON);
        assertEquals(5.0f, scaled.y(), EPSILON);
        assertEquals(7.5f, scaled.z(), EPSILON);
    }

    @Test
    void testDotAndCrossProduct() {
        Vector3f x = Vector3f.X;
        Vector3f y = Vector3f.Y;

        assertEquals(0.0f, x.dot(y), EPSILON);
        assertEquals(1.0f, x.dot(x), EPSILON);

        Vector3f z = x.cross(y);
        assertEquals(0.0f, z.x(), EPSILON);
        assertEquals(0.0f, z.y(), EPSILON);
        assertEquals(1.0f, z.z(), EPSILON);
    }

    @Test
    void testLengthAndNormalize() {
        Vector3f v = new Vector3f(0.0f, 3.0f, 4.0f);
        assertEquals(25.0f, v.lengthSquared(), EPSILON);
        assertEquals(5.0f, v.length(), EPSILON);

        Vector3f norm = v.normalize();
        assertEquals(0.0f, norm.x(), EPSILON);
        assertEquals(0.6f, norm.y(), EPSILON);
        assertEquals(0.8f, norm.z(), EPSILON);
        assertEquals(1.0f, norm.length(), EPSILON);

        Vector3f zeroNorm = Vector3f.ZERO.normalize();
        assertEquals(Vector3f.ZERO, zeroNorm);
    }

    @Test
    void testDistance() {
        Vector3f p1 = new Vector3f(1.0f, 0.0f, 0.0f);
        Vector3f p2 = new Vector3f(4.0f, 4.0f, 0.0f);
        assertEquals(25.0f, p1.distanceSquared(p2), EPSILON);
        assertEquals(5.0f, p1.distance(p2), EPSILON);
    }
}
