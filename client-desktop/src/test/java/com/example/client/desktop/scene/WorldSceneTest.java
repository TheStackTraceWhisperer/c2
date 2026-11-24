package com.example.client.desktop.scene;

import com.example.asset.config.FfxiPathConfig;
import com.ffxi.model.SkeletonResource;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.model.LoadedCharacter;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldSceneTest {

    @Test
    @DisplayName("SceneCamera computes authentic view and projection matrices in third-person mode (North=+Z, East=+X)")
    void testSceneCameraThirdPerson() {
        SceneCamera camera = new SceneCamera();
        camera.setMode(CameraMode.THIRD_PERSON);
        camera.setTarget(10.0f, 2.0f, -20.0f);
        camera.setYaw(90.0f); // 90 deg = facing East (+X), so camera eye sits West (-X) of target
        camera.setPitch(30.0f);
        camera.setDistance(10.0f);

        assertEquals(10.0f, camera.getTargetX());
        assertEquals(2.0f, camera.getTargetY());
        assertEquals(-20.0f, camera.getTargetZ());

        Matrix4f proj = camera.getProjectionMatrix(16.0f / 9.0f);
        Matrix4f view = camera.getViewMatrix();

        assertNotNull(proj);
        assertNotNull(view);
        assertTrue(camera.getEyeX() < camera.getTargetX(),
                "Eye should be behind target (West, eyeX < targetX) when yaw is 90 deg facing East (+X)");
        assertEquals(1.0f, camera.getForwardX(), 1e-4f);
        assertEquals(0.0f, camera.getForwardZ(), 1e-4f);
    }

    @Test
    @DisplayName("WorldScene shares the injected SceneCamera instance and maintains native FFXI vector conventions")
    void testWorldSceneSharedSceneCameraAndVectors() {
        SceneCamera sharedCamera = new SceneCamera();
        try (WorldScene scene = new WorldScene(sharedCamera)) {
            assertSame(sharedCamera, scene.getCamera());

            float[] yaws = {0.0f, 45.0f, 90.0f, 135.0f, 180.0f, 225.0f, 270.0f, 315.0f};
            float[] pitches = {-40.0f, 0.0f, 25.0f, 60.0f};

            for (float yaw : yaws) {
                for (float pitch : pitches) {
                    sharedCamera.setTarget(12.5f, -3.0f, 44.0f);
                    sharedCamera.setTargetOffsetY(1.0f);
                    sharedCamera.setDistance(14.0f);
                    sharedCamera.setYaw(yaw);
                    sharedCamera.setPitch(pitch);

                    double yawRad = Math.toRadians(yaw);
                    double pitchRad = Math.toRadians(pitch);
                    float expectedFwdX = (float) Math.sin(yawRad);
                    float expectedFwdZ = (float) Math.cos(yawRad);

                    assertEquals(expectedFwdX, scene.getCamera().getForwardX(), 1e-4f);
                    assertEquals(expectedFwdZ, scene.getCamera().getForwardZ(), 1e-4f);
                    assertEquals(12.5f - 14.0f * (float) Math.cos(pitchRad) * expectedFwdX, scene.getCamera().getEyeX(), 1e-4f);
                    assertEquals(-4.0f - 14.0f * (float) Math.sin(pitchRad), scene.getCamera().getEyeY(), 1e-4f);
                    assertEquals(44.0f - 14.0f * (float) Math.cos(pitchRad) * expectedFwdZ, scene.getCamera().getEyeZ(), 1e-4f);
                }
            }
        }
    }

    @Test
    @DisplayName("SceneCamera supports directed cutscene camera positioning and keyframe interpolation")
    void testSceneCameraCutscene() {
        SceneCamera camera = new SceneCamera();

        Vector3f eyeA = new Vector3f(0f, -5f, -10f);
        Vector3f targetA = new Vector3f(0f, -1f, 0f);
        Vector3f up = new Vector3f(0f, -1f, 0f);
        camera.setCutsceneCamera(eyeA, targetA, up, 45.0f);

        assertEquals(CameraMode.CUTSCENE, camera.getMode());
        assertEquals(45.0f, camera.getFov());
        assertEquals(0f, camera.getEyeX());
        assertEquals(-5f, camera.getEyeY());
        assertEquals(-10f, camera.getEyeZ());

        Vector3f eyeB = new Vector3f(10f, -8f, -20f);
        Vector3f targetB = new Vector3f(2f, -1f, 2f);

        camera.interpolateCutscene(eyeA, eyeB, targetA, targetB, 45.0f, 60.0f, 0.5f);

        assertEquals(5.0f, camera.getEyeX(), 1e-4f);
        assertEquals(-6.5f, camera.getEyeY(), 1e-4f);
        assertEquals(-15.0f, camera.getEyeZ(), 1e-4f);
        assertEquals(52.5f, camera.getFov(), 1e-4f);
    }

    @Test
    @DisplayName("WorldScene manages scene actors, normalizes yaw into [0, 360), and disposes visual instances on remove/close")
    void testWorldSceneActorManagementAndDisposal() {
        LoadedCharacter dummyChar = new LoadedCharacter(
                new SkeletonResource("skel", "29", 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                1.0f,
                0.0f,
                new Vector3f(),
                Map.of()
        );

        AuthenticCharacterInstance playerModel = new AuthenticCharacterInstance(dummyChar, List.of(), null, false);
        AuthenticCharacterInstance npcModel = new AuthenticCharacterInstance(dummyChar, List.of(), null, false);

        try (WorldScene scene = new WorldScene()) {
            SceneActor player = new SceneActor(1L, "Aldo", SceneActor.ActorType.PLAYER);
            player.setPosition(15.0f, 0.0f, -30.0f);
            player.setRotation(-45.0f);
            player.setVisualModel(playerModel);
            scene.setLocalPlayer(player);

            assertEquals(315.0f, player.getYaw(), 1e-4f);
            assertSame(player, scene.getLocalPlayerActor());
            assertTrue(player.isLocalPlayer());

            SceneActor npc = new SceneActor(2L, "Kupipi", SceneActor.ActorType.NPC);
            npc.setPosition(12.0f, 0.0f, -25.0f);
            npc.setTargetTransform(14.0f, 0.0f, -25.0f, 725.0f, 0f, 0f, 0f);
            npc.setVisualModel(npcModel);
            scene.addActor(npc);

            assertEquals(5.0f, npc.getTargetYaw(), 1e-4f);
            assertEquals(2, scene.getActors().size());
            assertSame(npc, scene.getActor(2L));

            scene.removeActor(2L);
            assertNull(scene.getActor(2L));
            assertTrue(npcModel.isClosed(), "removeActor must dispose AuthenticCharacterInstance");
            assertEquals(1, scene.getActors().size());

            SceneActor remote2 = new SceneActor(3L, "Curilla", SceneActor.ActorType.NPC);
            AuthenticCharacterInstance remote2Model = new AuthenticCharacterInstance(dummyChar, List.of(), null, false);
            remote2.setVisualModel(remote2Model);
            scene.addActor(remote2);
            scene.clearRemoteActors();
            assertTrue(remote2Model.isClosed(), "clearRemoteActors must dispose remote actors");
            assertFalse(playerModel.isClosed(), "clearRemoteActors must keep local player active");
            assertSame(player, scene.getActor(1L));
        }
        assertTrue(playerModel.isClosed(), "WorldScene.close must dispose remaining actor visual models");
    }

    @Test
    @DisplayName("WorldScene loads zone interactions headlessly from retail DAT files")
    void testWorldSceneLoadZoneInteractions() {
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        assumeTrue(Files.isDirectory(ffxiRoot) && FfxiPathConfig.resolveRomFile("ROM/1/31.DAT").isPresent(),
                "Retail FFXI installation required at " + ffxiRoot);

        try (WorldScene scene = new WorldScene()) {
            scene.loadZone(ffxiRoot, 230); // South San d'Oria

            assertFalse(scene.getZoneInteractions().isEmpty(),
                    "South San d'Oria (Zone 230) must contain authentic zone interactions");

            boolean hasEntrance = scene.getZoneInteractions().stream().anyMatch(com.example.asset.interaction.ZoneInteraction::isZoneEntrance);
            assertTrue(hasEntrance, "Zone 230 must contain at least one retail zone entrance");
        }
    }

    @Test
    @DisplayName("WorldScene.resolveEffectModelMatrix aligns local +X toward camera for BillboardMode.CAMERA")
    void testCameraOrientationModeAlignsLocalXAxisTowardCamera() {
        Vector3f camPos = new Vector3f(12.0f, -5.0f, 34.0f);
        Matrix4f viewMat = new Matrix4f();
        float scale = 70.0f;

        Vector3f[] positions = {
                new Vector3f(camPos.x + 1000.0f, camPos.y, camPos.z),          // East horizon (dawn)
                new Vector3f(camPos.x - 600.0f, camPos.y - 500.0f, camPos.z + 250.0f), // Elevated dusk/sky
                new Vector3f(camPos.x, camPos.y - 1000.0f, camPos.z)           // Overhead zenith (noon, +Y down so sun is at camY - 1000)
        };

        for (Vector3f objPos : positions) {
            Matrix4f baseModel = new Matrix4f().translation(objPos).scale(scale);
            Matrix4f resolved = WorldScene.resolveEffectModelMatrix(
                    baseModel,
                    com.ffxi.viewer.renderer.ZoneEffectParams.BillboardMode.CAMERA,
                    camPos,
                    viewMat
            );
            Vector3f tip = resolved.transformPosition(new Vector3f(1.0f, 0.0f, 0.0f));
            Vector3f actualAxis = tip.sub(objPos, new Vector3f());
            assertTrue(Float.isFinite(actualAxis.x) && Float.isFinite(actualAxis.y) && Float.isFinite(actualAxis.z));
            assertEquals(scale, actualAxis.length(), 1e-2f, "Scale must be preserved");
            Vector3f expectedDir = new Vector3f(camPos).sub(objPos).normalize();
            actualAxis.normalize();
            assertEquals(expectedDir.x, actualAxis.x, 1e-4f);
            assertEquals(expectedDir.y, actualAxis.y, 1e-4f);
            assertEquals(expectedDir.z, actualAxis.z, 1e-4f);
        }
    }
}
