package com.example.client.desktop.camera;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolarCameraTest {

    @Test
    @DisplayName("Default camera initializes to North (0 deg yaw), 25 deg pitch, 8m distance")
    void testDefaultInitialization() {
        PolarCamera camera = new PolarCamera();

        assertEquals(0.0f, camera.getYaw(), 0.001f);
        assertEquals(25.0f, camera.getPitch(), 0.001f);
        assertEquals(8.0f, camera.getDistance(), 0.001f);

        // Forward is North (-Z), Right is East (+X)
        assertEquals(0.0f, camera.getForwardX(), 0.001f);
        assertEquals(-1.0f, camera.getForwardZ(), 0.001f);
        assertEquals(1.0f, camera.getRightX(), 0.001f);
        assertEquals(0.0f, camera.getRightZ(), 0.001f);
    }

    @Test
    @DisplayName("Rotating yaw 90 degrees faces East (+X) and updates forward/right vectors")
    void testYawRotationEast() {
        PolarCamera camera = new PolarCamera();
        camera.setYaw(90.0f);

        assertEquals(90.0f, camera.getYaw(), 0.001f);

        // Forward is East (+X), Right is South (+Z)
        assertEquals(1.0f, camera.getForwardX(), 0.001f);
        assertEquals(0.0f, camera.getForwardZ(), 0.001f);
        assertEquals(0.0f, camera.getRightX(), 0.001f);
        assertEquals(1.0f, camera.getRightZ(), 0.001f);
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
        camera.setYaw(0.0f); // Looking North (-Z)

        // Eye should be at targetX=10, eyeY=targetY+offset=1, eyeZ = targetZ - (-1)*10 = 30 (South of target)
        assertEquals(10.0f, camera.getEyeX(), 0.001f);
        assertEquals(1.0f, camera.getEyeY(), 0.001f);
        assertEquals(30.0f, camera.getEyeZ(), 0.001f);

        // View vector points North (0, 0, -1)
        assertEquals(0.0f, camera.getViewX(), 0.001f);
        assertEquals(0.0f, camera.getViewY(), 0.001f);
        assertEquals(-1.0f, camera.getViewZ(), 0.001f);
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

    @Test
    @DisplayName("Default third-person camera is placed directly behind character with player facing away")
    void testDefaultThirdPersonCameraBehindCharacter() {
        PolarCamera camera = new PolarCamera();
        float charX = 0.0f, charY = 0.0f, charZ = 0.0f;
        float charYaw = 0.0f; // Character facing North (-Z)

        camera.setTarget(charX, charY, charZ);
        camera.setTargetOffsetY(1.0f);
        camera.setYaw(0.0f); // Default camera looking North (-Z)
        camera.setPitch(25.0f); // Default elevated pitch
        camera.setDistance(6.0f);

        // 1. Camera forward vector is aligned with character facing direction (North, -Z)
        float charFacingX = (float) Math.sin(Math.toRadians(charYaw));
        float charFacingZ = (float) -Math.cos(Math.toRadians(charYaw));
        assertEquals(charFacingX, camera.getForwardX(), 0.001f);
        assertEquals(charFacingZ, camera.getForwardZ(), 0.001f);

        // 2. Camera eye is positioned South (+Z) of target (behind character's back)
        float horizDist = 6.0f * (float) Math.cos(Math.toRadians(25.0));
        float vertDist = 6.0f * (float) Math.sin(Math.toRadians(25.0));
        assertEquals(charX, camera.getEyeX(), 0.001f, "Camera should be centered horizontally on character");
        assertEquals(charZ + horizDist, camera.getEyeZ(), 0.001f, "Camera eye must be behind character (+Z)");
        assertEquals(charY + 1.0f + vertDist, camera.getEyeY(), 0.001f, "Camera eye must be elevated above character");

        // 3. Euclidean distance from eye to target center of mass equals distance
        float dx = camera.getEyeX() - camera.getTargetX();
        float dy = camera.getEyeY() - (camera.getTargetY() + camera.getTargetOffsetY());
        float dz = camera.getEyeZ() - camera.getTargetZ();
        float actualDist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        assertEquals(6.0f, actualDist, 0.001f);

        // 4. Dot product of view vector and character facing vector is positive 1.0 (looking along character's gaze)
        float dot = camera.getViewX() * charFacingX + camera.getViewZ() * charFacingZ;
        assertTrue(dot > 0.8f, "Camera view must look along character facing direction (character facing away)");
    }

    @Test
    @DisplayName("Orbiting camera 360 degrees traces an exact circular trajectory around character")
    void testFull360OrbitCircleGeometry() {
        PolarCamera camera = new PolarCamera();
        camera.setTarget(5.0f, 2.0f, -10.0f);
        camera.setTargetOffsetY(1.2f);
        camera.setPitch(30.0f);
        camera.setDistance(8.0f);

        float expectedRadius = 8.0f * (float) Math.cos(Math.toRadians(30.0));
        float expectedEyeY = 2.0f + 1.2f + 8.0f * (float) Math.sin(Math.toRadians(30.0));

        for (int angleDeg = 0; angleDeg <= 360; angleDeg += 15) {
            camera.setYaw((float) angleDeg);

            // Eye height must remain completely constant throughout horizontal orbit
            assertEquals(expectedEyeY, camera.getEyeY(), 0.001f);

            // Horizontal radius in XZ plane around target must strictly equal expectedRadius
            float dx = camera.getEyeX() - camera.getTargetX();
            float dz = camera.getEyeZ() - camera.getTargetZ();
            float actualRadius = (float) Math.hypot(dx, dz);
            assertEquals(expectedRadius, actualRadius, 0.001f);

            // 3D distance to target must remain strictly 8.0m
            float dy = camera.getEyeY() - (camera.getTargetY() + camera.getTargetOffsetY());
            float dist3D = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(8.0f, dist3D, 0.001f);
        }
    }

    @Test
    @DisplayName("Chase camera maintains identical behind-the-back alignment across all cardinal directions")
    void testChaseCameraAlignmentInAllCardinalDirections() {
        PolarCamera camera = new PolarCamera();
        camera.setTarget(0.0f, 0.0f, 0.0f);
        camera.setTargetOffsetY(1.0f);
        camera.setPitch(20.0f);
        camera.setDistance(7.0f);

        // Cardinal directions in FFXI: North (0 deg, -Z), East (90 deg, +X), South (180 deg, +Z), West (270 deg, -X)
        float[][] cardinals = new float[][] {
                {0.0f,   0.0f, -1.0f}, // North: char faces -Z
                {90.0f,  1.0f,  0.0f}, // East:  char faces +X
                {180.0f, 0.0f,  1.0f}, // South: char faces +Z
                {270.0f,-1.0f,  0.0f}  // West:  char faces -X
        };

        float horizDist = 7.0f * (float) Math.cos(Math.toRadians(20.0));

        for (float[] dir : cardinals) {
            float facingDeg = dir[0];
            float fwdX = dir[1];
            float fwdZ = dir[2];

            // In third-person chase mode, camera yaw matches character facing
            camera.setYaw(facingDeg);

            // Camera forward matches character forward
            assertEquals(fwdX, camera.getForwardX(), 0.001f);
            assertEquals(fwdZ, camera.getForwardZ(), 0.001f);

            // Camera eye is opposite to forward vector (behind character)
            float expectedEyeX = -fwdX * horizDist;
            float expectedEyeZ = -fwdZ * horizDist;
            assertEquals(expectedEyeX, camera.getEyeX(), 0.001f);
            assertEquals(expectedEyeZ, camera.getEyeZ(), 0.001f);

            // Vector from eye to target points along character facing
            float toTargetX = camera.getTargetX() - camera.getEyeX();
            float toTargetZ = camera.getTargetZ() - camera.getEyeZ();
            float toTargetLen = (float) Math.hypot(toTargetX, toTargetZ);
            assertEquals(fwdX, toTargetX / toTargetLen, 0.001f);
            assertEquals(fwdZ, toTargetZ / toTargetLen, 0.001f);
        }
    }

    @Test
    @DisplayName("Pitch elevation angles smoothly adjust camera eye height and tilt angle")
    void testPitchElevationInvariants() {
        PolarCamera camera = new PolarCamera();
        camera.setTarget(0.0f, 0.0f, 0.0f);
        camera.setTargetOffsetY(1.0f);
        camera.setDistance(10.0f);

        // Level pitch (0 deg) -> Eye height equals targetOffsetY (1.0m), distance on ground is 10m
        camera.setPitch(0.0f);
        assertEquals(1.0f, camera.getEyeY(), 0.001f);
        assertEquals(10.0f, Math.hypot(camera.getEyeX(), camera.getEyeZ()), 0.001f);

        // 45 deg high pitch -> Eye height is 1.0 + 10 * sin(45) = 8.07m, ground dist is 10 * cos(45) = 7.07m
        camera.setPitch(45.0f);
        float expectedY45 = 1.0f + 10.0f * (float) Math.sin(Math.toRadians(45.0));
        float expectedXZ45 = 10.0f * (float) Math.cos(Math.toRadians(45.0));
        assertEquals(expectedY45, camera.getEyeY(), 0.001f);
        assertEquals(expectedXZ45, Math.hypot(camera.getEyeX(), camera.getEyeZ()), 0.001f);

        // -20 deg low pitch -> Eye is below targetOffsetY (camera looking upward at character)
        camera.setPitch(-20.0f);
        float expectedYNeg20 = 1.0f + 10.0f * (float) Math.sin(Math.toRadians(-20.0));
        assertEquals(expectedYNeg20, camera.getEyeY(), 0.001f);
        assertTrue(camera.getEyeY() < 1.0f, "Eye should be lower than target offset at negative pitch");
        assertTrue(camera.getViewY() > 0.0f, "View vector should point upward at negative pitch");
    }
}
