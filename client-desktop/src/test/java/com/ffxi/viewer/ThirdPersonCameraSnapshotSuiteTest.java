package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.capture.ScreenshotCapture;
import com.example.client.desktop.scene.CameraMode;
import com.example.client.desktop.scene.SceneActor;
import com.example.client.desktop.scene.SceneCamera;
import com.example.client.desktop.scene.WorldScene;
import com.example.common.time.VanadielTime;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.environment.WeatherPreset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Third-Person Polar Camera Snapshot Suite.
 * Renders authentic FFXI character avatars inside retail Southern San d'Oria (Zone 230) strictly
 * through the production OpenGL 4.6 Core {@link WorldScene} pipeline from third-person camera
 * viewpoints, proving polar orbit kinematics, chase-camera alignment, pitch elevation, boom zoom,
 * and in-place character rotation.
 */
public class ThirdPersonCameraSnapshotSuiteTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final float ANCHOR_X = 5.0f;
    private static final float ANCHOR_Z = -15.0f;

    private static HeadlessGlContext glContext;
    private static WorldScene worldScene;
    private static SceneActor localActor;
    private static float anchorY = 0.0f;

    @BeforeAll
    static void initCharacterModel() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required for OpenGL 4.6 Core snapshots (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        glContext = HeadlessGlContext.create();
        worldScene = new WorldScene();
        worldScene.init();
        assertTrue(worldScene.isInitialized(), "WorldScene must initialize in OpenGL 4.6 Core context");

        worldScene.setShowSkyDome(true);
        worldScene.setEnableShadows(false);
        worldScene.setEnableSsao(false);
        worldScene.setEnableActorShadows(true);
        worldScene.setManualTimeOverride(true);
        worldScene.setManualTimeHour(12.0f);
        worldScene.setWeatherPreset(WeatherPreset.FINE);

        worldScene.loadZone(FFXI_ROOT, 230);
        assertNotNull(worldScene.getLoadedZone(), "Zone 230 must load in WorldScene");
        if (worldScene.getLoadedZone().collisionSystem() != null) {
            float floorY = worldScene.getLoadedZone().collisionSystem().findFloorY(ANCHOR_X, ANCHOR_Z, -20.0f);
            if (Float.isFinite(floorY)) {
                anchorY = floorY;
            }
        }

        localActor = new SceneActor(1L, "Adventurer", SceneActor.ActorType.PLAYER);
        localActor.setVisualModel(worldScene.createCharacterInstance(FFXI_ROOT, RaceGenderConfig.HumeM));
        localActor.setPosition(ANCHOR_X, anchorY, ANCHOR_Z);
        worldScene.setLocalPlayer(localActor);
        worldScene.update(0.0f, VanadielTime.ofHour(12.0f));
    }

    @AfterAll
    static void tearDown() {
        if (worldScene != null) {
            worldScene.close();
        }
        if (glContext != null) {
            glContext.close();
        }
    }

    @Test
    @DisplayName("Default third-person camera state: player facing away (North) with camera positioned behind")
    void testDefaultThirdPersonCameraBehindCharacter() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        float charX = 0.0f, charY = 0.0f, charZ = 0.0f;
        float charYaw = 0.0f; // Player facing away (North, +Z)
        float camYaw = 0.0f;  // Camera looking North (+Z) from behind (-Z)
        float camPitch = 25.0f; // Default elevated third-person chase angle
        float camDist = 6.0f;

        SceneCamera cam = new SceneCamera();
        cam.setMode(CameraMode.FREE_ORBIT);
        cam.setTarget(charX, charY, charZ);
        cam.setTargetOffsetY(0.90f);
        cam.setYaw(camYaw);
        cam.setPitch(camPitch);
        cam.setDistance(camDist);

        assertEquals(0.0f, cam.getEyeX(), 0.001f, "Camera should be centered horizontally behind player");
        assertTrue(cam.getEyeZ() < charZ, "Camera eye must be South of player (-Z, behind player's back)");
        assertTrue(cam.getEyeY() < charY - 0.90f, "Camera eye must be elevated above player (-Y)");

        File snapshot = renderAndSaveSnapshot(
                charX, charY, charZ, charYaw,
                camYaw, camPitch, camDist,
                "XIM Third-Person Default State | Behind Player Facing Away",
                "Default FFXI Chase Camera: Player Facing North (+Z) | Camera Positioned Behind (-Z)",
                "camera_default_behind_facing_away.png"
        );

        assertTrue(snapshot.exists() && snapshot.length() > 10000, "Default state snapshot must exist and be non-empty");
    }

    @Test
    @DisplayName("Full 360-degree orbit around player at 45-degree intervals")
    void testOrbit360DegreesAt45DegreeIntervals() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        record OrbitAngle(float yawDeg, String filename, String title, String description) {}

        List<OrbitAngle> orbitAngles = List.of(
                new OrbitAngle(0.0f,   "camera_orbit_000_behind.png",        "Orbit 000 deg | Directly Behind Player",       "Default Third-Person Chase Angle: Player Facing North, Back to Camera"),
                new OrbitAngle(45.0f,  "camera_orbit_045_rear_left.png",    "Orbit 045 deg | Rear-Left 3/4 Perspective",    "Camera Over Left Shoulder: Positioned South-West Looking toward Player"),
                new OrbitAngle(90.0f,  "camera_orbit_090_left_profile.png",  "Orbit 090 deg | Left Side Profile",           "Camera Positioned West of Player: Viewing Full Left Profile"),
                new OrbitAngle(135.0f, "camera_orbit_135_front_left.png",   "Orbit 135 deg | Front-Left 3/4 Perspective",   "Camera in Front-Left Quadrant (North-West): Looking toward Player Left Chest and Face"),
                new OrbitAngle(180.0f, "camera_orbit_180_front.png",        "Orbit 180 deg | Front-Facing Portrait",        "Camera Directly North of Player: Player Facing Straight into Lens"),
                new OrbitAngle(225.0f, "camera_orbit_225_front_right.png",  "Orbit 225 deg | Front-Right 3/4 Perspective",  "Camera in Front-Right Quadrant (North-East): Looking toward Player Right Ribcage and Face"),
                new OrbitAngle(270.0f, "camera_orbit_270_right_profile.png", "Orbit 270 deg | Right Side Profile",          "Camera Positioned East of Player: Viewing Full Right Profile"),
                new OrbitAngle(315.0f, "camera_orbit_315_rear_right.png",   "Orbit 315 deg | Rear-Right 3/4 Perspective",   "Camera Over Right Shoulder: Positioned South-East Looking toward Player")
        );

        float charYaw = 0.0f;
        float camPitch = 22.0f;
        float camDist = 5.5f;

        for (OrbitAngle orbit : orbitAngles) {
            SceneCamera cam = new SceneCamera();
            cam.setMode(CameraMode.FREE_ORBIT);
            cam.setTarget(0.0f, 0.0f, 0.0f);
            cam.setTargetOffsetY(1.0f);
            cam.setYaw(orbit.yawDeg);
            cam.setPitch(camPitch);
            cam.setDistance(camDist);

            float expectedRadius = camDist * (float) Math.cos(Math.toRadians(camPitch));
            assertEquals(expectedRadius, Math.hypot(cam.getEyeX(), cam.getEyeZ()), 0.01f);

            File snapshot = renderAndSaveSnapshot(
                    0.0f, 0.0f, 0.0f, charYaw,
                    orbit.yawDeg, camPitch, camDist,
                    orbit.title, orbit.description, orbit.filename
            );

            assertTrue(snapshot.exists() && snapshot.length() > 10000);
        }
    }

    @Test
    @DisplayName("Vertical camera pitch elevation from low hero angle to high tactical overview")
    void testPitchElevationAngles() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        record PitchAngle(float pitchDeg, String filename, String title, String description) {}

        List<PitchAngle> pitchAngles = List.of(
                new PitchAngle(-15.0f, "camera_pitch_low_hero_neg15.png",  "Pitch -15 deg | Low Dramatic Hero Angle",   "Camera Positioned Below Character Waist: Tilted Upward toward Avatar"),
                new PitchAngle(0.0f,   "camera_pitch_level_00.png",        "Pitch 000 deg | Level Eye-Line Perspective", "Camera at Exact Torso Level: Parallel to Horizon Line"),
                new PitchAngle(25.0f,  "camera_pitch_elevated_25.png",     "Pitch 025 deg | Standard Chase Elevation",   "Default FFXI Elevated Angle: Clear View of Avatar and Foreground"),
                new PitchAngle(50.0f,  "camera_pitch_high_tactical_50.png","Pitch 050 deg | High Tactical Angle",       "Elevated Angle: Tactical Overhead View of Ground and Perimeter"),
                new PitchAngle(75.0f,  "camera_pitch_overhead_75.png",     "Pitch 075 deg | Steep Top-Down Angle",      "Near-Nadir Bird's-Eye View: Looking Directly Down at Head and Shoulders")
        );

        float charYaw = 0.0f;
        float camYaw = 35.0f;
        float camDist = 6.0f;

        for (PitchAngle pa : pitchAngles) {
            SceneCamera cam = new SceneCamera();
            cam.setMode(CameraMode.FREE_ORBIT);
            cam.setTarget(0.0f, 0.0f, 0.0f);
            cam.setTargetOffsetY(1.0f);
            cam.setYaw(camYaw);
            cam.setPitch(pa.pitchDeg);
            cam.setDistance(camDist);

            float expectedEyeY = -1.0f - camDist * (float) Math.sin(Math.toRadians(pa.pitchDeg));
            assertEquals(expectedEyeY, cam.getEyeY(), 0.01f);

            File snapshot = renderAndSaveSnapshot(
                    0.0f, 0.0f, 0.0f, charYaw,
                    camYaw, pa.pitchDeg, camDist,
                    pa.title, pa.description, pa.filename
            );

            assertTrue(snapshot.exists() && snapshot.length() > 10000);
        }
    }

    @Test
    @DisplayName("Camera boom zoom distance intervals from close-up to tactical wide-angle")
    void testZoomDistanceIntervals() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        record ZoomDistance(float dist, String filename, String title, String description) {}

        List<ZoomDistance> zooms = List.of(
                new ZoomDistance(2.5f,  "camera_zoom_close_2_5m.png",   "Zoom 2.5m | Close-Up Avatar Detail",   "Close Third-Person Framing: High-Detail Armor, Texture, and Rigging View"),
                new ZoomDistance(6.0f,  "camera_zoom_standard_6m.png",  "Zoom 6.0m | Standard Third-Person",   "Default FFXI Camera Distance: Full-Body Avatar with Immediate Surroundings"),
                new ZoomDistance(13.0f, "camera_zoom_distance_13m.png", "Zoom 13.0m | Wide Field Perspective", "Distant Tactical Framing: Broad Overview of Surrounding World Grid")
        );

        float charYaw = 0.0f;
        float camYaw = 25.0f;
        float camPitch = 20.0f;

        for (ZoomDistance zd : zooms) {
            File snapshot = renderAndSaveSnapshot(
                    0.0f, 0.0f, 0.0f, charYaw,
                    camYaw, camPitch, zd.dist,
                    zd.title, zd.description, zd.filename
            );

            assertTrue(snapshot.exists() && snapshot.length() > 10000);
        }
    }

    @Test
    @DisplayName("Chase camera follows behind character across all four cardinal facing directions")
    void testChaseCameraInAllCardinalDirections() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        record CardinalChase(float facingDeg, String filename, String title, String description) {}

        List<CardinalChase> cardinals = List.of(
                new CardinalChase(0.0f,   "camera_chase_facing_north.png", "Chase North (+Z) | Camera Directly Behind", "Player Facing North (000 deg): Camera Behind at -Z looking +Z"),
                new CardinalChase(90.0f,  "camera_chase_facing_east.png",  "Chase East (+X) | Camera Directly Behind",  "Player Facing East (090 deg): Camera Behind at -X looking +X"),
                new CardinalChase(180.0f, "camera_chase_facing_south.png", "Chase South (-Z) | Camera Directly Behind", "Player Facing South (180 deg): Camera Behind at +Z looking -Z"),
                new CardinalChase(270.0f, "camera_chase_facing_west.png",  "Chase West (-X) | Camera Directly Behind",  "Player Facing West (270 deg): Camera Behind at +X looking -X")
        );

        float camPitch = 22.0f;
        float camDist = 6.0f;

        for (CardinalChase cc : cardinals) {
            File snapshot = renderAndSaveSnapshot(
                    0.0f, 0.0f, 0.0f, cc.facingDeg,
                    cc.facingDeg, camPitch, camDist,
                    cc.title, cc.description, cc.filename
            );

            assertTrue(snapshot.exists() && snapshot.length() > 10000);
        }
    }

    @Test
    @DisplayName("Player rotates in place while third-person camera remains stationary looking North")
    void testCharacterInPlaceTurningWithStationaryCamera() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        record TurnAngle(float charYawDeg, String filename, String title, String description) {}

        List<TurnAngle> turnAngles = List.of(
                new TurnAngle(0.0f,   "camera_turn_char_000_north.png",     "Character North (000 deg) | Back to Camera",   "Player Facing North: Direct Rear View (Facing Away from Camera)"),
                new TurnAngle(45.0f,  "camera_turn_char_045_northeast.png", "Character North-East (045 deg) | Quarter Turn", "Player Facing North-East: Quarter Turn Showing Right Flank"),
                new TurnAngle(90.0f,  "camera_turn_char_090_east.png",      "Character East (090 deg) | Right Profile",     "Player Facing East: Full Right Profile Relative to Camera"),
                new TurnAngle(135.0f, "camera_turn_char_135_southeast.png", "Character South-East (135 deg) | 3/4 Front",  "Player Facing South-East: Three-Quarter Front View"),
                new TurnAngle(180.0f, "camera_turn_char_180_south.png",     "Character South (180 deg) | Facing Camera",   "Player Facing South: Head-On Frontal Portrait Looking at Camera"),
                new TurnAngle(270.0f, "camera_turn_char_270_west.png",      "Character West (270 deg) | Left Profile",     "Player Facing West: Full Left Profile Relative to Camera")
        );

        float camYaw = 0.0f;
        float camPitch = 20.0f;
        float camDist = 5.5f;

        for (TurnAngle ta : turnAngles) {
            File snapshot = renderAndSaveSnapshot(
                    0.0f, 0.0f, 0.0f, ta.charYawDeg,
                    camYaw, camPitch, camDist,
                    ta.title, ta.description, ta.filename
            );

            assertTrue(snapshot.exists() && snapshot.length() > 10000);
        }
    }

    private static File renderAndSaveSnapshot(
            float charX, float charY, float charZ, float charYaw,
            float camYaw, float camPitch, float camDist,
            String title, String subtitle, String filename
    ) throws Exception {
        float wx = ANCHOR_X + charX;
        float wy = anchorY + charY;
        float wz = ANCHOR_Z + charZ;
        localActor.setPosition(wx, wy, wz);
        localActor.setRotation(charYaw);

        SceneCamera camera = worldScene.getCamera();
        camera.setMode(CameraMode.FREE_ORBIT);
        camera.setFov(42.0f);
        camera.setNearPlane(0.1f);
        camera.setFarPlane(2000.0f);
        camera.setTarget(wx, wy, wz);
        camera.setTargetOffsetY(0.90f);
        camera.setYaw(camYaw);
        camera.setPitch(camPitch);
        camera.setDistance(camDist);

        worldScene.update(0.0f, VanadielTime.ofHour(12.0f));

        BufferedImage image = worldScene.captureFrame(WIDTH, HEIGHT);
        Path outPath = ScreenshotCapture.saveBufferedImage(image, filename);
        System.out.printf("[CAMERA-SUITE] Rendered GL4.6 '%s' -> %s (%,d bytes)%n",
                title, outPath.getFileName(), Files.size(outPath));
        return outPath.toFile();
    }
}
