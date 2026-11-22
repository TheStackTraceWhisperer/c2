package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.capture.ScreenshotCapture;
import com.example.client.desktop.scene.CameraMode;
import com.example.client.desktop.scene.SceneActor;
import com.example.client.desktop.scene.SceneCamera;
import com.example.client.desktop.scene.WorldScene;
import com.example.common.protocol.MovementFlags;
import com.example.common.time.VanadielTime;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.environment.WeatherPreset;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.renderer.FontMojiRenderer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Composite Town Walkthrough Snapshot Suite.
 * Drives the production {@link WorldScene} OpenGL 4.6 Core pipeline (ZoneShader, CharacterShader,
 * ShadowMapRenderer, SsaoRenderer, AuthenticSkyDome, AuthenticLocomotionController, SceneCamera)
 * across Northern & Southern San d'Oria walkthrough sequences.
 */
public class TownWalkthroughCameraSnapshotSuiteTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    public record WalkthroughStep(
            String id,
            String title,
            String subtitle,
            float worldX,
            float worldZ,
            float playerYaw,          // Facing heading (0 = North/+Z, 90 = East/+X, 180 = South/-Z, 270 = West/-X)
            int movementFlags,        // 0 = IDLE, 1 = FORWARD, etc.
            float horizontalSpeed,    // 0.0 = IDLE, 2.5 = WALK, 5.0 = RUN
            float updateDt,           // Delta-time to advance animation/blending
            float camYaw,             // SceneCamera yaw
            float camPitch,           // SceneCamera pitch
            float camDist,            // SceneCamera boom zoom
            float fovDegrees
    ) {
        public WalkthroughStep(
                String id, String title, String subtitle,
                float worldX, float worldZ, float playerYaw,
                int movementFlags, float horizontalSpeed, float updateDt,
                float camYaw, float camPitch, float camDist
        ) {
            this(id, title, subtitle, worldX, worldZ, playerYaw, movementFlags, horizontalSpeed, updateDt, camYaw, camPitch, camDist, 48.0f);
        }
    }

    private static HeadlessGlContext glContext;
    private static WorldScene worldScene;

    @BeforeAll
    static void initSuite() {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required for OpenGL 4.6 Core snapshots (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        glContext = HeadlessGlContext.create();
        worldScene = new WorldScene();
        worldScene.init();
        assertTrue(worldScene.isInitialized(), "WorldScene must initialize in OpenGL 4.6 Core context");
        worldScene.setManualTimeOverride(true);
        worldScene.setManualTimeHour(12.0f);
        worldScene.setWeatherPreset(WeatherPreset.FINE);
    }

    @AfterAll
    static void tearDownSuite() {
        if (worldScene != null) {
            worldScene.close();
        }
        if (glContext != null) {
            glContext.close();
        }
    }

    @Test
    @DisplayName("Northern San d'Oria (Zone 231) walkthrough with live locomotion animation blending")
    void testNorthernSanDoriaWalkthrough() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        List<WalkthroughStep> steps = List.of(
                new WalkthroughStep(
                        "walkthrough_231_01_spawn_south_gate",
                        "Spawn at South San d'Oria Gate Portal",
                        "Adventurer materializes at the city archway. Locomotion controller initialized in authentic IDLE breathing posture.",
                        0.0f, -26.0f, 0.0f,
                        0, 0.0f, 0.0f,
                        0.0f, 22.0f, 6.0f
                ),
                new WalkthroughStep(
                        "walkthrough_231_02_blend_idle_to_walk_50",
                        "Initiating Forward Stride | IDLE to WALK Blend",
                        "Player begins walking North along Rue du Bois. Authentic cross-fade smoothly interpolates skeletal joints between IDLE and WALK.",
                        0.0f, -22.0f, 0.0f,
                        MovementFlags.FORWARD, 2.5f, 0.133f,
                        0.0f, 21.0f, 5.8f
                ),
                new WalkthroughStep(
                        "walkthrough_231_03_walk_rue_du_bois",
                        "Striding Down Rue du Bois | Full WALK Locomotion",
                        "Player advances in continuous striding walk along cobblestone boulevard. Dual-layer animation swings legs and arms naturally.",
                        0.0f, -6.0f, 0.0f,
                        MovementFlags.FORWARD, 2.5f, 0.20f,
                        0.0f, 20.0f, 5.5f
                ),
                new WalkthroughStep(
                        "walkthrough_231_04_crossroads_shoulder_orbit",
                        "Victory Square Crossroads | 3/4 Shoulder Orbit",
                        "SceneCamera swung 35 deg over player's right shoulder, tracking active walk stride against street lamps and building facades.",
                        0.0f, 16.0f, 0.0f,
                        MovementFlags.FORWARD, 2.5f, 0.20f,
                        35.0f, 18.0f, 6.0f
                ),
                new WalkthroughStep(
                        "walkthrough_231_05_blend_walk_to_idle_50",
                        "Arriving at Plaza | WALK to IDLE Deceleration",
                        "Player halts movement upon entering Victory Square. Controller dynamically cross-fades from active stride back to rest.",
                        5.0f, 32.0f, 0.0f,
                        0, 0.0f, 0.133f,
                        20.0f, 26.0f, 6.8f
                ),
                new WalkthroughStep(
                        "walkthrough_231_06_victory_square_pause",
                        "Victory Square Plaza | Settled IDLE Overview",
                        "Adventurer fully settled in authentic IDLE posture. Camera elevates to 30 deg pitch for a majestic plaza overview.",
                        5.0f, 36.0f, 0.0f,
                        0, 0.0f, 0.20f,
                        20.0f, 30.0f, 7.5f
                ),
                new WalkthroughStep(
                        "walkthrough_231_07_turn_idle_to_run_50",
                        "Turning Toward Cathedral | IDLE to RUN Sprint Blend",
                        "Player turns heading toward northeastern promenade (facing 45 deg) and sprints forward. Joints blend into athletic sprint.",
                        35.0f, 52.0f, 45.0f,
                        MovementFlags.FORWARD, 5.0f, 0.133f,
                        45.0f, 20.0f, 5.5f
                ),
                new WalkthroughStep(
                        "walkthrough_231_08_run_cathedral_promenade",
                        "Cathedral Promenade Sprint | Full Athletic RUN",
                        "Adventurer at full sprint along royal promenade. High-speed skeletal animation evaluated with authentic dual-bone weights.",
                        70.0f, 70.0f, 45.0f,
                        MovementFlags.FORWARD, 5.0f, 0.25f,
                        45.0f, 18.0f, 5.2f
                ),
                new WalkthroughStep(
                        "walkthrough_231_09_run_to_walk_50",
                        "Cathedral Approach Close-Up | RUN to WALK Transition",
                        "Close third-person action zoom (3.8m) capturing dynamic deceleration blend from sprint into walking gait.",
                        86.0f, 80.0f, 45.0f,
                        MovementFlags.FORWARD, 2.5f, 0.133f,
                        45.0f, 16.0f, 3.8f
                ),
                new WalkthroughStep(
                        "walkthrough_231_10_cathedral_steps_hero",
                        "Cathedral Grand Steps | Low Hero Angle",
                        "Player halted in IDLE at the base of the Cathedral grand steps. Low pitch (8 deg) looks upward past adventurer to the soaring spires.",
                        93.0f, 87.0f, 45.0f,
                        0, 0.0f, 0.30f,
                        45.0f, 8.0f, 5.0f
                ),
                new WalkthroughStep(
                        "walkthrough_231_11_cathedral_court_tactical",
                        "Grand Cathedral Court | Tactical Overhead View",
                        "High overhead tactical perspective (50 deg pitch, 14m distance) validating 3D environment contact and complete scene depth.",
                        93.0f, 87.0f, 45.0f,
                        0, 0.0f, 0.10f,
                        45.0f, 50.0f, 14.0f
                )
        );

        executeWalkthroughSequence(231, steps, "Northern San d'Oria (Zone 231)");
    }

    @Test
    @DisplayName("Southern San d'Oria (Zone 230) walkthrough with live locomotion animation blending")
    void testSouthernSanDoriaWalkthrough() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        List<WalkthroughStep> steps = List.of(
                new WalkthroughStep(
                        "walkthrough_230_01_spawn_ronfaure_gate",
                        "Spawn at West Ronfaure Gate | South San d'Oria",
                        "Player materializes at the city outer ramparts in IDLE posture facing South (180 deg) down King's Square Avenue.",
                        0.0f, 42.0f, 180.0f,
                        0, 0.0f, 0.0f,
                        180.0f, 22.0f, 6.0f
                ),
                new WalkthroughStep(
                        "walkthrough_230_02_blend_idle_to_walk_50",
                        "Initiating Southbound Walk | IDLE to WALK Blend",
                        "Player steps forward down the avenue. Locomotion controller smoothly cross-fades skeletal pose into walk stride.",
                        0.0f, 35.0f, 180.0f,
                        MovementFlags.FORWARD, 2.5f, 0.133f,
                        180.0f, 21.0f, 5.8f
                ),
                new WalkthroughStep(
                        "walkthrough_230_03_walk_commercial_avenue",
                        "Walking South Along King's Square Avenue",
                        "Advancing southward along high ramparts and shopfronts in full WALK locomotion with standard third-person chase camera.",
                        0.0f, 15.0f, 180.0f,
                        MovementFlags.FORWARD, 2.5f, 0.25f,
                        180.0f, 20.0f, 5.5f
                ),
                new WalkthroughStep(
                        "walkthrough_230_04_fountain_square_approach",
                        "Approaching Auction House Plaza | 3/4 Shoulder View",
                        "Player strides toward Place de Saint d'Oraguille and the central Auction House in full WALK locomotion.",
                        3.0f, -5.0f, 180.0f,
                        MovementFlags.FORWARD, 2.5f, 0.20f,
                        215.0f, 18.0f, 6.0f
                ),
                new WalkthroughStep(
                        "walkthrough_230_05_blend_walk_to_idle_50",
                        "Halting Before Auction House | WALK to IDLE Deceleration",
                        "Player decelerates along the plaza thoroughfare as the Auction House entrance approaches. Locomotion cross-fades back into resting stance.",
                        5.0f, -15.0f, 180.0f,
                        0, 0.0f, 0.133f,
                        180.0f, 16.0f, 5.6f
                ),
                new WalkthroughStep(
                        "walkthrough_230_06_grand_fountain_pause",
                        "Southern San d'Oria Auction House | Frontal Resting View",
                        "Player fully halted in authentic IDLE posture before the grand Auction House entrance, viewing the stone masonry and central plaza.",
                        5.0f, -20.0f, 180.0f,
                        0, 0.0f, 0.25f,
                        0.0f, 14.0f, 5.5f
                )
        );

        executeWalkthroughSequence(230, steps, "Southern San d'Oria (Zone 230)");
    }

    private static void executeWalkthroughSequence(int zoneId, List<WalkthroughStep> steps, String zoneTitle) throws Exception {
        if (worldScene.getCurrentZoneId() != zoneId) {
            worldScene.loadZone(FFXI_ROOT, zoneId);
        }
        LoadedZone zone = worldScene.getLoadedZone();
        assertNotNull(zone, "Zone must be loaded in WorldScene: " + zoneId);

        SceneActor localActor = new SceneActor(1L, "Adventurer", SceneActor.ActorType.PLAYER);
        localActor.setVisualModel(worldScene.createCharacterInstance(FFXI_ROOT, RaceGenderConfig.HumeM));
        worldScene.setLocalPlayer(localActor);

        int stepNum = 1;
        int totalSteps = steps.size();
        float prevFloorY = 0.0f;

        for (WalkthroughStep step : steps) {
            float floorY = prevFloorY;
            if (zone.collisionSystem() != null) {
                float castStartY = Math.min(-3.0f, prevFloorY - 4.0f);
                float hit = zone.collisionSystem().findFloorY(step.worldX, step.worldZ, castStartY);
                if (!Float.isFinite(hit)) {
                    hit = zone.collisionSystem().findFloorY(step.worldX, step.worldZ, -20.0f);
                }
                if (Float.isFinite(hit)) {
                    floorY = hit;
                } else {
                    float[] offsets = {-1.0f, 1.0f, -2.0f, 2.0f};
                    for (float ox : offsets) {
                        float h = zone.collisionSystem().findFloorY(step.worldX + ox, step.worldZ, -20.0f);
                        if (Float.isFinite(h)) {
                            floorY = h;
                            break;
                        }
                    }
                }
            }
            prevFloorY = floorY;

            localActor.setPosition(step.worldX, floorY, step.worldZ);
            localActor.setRotation(step.playerYaw);
            localActor.setMovementFlags(step.movementFlags);
            localActor.setMovementSpeed(step.horizontalSpeed);

            SceneCamera camera = worldScene.getCamera();
            camera.setMode(CameraMode.FREE_ORBIT);
            camera.setFov(step.fovDegrees);
            camera.setNearPlane(0.2f);
            camera.setFarPlane(2000.0f);
            camera.setTarget(step.worldX, floorY, step.worldZ);
            camera.setTargetOffsetY(1.0f);
            camera.setYaw(step.camYaw);
            camera.setPitch(step.camPitch);
            camera.setDistance(step.camDist);

            worldScene.update(step.updateDt, VanadielTime.ofHour(12.0f));

            AuthenticLocomotionController locomotion = localActor.getVisualModel().getLocomotion();
            System.out.printf("[WALKTHROUGH] Step %d/%d '%s' @ (%.1f, %.2f, %.1f) | Facing=%.1f deg | Locomotion: %s -> %s (Blend=%.1f%%) | Cam: Yaw=%.1f deg Pitch=%.1f deg Dist=%.1fm%n",
                    stepNum, totalSteps, step.title, step.worldX, floorY, step.worldZ, step.playerYaw,
                    locomotion.getCurrentState(), locomotion.getTargetState(), locomotion.getBlendWeight() * 100.0f,
                    step.camYaw, step.camPitch, step.camDist);

            BufferedImage frame = worldScene.captureFrame(WIDTH, HEIGHT);

            Graphics2D g2 = frame.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FontMojiRenderer fontMoji = worldScene.getFontMojiGlRenderer() != null
                    ? worldScene.getFontMojiGlRenderer().getFont()
                    : null;
            drawTelemetryOverlay(frame, g2, fontMoji, camera, locomotion, step.worldX, floorY, step.worldZ, step.playerYaw, step, stepNum, totalSteps, zoneTitle);
            g2.dispose();

            Path outPath = ScreenshotCapture.saveBufferedImage(frame, step.id);
            assertTrue(Files.isRegularFile(outPath) && Files.size(outPath) > 50000, "Walkthrough snapshot must be saved: " + outPath.getFileName());
            System.out.printf("[WALKTHROUGH] Saved GL4.6 snapshot -> %s (%,d bytes)%n", outPath.getFileName(), Files.size(outPath));
            stepNum++;
        }
    }

    private static void drawTelemetryOverlay(
            BufferedImage dest, Graphics2D g, FontMojiRenderer fontMoji, SceneCamera cam,
            AuthenticLocomotionController locomotion,
            float charX, float charY, float charZ, float charYaw,
            WalkthroughStep step, int stepNum, int totalSteps, String zoneTitle
    ) {
        g.setColor(new Color(14, 20, 30, 225));
        g.fillRect(24, 24, 1020, 130);
        g.setColor(new Color(60, 100, 155));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect(24, 24, 1020, 130);

        String mainTitle = String.format("Step %d/%d: %s | %s", stepNum, totalSteps, step.title, zoneTitle);
        drawText(dest, fontMoji, mainTitle, 40, 34, new Color(255, 215, 60), 2);
        drawText(dest, fontMoji, step.subtitle, 40, 68, new Color(190, 215, 240), 1);

        float relativeAngle = (cam.getYaw() - charYaw) % 360.0f;
        if (relativeAngle < 0.0f) relativeAngle += 360.0f;
        String aspectLabel = resolveAspectLabel(relativeAngle);

        String locomotionDesc;
        if (locomotion.isTransitioning()) {
            locomotionDesc = String.format("%s -> %s (Blend: %.0f%%)",
                    locomotion.getCurrentState(), locomotion.getTargetState(), locomotion.getBlendWeight() * 100.0f);
        } else {
            locomotionDesc = String.format("%s (100%% Active)", locomotion.getCurrentState());
        }

        String line1 = String.format("SceneCamera: Yaw=%5.1f deg | Pitch=%5.1f deg | Boom=%4.1fm | Eye=(%5.2f, %5.2f, %5.2f) | OpenGL 4.6",
                cam.getYaw(), cam.getPitch(), cam.getDistance(),
                cam.getEyeX(), cam.getEyeY(), cam.getEyeZ());

        String line2 = String.format("Avatar: Pos=(%5.1f, %5.2f, %5.1f) | Facing=%5.1f deg [%-5s] | Locomotion: %s | Aspect: %s",
                charX, charY, charZ, charYaw, resolveCardinal(charYaw), locomotionDesc, aspectLabel);

        drawText(dest, fontMoji, line1, 40, 92, new Color(225, 235, 250), 1);
        drawText(dest, fontMoji, line2, 40, 114, new Color(100, 230, 150), 1);

        drawCompassRose(dest, g, fontMoji, WIDTH - 110, 90, cam.getYaw());
    }

    private static void drawCompassRose(BufferedImage dest, Graphics2D g, FontMojiRenderer fontMoji, int cx, int cy, float camYaw) {
        int r = 45;
        g.setColor(new Color(15, 22, 32, 220));
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
        g.setColor(new Color(70, 110, 160));
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(cx - r, cy - r, r * 2, r * 2);

        double needleAngle = Math.toRadians(-camYaw);
        int nx = cx + (int) (r * 0.75 * Math.sin(needleAngle));
        int ny = cy - (int) (r * 0.75 * Math.cos(needleAngle));

        g.setColor(new Color(255, 60, 60));
        g.setStroke(new BasicStroke(2.8f));
        g.drawLine(cx, cy, nx, ny);

        drawText(dest, fontMoji, "N", nx - 4, ny - 8, Color.YELLOW, 1);

        g.setColor(Color.WHITE);
        g.fillOval(cx - 3, cy - 3, 6, 6);
    }

    private static void drawText(BufferedImage dest, FontMojiRenderer fontMoji, String text, int startX, int startY, Color color, int scale) {
        if (text == null || text.isEmpty()) return;
        if (fontMoji != null) {
            fontMoji.drawString(dest, text, startX, startY, color, scale);
        } else {
            Graphics2D g = dest.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12 * scale));
            g.setColor(Color.BLACK);
            g.drawString(text, startX + 1, startY + 12 * scale + 1);
            g.setColor(color);
            g.drawString(text, startX, startY + 12 * scale);
            g.dispose();
        }
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
}
