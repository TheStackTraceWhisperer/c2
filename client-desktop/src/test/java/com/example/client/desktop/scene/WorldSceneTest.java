package com.example.client.desktop.scene;

import com.example.asset.config.FfxiPathConfig;
import com.example.common.protocol.MovementFlags;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldSceneTest {

    @Test
    @DisplayName("SceneCamera computes authentic view and projection matrices in third-person mode")
    void testSceneCameraThirdPerson() {
        SceneCamera camera = new SceneCamera();
        camera.setMode(CameraMode.THIRD_PERSON);
        camera.setTarget(10.0f, 2.0f, -20.0f);
        camera.setYaw(90.0f); // 90 deg = East (+X)
        camera.setPitch(30.0f);
        camera.setDistance(10.0f);

        assertEquals(10.0f, camera.getTargetX());
        assertEquals(2.0f, camera.getTargetY());
        assertEquals(-20.0f, camera.getTargetZ());

        Matrix4f proj = camera.getProjectionMatrix(16.0f / 9.0f);
        Matrix4f view = camera.getViewMatrix();

        assertNotNull(proj);
        assertNotNull(view);
        assertTrue(camera.getEyeX() > camera.getTargetX(), "Eye should be offset to the East when yaw is 90 deg");
    }

    @Test
    @DisplayName("SceneCamera supports directed cutscene camera positioning and keyframe interpolation")
    void testSceneCameraCutscene() {
        SceneCamera camera = new SceneCamera();

        Vector3f eyeA = new Vector3f(0f, 5f, 10f);
        Vector3f targetA = new Vector3f(0f, 1f, 0f);
        Vector3f up = new Vector3f(0f, 1f, 0f);
        camera.setCutsceneCamera(eyeA, targetA, up, 45.0f);

        assertEquals(CameraMode.CUTSCENE, camera.getMode());
        assertEquals(45.0f, camera.getFov());
        assertEquals(0f, camera.getEyeX());
        assertEquals(5f, camera.getEyeY());
        assertEquals(10f, camera.getEyeZ());

        Vector3f eyeB = new Vector3f(10f, 8f, 20f);
        Vector3f targetB = new Vector3f(2f, 1f, 2f);

        // Interpolate half-way (t = 0.5)
        camera.interpolateCutscene(eyeA, eyeB, targetA, targetB, 45.0f, 60.0f, 0.5f);

        assertEquals(5.0f, camera.getEyeX(), 1e-4f);
        assertEquals(6.5f, camera.getEyeY(), 1e-4f);
        assertEquals(15.0f, camera.getEyeZ(), 1e-4f);
        assertEquals(52.5f, camera.getFov(), 1e-4f);
    }

    @Test
    @DisplayName("WorldScene manages scene actors and updates transforms")
    void testWorldSceneActorManagement() {
        try (WorldScene scene = new WorldScene()) {
            SceneActor player = new SceneActor(1L, "Aldo", SceneActor.ActorType.PLAYER);
            player.setPosition(15.0f, 0.0f, -30.0f);
            player.setRotation(180.0f);
            scene.setLocalPlayer(player);

            assertSame(player, scene.getLocalPlayerActor());
            assertTrue(player.isLocalPlayer());

            SceneActor npc = new SceneActor(2L, "Kupipi", SceneActor.ActorType.NPC);
            npc.setPosition(12.0f, 0.0f, -25.0f);
            scene.addActor(npc);

            assertEquals(2, scene.getActors().size());
            assertSame(npc, scene.getActor(2L));

            scene.removeActor(2L);
            assertNull(scene.getActor(2L));
            assertEquals(1, scene.getActors().size());
        }
    }

    @Test
    @DisplayName("WorldScene loads zone interactions headlessly from retail DAT files")
    void testWorldSceneLoadZoneInteractions() {
        try (WorldScene scene = new WorldScene()) {
            Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
            scene.loadZone(ffxiRoot, 230); // South San d'Oria

            assertFalse(scene.getZoneInteractions().isEmpty(),
                    "South San d'Oria (Zone 230) must contain authentic zone interactions");

            boolean hasEntrance = scene.getZoneInteractions().stream().anyMatch(com.example.asset.interaction.ZoneInteraction::isZoneEntrance);
            assertTrue(hasEntrance, "Zone 230 must contain at least one retail zone entrance");
        }
    }
}
