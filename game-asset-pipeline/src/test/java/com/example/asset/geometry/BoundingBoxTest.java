package com.example.asset.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoundingBoxTest {

    private static final float EPSILON = 1e-5f;

    @Test
    void testBoundingBoxProperties() {
        BoundingBox box = new BoundingBox(new Vector3f(-10f, -5f, -2f), new Vector3f(10f, 15f, 8f));

        assertEquals(0f, box.center().x(), EPSILON);
        assertEquals(5f, box.center().y(), EPSILON);
        assertEquals(3f, box.center().z(), EPSILON);

        assertEquals(20f, box.extents().x(), EPSILON);
        assertEquals(20f, box.extents().y(), EPSILON);
        assertEquals(10f, box.extents().z(), EPSILON);

        assertTrue(box.contains(new Vector3f(0f, 0f, 0f)));
        assertTrue(box.contains(new Vector3f(-10f, -5f, -2f)));
        assertFalse(box.contains(new Vector3f(11f, 0f, 0f)));
    }

    @Test
    void testIntersects() {
        BoundingBox a = new BoundingBox(new Vector3f(0f, 0f, 0f), new Vector3f(10f, 10f, 10f));
        BoundingBox b = new BoundingBox(new Vector3f(5f, 5f, 5f), new Vector3f(15f, 15f, 15f));
        BoundingBox c = new BoundingBox(new Vector3f(20f, 20f, 20f), new Vector3f(30f, 30f, 30f));

        assertTrue(a.intersects(b));
        assertTrue(b.intersects(a));
        assertFalse(a.intersects(c));
    }

    @Test
    void testBuilder() {
        BoundingBox box = BoundingBox.builder()
                .include(new Vector3f(1f, 5f, -3f))
                .include(new Vector3f(-2f, 10f, 7f))
                .include(new Vector3f(4f, -1f, 2f))
                .build();

        assertEquals(-2f, box.min().x(), EPSILON);
        assertEquals(-1f, box.min().y(), EPSILON);
        assertEquals(-3f, box.min().z(), EPSILON);

        assertEquals(4f, box.max().x(), EPSILON);
        assertEquals(10f, box.max().y(), EPSILON);
        assertEquals(7f, box.max().z(), EPSILON);
    }
}
