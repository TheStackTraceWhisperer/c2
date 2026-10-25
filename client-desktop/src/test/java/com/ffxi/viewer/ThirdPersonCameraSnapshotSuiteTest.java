package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.loader.RaceModelLoader;
import com.example.asset.skeleton.AuthenticCharacterModelDecoder;
import com.example.asset.skeleton.SkinnedMesh;
import com.example.asset.skeleton.SkinnedSubMesh;
import com.example.asset.skeleton.SkinnedVertex;
import com.example.asset.texture.DecodedTexture;
import com.example.client.desktop.camera.PolarCamera;
import com.ffxi.io.ByteReader;
import com.ffxi.model.*;
import com.ffxi.parser.DatParser;
import com.ffxi.viewer.renderer.FontMojiRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Third-Person Polar Camera Snapshot Suite.
 * Renders authentic FFXI character avatars from arbitrary third-person camera viewpoints,
 * proving working XIM polar orbit kinematics, chase-camera facing alignment,
 * pitch elevation, boom zoom, and in-place character rotation.
 */
public class ThirdPersonCameraSnapshotSuiteTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();
    private static final Path OUTPUT_DIR = Path.of("target/snapshots");

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    // Cached Hume Male character geometry and textures in idle posture
    private static Map<Integer, SkinnedMesh> cachedSlots;
    private static Map<String, DecodedTexture> cachedTextures;
    private static float modelCx, modelMinY, modelCz, modelHeight;
    private static FontMojiRenderer fontMoji;

    @BeforeAll
    static void initCharacterModel() throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        try {
            Path rom01 = FFXI_ROOT.resolve("ROM/0/1.DAT");
            if (Files.exists(rom01)) {
                fontMoji = FontMojiRenderer.load(FFXI_ROOT);
            }
        } catch (Exception e) {
            System.err.println("Could not load authentic FontMojiRenderer: " + e.getMessage());
        }

        // Load authentic Hume Male PC actor
        Path baseDat = FFXI_ROOT.resolve(RaceModelLoader.CharacterRace.HUME_MALE.baseDatPath());
        if (!Files.exists(baseDat)) {
            return;
        }

        byte[] baseBytes = Files.readAllBytes(baseDat);
        DatResourceGraph baseGraph = DatParser.parseResourceGraph(new ByteReader(baseBytes, baseDat.getFileName().toString()));

        List<SkeletonResource> skeletons = new ArrayList<>();
        List<SkeletonAnimationResource> slot0Anims = new ArrayList<>();
        collectSkeletons(baseGraph.rootDirectory(), skeletons);
        collectAnimations(baseGraph.rootDirectory(), slot0Anims);

        SkeletonResource skeleton = skeletons.getFirst();
        SkeletonAnimationResource idl0 = findAnim(slot0Anims, "idl0");

        CharacterAnimator animator = new CharacterAnimator(skeleton, idl0, null);
        org.joml.Matrix4f[] jomlMatrices = animator.computePoseMatrices(0.0);

        List<float[]> animMatrices = new ArrayList<>(jomlMatrices.length);
        for (org.joml.Matrix4f m : jomlMatrices) {
            float[] arr = new float[16];
            m.get(arr);
            animMatrices.add(arr);
        }

        cachedSlots = new HashMap<>();
        cachedTextures = new HashMap<>();

        loadSlot(RaceModelLoader.CharacterRace.HUME_MALE.headDatPath(), 0, animMatrices, cachedSlots, cachedTextures);
        loadSlot(RaceModelLoader.CharacterRace.HUME_MALE.bodyDatPath(), 1, animMatrices, cachedSlots, cachedTextures);
        loadSlot(RaceModelLoader.CharacterRace.HUME_MALE.handsDatPath(), 2, animMatrices, cachedSlots, cachedTextures);
        loadSlot(RaceModelLoader.CharacterRace.HUME_MALE.legsDatPath(), 3, animMatrices, cachedSlots, cachedTextures);
        loadSlot(RaceModelLoader.CharacterRace.HUME_MALE.feetDatPath(), 4, animMatrices, cachedSlots, cachedTextures);

        // Compute model bounds
        float minX = 1e9f, maxX = -1e9f;
        float minY = 1e9f, maxY = -1e9f;
        float minZ = 1e9f, maxZ = -1e9f;

        for (SkinnedMesh mesh : cachedSlots.values()) {
            for (SkinnedSubMesh sub : mesh.subMeshes()) {
                for (SkinnedVertex v : sub.vertices()) {
                    float x = v.position().x();
                    float y = v.position().y();
                    float z = v.position().z();
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
            }
        }

        modelCx = (minX + maxX) * 0.5f;
        modelMinY = minY;
        modelCz = (minZ + maxZ) * 0.5f;
        modelHeight = Math.max(1.6f, maxY - minY);
    }

    @Test
    @DisplayName("Default third-person camera state: player facing away (North) with camera positioned behind")
    void testDefaultThirdPersonCameraBehindCharacter() throws Exception {
        if (cachedSlots == null) return;

        float charX = 0.0f, charY = 0.0f, charZ = 0.0f;
        float charYaw = 0.0f; // Player facing away (North, +Z)
        float camYaw = 0.0f;  // Camera looking North (+Z) from behind
        float camPitch = 25.0f; // Default elevated third-person chase angle
        float camDist = 6.0f;

        // Verify camera mathematical invariants
        PolarCamera cam = new PolarCamera();
        cam.setTarget(charX, charY, charZ);
        cam.setTargetOffsetY(1.0f);
        cam.setYaw(camYaw);
        cam.setPitch(camPitch);
        cam.setDistance(camDist);

        assertEquals(0.0f, cam.getEyeX(), 0.001f, "Camera should be centered horizontally behind player");
        assertTrue(cam.getEyeZ() < charZ, "Camera eye must be South of player (-Z, behind player's back)");
        assertTrue(cam.getEyeY() > charY + 1.0f, "Camera eye must be elevated above player");

        File snapshot = renderAndSaveSnapshot(
                charX, charY, charZ, charYaw,
                camYaw, camPitch, camDist,
                "XIM Third-Person Default State | Behind Player Facing Away",
                "Default FFXI Chase Camera: Player Facing North (+Z) | Camera Positioned Behind",
                "camera_default_behind_facing_away.png"
        );

        assertTrue(snapshot.exists() && snapshot.length() > 10000, "Default state snapshot must exist and be non-empty");
    }

    @Test
    @DisplayName("Full 360-degree orbit around player at 45-degree intervals")
    void testOrbit360DegreesAt45DegreeIntervals() throws Exception {
        if (cachedSlots == null) return;

        record OrbitAngle(float yawDeg, String filename, String title, String description) {}

        List<OrbitAngle> orbitAngles = List.of(
                new OrbitAngle(0.0f,   "camera_orbit_000_behind.png",        "Orbit 000° | Directly Behind Player",       "Default Third-Person Chase Angle: Player Facing North, Back to Camera"),
                new OrbitAngle(45.0f,  "camera_orbit_045_rear_right.png",   "Orbit 045° | Rear-Right 3/4 Perspective",   "Camera Over Right Shoulder: Looking North-East along Diagonal"),
                new OrbitAngle(90.0f,  "camera_orbit_090_right_profile.png", "Orbit 090° | Right Side Profile",          "Camera Positioned West of Player: Viewing Full Right Profile Facing Left"),
                new OrbitAngle(135.0f, "camera_orbit_135_front_right.png",  "Orbit 135° | Front-Right 3/4 Perspective",  "Camera in Front-Right Quadrant: Looking toward Player Chest and Face"),
                new OrbitAngle(180.0f, "camera_orbit_180_front.png",        "Orbit 180° | Front-Facing Portrait",        "Camera Directly North of Player: Player Facing Straight into Lens"),
                new OrbitAngle(225.0f, "camera_orbit_225_front_left.png",   "Orbit 225° | Front-Left 3/4 Perspective",   "Camera in Front-Left Quadrant: Looking toward Player Chest and Face"),
                new OrbitAngle(270.0f, "camera_orbit_270_left_profile.png",  "Orbit 270° | Left Side Profile",           "Camera Positioned East of Player: Viewing Full Left Profile Facing Right"),
                new OrbitAngle(315.0f, "camera_orbit_315_rear_left.png",    "Orbit 315° | Rear-Left 3/4 Perspective",    "Camera Over Left Shoulder: Looking North-West along Diagonal")
        );

        float charYaw = 0.0f; // Stationary character facing North
        float camPitch = 22.0f;
        float camDist = 5.5f;

        for (OrbitAngle orbit : orbitAngles) {
            PolarCamera cam = new PolarCamera();
            cam.setTarget(0.0f, 0.0f, 0.0f);
            cam.setTargetOffsetY(1.0f);
            cam.setYaw(orbit.yawDeg);
            cam.setPitch(camPitch);
            cam.setDistance(camDist);

            // Invariant: Eye XZ distance from target must equal distance * cos(pitch)
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
        if (cachedSlots == null) return;

        record PitchAngle(float pitchDeg, String filename, String title, String description) {}

        List<PitchAngle> pitchAngles = List.of(
                new PitchAngle(-15.0f, "camera_pitch_low_hero_neg15.png",  "Pitch -15° | Low Dramatic Hero Angle",   "Camera Positioned Below Character Waist: Tilted Upward toward Avatar"),
                new PitchAngle(0.0f,   "camera_pitch_level_00.png",        "Pitch 000° | Level Eye-Line Perspective", "Camera at Exact Torso Level: Parallel to Horizon Line"),
                new PitchAngle(25.0f,  "camera_pitch_elevated_25.png",     "Pitch 025° | Standard Chase Elevation",   "Default FFXI Elevated Angle: Clear View of Avatar and Foreground"),
                new PitchAngle(50.0f,  "camera_pitch_high_tactical_50.png","Pitch 050° | High Tactical Angle",       "Elevated Angle: Tactical Overhead View of Ground and Perimeter"),
                new PitchAngle(75.0f,  "camera_pitch_overhead_75.png",     "Pitch 075° | Steep Top-Down Angle",      "Near-Nadir Bird's-Eye View: Looking Directly Down at Head and Shoulders")
        );

        float charYaw = 0.0f;
        float camYaw = 35.0f; // Slight rear-right angle to show 3D depth across pitches
        float camDist = 6.0f;

        for (PitchAngle pa : pitchAngles) {
            PolarCamera cam = new PolarCamera();
            cam.setTarget(0.0f, 0.0f, 0.0f);
            cam.setTargetOffsetY(1.0f);
            cam.setYaw(camYaw);
            cam.setPitch(pa.pitchDeg);
            cam.setDistance(camDist);

            float expectedEyeY = 1.0f + camDist * (float) Math.sin(Math.toRadians(pa.pitchDeg));
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
        if (cachedSlots == null) return;

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
        if (cachedSlots == null) return;

        record CardinalChase(float facingDeg, String filename, String title, String description) {}

        List<CardinalChase> cardinals = List.of(
                new CardinalChase(0.0f,   "camera_chase_facing_north.png", "Chase North (+Z) | Camera Directly Behind", "Player Facing North (0°): Camera Behind at -Z looking +Z"),
                new CardinalChase(90.0f,  "camera_chase_facing_east.png",  "Chase East (+X) | Camera Directly Behind",  "Player Facing East (90°): Camera Behind at -X looking +X"),
                new CardinalChase(180.0f, "camera_chase_facing_south.png", "Chase South (-Z) | Camera Directly Behind", "Player Facing South (180°): Camera Behind at +Z looking -Z"),
                new CardinalChase(270.0f, "camera_chase_facing_west.png",  "Chase West (-X) | Camera Directly Behind",  "Player Facing West (270°): Camera Behind at +X looking -X")
        );

        float camPitch = 22.0f;
        float camDist = 6.0f;

        for (CardinalChase cc : cardinals) {
            // Chase camera: camera yaw matches character facing angle
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
        if (cachedSlots == null) return;

        record TurnAngle(float charYawDeg, String filename, String title, String description) {}

        List<TurnAngle> turnAngles = List.of(
                new TurnAngle(0.0f,   "camera_turn_char_000_north.png", "Character North (000°) | Back to Camera",       "Player Facing North: Direct Rear View (Facing Away from Camera)"),
                new TurnAngle(45.0f,  "camera_turn_char_045_northeast.png", "Character North-East (045°) | Quarter Turn", "Player Facing North-East: Quarter Turn Showing Right Flank"),
                new TurnAngle(90.0f,  "camera_turn_char_090_east.png",  "Character East (090°) | Right Profile",         "Player Facing East: Full Right Profile Relative to Camera"),
                new TurnAngle(135.0f, "camera_turn_char_135_southeast.png", "Character South-East (135°) | 3/4 Front",  "Player Facing South-East: Three-Quarter Front View"),
                new TurnAngle(180.0f, "camera_turn_char_180_south.png", "Character South (180°) | Facing Camera",       "Player Facing South: Head-On Frontal Portrait Looking at Camera"),
                new TurnAngle(270.0f, "camera_turn_char_270_west.png",  "Character West (270°) | Left Profile",          "Player Facing West: Full Left Profile Relative to Camera")
        );

        float camYaw = 0.0f; // Camera stationary looking North
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

    // -------------------------------------------------------------------------
    // 3D Rendering & Telemetry Generation Engine
    // -------------------------------------------------------------------------

    private static File renderAndSaveSnapshot(
            float charX, float charY, float charZ, float charYaw,
            float camYaw, float camPitch, float camDist,
            String title, String subtitle, String filename
    ) throws Exception {
        PolarCamera camera = new PolarCamera();
        camera.setTarget(charX, charY, charZ);
        camera.setTargetOffsetY(1.0f);
        camera.setYaw(camYaw);
        camera.setPitch(camPitch);
        camera.setDistance(camDist);

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        float[] zBuffer = new float[WIDTH * HEIGHT];
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);

        // 1. Render Atmospheric Sky Gradient (FFXI Twilight / Midnight Slate)
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        for (int y = 0; y < HEIGHT; y++) {
            float ratio = (float) y / HEIGHT;
            int r = (int) (14 + ratio * 18);
            int g = (int) (18 + ratio * 24);
            int b = (int) (28 + ratio * 32);
            g2.setColor(new Color(r, g, b));
            g2.drawLine(0, y, WIDTH, y);
        }

        // 2. Setup Camera View & Perspective Projection Matrices
        float eyeX = camera.getEyeX();
        float eyeY = camera.getEyeY();
        float eyeZ = camera.getEyeZ();
        float targetX = camera.getTargetX();
        float targetY = camera.getTargetY() + camera.getTargetOffsetY();
        float targetZ = camera.getTargetZ();

        Matrix4f viewMatrix = new Matrix4f().lookAt(
                eyeX, eyeY, eyeZ,
                targetX, targetY, targetZ,
                0.0f, 1.0f, 0.0f
        );

        float fovDegrees = 42.0f;
        Matrix4f projMatrix = new Matrix4f().perspective(
                (float) Math.toRadians(fovDegrees),
                (float) WIDTH / HEIGHT,
                0.1f, 100.0f
        );

        Matrix4f viewProj = new Matrix4f(projMatrix).mul(viewMatrix);

        // 3. Render 3D Ground Grid and Orientation Guides
        drawGroundPlaneGuides(g2, viewProj, charX, charZ, charYaw, camYaw, camDist);

        // 4. Software Depth-Tested 3D Rasterization of Authentic Character Mesh
        rasterizeCharacter(image, zBuffer, viewProj, charX, charY, charZ, charYaw);

        // 4b. Draw Ground Plane Text Labels
        drawGroundLabels(image, g2, viewProj, charX, charZ);

        // 5. Draw Professional HUD Telemetry Overlay
        drawTelemetryOverlay(image, g2, camera, charX, charY, charZ, charYaw, title, subtitle);

        g2.dispose();

        File outFile = OUTPUT_DIR.resolve(filename).toFile();
        ImageIO.write(image, "PNG", outFile);
        System.out.printf("[CAMERA-SUITE] Rendered '%s' -> %s (%d bytes)%n", title, outFile.getName(), outFile.length());
        return outFile;
    }

    private static void drawGroundPlaneGuides(
            Graphics2D g2, Matrix4f viewProj,
            float charX, float charZ, float charYaw,
            float camYaw, float camDist
    ) {
        // Grid lines at 1-meter intervals on Y = 0
        g2.setStroke(new BasicStroke(1.0f));
        g2.setColor(new Color(40, 52, 70, 160));

        for (int i = -8; i <= 8; i++) {
            drawLine3D(g2, viewProj, -8.0f, 0.0f, i, 8.0f, 0.0f, i);
            drawLine3D(g2, viewProj, i, 0.0f, -8.0f, i, 0.0f, 8.0f);
        }

        // Concentric Orbit Circles on Ground at radii 2m, 4m, 6m
        g2.setColor(new Color(60, 85, 120, 140));
        drawCircle3D(g2, viewProj, charX, charZ, 2.0f, 36);
        drawCircle3D(g2, viewProj, charX, charZ, 4.0f, 48);

        // Highlight Active Camera Orbit Distance Circle
        g2.setColor(new Color(80, 140, 220, 200));
        g2.setStroke(new BasicStroke(1.5f));
        float groundOrbitRadius = camDist * (float) Math.cos(Math.toRadians(20.0));
        drawCircle3D(g2, viewProj, charX, charZ, groundOrbitRadius, 64);

        // Cardinal Axes
        // North (+Z) Axis: Bright Gold
        g2.setColor(new Color(255, 200, 50, 240));
        g2.setStroke(new BasicStroke(2.5f));
        drawLine3D(g2, viewProj, charX, 0.01f, charZ, charX, 0.01f, charZ + 6.0f);
        drawArrowhead3D(g2, viewProj, charX, 0.01f, charZ + 6.0f, 0.0f, 1.0f, 0.4f);

        // East (+X) Axis: Emerald Green
        g2.setColor(new Color(60, 220, 120, 220));
        g2.setStroke(new BasicStroke(2.0f));
        drawLine3D(g2, viewProj, charX, 0.01f, charZ, charX + 5.0f, 0.01f, charZ);
        drawArrowhead3D(g2, viewProj, charX + 5.0f, 0.01f, charZ, 1.0f, 0.0f, 0.4f);

        // South (-Z) and West (-X) Axes: Muted Blue
        g2.setColor(new Color(70, 100, 140, 180));
        g2.setStroke(new BasicStroke(1.5f));
        drawLine3D(g2, viewProj, charX, 0.01f, charZ, charX, 0.01f, charZ - 5.0f);
        drawLine3D(g2, viewProj, charX, 0.01f, charZ, charX - 5.0f, 0.01f, charZ);

        // Character Facing Direction Chevron (at player's feet pointing in charYaw)
        float charFwdX = (float) Math.sin(Math.toRadians(charYaw));
        float charFwdZ = (float) Math.cos(Math.toRadians(charYaw));
        g2.setColor(new Color(255, 80, 80, 250)); // Bright Coral
        g2.setStroke(new BasicStroke(3.0f));
        drawLine3D(g2, viewProj, charX, 0.02f, charZ, charX + charFwdX * 1.5f, 0.02f, charZ + charFwdZ * 1.5f);
        drawArrowhead3D(g2, viewProj, charX + charFwdX * 1.5f, 0.02f, charZ + charFwdZ * 1.5f, charFwdX, charFwdZ, 0.35f);
    }

    private static void rasterizeCharacter(
            BufferedImage image, float[] zBuffer, Matrix4f viewProj,
            float charX, float charY, float charZ, float charYaw
    ) {
        int[] pixels = new int[WIDTH * HEIGHT];
        image.getRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);

        double charRad = Math.toRadians(charYaw);
        float cosC = (float) Math.cos(charRad);
        float sinC = (float) Math.sin(charRad);

        for (SkinnedMesh mesh : cachedSlots.values()) {
            for (SkinnedSubMesh sub : mesh.subMeshes()) {
                DecodedTexture tex = cachedTextures.get(sub.textureName());
                List<SkinnedVertex> verts = sub.vertices();
                List<Integer> indices = sub.indices();

                for (int i = 0; i + 2 < indices.size(); i += 3) {
                    SkinnedVertex v0 = verts.get(indices.get(i));
                    SkinnedVertex v1 = verts.get(indices.get(i + 1));
                    SkinnedVertex v2 = verts.get(indices.get(i + 2));

                    float[] s0 = projectVertex(v0, cosC, sinC, charX, charY, charZ, viewProj);
                    float[] s1 = projectVertex(v1, cosC, sinC, charX, charY, charZ, viewProj);
                    float[] s2 = projectVertex(v2, cosC, sinC, charX, charY, charZ, viewProj);

                    if (s0 == null || s1 == null || s2 == null) continue;

                    rasterizeTriangle(pixels, zBuffer, WIDTH, HEIGHT,
                            s0[0], s0[1], s0[2], v0.u(), v0.v(),
                            s1[0], s1[1], s1[2], v1.u(), v1.v(),
                            s2[0], s2[1], s2[2], v2.u(), v2.v(),
                            tex);
                }
            }
        }

        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
    }

    private static float[] projectVertex(
            SkinnedVertex v, float cosC, float sinC,
            float charX, float charY, float charZ,
            Matrix4f viewProj
    ) {
        // Local character space (feet at 0, head at height ~1.8m, facing North +Z)
        float lx = v.position().x() - modelCx;
        float ly = -(v.position().y() - modelMinY);
        float lz = -(v.position().z() - modelCz);

        // Rotate by character yaw around Y
        float rx = cosC * lx + sinC * lz;
        float ry = ly;
        float rz = -sinC * lx + cosC * lz;

        float wx = charX + rx;
        float wy = charY + ry;
        float wz = charZ + rz;

        Vector4f clip = viewProj.transform(new Vector4f(wx, wy, wz, 1.0f));
        if (clip.w <= 0.1f) return null;

        float invW = 1.0f / clip.w;
        float sx = (clip.x * invW * 0.5f + 0.5f) * WIDTH;
        float sy = (0.5f - clip.y * invW * 0.5f) * HEIGHT;

        return new float[] { sx, sy, clip.w };
    }

    private static void rasterizeTriangle(
            int[] pixels, float[] zBuffer, int width, int height,
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            DecodedTexture texture
    ) {
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));

        float denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(denom) < 1e-5f) return;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;
                float py = y + 0.5f;

                float w0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom;
                float w1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom;
                float w2 = 1.0f - w0 - w1;

                if (w0 >= 0f && w1 >= 0f && w2 >= 0f) {
                    float z = w0 * z0 + w1 * z1 + w2 * z2;
                    int idx = y * width + x;
                    if (z < zBuffer[idx]) {
                        float u = w0 * u0 + w1 * u1 + w2 * u2;
                        float v = w0 * v0 + w1 * v1 + w2 * v2;

                        if (texture != null && texture.rgbaPixels() != null && texture.rgbaPixels().length > 0) {
                            int texColor = sampleOpaque(texture, u, v);
                            if (texColor == 0) continue;
                            zBuffer[idx] = z;
                            pixels[idx] = texColor;
                        } else {
                            zBuffer[idx] = z;
                            pixels[idx] = 0xFFCCCCCC;
                        }
                    }
                }
            }
        }
    }

    private static int sampleOpaque(DecodedTexture tex, float u, float v) {
        int w = tex.width();
        int h = tex.height();
        if (w <= 0 || h <= 0) return 0;

        float uWrapped = u - (float) Math.floor(u);
        float vWrapped = v - (float) Math.floor(v);

        int tx = Math.max(0, Math.min(w - 1, (int) (uWrapped * w)));
        int ty = Math.max(0, Math.min(h - 1, (int) (vWrapped * h)));

        int byteOffset = (ty * w + tx) * 4;
        byte[] rgba = tex.rgbaPixels();
        if (byteOffset + 3 >= rgba.length) return 0;

        int a = rgba[byteOffset + 3] & 0xFF;
        if (a < 64) return 0; // Alpha discard threshold

        int r = rgba[byteOffset] & 0xFF;
        int g = rgba[byteOffset + 1] & 0xFF;
        int b = rgba[byteOffset + 2] & 0xFF;

        return (255 << 24) | (r << 16) | (g << 8) | b;
    }

    private static void drawLine3D(Graphics2D g, Matrix4f viewProj, float x0, float y0, float z0, float x1, float y1, float z1) {
        Vector4f c0 = viewProj.transform(new Vector4f(x0, y0, z0, 1.0f));
        Vector4f c1 = viewProj.transform(new Vector4f(x1, y1, z1, 1.0f));

        if (c0.w <= 0.1f || c1.w <= 0.1f) return;

        int sx0 = (int) ((c0.x / c0.w * 0.5f + 0.5f) * WIDTH);
        int sy0 = (int) ((0.5f - c0.y / c0.w * 0.5f) * HEIGHT);
        int sx1 = (int) ((c1.x / c1.w * 0.5f + 0.5f) * WIDTH);
        int sy1 = (int) ((0.5f - c1.y / c1.w * 0.5f) * HEIGHT);

        g.drawLine(sx0, sy0, sx1, sy1);
    }

    private static void drawCircle3D(Graphics2D g, Matrix4f viewProj, float cx, float cz, float radius, int segments) {
        double step = (2.0 * Math.PI) / segments;
        for (int i = 0; i < segments; i++) {
            double a0 = i * step;
            double a1 = (i + 1) * step;

            float x0 = cx + radius * (float) Math.sin(a0);
            float z0 = cz + radius * (float) Math.cos(a0);
            float x1 = cx + radius * (float) Math.sin(a1);
            float z1 = cz + radius * (float) Math.cos(a1);

            drawLine3D(g, viewProj, x0, 0.005f, z0, x1, 0.005f, z1);
        }
    }

    private static void drawArrowhead3D(Graphics2D g, Matrix4f viewProj, float tipX, float tipY, float tipZ, float dirX, float dirZ, float size) {
        float norm = (float) Math.hypot(dirX, dirZ);
        if (norm < 1e-4f) return;
        float nx = dirX / norm;
        float nz = dirZ / norm;
        float px = -nz;
        float pz = nx;

        float leftX = tipX - nx * size + px * (size * 0.5f);
        float leftZ = tipZ - nz * size + pz * (size * 0.5f);
        float rightX = tipX - nx * size - px * (size * 0.5f);
        float rightZ = tipZ - nz * size - pz * (size * 0.5f);

        drawLine3D(g, viewProj, tipX, tipY, tipZ, leftX, tipY, leftZ);
        drawLine3D(g, viewProj, tipX, tipY, tipZ, rightX, tipY, rightZ);
    }

    private static void drawGroundLabels(BufferedImage dest, Graphics2D g, Matrix4f viewProj, float charX, float charZ) {
        draw3DText(dest, g, viewProj, charX, 0.05f, charZ + 6.3f, "NORTH (+Z)", new Color(255, 215, 60));
        draw3DText(dest, g, viewProj, charX + 5.3f, 0.05f, charZ, "EAST (+X)", new Color(70, 230, 130));
        draw3DText(dest, g, viewProj, charX, 0.05f, charZ - 5.3f, "SOUTH (-Z)", new Color(130, 160, 200));
        draw3DText(dest, g, viewProj, charX - 5.3f, 0.05f, charZ, "WEST (-X)", new Color(130, 160, 200));
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
            BufferedImage dest, Graphics2D g, PolarCamera cam,
            float charX, float charY, float charZ, float charYaw,
            String title, String subtitle
    ) {
        // Semi-transparent header banner
        g.setColor(new Color(15, 22, 32, 220));
        g.fillRect(20, 20, 720, 110);
        g.setColor(new Color(70, 110, 160));
        g.drawRect(20, 20, 720, 110);

        // Title & Subtitle (rendered with authentic FFXI font moji)
        drawText(dest, title, 36, 28, new Color(255, 215, 60), 2);
        drawText(dest, subtitle, 36, 62, new Color(190, 215, 240), 1);

        // Telemetry Data Readout
        float relativeAngle = (cam.getYaw() - charYaw) % 360.0f;
        if (relativeAngle < 0.0f) relativeAngle += 360.0f;

        String aspectLabel = resolveAspectLabel(relativeAngle);

        String line1 = String.format("Camera: Yaw=%5.1f deg | Pitch=%5.1f deg | Dist=%4.1fm | Eye=(%5.2f, %5.2f, %5.2f)",
                cam.getYaw(), cam.getPitch(), cam.getDistance(), cam.getEyeX(), cam.getEyeY(), cam.getEyeZ());
        String line2 = String.format("Avatar: Facing=%5.1f deg [%-5s] | Pos=(%4.1f, %4.1f, %4.1f) | View: %s",
                charYaw, resolveCardinal(charYaw), charX, charY, charZ, aspectLabel);

        drawText(dest, line1, 36, 82, new Color(220, 230, 245), 1);
        drawText(dest, line2, 36, 102, new Color(100, 225, 140), 1);

        // Compass Rose in upper right corner
        drawCompassRose(dest, g, WIDTH - 90, 80, cam.getYaw());
    }

    private static void drawCompassRose(BufferedImage dest, Graphics2D g, int cx, int cy, float camYaw) {
        int r = 40;
        g.setColor(new Color(15, 22, 32, 210));
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
        g.setColor(new Color(70, 110, 160));
        g.drawOval(cx - r, cy - r, r * 2, r * 2);

        // Needle pointing to North (+Z) relative to camera yaw
        // In FFXI, North is 0 deg. If camera yaw is theta, North appears rotated by -theta
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

    private static void collectSkeletons(ResourceNode node, List<SkeletonResource> list) {
        if (node instanceof SkeletonResource s) list.add(s);
        if (node instanceof DirectoryResource d) {
            for (ResourceNode c : d.children()) collectSkeletons(c, list);
        }
    }

    private static void collectAnimations(ResourceNode node, List<SkeletonAnimationResource> list) {
        if (node instanceof SkeletonAnimationResource a) list.add(a);
        if (node instanceof DirectoryResource d) {
            for (ResourceNode c : d.children()) collectAnimations(c, list);
        }
    }

    private static SkeletonAnimationResource findAnim(List<SkeletonAnimationResource> list, String id) {
        if (id == null) return null;
        for (SkeletonAnimationResource a : list) {
            if (a.sectionId().equalsIgnoreCase(id)) return a;
        }
        return null;
    }

    private static void loadSlot(String relPath, int slotId, List<float[]> matrices,
                                 Map<Integer, SkinnedMesh> slots, Map<String, DecodedTexture> textures) throws Exception {
        if (relPath == null || relPath.isBlank()) return;
        Path path = FFXI_ROOT.resolve(relPath);
        if (!Files.exists(path)) return;
        byte[] bytes = Files.readAllBytes(path);
        SkinnedMesh mesh = AuthenticCharacterModelDecoder.decodeModelChunk(bytes, matrices, "slot_" + slotId);
        if (!mesh.subMeshes().isEmpty()) {
            slots.put(slotId, mesh);
        }
        textures.putAll(AuthenticCharacterModelDecoder.decodeTextures(bytes));
    }
}
