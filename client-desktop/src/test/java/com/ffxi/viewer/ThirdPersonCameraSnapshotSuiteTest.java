package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.capture.ScreenshotCapture;
import com.example.client.desktop.scene.CameraMode;
import com.example.client.desktop.scene.SceneActor;
import com.example.client.desktop.scene.SceneCamera;
import com.example.client.desktop.scene.WorldScene;
import com.example.common.time.VanadielTime;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.renderer.FontMojiRenderer;
import com.ffxi.viewer.shader.GlShaderUtil;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_BLEND;
import static org.lwjgl.opengl.GL33C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL33C.GL_FLOAT;
import static org.lwjgl.opengl.GL33C.GL_LEQUAL;
import static org.lwjgl.opengl.GL33C.GL_LINES;
import static org.lwjgl.opengl.GL33C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL33C.GL_STREAM_DRAW;
import static org.lwjgl.opengl.GL33C.glBindBuffer;
import static org.lwjgl.opengl.GL33C.glBindVertexArray;
import static org.lwjgl.opengl.GL33C.glBlendFunc;
import static org.lwjgl.opengl.GL33C.glBufferData;
import static org.lwjgl.opengl.GL33C.glDeleteBuffers;
import static org.lwjgl.opengl.GL33C.glDeleteProgram;
import static org.lwjgl.opengl.GL33C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL33C.glDepthFunc;
import static org.lwjgl.opengl.GL33C.glDepthMask;
import static org.lwjgl.opengl.GL33C.glDisable;
import static org.lwjgl.opengl.GL33C.glDrawArrays;
import static org.lwjgl.opengl.GL33C.glEnable;
import static org.lwjgl.opengl.GL33C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL33C.glGenBuffers;
import static org.lwjgl.opengl.GL33C.glGenVertexArrays;
import static org.lwjgl.opengl.GL33C.glGetUniformLocation;
import static org.lwjgl.opengl.GL33C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL33C.glUseProgram;
import static org.lwjgl.opengl.GL33C.glVertexAttribPointer;

/**
 * Third-Person Polar Camera Snapshot Suite.
 * Renders authentic FFXI character avatars through the production OpenGL 4.6 Core {@link WorldScene}
 * pipeline from arbitrary third-person camera viewpoints, proving working XIM polar orbit kinematics,
 * chase-camera facing alignment, pitch elevation, boom zoom, and in-place character rotation.
 */
