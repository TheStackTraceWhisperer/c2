package com.example.client.desktop.camera;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolarCameraTest {

    @Test
    @DisplayName("Default camera initializes to North (0 deg yaw), 25 deg pitch, 8m distance")
    void testDefaultInitialization() {
        PolarCamera camera = new PolarCamera();

        assertEquals(0.0f, camera.getYaw(), 0.001f);
        assertEquals(25.0f, camera.getPitch(), 0.001f);
        assertEquals(8.0f, camera.getDistance(), 0.001f);

        // Forward is North (+Z), Right is East (+X)
        assertEquals(0.0f, camera.getForwardX(), 0.001f);
        assertEquals(1.0f, camera.getForwardZ(), 0.001f);
        assertEquals(1.0f, camera.getRightX(), 0.001f);
        assertEquals(0.0f, camera.getRightZ(), 0.001f);
    }

    @Test
    @DisplayName("Rotating yaw 90 degrees faces East (+X) and updates forward/right vectors")
    void testYawRotationEast() {
        PolarCamera camera = new PolarCamera();
        camera.setYaw(90.0f);

        assertEquals(90.0f, camera.getYaw(), 0.001f);

        // Forward is East (+X), Right is South (-Z)
        assertEquals(1.0f, camera.getForwardX(), 0.001f);
        assertEquals(0.0f, camera.getForwardZ(), 0.001f);
        assertEquals(0.0f, camera.getRightX(), 0.001f);
        assertEquals(-1.0f, camera.getRightZ(), 0.001f);
    }

    @Test
    @DisplayName("Yaw normalizes within [0, 360) degrees on negative or overflow values")
    void testYawNormalization() {
        PolarCamera camera = new PolarCamera();

        camera.setYaw(-90.0f);
        assertEquals(270.0f, camera.getYaw(), 0.001f);

        camera.setYaw(450.0f);
        assertEquals(90.0f, camera.getYaw(), 0.001f);
    }

    @Test
    @DisplayName("Pitch clamps strictly between -80 and +80 degrees")
    void testPitchClamping() {
        PolarCamera camera = new PolarCamera();

        camera.setPitch(95.0f);
        assertEquals(80.0f, camera.getPitch(), 0.001f);

        camera.setPitch(-95.0f);
        assertEquals(-80.0f, camera.getPitch(), 0.001f);
    }

    @Test
    @DisplayName("Distance clamps between min (2m) and max (50m) bounds")
    void testDistanceClamping() {
        PolarCamera camera = new PolarCamera();

        camera.zoom(-20.0f);
        assertEquals(PolarCamera.MIN_DISTANCE, camera.getDistance(), 0.001f);

        camera.zoom(100.0f);
        assertEquals(PolarCamera.MAX_DISTANCE, camera.getDistance(), 0.001f);
    }

    @Test
    @DisplayName("Camera eye is positioned behind target based on yaw and pitch")
    void testEyePositioning() {
        PolarCamera camera = new PolarCamera();
        camera.setTarget(10.0f, 0.0f, 20.0f);
        camera.setPitch(0.0f); // Level pitch
        camera.setDistance(10.0f);
        camera.setYaw(0.0f); // Looking North

        // Eye should be at targetX=10, eyeY=targetY+offset=1, eyeZ = targetZ - 10 = 10 (South of target)
        assertEquals(10.0f, camera.getEyeX(), 0.001f);
        assertEquals(1.0f, camera.getEyeY(), 0.001f);
        assertEquals(10.0f, camera.getEyeZ(), 0.001f);

        // View vector points North (0, 0, 1)
        assertEquals(0.0f, camera.getViewX(), 0.001f);
        assertEquals(0.0f, camera.getViewY(), 0.001f);
        assertEquals(1.0f, camera.getViewZ(), 0.001f);
    }

    @Test
    @DisplayName("Reset restores camera to default North-facing orientation and distance")
    void testReset() {
        PolarCamera camera = new PolarCamera();
        camera.rotate(120.0f, 30.0f);
        camera.zoom(15.0f);

        camera.reset();

        assertEquals(PolarCamera.DEFAULT_YAW, camera.getYaw(), 0.001f);
        assertEquals(PolarCamera.DEFAULT_PITCH, camera.getPitch(), 0.001f);
        assertEquals(PolarCamera.DEFAULT_DISTANCE, camera.getDistance(), 0.001f);
    }
}
