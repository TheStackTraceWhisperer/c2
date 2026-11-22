package com.example.client.desktop.scene;

import com.ffxi.model.ZoneCollisionMap;
import com.ffxi.model.ZoneCollisionObject;
import com.ffxi.model.ZoneCollisionObjectGroup;
import com.ffxi.model.ZoneCollisionTriangle;
import com.ffxi.viewer.collision.ZoneCollisionSystem;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneCameraTest {

    private static float[] identityMatrix() {
        return new float[]{
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
        };
    }

    @Test
    @DisplayName("Default camera initializes to North (0 deg yaw), 25 deg pitch, 8m distance")
    void testDefaultInitialization() {
        SceneCamera camera = new SceneCamera();

        assertEquals(0.0f, camera.getYaw(), 0.001f);
        assertEquals(25.0f, camera.getPitch(), 0.001f);
        assertEquals(8.0f, camera.getDistance(), 0.001f);

        assertEquals(0.0f, camera.getForwardX(), 0.001f);
        assertEquals(1.0f, camera.getForwardZ(), 0.001f);
        assertEquals(1.0f, camera.getRightX(), 0.001f);
        assertEquals(0.0f, camera.getRightZ(), 0.001f);
    }

    @Test
    @DisplayName("Rotating yaw 90 degrees faces East (+X) and updates forward/right vectors")
    void testYawRotationEast() {
        SceneCamera camera = new SceneCamera();
        camera.setYaw(90.0f);

        assertEquals(90.0f, camera.getYaw(), 0.001f);
        assertEquals(1.0f, camera.getForwardX(), 0.001f);
        assertEquals(0.0f, camera.getForwardZ(), 0.001f);
        assertEquals(0.0f, camera.getRightX(), 0.001f);
        assertEquals(-1.0f, camera.getRightZ(), 0.001f);
    }

    @Test
    @DisplayName("Yaw normalizes within [0, 360) degrees on negative or overflow values")
    void testYawNormalization() {
        SceneCamera camera = new SceneCamera();

        camera.setYaw(-90.0f);
        assertEquals(270.0f, camera.getYaw(), 0.001f);

        camera.setYaw(450.0f);
        assertEquals(90.0f, camera.getYaw(), 0.001f);
    }

    @Test
    @DisplayName("Pitch clamps strictly between MIN_PITCH and MAX_PITCH degrees")
    void testPitchClamping() {
        SceneCamera camera = new SceneCamera();

        camera.setPitch(95.0f);
        assertEquals(SceneCamera.MAX_PITCH, camera.getPitch(), 0.001f);

        camera.setPitch(-95.0f);
        assertEquals(SceneCamera.MIN_PITCH, camera.getPitch(), 0.001f);
    }

    @Test
    @DisplayName("Distance clamps between MIN_DISTANCE and MAX_DISTANCE bounds")
    void testDistanceClamping() {
        SceneCamera camera = new SceneCamera();

        camera.zoom(-20.0f);
        assertEquals(SceneCamera.MIN_DISTANCE, camera.getDistance(), 0.001f);

        camera.zoom(150.0f);
        assertEquals(SceneCamera.MAX_DISTANCE, camera.getDistance(), 0.001f);
    }

    @Test
    @DisplayName("Camera eye is positioned behind target based on yaw and pitch")
    void testEyePositioning() {
        SceneCamera camera = new SceneCamera();
        camera.setTarget(10.0f, 0.0f, 20.0f);
        camera.setPitch(0.0f);
        camera.setDistance(10.0f);
        camera.setYaw(0.0f);

        assertEquals(10.0f, camera.getEyeX(), 0.001f);
        assertEquals(-1.0f, camera.getEyeY(), 0.001f);
        assertEquals(10.0f, camera.getEyeZ(), 0.001f);

        assertEquals(0.0f, camera.getViewX(), 0.001f);
        assertEquals(0.0f, camera.getViewY(), 0.001f);
        assertEquals(1.0f, camera.getViewZ(), 0.001f);
    }

    @Test
    @DisplayName("Reset restores camera to default North-facing orientation and distance")
    void testReset() {
        SceneCamera camera = new SceneCamera();
        camera.rotate(120.0f, 30.0f);
        camera.zoom(15.0f);

        camera.reset();

        assertEquals(SceneCamera.DEFAULT_YAW, camera.getYaw(), 0.001f);
        assertEquals(SceneCamera.DEFAULT_PITCH, camera.getPitch(), 0.001f);
        assertEquals(SceneCamera.DEFAULT_DISTANCE, camera.getDistance(), 0.001f);
    }

    @Test
    @DisplayName("Default third-person camera is placed directly behind character with player facing away")
    void testDefaultThirdPersonCameraBehindCharacter() {
        SceneCamera camera = new SceneCamera();
        float charX = 0.0f, charY = 0.0f, charZ = 0.0f;
        float charYaw = 0.0f;

        camera.setTarget(charX, charY, charZ);
        camera.setTargetOffsetY(1.0f);
        camera.setYaw(0.0f);
        camera.setPitch(25.0f);
        camera.setDistance(6.0f);

        float charFacingX = (float) Math.sin(Math.toRadians(charYaw));
        float charFacingZ = (float) Math.cos(Math.toRadians(charYaw));
        assertEquals(charFacingX, camera.getForwardX(), 0.001f);
        assertEquals(charFacingZ, camera.getForwardZ(), 0.001f);

        float horizDist = 6.0f * (float) Math.cos(Math.toRadians(25.0));
        float vertDist = 6.0f * (float) Math.sin(Math.toRadians(25.0));
        assertEquals(charX, camera.getEyeX(), 0.001f);
        assertEquals(charZ - horizDist, camera.getEyeZ(), 0.001f);
        assertEquals(charY - 1.0f - vertDist, camera.getEyeY(), 0.001f);

        float dx = camera.getEyeX() - camera.getTargetX();
        float dy = camera.getEyeY() - (camera.getTargetY() - camera.getTargetOffsetY());
        float dz = camera.getEyeZ() - camera.getTargetZ();
        assertEquals(6.0f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz), 0.001f);

        float dot = camera.getViewX() * charFacingX + camera.getViewZ() * charFacingZ;
        assertTrue(dot > 0.8f);
    }

    @Test
    @DisplayName("Orbiting camera 360 degrees traces an exact circular trajectory around character")
    void testFull360OrbitCircleGeometry() {
        SceneCamera camera = new SceneCamera();
        camera.setTarget(5.0f, 2.0f, -10.0f);
        camera.setTargetOffsetY(1.2f);
        camera.setPitch(30.0f);
        camera.setDistance(8.0f);

        float expectedRadius = 8.0f * (float) Math.cos(Math.toRadians(30.0));
        float expectedEyeY = 2.0f - 1.2f - 8.0f * (float) Math.sin(Math.toRadians(30.0));

        for (int angleDeg = 0; angleDeg <= 360; angleDeg += 15) {
            camera.setYaw((float) angleDeg);

            assertEquals(expectedEyeY, camera.getEyeY(), 0.001f);

            float dx = camera.getEyeX() - camera.getTargetX();
            float dz = camera.getEyeZ() - camera.getTargetZ();
            assertEquals(expectedRadius, (float) Math.hypot(dx, dz), 0.001f);

            float dy = camera.getEyeY() - (camera.getTargetY() - camera.getTargetOffsetY());
            assertEquals(8.0f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz), 0.001f);
        }
    }

    @Test
    @DisplayName("Chase camera maintains identical behind-the-back alignment across all cardinal directions")
    void testChaseCameraAlignmentInAllCardinalDirections() {
        SceneCamera camera = new SceneCamera();
        camera.setTarget(0.0f, 0.0f, 0.0f);
        camera.setTargetOffsetY(1.0f);
        camera.setPitch(20.0f);
        camera.setDistance(7.0f);

        float[][] cardinals = new float[][]{
                {0.0f,   0.0f,  1.0f},
                {90.0f,  1.0f,  0.0f},
                {180.0f, 0.0f, -1.0f},
                {270.0f,-1.0f,  0.0f}
        };

        float horizDist = 7.0f * (float) Math.cos(Math.toRadians(20.0));

        for (float[] dir : cardinals) {
            float facingDeg = dir[0];
            float fwdX = dir[1];
            float fwdZ = dir[2];

            camera.setYaw(facingDeg);
            assertEquals(fwdX, camera.getForwardX(), 0.001f);
            assertEquals(fwdZ, camera.getForwardZ(), 0.001f);

            assertEquals(-fwdX * horizDist, camera.getEyeX(), 0.001f);
            assertEquals(-fwdZ * horizDist, camera.getEyeZ(), 0.001f);

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
        SceneCamera camera = new SceneCamera();
        camera.setTarget(0.0f, 0.0f, 0.0f);
        camera.setTargetOffsetY(1.0f);
        camera.setDistance(10.0f);

        camera.setPitch(0.0f);
        assertEquals(-1.0f, camera.getEyeY(), 0.001f);
        assertEquals(10.0f, Math.hypot(camera.getEyeX(), camera.getEyeZ()), 0.001f);

        camera.setPitch(45.0f);
        float expectedY45 = -1.0f - 10.0f * (float) Math.sin(Math.toRadians(45.0));
        float expectedXZ45 = 10.0f * (float) Math.cos(Math.toRadians(45.0));
        assertEquals(expectedY45, camera.getEyeY(), 0.001f);
        assertEquals(expectedXZ45, Math.hypot(camera.getEyeX(), camera.getEyeZ()), 0.001f);

        camera.setPitch(-20.0f);
        float expectedYNeg20 = -1.0f - 10.0f * (float) Math.sin(Math.toRadians(-20.0));
        assertEquals(expectedYNeg20, camera.getEyeY(), 0.001f);
        assertTrue(camera.getEyeY() > -1.0f);
        assertTrue(camera.getViewY() < 0.0f);
    }

    @Test
    @DisplayName("applyCollisionClamp pulls camera eye in front of a vertical wall while preserving desired distance")
    void testCollisionClampPullsEyeInFrontOfWall() {
        List<ZoneCollisionTriangle> wallTris = List.of(
                new ZoneCollisionTriangle(-10f, -10f, -3f, 10f, -10f, -3f, 10f, 5f, -3f, 0f, 0f, 1f),
                new ZoneCollisionTriangle(-10f, -10f, -3f, 10f, 5f, -3f, -10f, 5f, -3f, 0f, 0f, 1f)
        );
        ZoneCollisionObject obj = new ZoneCollisionObject(wallTris, identityMatrix());
        ZoneCollisionObjectGroup group = new ZoneCollisionObjectGroup(List.of(obj));
        ZoneCollisionSystem collisionSystem = new ZoneCollisionSystem(
                new ZoneCollisionMap(1, 1, 64, 64, 1, 1, new ZoneCollisionObjectGroup[][]{{group}})
        );

        SceneCamera camera = new SceneCamera();
        camera.setTarget(0.0f, 0.0f, 0.0f);
        camera.setTargetOffsetY(1.0f);
        camera.setYaw(0.0f);   // Looking North (+Z), eye starts South (-Z)
        camera.setPitch(0.0f);
        camera.setDistance(10.0f);

        camera.applyCollisionClamp(collisionSystem);

        assertEquals(10.0f, camera.getDistance(), 0.001f, "Desired boom distance must be preserved");
        assertEquals(3.0f - SceneCamera.CAMERA_COLLISION_MARGIN, camera.getEffectiveDistance(), 0.01f);
        assertTrue(camera.getEyeZ() > -3.0f, "Camera eye must be pulled in front of z=-3 wall: eyeZ=" + camera.getEyeZ());
    }

    @Test
    @DisplayName("applyCollisionClamp prevents camera eye from dropping beneath the floor surface")
    void testCollisionClampPreventsUndergroundEye() {
        // Upward floor at y = 0 (-Y = Up in native FFXI)
        List<ZoneCollisionTriangle> floorTris = List.of(
                new ZoneCollisionTriangle(-20f, 0f, -20f, 20f, 0f, -20f, 20f, 0f, 20f, 0f, -1f, 0f),
                new ZoneCollisionTriangle(-20f, 0f, -20f, 20f, 0f, 20f, -20f, 0f, 20f, 0f, -1f, 0f)
        );
        ZoneCollisionObject obj = new ZoneCollisionObject(floorTris, identityMatrix());
        ZoneCollisionObjectGroup group = new ZoneCollisionObjectGroup(List.of(obj));
        ZoneCollisionSystem collisionSystem = new ZoneCollisionSystem(
                new ZoneCollisionMap(1, 1, 64, 64, 1, 1, new ZoneCollisionObjectGroup[][]{{group}})
        );

        SceneCamera camera = new SceneCamera();
        camera.setTarget(0.0f, 0.0f, 0.0f);
        camera.setTargetOffsetY(0.5f);
        camera.setYaw(0.0f);
        camera.setPitch(-45.0f); // Unclamped eyeY would drop positive (+Y = underground)
        camera.setDistance(6.0f);
        assertTrue(camera.getEyeY() > 0.0f, "Unclamped negative pitch should place eye underground");

        camera.applyCollisionClamp(collisionSystem);

        assertTrue(camera.getEyeY() <= -SceneCamera.CAMERA_COLLISION_MARGIN + 0.001f,
                "Clamped eyeY must remain at or above floor margin (-Y=Up), was " + camera.getEyeY());
    }

    @Test
    @DisplayName("applyCollisionClamp leaves CUTSCENE mode camera untouched")
    void testCollisionClampIgnoresCutsceneMode() {
        List<ZoneCollisionTriangle> wallTris = List.of(
                new ZoneCollisionTriangle(-10f, -10f, -3f, 10f, -10f, -3f, 10f, 5f, -3f, 0f, 0f, 1f),
                new ZoneCollisionTriangle(-10f, -10f, -3f, 10f, 5f, -3f, -10f, 5f, -3f, 0f, 0f, 1f)
        );
        ZoneCollisionObject obj = new ZoneCollisionObject(wallTris, identityMatrix());
        ZoneCollisionObjectGroup group = new ZoneCollisionObjectGroup(List.of(obj));
        ZoneCollisionSystem collisionSystem = new ZoneCollisionSystem(
                new ZoneCollisionMap(1, 1, 64, 64, 1, 1, new ZoneCollisionObjectGroup[][]{{group}})
        );

        SceneCamera camera = new SceneCamera();
        camera.setCutsceneCamera(new Vector3f(0f, -1f, -10f), new Vector3f(0f, -1f, 0f), new Vector3f(0f, -1f, 0f), 50.0f);

        camera.applyCollisionClamp(collisionSystem);

        assertEquals(CameraMode.CUTSCENE, camera.getMode());
        assertEquals(0.0f, camera.getEyeX(), 0.001f);
        assertEquals(-1.0f, camera.getEyeY(), 0.001f);
        assertEquals(-10.0f, camera.getEyeZ(), 0.001f);
    }
}
