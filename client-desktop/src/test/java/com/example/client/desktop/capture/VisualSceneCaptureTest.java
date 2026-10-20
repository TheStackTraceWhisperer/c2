package com.example.client.desktop.capture;

import com.example.asset.environment.DiurnalLighting;
import com.example.asset.mesh.VisualSubMesh;
import com.example.asset.mesh.VisualZoneLoader;
import com.example.asset.mesh.VisualZoneScene;
import com.example.asset.skeleton.AnimationStateMachine;
import com.example.asset.skeleton.BlendedPose;
import com.example.asset.skeleton.CharacterMeshGenerator;
import com.example.asset.skeleton.CompositeCharacterModel;
import com.example.asset.skeleton.StandardAnimations;
import com.example.common.protocol.JobId;
import com.example.common.protocol.MovementFlags;
import com.example.common.time.VanadielTime;
import com.example.common.time.WeatherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Visual capture test suite demonstrating mid-test screenshot captures and RenderDoc frame dumps.
 * Visual outputs are stored in target/snapshots/ and RenderDoc templates in target/renderdoc/.
 */
public class VisualSceneCaptureTest {

    @Test
    @DisplayName("Visual Test: Capture South San d'Oria (Zone 230) geometry layout with RenderDoc capture hook")
    void testCaptureSouthSanDoriaSceneVisual() throws Exception {
        byte[] datBytes = loadDatFile("south_san_doria_230.DAT");
        assertThat(datBytes).isNotNull();

        VisualZoneScene scene = VisualZoneLoader.load(datBytes);
        assertThat(scene).isNotNull();

        // Enclose scene visual processing inside a RenderDoc frame capture if RenderDoc is active
        RenderDocCapture.captureFrameIfAvailable(0L, () -> {
            // Render 2D isometric/orthographic preview of zone bounding geometry
            int width = 800;
            int height = 600;
            BufferedImage preview = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = preview.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Sky / background
            g.setColor(new Color(25, 30, 45));
            g.fillRect(0, 0, width, height);

            // Grid
            g.setColor(new Color(40, 50, 70));
            for (int i = 0; i < width; i += 40) g.drawLine(i, 0, i, height);
            for (int i = 0; i < height; i += 40) g.drawLine(0, i, width, i);

            // Draw bounding boxes of all sub-meshes
            g.setColor(new Color(100, 180, 255, 180));
            for (var mesh : scene.meshes()) {
                var bbox = mesh.boundingBox();
                int rx = (int) (width / 2 + (bbox.min().x() + bbox.max().x()) * 0.5f * 1.5f);
                int ry = (int) (height / 2 + (bbox.min().z() + bbox.max().z()) * 0.5f * 1.5f);
                int rw = Math.max(4, (int) (bbox.max().x() - bbox.min().x()) * 2);
                int rh = Math.max(4, (int) (bbox.max().z() - bbox.min().z()) * 2);
                g.drawRect(rx - rw / 2, ry - rh / 2, rw, rh);
            }

            // Visual Header Bar
            g.setColor(new Color(60, 90, 140));
            g.fillRect(20, 20, 240, 28);
            g.setColor(new Color(180, 220, 255));
            g.drawRect(20, 20, 240, 28);

            g.dispose();

            // Save visual snapshot to target/snapshots/south_san_doria_preview.png
            Path snapshotFile = ScreenshotCapture.saveBufferedImage(preview, "south_san_doria_preview");
            assertThat(Files.isRegularFile(snapshotFile)).isTrue();
            assertThat(snapshotFile.getFileName().toString()).isEqualTo("south_san_doria_preview.png");
        });
    }