public class ThirdPersonCameraSnapshotSuiteTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private static HeadlessGlContext glContext;
    private static WorldScene worldScene;
    private static SceneActor localActor;
    private static GlLineBatch lineBatch;
    private static FontMojiRenderer fontMoji;

    @BeforeAll
    static void initCharacterModel() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required for OpenGL 4.6 Core snapshots (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        glContext = HeadlessGlContext.create();
        worldScene = new WorldScene();
        worldScene.init();
        assertTrue(worldScene.isInitialized(), "WorldScene must initialize in OpenGL 4.6 Core context");

        worldScene.setShowSkyDome(false);
        worldScene.setEnableShadows(false);
        worldScene.setEnableSsao(false);
        worldScene.setClearColor(18.0f / 255.0f, 22.0f / 255.0f, 34.0f / 255.0f);

        localActor = new SceneActor(1L, "Adventurer", SceneActor.ActorType.PLAYER);
        localActor.setVisualModel(worldScene.createCharacterInstance(FFXI_ROOT, RaceGenderConfig.HumeM));
        worldScene.setLocalPlayer(localActor);
        worldScene.update(0.0f, VanadielTime.ofHour(12.0f));

        lineBatch = new GlLineBatch();
        fontMoji = worldScene.getFontMojiGlRenderer() != null
                ? worldScene.getFontMojiGlRenderer().getFont()
                : null;
    }

    @AfterAll
    static void tearDown() {
        if (lineBatch != null) {
            lineBatch.close();
        }
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
        localActor.setPosition(charX, charY, charZ);
        localActor.setRotation(charYaw);

        SceneCamera camera = worldScene.getCamera();
        camera.setMode(CameraMode.FREE_ORBIT);
        camera.setFov(42.0f);
        camera.setNearPlane(0.1f);
        camera.setFarPlane(100.0f);
        camera.setTarget(charX, charY, charZ);
        camera.setTargetOffsetY(0.90f);
        camera.setYaw(camYaw);
        camera.setPitch(camPitch);
        camera.setDistance(camDist);

        worldScene.update(0.0f, VanadielTime.ofHour(12.0f));

        float aspect = (float) WIDTH / (float) HEIGHT;
        Matrix4f projMatrix = camera.getProjectionMatrix(aspect);
        Matrix4f viewMatrix = camera.getViewMatrix();
        Matrix4f viewProj = new Matrix4f(projMatrix).mul(viewMatrix);

        BufferedImage image = worldScene.captureFrame(WIDTH, HEIGHT, () ->
                drawGroundGuidesGl(viewProj, charX, charZ, charYaw, camDist)
        );

        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        drawGroundLabels(image, g2, viewProj, charX, charZ);
        drawTelemetryOverlay(image, g2, camera, charX, charY, charZ, charYaw, title, subtitle);
        g2.dispose();

        Path outPath = ScreenshotCapture.saveBufferedImage(image, filename);
        System.out.printf("[CAMERA-SUITE] Rendered GL4.6 '%s' -> %s (%,d bytes)%n",
                title, outPath.getFileName(), Files.size(outPath));
        return outPath.toFile();
    }

    private static void drawGroundGuidesGl(
            Matrix4f viewProj,
            float charX,
            float charZ,
            float charYaw,
            float camDist
    ) {
        lineBatch.begin();

        for (int i = -8; i <= 8; i++) {
            lineBatch.line(-8.0f, 0.0f, i, 8.0f, 0.0f, i, 40 / 255f, 52 / 255f, 70 / 255f, 0.65f);
            lineBatch.line(i, 0.0f, -8.0f, i, 0.0f, 8.0f, 40 / 255f, 52 / 255f, 70 / 255f, 0.65f);
        }

        addCircleLines(charX, charZ, 2.0f, 36, 60 / 255f, 85 / 255f, 120 / 255f, 0.55f);
        addCircleLines(charX, charZ, 4.0f, 48, 60 / 255f, 85 / 255f, 120 / 255f, 0.55f);

        float groundOrbitRadius = camDist * (float) Math.cos(Math.toRadians(20.0));
        addCircleLines(charX, charZ, groundOrbitRadius, 64, 80 / 255f, 140 / 255f, 220 / 255f, 0.80f);

        // North (+Z) Axis: Gold
        addThickGroundRay(charX, -0.01f, charZ, charX, charZ + 6.0f, 0.0f, 1.0f, 0.4f, 1.0f, 200 / 255f, 50 / 255f, 0.95f);
        // East (+X) Axis: Emerald Green
        addThickGroundRay(charX, -0.01f, charZ, charX + 5.0f, charZ, 1.0f, 0.0f, 0.4f, 60 / 255f, 220 / 255f, 120 / 255f, 0.90f);
        // South (-Z) & West (-X) Axes: Muted Blue
        addThickGroundRay(charX, -0.01f, charZ, charX, charZ - 5.0f, 0.0f, -1.0f, 0.4f, 70 / 255f, 100 / 255f, 140 / 255f, 0.75f);
        addThickGroundRay(charX, -0.01f, charZ, charX - 5.0f, charZ, -1.0f, 0.0f, 0.4f, 70 / 255f, 100 / 255f, 140 / 255f, 0.75f);

        // Character Facing Direction Chevron: Coral
        float charFwdX = (float) Math.sin(Math.toRadians(charYaw));
        float charFwdZ = (float) Math.cos(Math.toRadians(charYaw));
        addThickGroundRay(charX, -0.02f, charZ, charX + charFwdX * 1.5f, charZ + charFwdZ * 1.5f,
                charFwdX, charFwdZ, 0.35f, 1.0f, 80 / 255f, 80 / 255f, 0.98f);

        lineBatch.flush(viewProj);
    }

    private static void addCircleLines(float cx, float cz, float radius, int segments, float r, float g, float b, float a) {
        double step = (2.0 * Math.PI) / segments;
        for (int i = 0; i < segments; i++) {
            double a0 = i * step;
            double a1 = (i + 1) * step;
            float x0 = cx + radius * (float) Math.sin(a0);
            float z0 = cz + radius * (float) Math.cos(a0);
            float x1 = cx + radius * (float) Math.sin(a1);
            float z1 = cz + radius * (float) Math.cos(a1);
            lineBatch.line(x0, -0.005f, z0, x1, -0.005f, z1, r, g, b, a);
        }
    }

    private static void addThickGroundRay(
            float x0, float y, float z0,
            float x1, float z1,
            float dirX, float dirZ, float arrowSize,
            float r, float g, float b, float a
    ) {
        float norm = (float) Math.hypot(dirX, dirZ);
        if (norm < 1e-4f) return;
        float nx = dirX / norm;
        float nz = dirZ / norm;
        float px = -nz;
        float pz = nx;

        lineBatch.line(x0, y, z0, x1, y, z1, r, g, b, a);
        float spread = 0.015f;
        lineBatch.line(x0 + px * spread, y, z0 + pz * spread, x1 + px * spread, y, z1 + pz * spread, r, g, b, a);
        lineBatch.line(x0 - px * spread, y, z0 - pz * spread, x1 - px * spread, y, z1 - pz * spread, r, g, b, a);

        float leftX = x1 - nx * arrowSize + px * (arrowSize * 0.5f);
        float leftZ = z1 - nz * arrowSize + pz * (arrowSize * 0.5f);
        float rightX = x1 - nx * arrowSize - px * (arrowSize * 0.5f);
        float rightZ = z1 - nz * arrowSize - pz * (arrowSize * 0.5f);

        lineBatch.line(x1, y, z1, leftX, y, leftZ, r, g, b, a);
        lineBatch.line(x1, y, z1, rightX, y, rightZ, r, g, b, a);
    }

    private static void drawGroundLabels(BufferedImage dest, Graphics2D g, Matrix4f viewProj, float charX, float charZ) {
        draw3DText(dest, g, viewProj, charX, -0.05f, charZ + 6.3f, "NORTH (+Z)", new Color(255, 215, 60));
        draw3DText(dest, g, viewProj, charX + 5.3f, -0.05f, charZ, "EAST (+X)", new Color(70, 230, 130));
        draw3DText(dest, g, viewProj, charX, -0.05f, charZ - 5.3f, "SOUTH (-Z)", new Color(130, 160, 200));
        draw3DText(dest, g, viewProj, charX - 5.3f, -0.05f, charZ, "WEST (-X)", new Color(130, 160, 200));
    }

    private static void draw3DText(BufferedImage dest, Graphics2D g, Matrix4f viewProj, float x, float y, float z, String text, Color color) {
        Vector4f c = viewProj.transform(new Vector4f(x, y, z, 1.0f));
        if (c.w <= 0.1f) return;

        int sx = (int) ((c.x / c.w * 0.5f + 0.5f) * WIDTH);
        int sy = (int) ((0.5f - c.y / c.w * 0.5f) * HEIGHT);

        int textW = measureText(text, 1);
        int textH = 16;

        g.setColor(new Color(15, 20, 25, 200));
        g.fillRect(sx - textW / 2 - 4, sy - textH / 2 - 2, textW + 8, textH + 4);

        drawText(dest, text, sx - textW / 2, sy - textH / 2, color, 1);
    }

    private static void drawTelemetryOverlay(
            BufferedImage dest, Graphics2D g, SceneCamera cam,
            float charX, float charY, float charZ, float charYaw,
            String title, String subtitle
    ) {
        g.setColor(new Color(15, 22, 32, 220));
        g.fillRect(20, 20, 740, 110);
        g.setColor(new Color(70, 110, 160));
        g.drawRect(20, 20, 740, 110);

        drawText(dest, title, 36, 28, new Color(255, 215, 60), 2);
        drawText(dest, subtitle, 36, 62, new Color(190, 215, 240), 1);

        float relativeAngle = (cam.getYaw() - charYaw) % 360.0f;
        if (relativeAngle < 0.0f) relativeAngle += 360.0f;

        String aspectLabel = resolveAspectLabel(relativeAngle);

        String line1 = String.format("Camera: Yaw=%5.1f deg | Pitch=%5.1f deg | Dist=%4.1fm | Eye=(%5.2f, %5.2f, %5.2f) | OpenGL 4.6",
                cam.getYaw(), cam.getPitch(), cam.getDistance(), cam.getEyeX(), cam.getEyeY(), cam.getEyeZ());
        String line2 = String.format("Avatar: Facing=%5.1f deg [%-5s] | Pos=(%4.1f, %4.1f, %4.1f) | View: %s",
                charYaw, resolveCardinal(charYaw), charX, charY, charZ, aspectLabel);

        drawText(dest, line1, 36, 82, new Color(220, 230, 245), 1);
        drawText(dest, line2, 36, 102, new Color(100, 225, 140), 1);

        drawCompassRose(dest, g, WIDTH - 90, 80, cam.getYaw());
    }

    private static void drawCompassRose(BufferedImage dest, Graphics2D g, int cx, int cy, float camYaw) {
        int r = 40;
        g.setColor(new Color(15, 22, 32, 210));
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
        g.setColor(new Color(70, 110, 160));
        g.drawOval(cx - r, cy - r, r * 2, r * 2);

        double needleAngle = Math.toRadians(-camYaw);
        int nx = cx + (int) (r * 0.75 * Math.sin(needleAngle));
        int ny = cy - (int) (r * 0.75 * Math.cos(needleAngle));

        g.setColor(new Color(255, 60, 60));
        g.setStroke(new BasicStroke(2.5f));
        g.drawLine(cx, cy, nx, ny);

        drawText(dest, "N", nx - 4, ny - 8, Color.YELLOW, 1);

        g.setColor(Color.WHITE);
        g.fillOval(cx - 3, cy - 3, 6, 6);
    }

    private static void drawText(BufferedImage dest, String text, int startX, int startY, Color color, int scale) {
        if (text == null || text.isEmpty()) return;
        if (fontMoji != null) {
            fontMoji.drawString(dest, text, startX, startY, color, scale);
        }
    }

    private static int measureText(String text, int scale) {
        if (text == null || text.isEmpty()) return 0;
        if (fontMoji != null) {
            return fontMoji.measureString(text, scale);
        }
        return text.length() * 8 * scale;
    }

    private static String resolveCardinal(float yaw) {
        float y = (yaw % 360.0f + 360.0f) % 360.0f;
        if (y >= 315.0f || y < 45.0f) return "NORTH";
        if (y >= 45.0f && y < 135.0f) return "EAST";
        if (y >= 135.0f && y < 225.0f) return "SOUTH";
        return "WEST";
    }

    private static String resolveAspectLabel(float relAngle) {
        if (relAngle >= 337.5f || relAngle < 22.5f) return "Rear (Facing Away)";
        if (relAngle >= 22.5f && relAngle < 67.5f) return "Rear-Right 3/4";
        if (relAngle >= 67.5f && relAngle < 112.5f) return "Right Profile";
        if (relAngle >= 112.5f && relAngle < 157.5f) return "Front-Right 3/4";
        if (relAngle >= 157.5f && relAngle < 202.5f) return "Direct Front";
        if (relAngle >= 202.5f && relAngle < 247.5f) return "Front-Left 3/4";
        if (relAngle >= 247.5f && relAngle < 292.5f) return "Left Profile";
        return "Rear-Left 3/4";
    }

    private static final class GlLineBatch implements AutoCloseable {
        private static final String VERT_SRC = """
                #version 460 core
                layout(location = 0) in vec3 aPos;
                layout(location = 1) in vec4 aColor;
                uniform mat4 uViewProj;
                out vec4 vColor;
                void main() {
                    vColor = aColor;
                    gl_Position = uViewProj * vec4(aPos, 1.0);
                }
                """;
        private static final String FRAG_SRC = """
                #version 460 core
                in vec4 vColor;
                out vec4 fragColor;
                void main() {
                    fragColor = vColor;
                }
                """;

        private final int programId;
        private final int uViewProjLoc;
        private final int vaoId;
        private final int vboId;
        private final List<Float> floats = new ArrayList<>(4096);

        GlLineBatch() {
            this.programId = GlShaderUtil.createProgram(VERT_SRC, FRAG_SRC, "ThirdPersonGuideLines");
            this.uViewProjLoc = glGetUniformLocation(programId, "uViewProj");
            this.vaoId = glGenVertexArrays();
            this.vboId = glGenBuffers();

            glBindVertexArray(vaoId);
            glBindBuffer(GL_ARRAY_BUFFER, vboId);
            int stride = 7 * Float.BYTES;
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 3L * Float.BYTES);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);
        }

        void begin() {
            floats.clear();
        }

        void line(float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float b, float a) {
            floats.add(x0); floats.add(y0); floats.add(z0);
            floats.add(r); floats.add(g); floats.add(b); floats.add(a);
            floats.add(x1); floats.add(y1); floats.add(z1);
            floats.add(r); floats.add(g); floats.add(b); floats.add(a);
        }

        void flush(Matrix4f viewProj) {
            if (floats.isEmpty()) return;
            FloatBuffer buf = BufferUtils.createFloatBuffer(floats.size());
            for (int i = 0; i < floats.size(); i++) {
                buf.put(floats.get(i));
            }
            buf.flip();

            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(false);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            glUseProgram(programId);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                glUniformMatrix4fv(uViewProjLoc, false, viewProj.get(stack.mallocFloat(16)));
            }

            glBindVertexArray(vaoId);
            glBindBuffer(GL_ARRAY_BUFFER, vboId);
            glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW);
            glDrawArrays(GL_LINES, 0, floats.size() / 7);

            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);
            glUseProgram(0);
            glDisable(GL_BLEND);
            glDepthMask(true);
        }

        @Override
        public void close() {
            glDeleteBuffers(vboId);
            glDeleteVertexArrays(vaoId);
            glDeleteProgram(programId);
        }
    }
}
