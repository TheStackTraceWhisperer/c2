package com.example.client.desktop.e2e;

import com.example.asset.environment.DiurnalLighting;
import com.example.asset.loader.RaceModelLoader;
import com.example.asset.loader.RomAssetLoader;
import com.example.asset.mesh.VisualSubMesh;
import com.example.asset.mesh.VisualZoneLoader;
import com.example.asset.mesh.VisualZoneScene;
import com.example.asset.skeleton.AnimationStateMachine;
import com.example.asset.skeleton.BlendedPose;
import com.example.asset.skeleton.CharacterMeshGenerator;
import com.example.asset.skeleton.CompositeCharacterModel;
import com.example.asset.skeleton.SkeletonAnimation;
import com.example.asset.skeleton.StandardAnimations;
import com.example.client.desktop.ui.ScreenFader;
import com.example.client.interp.EntityInterpolationBuffer;
import com.example.client.interp.InterpolatedEntityState;
import com.example.client.interp.InterpolationSnapshot;
import com.example.common.protocol.JobId;
import com.example.common.protocol.MovementFlags;
import com.example.common.time.VanadielTime;
import com.example.common.time.WeatherType;
import com.example.world.dto.ZoneTransferNotification;
import com.example.client.desktop.capture.RenderDocCapture;
import com.example.client.desktop.capture.ScreenshotCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-End client pipeline test verifying that all milestones delivered in the past week
 * integrate cleanly:
 * - Real DAT loading (VisualZoneLoader extracting 3D meshes, materials & textures from authentic DATs)
 * - 20 Hz server AoI delta packet ingestion into 60+ FPS EntityInterpolationBuffer
 * - Humanoid character compositing, skeletal animation blending, and continuous playback
 * - Vana'diel calendar, time of day, and diurnal lighting atmospheric calculations
 * - Zone transition notification handling and screen fader lifecycle
 */
public class ClientAssetPipelineE2ETest {

    @Test
    @DisplayName("E2E: Client loads authentic DAT visual geometry, sub-meshes, materials, and textures")
    void testAuthenticDatGeometryLoading() throws Exception {
        byte[] datBytes = loadDatFile("south_san_doria_230.DAT");
        assertThat(datBytes).isNotNull();
        assertThat(datBytes.length).isGreaterThan(100_000);

        VisualZoneScene scene = VisualZoneLoader.load(datBytes);

        assertThat(scene).isNotNull();
        assertThat(scene.totalMeshCount()).isGreaterThan(0);
        assertThat(scene.textures()).isNotEmpty();

        // Verify textured sub-mesh structure
        VisualSubMesh firstSubMesh = scene.meshes().get(0).subMeshes().get(0);
        assertThat(firstSubMesh.vertices()).isNotEmpty();
        assertThat(firstSubMesh.indices()).isNotEmpty();
        assertThat(firstSubMesh.textureName()).isNotNull();
    }

