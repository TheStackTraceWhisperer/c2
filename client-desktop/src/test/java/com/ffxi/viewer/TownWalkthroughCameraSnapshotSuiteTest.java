package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.common.protocol.MovementFlags;
import com.ffxi.export.TextureDecoder;
import com.ffxi.model.*;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.model.ZoneMeshMath;
import com.ffxi.viewer.model.ZoneModelLoader;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import com.ffxi.viewer.renderer.FontMojiRenderer;
import com.example.client.desktop.camera.PolarCamera;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Composite Town Walkthrough Snapshot Suite.
 * Directly integrates the authentic game engine character animation system (LoadedCharacter,
 * AuthenticLocomotionController, dual-layer upper/lower body posing, and smooth cross-fade blending)
 * into iconic city maps (Northern & Southern San d'Oria).
 *
 * Simulates active locomotion transitions (IDLE -> WALK -> RUN -> WALK -> IDLE) while PolarCamera
 * follows the player in real-time, capturing multi-aspect screenshots that verify true dual-bone
 * skinning, skeletal interpolation, collision floor contact, and architectural occlusion.
 */
public class TownWalkthroughCameraSnapshotSuiteTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();
    private static final Path OUTPUT_DIR = Path.of("target/snapshots");

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    public record WalkthroughStep(
            String id,
            String title,
            String subtitle,
            float worldX,
            float worldZ,
            float playerYaw,          // Facing heading (0 = North/-Z, 90 = East/+X, 180 = South/+Z, 270 = West/-X)
            int movementFlags,        // 0 = IDLE, 1 = FORWARD, etc.
            float horizontalSpeed,    // 0.0 = IDLE, 2.5 = WALK, 5.0 = RUN
            float updateDt,           // Delta-time to advance animation/blending
            float camYaw,             // PolarCamera yaw
            float camPitch,           // PolarCamera pitch
            float camDist,            // PolarCamera boom zoom
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

    private record DecodedTextureBuffer(int width, int height, byte[] rgba) {}

    // Authentic character model and texture caches
    private static LoadedCharacter playerCharacter;
    private static Map<String, DecodedTextureBuffer> decodedCharTextures;
    private static FontMojiRenderer fontMoji;

    // Cache loaded zones across tests
    private static final Map<Integer, LoadedZone> loadedZoneCache = new HashMap<>();

    @BeforeAll
    static void initSuite() throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        // 1. Initialize Authentic FontMoji typography
        try {
            Path rom01 = FFXI_ROOT.resolve("ROM/0/1.DAT");
            if (Files.exists(rom01)) {
                fontMoji = FontMojiRenderer.load(FFXI_ROOT);
            }
        } catch (Exception e) {
            System.err.println("[WALKTHROUGH] Could not load authentic FontMojiRenderer: " + e.getMessage());
        }

        // 2. Load authentic Hume Male PC actor using real game engine loader
        if (!Files.isDirectory(FFXI_ROOT)) {
            System.err.println("[WALKTHROUGH] FFXI directory not found: " + FFXI_ROOT);
            return;
        }

        System.out.println("[WALKTHROUGH] Loading authentic Hume Male PC actor via CharacterModelLoader...");
        playerCharacter = CharacterModelLoader.loadCharacter(FFXI_ROOT, RaceGenderConfig.HumeM);
        assertNotNull(playerCharacter, "Hume Male character must be loaded");
        assertNotNull(playerCharacter.skeleton(), "Character skeleton must be present");
        assertFalse(playerCharacter.meshes().isEmpty(), "Character meshes must be present");

        // Pre-decode all authentic character equipment textures
        decodedCharTextures = new HashMap<>();
        for (Map.Entry<String, CharacterTextureLoader.TextureSource> entry : playerCharacter.textures().entrySet()) {
            String norm = CharacterTextureLoader.normalizeName(entry.getKey());
            CharacterTextureLoader.TextureSource src = entry.getValue();
            try {
                byte[] rgba = TextureDecoder.decode(src.rawData(), src.resource());
                if (rgba != null && rgba.length > 0) {
                    DecodedTextureBuffer dtb = new DecodedTextureBuffer(src.resource().width(), src.resource().height(), rgba);
                    decodedCharTextures.putIfAbsent(norm, dtb);
                    if (norm.contains(" ")) {
                        String shortName = norm.substring(norm.lastIndexOf(' ') + 1);
                        decodedCharTextures.putIfAbsent(shortName, dtb);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        System.out.printf("[WALKTHROUGH] Character loaded: %d meshes, %d textures, %d joints, %d slot0 anims, %d slot1 anims%n",
                playerCharacter.meshes().size(), decodedCharTextures.size(),
                playerCharacter.skeleton().joints().size(),
                playerCharacter.slot0Animations().size(), playerCharacter.slot1Animations().size());
    }

    @Test
    @DisplayName("Northern San d'Oria (Zone 231) walkthrough with live locomotion animation blending")
    void testNorthernSanDoriaWalkthrough() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);
        assumeTrue(playerCharacter != null, "Character model must be initialized");

        int zoneId = 231;
        LoadedZone zone = getOrLoadZone(zoneId);
        assertNotNull(zone, "Northern San d'Oria must be loaded");

        // Build continuous sequence of steps demonstrating locomotion blending:
        // IDLE (gate) -> Blending to WALK (50%) -> Full WALK (Rue du Bois) -> WALK Stride 2 ->
        // Blending to IDLE (50%) -> Settled IDLE (Victory Square) -> Blending to RUN (50%) ->
        // Full RUN (Promenade) -> Blending to WALK (50%) -> Settled IDLE (Cathedral) -> Tactical Overview
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
                        "Initiating Forward Stride | 50% IDLE to WALK Blend",
                        "Player begins walking North along Rue du Bois. Authentic cross-fade smoothly interpolates skeletal joints 50% between IDLE and WALK.",
                        0.0f, -22.0f, 0.0f,
                        MovementFlags.FORWARD, 2.5f, 0.075f, // half transition (0.075s of 0.150s duration)
                        0.0f, 21.0f, 5.8f
                ),
                new WalkthroughStep(
                        "walkthrough_231_03_walk_rue_du_bois",
                        "Striding Down Rue du Bois | Full WALK Locomotion",
                        "Player advances in continuous striding walk along cobblestone boulevard. Dual-layer animation swings legs and arms naturally.",
                        0.0f, -6.0f, 0.0f,
                        MovementFlags.FORWARD, 2.5f, 0.15f,  // full walk stride
                        0.0f, 20.0f, 5.5f
                ),
                new WalkthroughStep(
                        "walkthrough_231_04_crossroads_shoulder_orbit",
                        "Victory Square Crossroads | 3/4 Shoulder Orbit",
                        "PolarCamera swung 35° over player's right shoulder, tracking active walk stride against street lamps and building facades.",
                        0.0f, 16.0f, 0.0f,
                        MovementFlags.FORWARD, 2.5f, 0.20f,  // advancing walk stride cycle
                        35.0f, 18.0f, 6.0f
                ),
                new WalkthroughStep(
                        "walkthrough_231_05_blend_walk_to_idle_50",
                        "Arriving at Plaza | 50% WALK to IDLE Deceleration",
                        "Player halts movement upon entering Victory Square. Controller dynamically cross-fades 50% from active stride back to rest.",
                        -5.0f, 32.0f, 0.0f,
                        0, 0.0f, 0.075f,                    // 50% decelerate cross-fade
                        20.0f, 26.0f, 6.8f
                ),
                new WalkthroughStep(
                        "walkthrough_231_06_victory_square_pause",
                        "Victory Square Plaza | Settled IDLE Overview",
                        "Adventurer fully settled in authentic IDLE posture. Camera elevates to 30° pitch for a majestic plaza overview.",
                        -5.0f, 36.0f, 0.0f,
                        0, 0.0f, 0.15f,                     // fully settled in idle
                        20.0f, 30.0f, 7.5f
                ),
                new WalkthroughStep(
                        "walkthrough_231_07_turn_idle_to_run_50",
                        "Turning Toward Cathedral | 50% IDLE to RUN Sprint Blend",
                        "Player turns heading toward western promenade (facing 300°) and sprints forward. Joints blend 50% into athletic sprint.",
                        -35.0f, 55.0f, 300.0f,
                        MovementFlags.FORWARD, 5.0f, 0.075f, // 50% blend to run
                        300.0f, 20.0f, 5.5f
                ),
                new WalkthroughStep(
                        "walkthrough_231_08_run_cathedral_promenade",
                        "Cathedral Promenade Sprint | Full Athletic RUN",
                        "Adventurer at full sprint along royal promenade. High-speed skeletal animation evaluated with authentic dual-bone weights.",
                        -70.0f, 70.0f, 295.0f,
                        MovementFlags.FORWARD, 5.0f, 0.20f,
                        295.0f, 18.0f, 5.2f
                ),
                new WalkthroughStep(
                        "walkthrough_231_09_run_to_walk_50",
                        "Cathedral Approach Close-Up | 50% RUN to WALK Transition",
                        "Close third-person action zoom (3.8m) capturing dynamic 50% deceleration blend from sprint into walking gait.",
                        -95.0f, 85.0f, 290.0f,
                        MovementFlags.FORWARD, 2.5f, 0.075f, // blend run -> walk
                        290.0f, 16.0f, 3.8f
                ),
                new WalkthroughStep(
                        "walkthrough_231_10_cathedral_steps_hero",
                        "Cathedral Grand Steps | Low Hero Angle",
                        "Player halted in IDLE at the base of the Cathedral grand steps. Low pitch (8°) looks upward past adventurer to the soaring spires.",
                        -115.0f, 95.0f, 285.0f,
                        0, 0.0f, 0.20f,                     // settle in idle
                        285.0f, 8.0f, 5.0f
                ),
                new WalkthroughStep(
                        "walkthrough_231_11_cathedral_court_tactical",
                        "Grand Cathedral Court | Tactical Overhead View",
                        "High overhead tactical perspective (55° pitch, 14m distance) validating 3D environment contact and complete scene depth.",
                        -115.0f, 95.0f, 285.0f,
                        0, 0.0f, 0.10f,
                        135.0f, 55.0f, 14.0f
                )
        );

        executeWalkthroughSequence(zone, steps, "Northern San d'Oria (Zone 231)");
    }

    @Test
    @DisplayName("Southern San d'Oria (Zone 230) walkthrough with live locomotion animation blending")
    void testSouthernSanDoriaWalkthrough() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);
        assumeTrue(playerCharacter != null, "Character model must be initialized");

        int zoneId = 230;
        LoadedZone zone = getOrLoadZone(zoneId);
        assertNotNull(zone, "Southern San d'Oria must be loaded");

        List<WalkthroughStep> steps = List.of(
                new WalkthroughStep(
                        "walkthrough_230_01_spawn_ronfaure_gate",
                        "Spawn at West Ronfaure Gate | South San d'Oria",
                        "Player materializes at the city outer ramparts in IDLE posture facing South (180°) down King's Square Avenue.",
                        0.0f, 42.0f, 180.0f,
                        0, 0.0f, 0.0f,
                        180.0f, 22.0f, 6.0f
                ),
                new WalkthroughStep(
                        "walkthrough_230_02_blend_idle_to_walk_50",
                        "Initiating Southbound Walk | 50% IDLE to WALK Blend",
                        "Player steps forward down the avenue. Locomotion controller smoothly cross-fades skeletal pose 50% into walk stride.",
                        0.0f, 35.0f, 180.0f,
                        MovementFlags.FORWARD, 2.5f, 0.075f,
                        180.0f, 21.0f, 5.8f
                ),
                new WalkthroughStep(
                        "walkthrough_230_03_walk_commercial_avenue",
                        "Walking South Along King's Square Avenue",
                        "Advancing southward along high ramparts and shopfronts in full WALK locomotion with standard third-person chase camera.",
                        0.0f, 15.0f, 180.0f,
                        MovementFlags.FORWARD, 2.5f, 0.20f,
                        180.0f, 20.0f, 5.5f
                ),
                new WalkthroughStep(
                        "walkthrough_230_04_fountain_square_approach",
                        "Approaching Fountain Square | 3/4 Shoulder View",
                        "Player strides toward the grand plaza. PolarCamera orbits to 215° yaw over left shoulder showing continuous walking stride.",
                        -5.0f, -12.0f, 180.0f,
                        MovementFlags.FORWARD, 2.5f, 0.20f,
                        215.0f, 18.0f, 6.0f
                ),
                new WalkthroughStep(
                        "walkthrough_230_05_blend_walk_to_idle_50",
                        "Halting Before Fountain | 50% WALK to IDLE Deceleration",
                        "Player releases movement as the fountain comes into view. Locomotion cross-fades 50% back into resting stance.",
                        -5.0f, -22.0f, 0.0f,
                        0, 0.0f, 0.075f,
                        0.0f, 16.0f, 5.6f
                ),
                new WalkthroughStep(
                        "walkthrough_230_06_grand_fountain_pause",
                        "Grand Fountain of San d'Oria | Frontal Resting View",
                        "Player fully halted in authentic IDLE posture before the iconic fountain, viewing cascading waters and arched architecture.",
                        -5.0f, -26.0f, 0.0f,
                        0, 0.0f, 0.15f,
                        0.0f, 14.0f, 5.5f
                )
        );

        executeWalkthroughSequence(zone, steps, "Southern San d'Oria (Zone 230)");
    }

    private static void executeWalkthroughSequence(LoadedZone zone, List<WalkthroughStep> steps, String zoneTitle) throws Exception {
        Map<String, DecodedTextureBuffer> decodedZoneTextures = decodeAllZoneTextures(zone);

        // Instantiate live AuthenticLocomotionController with real skeleton and motion DATs
        AuthenticLocomotionController locomotion = new AuthenticLocomotionController(
                playerCharacter.skeleton(),
                playerCharacter.slot0Animations(),
                playerCharacter.slot1Animations()
        );

        PolarCamera camera = new PolarCamera();

        int stepNum = 1;
        int totalSteps = steps.size();

        for (WalkthroughStep step : steps) {
            // 1. Advance authentic locomotion controller time and input flags
            locomotion.update(step.updateDt, step.movementFlags, step.horizontalSpeed);

            // 2. Query authentic floor elevation from zone collision mesh
            float floorY = 0.0f;
            if (zone.collisionSystem() != null) {
                float hit = zone.collisionSystem().findFloorY(step.worldX, step.worldZ, 200.0f);
                if (Float.isFinite(hit)) {
                    floorY = hit;
                } else {
                    float[] offsets = {-1.0f, 1.0f, -2.0f, 2.0f};
                    for (float ox : offsets) {
                        float h = zone.collisionSystem().findFloorY(step.worldX + ox, step.worldZ, 200.0f);
                        if (Float.isFinite(h)) {
                            floorY = h;
                            break;
                        }
                    }
                }
            }

            // 3. Drive the actual player controller PolarCamera system
            camera.setTarget(step.worldX, floorY, step.worldZ);
            camera.setTargetOffsetY(1.0f); // Center focus on character chest/torso
            camera.setYaw(step.camYaw);
            camera.setPitch(step.camPitch);
            camera.setDistance(step.camDist);

            System.out.printf("[WALKTHROUGH] Step %d/%d '%s' @ (%.1f, %.2f, %.1f) | Facing=%.1f° | Locomotion: %s -> %s (Blend=%.1f%%) | Cam: Yaw=%.1f° Pitch=%.1f° Dist=%.1fm%n",
                    stepNum, totalSteps, step.title, step.worldX, floorY, step.worldZ, step.playerYaw,
                    locomotion.getCurrentState(), locomotion.getTargetState(), locomotion.getBlendWeight() * 100.0f,
                    step.camYaw, step.camPitch, step.camDist);

            // 4. Render Composite Frame with live dynamically computed skeletal skinning
            BufferedImage frame = renderCompositeFrame(
                    zone, decodedZoneTextures, camera, locomotion,
                    step.worldX, floorY, step.worldZ, step.playerYaw,
                    step, stepNum, totalSteps, zoneTitle
            );

            // 5. Save snapshot to target/snapshots/
            File outFile = OUTPUT_DIR.resolve(step.id + ".png").toFile();
            ImageIO.write(frame, "PNG", outFile);

            assertTrue(outFile.exists() && outFile.length() > 50000, "Walkthrough snapshot must be saved: " + outFile.getName());
            System.out.printf("[WALKTHROUGH] Saved snapshot -> %s (%,d bytes)%n", outFile.getName(), outFile.length());
            stepNum++;
        }
    }

    private static BufferedImage renderCompositeFrame(
            LoadedZone zone, Map<String, DecodedTextureBuffer> decodedZoneTextures, PolarCamera camera,
            AuthenticLocomotionController locomotion,
            float charX, float charY, float charZ, float charYaw,
            WalkthroughStep step, int stepNum, int totalSteps, String zoneTitle
    ) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        float[] zBuffer = new float[WIDTH * HEIGHT];
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);

        // 1. Draw atmospheric sky gradient
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Color skyZenith = new Color(30, 60, 115);
        Color skyHorizon = new Color(175, 205, 235);
        for (int y = 0; y < HEIGHT; y++) {
            float ratio = (float) y / HEIGHT;
            int r = (int) (skyZenith.getRed() + ratio * (skyHorizon.getRed() - skyZenith.getRed()));
            int gr = (int) (skyZenith.getGreen() + ratio * (skyHorizon.getGreen() - skyZenith.getGreen()));
            int b = (int) (skyZenith.getBlue() + ratio * (skyHorizon.getBlue() - skyZenith.getBlue()));
            g2.setColor(new Color(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, gr)), Math.max(0, Math.min(255, b))));
            g2.drawLine(0, y, WIDTH, y);
        }

        // 2. Perspective Projection & View Matrix from PolarCamera
        Matrix4f proj = new Matrix4f().perspective(
                (float) Math.toRadians(step.fovDegrees),
                (float) WIDTH / HEIGHT,
                0.2f, 2000.0f
        );

        Matrix4f view = new Matrix4f().lookAt(
                new Vector3f(camera.getEyeX(), camera.getEyeY(), camera.getEyeZ()),
                new Vector3f(camera.getTargetX(), camera.getTargetY() + camera.getTargetOffsetY(), camera.getTargetZ()),
                new Vector3f(0.0f, 1.0f, 0.0f)
        );

        Matrix4f viewProj = new Matrix4f(proj).mul(view);

        int[] pixels = new int[WIDTH * HEIGHT];
        image.getRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);

        // 3. Rasterize Opaque Zone Architecture (pass 0)
        rasterizeZoneGeometry(zone, decodedZoneTextures, viewProj, pixels, zBuffer, false);

        // 4. Rasterize Player Character with DYNAMIC joint matrices from AuthenticLocomotionController
        Matrix4f[] jointMatrices = locomotion.computePoseMatrices();
        rasterizeDynamicCharacter(pixels, zBuffer, viewProj, jointMatrices, charX, charY, charZ, charYaw);

        // 5. Rasterize Alpha-Blended Zone Geometry (pass 1: glass, water, banners)
        rasterizeZoneGeometry(zone, decodedZoneTextures, viewProj, pixels, zBuffer, true);

        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);

        // 6. Draw Professional HUD Telemetry Overlay with live locomotion blend state
        drawTelemetryOverlay(image, g2, camera, locomotion, charX, charY, charZ, charYaw, step, stepNum, totalSteps, zoneTitle);
        g2.dispose();

        return image;
    }

    private static void rasterizeDynamicCharacter(
            int[] pixels, float[] zBuffer, Matrix4f viewProj,
            Matrix4f[] jointMatrices,
            float charX, float charY, float charZ, float charYaw
    ) {
        if (playerCharacter == null || playerCharacter.meshes().isEmpty()) return;

        // Matches AuthenticCharacterInstance charModelMat exactly
        Matrix4f charModelMat = new Matrix4f()
                .scale(1f, -1f, -1f)
                .translate(playerCharacter.modelOffset())
                .translate(charX, -playerCharacter.feetOffsetY() - charY, -charZ)
                .rotateY((float) Math.toRadians(charYaw));

        Vector4f scratch = new Vector4f();
        Vector4f accum = new Vector4f();
        Vector4f worldPos = new Vector4f();
        Vector4f clipPos = new Vector4f();

        for (SkeletonMeshMesh mesh : playerCharacter.meshes()) {
            DecodedTextureBuffer tex = decodedCharTextures.get(CharacterTextureLoader.normalizeName(mesh.textureName()));
            if (tex == null && mesh.textureName() != null && !mesh.textureName().isBlank()) {
                String norm = CharacterTextureLoader.normalizeName(mesh.textureName());
                for (Map.Entry<String, DecodedTextureBuffer> e : decodedCharTextures.entrySet()) {
                    if (e.getKey().endsWith(" " + norm) || e.getKey().startsWith(norm + " ") || e.getKey().contains(norm)) {
                        tex = e.getValue();
                        break;
                    }
                }
            }

            ExpandedMesh expanded = expandToTriangleList(mesh);
            int triVertCount = expanded.count();
            if (triVertCount < 3) continue;

            List<SkeletonMeshFaceVertex> srcFace = mesh.vertices();
            List<SkeletonMeshVertex> srcResolved = mesh.resolvedVertices();

            float[] sx = new float[triVertCount];
            float[] sy = new float[triVertCount];
            float[] sz = new float[triVertCount];
            float[] sw = new float[triVertCount];
            boolean[] valid = new boolean[triVertCount];

            for (int i = 0; i < triVertCount; i++) {
                SkeletonMeshVertex vertex = srcResolved.get(expanded.resolIdx()[i]);
                Vector3f modelPos = computeSkinnedPosition(vertex, jointMatrices, accum, scratch);

                worldPos.set(modelPos.x(), modelPos.y(), modelPos.z(), 1.0f);
                charModelMat.transform(worldPos);

                clipPos.set(worldPos);
                viewProj.transform(clipPos);

                if (clipPos.w > 0.1f) {
                    float invW = 1.0f / clipPos.w;
                    sx[i] = (clipPos.x * invW * 0.5f + 0.5f) * WIDTH;
                    sy[i] = (0.5f - clipPos.y * invW * 0.5f) * HEIGHT;
                    sz[i] = clipPos.z * invW;
                    sw[i] = clipPos.w;
                    valid[i] = true;
                } else {
                    valid[i] = false;
                }
            }

            for (int t = 0; t + 2 < triVertCount; t += 3) {
                if (!valid[t] || !valid[t + 1] || !valid[t + 2]) continue;

                float x0 = sx[t], y0 = sy[t], z0 = sz[t], w0 = sw[t];
                float x1 = sx[t + 1], y1 = sy[t + 1], z1 = sz[t + 1], w1 = sw[t + 1];
                float x2 = sx[t + 2], y2 = sy[t + 2], z2 = sz[t + 2], w2 = sw[t + 2];

                float cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
                if (!mesh.mirrored() && cross >= 0.0f) continue;
                if (mesh.mirrored() && cross <= 0.0f) continue;

                SkeletonMeshFaceVertex f0 = srcFace.get(expanded.faceIdx()[t]);
                SkeletonMeshFaceVertex f1 = srcFace.get(expanded.faceIdx()[t + 1]);
                SkeletonMeshFaceVertex f2 = srcFace.get(expanded.faceIdx()[t + 2]);

                rasterizeCharTriangle(
                        pixels, zBuffer, WIDTH, HEIGHT,
                        x0, y0, z0, w0, f0.u(), f0.v(),
                        x1, y1, z1, w1, f1.u(), f1.v(),
                        x2, y2, z2, w2, f2.u(), f2.v(),
                        tex
                );
            }
        }
    }

    private static Vector3f computeSkinnedPosition(
            SkeletonMeshVertex vertex,
            Matrix4f[] jointMatrices,
            Vector4f accum,
            Vector4f scratch
    ) {
        float joint0Weight = vertex.joint0Weight();
        float joint1Weight = vertex.joint1Weight();
        if (joint1Weight <= 0.0f) {
            joint0Weight = 1.0f;
            joint1Weight = 0.0f;
        } else {
            float total = joint0Weight + joint1Weight;
            if (total > 0.0f && Math.abs(total - 1.0f) > 1.0e-6f) {
                joint0Weight /= total;
                joint1Weight /= total;
            }
        }

        accum.set(0.0f, 0.0f, 0.0f, 0.0f);
        transformJoint(jointMatrices, vertex.jointIndex0(), vertex.p0(), joint0Weight, scratch);
        accum.add(scratch);
        transformJoint(jointMatrices, vertex.jointIndex1(), vertex.p1(), joint1Weight, scratch);
        accum.add(scratch);
        return new Vector3f(accum.x, accum.y, accum.z);
    }

    private static void transformJoint(
            Matrix4f[] jointMatrices,
            Integer jointIndex,
            Vec3f position,
            float weight,
            Vector4f dest
    ) {
        if (position == null || weight == 0.0f) {
            dest.set(0.0f, 0.0f, 0.0f, 0.0f);
            return;
        }
        int index = jointIndex == null ? 0 : Math.max(0, Math.min(jointMatrices.length - 1, jointIndex));
        jointMatrices[index].transform(position.x(), position.y(), position.z(), weight, dest);
    }

    private static void rasterizeCharTriangle(
            int[] pixels, float[] zBuffer, int width, int height,
            float x0, float y0, float z0, float w0, float u0, float v0,
            float x1, float y1, float z1, float w1, float u1, float v1,
            float x2, float y2, float z2, float w2, float u2, float v2,
            DecodedTextureBuffer texture
    ) {
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));

        float denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(denom) < 1e-5f) return;

        float invW0 = 1.0f / w0;
        float invW1 = 1.0f / w1;
        float invW2 = 1.0f / w2;

        float u0W = u0 * invW0, v0W = v0 * invW0;
        float u1W = u1 * invW1, v1W = v1 * invW1;
        float u2W = u2 * invW2, v2W = v2 * invW2;

        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;

                float b0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom;
                float b1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom;
                float b2 = 1.0f - b0 - b1;

                if (b0 >= 0f && b1 >= 0f && b2 >= 0f) {
                    float z = b0 * z0 + b1 * z1 + b2 * z2;
                    int idx = y * width + x;

                    if (z < zBuffer[idx]) {
                        float interpInvW = b0 * invW0 + b1 * invW1 + b2 * invW2;
                        float w = 1.0f / interpInvW;

                        float u = (b0 * u0W + b1 * u1W + b2 * u2W) * w;
                        float v = (b0 * v0W + b1 * v1W + b2 * v2W) * w;

                        int color = sampleCharTexture(texture, u, v);
                        if ((color >>> 24) > 64) {
                            pixels[idx] = color;
                            zBuffer[idx] = z;
                        }
                    }
                }
            }
        }
    }

    private static int sampleCharTexture(DecodedTextureBuffer texture, float u, float v) {
        if (texture == null) return 0xFFCCCCCC;

        int tw = texture.width();
        int th = texture.height();
        if (tw <= 0 || th <= 0) return 0xFFCCCCCC;

        float uFrac = u - (float) Math.floor(u);
        float vFrac = v - (float) Math.floor(v);

        int tx = Math.min(tw - 1, Math.max(0, (int) (uFrac * tw)));
        int ty = Math.min(th - 1, Math.max(0, (int) (vFrac * th)));

        int byteOffset = (ty * tw + tx) * 4;
        byte[] rgba = texture.rgba();
        if (byteOffset + 3 >= rgba.length) return 0xFFCCCCCC;

        int a = rgba[byteOffset + 3] & 0xFF;
        if (a < 64) return 0;

        int r = rgba[byteOffset] & 0xFF;
        int g = rgba[byteOffset + 1] & 0xFF;
        int b = rgba[byteOffset + 2] & 0xFF;

        return (255 << 24) | (r << 16) | (g << 8) | b;
    }

    private static void rasterizeZoneGeometry(
            LoadedZone zone, Map<String, DecodedTextureBuffer> decodedTextures, Matrix4f viewProj,
            int[] pixels, float[] zBuffer, boolean blendPass
    ) {
        for (LoadedZone.ZoneChunkInstance inst : zone.chunkInstances()) {
            ZoneMeshChunk chunk = inst.chunk();
            if (chunk.renderBlend() != blendPass) continue;
            if (!ZoneMeshMath.hasValidVertices(chunk)) continue;

            List<Integer> triangles = ZoneMeshMath.expandTriangles(chunk);
            if (triangles.isEmpty()) continue;

            List<ZoneMeshVertex> verts = chunk.vertices();
            Matrix4f modelMatrix = inst.modelMatrix();
            DecodedTextureBuffer tex = decodedTextures.get(normalizeName(chunk.textureName()));
            if (tex == null && chunk.textureName() != null && !chunk.textureName().isBlank()) {
                String norm = normalizeName(chunk.textureName());
                for (Map.Entry<String, DecodedTextureBuffer> e : decodedTextures.entrySet()) {
                    if (e.getKey().endsWith(" " + norm) || e.getKey().startsWith(norm + " ") || e.getKey().contains(norm)) {
                        tex = e.getValue();
                        break;
                    }
                }
            }

            float discardThresh = inst.discardThreshold();
            boolean invertCull = inst.invertCull();
            boolean mirrored = modelMatrix.determinant() < 0.0f;

            int numV = verts.size();
            float[] sx = new float[numV];
            float[] sy = new float[numV];
            float[] clipW = new float[numV];
            float[] clipZ = new float[numV];
            boolean[] valid = new boolean[numV];

            for (int i = 0; i < numV; i++) {
                ZoneMeshVertex v = verts.get(i);
                Vector4f p = new Vector4f(
                        ZoneMeshMath.sanitizeFinite(-v.px()),
                        ZoneMeshMath.sanitizeFinite(-v.py()),
                        ZoneMeshMath.sanitizeFinite(v.pz()),
                        1.0f
                );
                modelMatrix.transform(p);
                viewProj.transform(p);

                clipW[i] = p.w;
                clipZ[i] = p.z / p.w;

                if (p.w > 0.1f) {
                    sx[i] = (p.x / p.w * 0.5f + 0.5f) * WIDTH;
                    sy[i] = (0.5f - p.y / p.w * 0.5f) * HEIGHT;
                    valid[i] = true;
                } else {
                    valid[i] = false;
                }
            }

            for (int t = 0; t + 2 < triangles.size(); t += 3) {
                int i0 = triangles.get(t);
                int i1 = triangles.get(t + 1);
                int i2 = triangles.get(t + 2);

                if (!valid[i0] || !valid[i1] || !valid[i2]) continue;

                float x0 = sx[i0], y0 = sy[i0], w0 = clipW[i0], z0 = clipZ[i0];
                float x1 = sx[i1], y1 = sy[i1], w1 = clipW[i1], z1 = clipZ[i1];
                float x2 = sx[i2], y2 = sy[i2], w2 = clipW[i2], z2 = clipZ[i2];

                float cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
                if (!invertCull) {
                    if (!mirrored && cross >= 0.0f) continue;
                    if (mirrored && cross <= 0.0f) continue;
                }

                float minXF = Math.min(x0, Math.min(x1, x2));
                float maxXF = Math.max(x0, Math.max(x1, x2));
                float minYF = Math.min(y0, Math.min(y1, y2));
                float maxYF = Math.max(y0, Math.max(y1, y2));

                if (maxXF < 0f || minXF >= WIDTH || maxYF < 0f || minYF >= HEIGHT) continue;

                ZoneMeshVertex v0 = verts.get(i0);
                ZoneMeshVertex v1 = verts.get(i1);
                ZoneMeshVertex v2 = verts.get(i2);

                rasterizeZoneTriangle(
                        pixels, zBuffer, WIDTH, HEIGHT,
                        x0, y0, z0, w0, v0.u(), v0.v(), v0.colorR(), v0.colorG(), v0.colorB(), v0.colorA(),
                        x1, y1, z1, w1, v1.u(), v1.v(), v1.colorR(), v1.colorG(), v1.colorB(), v1.colorA(),
                        x2, y2, z2, w2, v2.u(), v2.v(), v2.colorR(), v2.colorG(), v2.colorB(), v2.colorA(),
                        tex, discardThresh, blendPass
                );
            }
        }
    }

    private static void rasterizeZoneTriangle(
            int[] pixels, float[] zBuffer, int width, int height,
            float x0, float y0, float z0, float w0, float u0, float v0, int cr0, int cg0, int cb0, int ca0,
            float x1, float y1, float z1, float w1, float u1, float v1, int cr1, int cg1, int cb1, int ca1,
            float x2, float y2, float z2, float w2, float u2, float v2, int cr2, int cg2, int cb2, int ca2,
            DecodedTextureBuffer tex, float discardThresh, boolean blendPass
    ) {
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));

        float denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(denom) < 1e-5f) return;

        float invW0 = 1.0f / w0;
        float invW1 = 1.0f / w1;
        float invW2 = 1.0f / w2;

        float u0W = u0 * invW0, v0W = v0 * invW0;
        float u1W = u1 * invW1, v1W = v1 * invW1;
        float u2W = u2 * invW2, v2W = v2 * invW2;

        float r0W = (cr0 / 255.0f) * invW0, g0W = (cg0 / 255.0f) * invW0, b0W = (cb0 / 255.0f) * invW0;
        float r1W = (cr1 / 255.0f) * invW1, g1W = (cg1 / 255.0f) * invW1, b1W = (cb1 / 255.0f) * invW1;
        float r2W = (cr2 / 255.0f) * invW2, g2W = (cg2 / 255.0f) * invW2, b2W = (cb2 / 255.0f) * invW2;

        float a0W = (ca0 / 255.0f) * invW0;
        float a1W = (ca1 / 255.0f) * invW1;
        float a2W = (ca2 / 255.0f) * invW2;

        boolean useAlphaDiscard = discardThresh > 0.0f;
        int alphaThresholdByte = Math.max(1, (int) (discardThresh * 255.0f));

        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;

                float b0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom;
                float b1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom;
                float b2 = 1.0f - b0 - b1;

                if (b0 >= 0f && b1 >= 0f && b2 >= 0f) {
                    float z = b0 * z0 + b1 * z1 + b2 * z2;
                    int idx = y * width + x;

                    if (z < zBuffer[idx]) {
                        float interpInvW = b0 * invW0 + b1 * invW1 + b2 * invW2;
                        float w = 1.0f / interpInvW;

                        float u = (b0 * u0W + b1 * u1W + b2 * u2W) * w;
                        float v = (b0 * v0W + b1 * v1W + b2 * v2W) * w;

                        float vr = (b0 * r0W + b1 * r1W + b2 * r2W) * w;
                        float vg = (b0 * g0W + b1 * g1W + b2 * g2W) * w;
                        float vb = (b0 * b0W + b1 * b1W + b2 * b2W) * w;

                        if (vr < 0.01f && vg < 0.01f && vb < 0.01f) {
                            vr = 0.5f; vg = 0.5f; vb = 0.5f;
                        }

                        float va = (b0 * a0W + b1 * a1W + b2 * a2W) * w;
                        if (va <= 0.001f && ca0 == 0 && ca1 == 0 && ca2 == 0) {
                            va = 1.0f;
                        }

                        int r, g, b, a;
                        if (tex != null && tex.rgba() != null && tex.rgba().length > 0) {
                            int tw = tex.width();
                            int th = tex.height();
                            int tx = Math.floorMod((int) (u * tw), tw);
                            int ty = Math.floorMod((int) (v * th), th);
                            int texIdx = (ty * tw + tx) * 4;

                            int tr = tex.rgba()[texIdx] & 0xFF;
                            int tg = tex.rgba()[texIdx + 1] & 0xFF;
                            int tb = tex.rgba()[texIdx + 2] & 0xFF;
                            int ta = tex.rgba()[texIdx + 3] & 0xFF;

                            if (useAlphaDiscard && ta < alphaThresholdByte) {
                                continue;
                            }

                            r = Math.min(255, (int) (tr * vr * 2.0f));
                            g = Math.min(255, (int) (tg * vg * 2.0f));
                            b = Math.min(255, (int) (tb * vb * 2.0f));
                            a = (int) (ta * va);
                        } else {
                            r = Math.min(255, (int) (vr * 255.0f * 1.5f));
                            g = Math.min(255, (int) (vg * 255.0f * 1.5f));
                            b = Math.min(255, (int) (vb * 255.0f * 1.5f));
                            a = (int) (va * 255.0f);
                        }

                        if (!blendPass) {
                            pixels[idx] = (255 << 24) | (r << 16) | (g << 8) | b;
                            zBuffer[idx] = z;
                        } else {
                            if (a > 5) {
                                float alpha = a / 255.0f;
                                int bg = pixels[idx];
                                int bgr = (bg >> 16) & 0xFF;
                                int bgg = (bg >> 8) & 0xFF;
                                int bgb = bg & 0xFF;

                                int outR = (int) (r * alpha + bgr * (1.0f - alpha));
                                int outG = (int) (g * alpha + bgg * (1.0f - alpha));
                                int outB = (int) (b * alpha + bgb * (1.0f - alpha));

                                pixels[idx] = (255 << 24) | (outR << 16) | (outG << 8) | outB;
                            }
                        }
                    }
                }
            }
        }
    }

    private static void drawTelemetryOverlay(
            BufferedImage dest, Graphics2D g, PolarCamera cam,
            AuthenticLocomotionController locomotion,
            float charX, float charY, float charZ, float charYaw,
            WalkthroughStep step, int stepNum, int totalSteps, String zoneTitle
    ) {
        // Semi-transparent HUD Header Bar
        g.setColor(new Color(14, 20, 30, 225));
        g.fillRect(24, 24, 1020, 130);
        g.setColor(new Color(60, 100, 155));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect(24, 24, 1020, 130);

        // Header Title & Narrative
        String mainTitle = String.format("Step %d/%d: %s | %s", stepNum, totalSteps, step.title, zoneTitle);
        drawText(dest, mainTitle, 40, 34, new Color(255, 215, 60), 2);
        drawText(dest, step.subtitle, 40, 68, new Color(190, 215, 240), 1);

        // Readout Lines
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

        String line1 = String.format("PolarCamera: Yaw=%5.1f° | Pitch=%5.1f° | Boom=%4.1fm | Eye=(%5.2f, %5.2f, %5.2f)",
                cam.getYaw(), cam.getPitch(), cam.getDistance(),
                cam.getEyeX(), cam.getEyeY(), cam.getEyeZ());

        String line2 = String.format("Avatar: Pos=(%5.1f, %5.2f, %5.1f) | Facing=%5.1f° [%-5s] | Locomotion: %s | Aspect: %s",
                charX, charY, charZ, charYaw, resolveCardinal(charYaw), locomotionDesc, aspectLabel);

        drawText(dest, line1, 40, 92, new Color(225, 235, 250), 1);
        drawText(dest, line2, 40, 114, new Color(100, 230, 150), 1);

        // Compass Rose in top-right corner
        drawCompassRose(dest, g, WIDTH - 110, 90, cam.getYaw());
    }

    private static void drawCompassRose(BufferedImage dest, Graphics2D g, int cx, int cy, float camYaw) {
        int r = 45;
        g.setColor(new Color(15, 22, 32, 220));
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
        g.setColor(new Color(70, 110, 160));
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(cx - r, cy - r, r * 2, r * 2);

        // Needle pointing to North (+Z world axis) relative to camera yaw
        double needleAngle = Math.toRadians(-camYaw);
        int nx = cx + (int) (r * 0.75 * Math.sin(needleAngle));
        int ny = cy - (int) (r * 0.75 * Math.cos(needleAngle));

        g.setColor(new Color(255, 60, 60));
        g.setStroke(new BasicStroke(2.8f));
        g.drawLine(cx, cy, nx, ny);

        drawText(dest, "N", nx - 4, ny - 8, Color.YELLOW, 1);

        g.setColor(Color.WHITE);
        g.fillOval(cx - 3, cy - 3, 6, 6);
    }

    private static void drawText(BufferedImage dest, String text, int startX, int startY, Color color, int scale) {
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

    private static LoadedZone getOrLoadZone(int zoneId) throws IOException {
        LoadedZone zone = loadedZoneCache.get(zoneId);
        if (zone == null) {
            zone = ZoneModelLoader.loadZone(FFXI_ROOT, zoneId);
            loadedZoneCache.put(zoneId, zone);
        }
        return zone;
    }

    private static Map<String, DecodedTextureBuffer> decodeAllZoneTextures(LoadedZone zone) {
        Map<String, DecodedTextureBuffer> map = new HashMap<>();
        for (Map.Entry<String, CharacterTextureLoader.TextureSource> entry : zone.textureSources().entrySet()) {
            String norm = normalizeName(entry.getKey());
            CharacterTextureLoader.TextureSource src = entry.getValue();
            try {
                byte[] rgba = TextureDecoder.decode(src.rawData(), src.resource());
                if (rgba != null && rgba.length > 0) {
                    DecodedTextureBuffer dzt = new DecodedTextureBuffer(src.resource().width(), src.resource().height(), rgba);
                    map.putIfAbsent(norm, dzt);
                    if (norm.contains(" ")) {
                        String shortName = norm.substring(norm.lastIndexOf(' ') + 1);
                        map.putIfAbsent(shortName, dzt);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return map;
    }

    private static String normalizeName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase();
    }

    private record ExpandedMesh(int[] faceIdx, int[] resolIdx) {
        int count() { return faceIdx.length; }
    }

    private static ExpandedMesh expandToTriangleList(SkeletonMeshMesh mesh) {
        List<SkeletonMeshFaceVertex> src = mesh.vertices();
        int n = src.size();

        if (n < 3) {
            return new ExpandedMesh(new int[0], new int[0]);
        }

        if (mesh.vertexCount() == mesh.triangleCount() * 3) {
            int triCount = (n / 3) * 3;
            int[] fi = new int[triCount];
            int[] ri = new int[triCount];
            for (int i = 0; i + 2 < triCount; i += 3) {
                fi[i]     = i;     ri[i]     = i;
                fi[i + 1] = i + 2; ri[i + 1] = i + 2;
                fi[i + 2] = i + 1; ri[i + 2] = i + 1;
            }
            return new ExpandedMesh(fi, ri);
        }

        int maxTris = n - 2;
        int[] fi = new int[maxTris * 3];
        int[] ri = new int[maxTris * 3];
        int out = 0;
        for (int i = 2; i < n; i++) {
            int a, b, c;
            if ((i & 1) == 0) {
                a = i - 2; b = i; c = i - 1;
            } else {
                a = i - 1; b = i; c = i - 2;
            }
            if (a == b || b == c || a == c) continue;
            fi[out] = a; ri[out] = a; out++;
            fi[out] = b; ri[out] = b; out++;
            fi[out] = c; ri[out] = c; out++;
        }
        int[] trimFi = new int[out];
        int[] trimRi = new int[out];
        System.arraycopy(fi, 0, trimFi, 0, out);
        System.arraycopy(ri, 0, trimRi, 0, out);
        return new ExpandedMesh(trimFi, trimRi);
    }
}