    @Test
    @DisplayName("Visual Test: Capture animated character skeletal pose with RenderDoc trigger")
    void testCaptureCharacterLocomotionVisual() {
        CompositeCharacterModel model = CharacterMeshGenerator.createCharacterModel(JobId.WARRIOR);
        AnimationStateMachine asm = StandardAnimations.createLocomotionStateMachine();

        // Advance into running stride
        for (int i = 0; i < 15; i++) {
            asm.update(0.033f, MovementFlags.FORWARD, 4.5f);
        }

        // Trigger RenderDoc frame capture
        RenderDocCapture.triggerCapture();

        BlendedPose pose = asm.evaluate(model.skeleton());
        assertThat(pose.jointPoseMatrices()).isNotEmpty();

        int width = 500;
        int height = 500;
        BufferedImage charPreview = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = charPreview.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Dark background
        g.setColor(new Color(20, 22, 28));
        g.fillRect(0, 0, width, height);

        // Ground shadow
        g.setColor(new Color(10, 10, 12, 180));
        g.fillOval(width / 2 - 80, height - 70, 160, 30);

        // Draw skeletal bones from pose matrices
        g.setColor(new Color(255, 215, 0));
        var joints = model.skeleton().joints();
        for (int i = 0; i < joints.size(); i++) {
            var matrix = pose.jointPoseMatrices().get(i);
            float[] elem = matrix.elements();
            // Translation is at indices (12, 13, 14) in column-major 4x4 matrix
            float tx = elem[12];
            float ty = elem[13];
            float tz = elem[14];

            int screenX = (int) (width / 2 + tx * 150.0f);
            int screenY = (int) (height - 80 - ty * 180.0f);

            g.fillOval(screenX - 4, screenY - 4, 8, 8);
            if (joints.get(i).parentIndex() >= 0) {
                var parentMatrix = pose.jointPoseMatrices().get(joints.get(i).parentIndex());
                float px = parentMatrix.elements()[12];
                float py = parentMatrix.elements()[13];
                int parentScreenX = (int) (width / 2 + px * 150.0f);
                int parentScreenY = (int) (height - 80 - py * 180.0f);
                g.drawLine(parentScreenX, parentScreenY, screenX, screenY);
            }
        }

        // Visual HUD Indicator Bar
        g.setColor(new Color(255, 180, 50));
        g.fillRect(20, 20, 180, 10);
        g.setColor(new Color(255, 255, 255));
        g.drawRect(20, 20, 180, 10);

        g.dispose();

        Path snapshotFile = ScreenshotCapture.saveBufferedImage(charPreview, "character_locomotion_preview");
        assertThat(Files.isRegularFile(snapshotFile)).isTrue();
    }

    @Test
    @DisplayName("Visual Test: Capture 24-hour Diurnal atmospheric lighting cycle swatch")
    void testCaptureDiurnalSkyboxAndLightingVisual() {
        int swatchWidth = 200;
        int swatchHeight = 300;
        int totalWidth = swatchWidth * 4;
        BufferedImage cycleImage = new BufferedImage(totalWidth, swatchHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cycleImage.createGraphics();

        float[] hours = new float[]{5.0f, 12.0f, 18.0f, 0.0f};

        for (int i = 0; i < 4; i++) {
            VanadielTime time = VanadielTime.ofHour(hours[i]);
            DiurnalLighting lighting = DiurnalLighting.compute(time, WeatherType.CLEAR);

            float[] sky = lighting.getSkyColor();
            float[] sun = lighting.getSunColor();
            float[] ambient = lighting.getAmbientColor();

            // Draw sky swatch
            g.setColor(new Color(sky[0], sky[1], sky[2]));
            g.fillRect(i * swatchWidth, 0, swatchWidth, swatchHeight);

            // Sunlight celestial indicator
            g.setColor(new Color(sun[0], sun[1], sun[2]));
            g.fillOval(i * swatchWidth + 30, 60, 40, 40);

            // Ambient light swatch rectangle
            g.setColor(new Color(ambient[0], ambient[1], ambient[2]));
            g.fillRect(i * swatchWidth + 30, 140, 80, 20);
            g.setColor(Color.WHITE);
            g.drawRect(i * swatchWidth + 30, 140, 80, 20);
        }

        g.dispose();

        Path snapshotFile = ScreenshotCapture.saveBufferedImage(cycleImage, "diurnal_skybox_cycle");
        assertThat(Files.isRegularFile(snapshotFile)).isTrue();
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

        try (InputStream is = getClass().getResourceAsStream("/dats/zones/" + filename)) {
            if (is != null) return is.readAllBytes();
        } catch (Exception ignored) {}

        return null;
    }
}