    @Test
    @DisplayName("E2E: 20 Hz server AoI delta messages smoothly interpolate into 60 FPS client frames")
    void testServerDeltaToClientInterpolationE2E() {
        EntityInterpolationBuffer interpBuffer = new EntityInterpolationBuffer();

        // 1. Ingest server snapshot at tick 100 (t = 1000ms) at (10.0, 0.0, 20.0)
        interpBuffer.pushSnapshot(new InterpolationSnapshot(
                1000L, 10.0f, 0.0f, 20.0f,
                2.0f, 0.0f, 0.0f,
                90.0f, 0.0f,
                (byte) 1
        ));

        // 2. Ingest server snapshot at tick 101 (t = 1050ms) at (10.1, 0.0, 20.0)
        interpBuffer.pushSnapshot(new InterpolationSnapshot(
                1050L, 10.1f, 0.0f, 20.0f,
                2.0f, 0.0f, 0.0f,
                90.0f, 0.0f,
                (byte) 1
        ));

        // 3. Client evaluates at 60 FPS sub-frame intervals with 100ms interpolation buffer
        // targetTime = currentClientTime - 100ms
        InterpolatedEntityState frame1 = interpBuffer.sample(1116L, 100L); // targetTime = 1016ms
        InterpolatedEntityState frame2 = interpBuffer.sample(1133L, 100L); // targetTime = 1033ms
        InterpolatedEntityState frame3 = interpBuffer.sample(1150L, 100L); // targetTime = 1050ms

        assertThat(frame1).isNotNull();
        assertThat(frame2).isNotNull();
        assertThat(frame3).isNotNull();

        // Verify positions advance monotonically and smoothly without pops
        assertThat(frame1.x()).isGreaterThanOrEqualTo(10.0f);
        assertThat(frame2.x()).isGreaterThan(frame1.x());
        assertThat(frame3.x()).isGreaterThan(frame2.x());
        assertThat(frame3.x()).isCloseTo(10.1f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    @DisplayName("E2E: Composite character model with skeletal animation state machine runs and loops continuously")
    void testCharacterLocomotionAndContinuousLoopingE2E() {
        CompositeCharacterModel model = CharacterMeshGenerator.createCharacterModel(JobId.WARRIOR);
        assertThat(model).isNotNull();
        assertThat(model.skeleton()).isNotNull();

        AnimationStateMachine asm = StandardAnimations.createLocomotionStateMachine();

        // Advance animation by 0.1s steps for 120 seconds (over 100 complete animation cycles)
        for (int i = 0; i < 1200; i++) {
            asm.update(0.1f, MovementFlags.FORWARD, 4.5f);
        }

        // Bone matrices should be successfully generated and non-degenerate
        BlendedPose pose = asm.evaluate(model.skeleton());
        assertThat(pose).isNotNull();
        assertThat(pose.jointPoseMatrices()).hasSize(model.skeleton().joints().size());

        // Verify root and spine bone matrices are valid affine transforms (non-zero matrix elements)
        for (var matrix : pose.jointPoseMatrices()) {
            float[] elements = matrix.elements();
            assertThat(elements).hasSize(16);
            float sum = 0.0f;
            for (float v : elements) sum += Math.abs(v);
            assertThat(sum).isGreaterThan(0.5f); // Valid non-zero transform
        }

        // Capture visual snapshot of the animated rig and trigger RenderDoc hook
        RenderDocCapture.captureFrameIfAvailable(0L, () -> {
            BufferedImage posePreview = new BufferedImage(320, 320, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = posePreview.createGraphics();
            g.setColor(new Color(25, 28, 35));
            g.fillRect(0, 0, 320, 320);
            g.setColor(new Color(255, 215, 0));
            var joints = model.skeleton().joints();
            for (int i = 0; i < joints.size(); i++) {
                float[] elem = pose.jointPoseMatrices().get(i).elements();
                int sx = (int) (160 + elem[12] * 100.0f);
                int sy = (int) (260 - elem[13] * 120.0f);
                g.fillOval(sx - 3, sy - 3, 6, 6);
            }
            g.dispose();
            ScreenshotCapture.saveBufferedImage(posePreview, "e2e_character_locomotion");
        });
    }

    @Test
    @DisplayName("E2E: Authentic retail ROM character models and NPC geometry load, texture, and animate")
    void testAuthenticRomCharacterAndNpcModelsE2E() {
        RomAssetLoader romLoader = RomAssetLoader.getInstance();
        RaceModelLoader raceLoader = new RaceModelLoader(romLoader);

        // 1. Authentic Hume Male PC actor
        CompositeCharacterModel hume = raceLoader.loadRaceModel(RaceModelLoader.CharacterRace.HUME_MALE);
        assertThat(hume).isNotNull();
        assertThat(hume.skeleton()).isNotNull();
        assertThat(hume.skeleton().boneCount()).isGreaterThan(20);
        assertThat(hume.equipmentSlots()).containsKeys(
                CompositeCharacterModel.SLOT_HEAD,
                CompositeCharacterModel.SLOT_BODY,
                CompositeCharacterModel.SLOT_HANDS,
                CompositeCharacterModel.SLOT_LEGS,
                CompositeCharacterModel.SLOT_FEET
        );
        assertThat(hume.textures()).isNotEmpty();

        // 2. Authentic Town Guard NPC actor
        CompositeCharacterModel npc = raceLoader.loadNpcModel(0, null);
        assertThat(npc).isNotNull();
        assertThat(npc.skeleton()).isNotNull();
        assertThat(npc.equipmentSlots()).containsKey(CompositeCharacterModel.SLOT_BODY);
        assertThat(npc.textures()).isNotEmpty();

        // 3. Evaluate authentic S2B idle animation track
        var anims = raceLoader.loadRaceAnimations(RaceModelLoader.CharacterRace.HUME_MALE);
        assertThat(anims).containsKey("idl0");
        SkeletonAnimation idl0 = anims.get("idl0");
        List<com.example.asset.geometry.Matrix4f> pose = hume.computePoseMatrices(idl0, 0.0f);
        assertThat(pose).hasSize(hume.skeleton().boneCount());

        // 4. Capture screenshot of authentic skeletal pose
        BufferedImage preview = new BufferedImage(320, 320, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = preview.createGraphics();
        g.setColor(new Color(20, 24, 30));
        g.fillRect(0, 0, 320, 320);
        g.setColor(new Color(100, 200, 255));
        for (int i = 0; i < pose.size(); i++) {
            float[] elem = pose.get(i).elements();
            int sx = (int) (160 + elem[12] * 80.0f);
            int sy = (int) (270 - elem[13] * 100.0f);
            g.fillOval(sx - 3, sy - 3, 6, 6);
        }
        g.dispose();
        ScreenshotCapture.saveBufferedImage(preview, "e2e_authentic_hume_pose");
    }

    @Test
    @DisplayName("E2E: Diurnal lighting calculates celestial orbit, ambient, and distance fog from time and weather")
    void testDiurnalLightingAndAtmosphericFogE2E() {
        // Noon under clear skies
        VanadielTime noon = VanadielTime.ofHour(12.0f);
        DiurnalLighting noonLighting = DiurnalLighting.compute(noon, WeatherType.CLEAR);

        // Midnight under clear skies
        VanadielTime midnight = VanadielTime.ofHour(0.0f);
        DiurnalLighting midnightLighting = DiurnalLighting.compute(midnight, WeatherType.CLEAR);

        // Foggy weather
        DiurnalLighting fogLighting = DiurnalLighting.compute(noon, WeatherType.FOG);

        // Sun elevation at noon should be high (> 0)
        assertThat(noonLighting.getSunDirection().y()).isGreaterThan(0.5f);

        // Noon sunlight should be significantly brighter than midnight ambient
        float noonBrightness = noonLighting.getSunColor()[0] + noonLighting.getSunColor()[1] + noonLighting.getSunColor()[2];
        float midnightBrightness = midnightLighting.getSunColor()[0] + midnightLighting.getSunColor()[1] + midnightLighting.getSunColor()[2];
        assertThat(noonBrightness).isGreaterThan(midnightBrightness);

        // Distance fog under FOG weather should be denser (shorter fogEnd) than CLEAR weather
        assertThat(fogLighting.getFogEnd()).isLessThan(noonLighting.getFogEnd());

        // Capture diurnal skybox snapshot
        BufferedImage swatch = new BufferedImage(400, 150, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = swatch.createGraphics();
        float[] skyNoon = noonLighting.getSkyColor();
        float[] skyNight = midnightLighting.getSkyColor();
        g.setColor(new Color(skyNoon[0], skyNoon[1], skyNoon[2]));
        g.fillRect(0, 0, 200, 150);
        g.setColor(new Color(skyNight[0], skyNight[1], skyNight[2]));
        g.fillRect(200, 0, 200, 150);
        g.dispose();
        ScreenshotCapture.saveBufferedImage(swatch, "e2e_diurnal_lighting_swatch");
    }

    @Test
    @DisplayName("E2E: ZoneTransferNotification triggers ScreenFader fade-out, zone swap, and fade-in lifecycle")
    void testZoneTransferAndScreenFaderLifecycleE2E() {
        ScreenFader fader = new ScreenFader();
        assertThat(fader.getAlpha()).isEqualTo(0.0f);
        assertThat(fader.getState()).isEqualTo(ScreenFader.State.IDLE);

        // 1. Receive ZoneTransferNotification from server
        ZoneTransferNotification notification = new ZoneTransferNotification(
                100, "127.0.0.1", 9001,
                -120.0f, -5.0f, 85.0f
        );
        assertThat(notification.targetZoneId()).isEqualTo(100);

        // 2. Start fade transition (1 second duration)
        AtomicBoolean assetSwapped = new AtomicBoolean(false);
        fader.startTransition(1.0f, () -> {
            // While screen is completely black, swap zone DAT
            byte[] zone100Bytes = loadDatFile("west_ronfaure_100.DAT");
            assertThat(zone100Bytes).isNotNull();
            VisualZoneScene targetZoneScene = VisualZoneLoader.load(zone100Bytes);
            assertThat(targetZoneScene).isNotNull();
            assetSwapped.set(true);
        });

        assertThat(fader.getState()).isEqualTo(ScreenFader.State.FADING_OUT);
        assertThat(fader.isTransitioning()).isTrue();

        // Advance 0.5s -> half faded
        fader.update(0.5f);
        assertThat(fader.getAlpha()).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.05f));
        assertThat(assetSwapped.get()).isFalse();

        // Advance another 0.55s -> reaches black, executes callback, transitions to FADING_IN
        fader.update(0.55f);
        assertThat(assetSwapped.get()).isTrue();
        assertThat(fader.getState()).isEqualTo(ScreenFader.State.FADING_IN);

        // Advance 1.0s -> completes fade-in
        fader.update(1.0f);
        assertThat(fader.getAlpha()).isEqualTo(0.0f);
        assertThat(fader.getState()).isEqualTo(ScreenFader.State.IDLE);
        assertThat(fader.isTransitioning()).isFalse();
    }

    private byte[] loadDatFile(String filename) {
        List<Path> candidates = List.of(
                Path.of("game-asset-pipeline/src/test/resources/dats/zones", filename),
                Path.of("../game-asset-pipeline/src/test/resources/dats/zones", filename),
                Path.of("src/test/resources/dats/zones", filename)
        );

        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readAllBytes(p);
                } catch (Exception ignored) {}
            }
        }

        // Try classpath
        try (InputStream is = getClass().getResourceAsStream("/dats/zones/" + filename)) {
            if (is != null) return is.readAllBytes();
        } catch (Exception ignored) {}

        return null;
    }
}
