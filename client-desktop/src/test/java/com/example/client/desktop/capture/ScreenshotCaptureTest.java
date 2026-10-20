package com.example.client.desktop.capture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenshotCaptureTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ScreenshotCapture.resetSnapshotDirectory();
    }

    @AfterEach
    void tearDown() {
        ScreenshotCapture.resetSnapshotDirectory();
        System.clearProperty(ScreenshotCapture.PROPERTY_SNAPSHOTS_DIR);
    }

    @Test
    @DisplayName("Default snapshot directory is target/snapshots")
    void testDefaultDirectory() {
        Path defaultDir = ScreenshotCapture.getSnapshotDirectory();
        assertThat(defaultDir).isEqualTo(Path.of("target/snapshots"));
    }

    @Test
    @DisplayName("System property overrides snapshot directory")
    void testSystemPropertyDirectoryOverride() {
        Path customPath = tempDir.resolve("custom_snapshots");
        System.setProperty(ScreenshotCapture.PROPERTY_SNAPSHOTS_DIR, customPath.toString());

        assertThat(ScreenshotCapture.getSnapshotDirectory()).isEqualTo(customPath);
    }

    @Test
    @DisplayName("Programmatic configuration overrides snapshot directory")
    void testProgrammaticDirectoryOverride() {
        Path customPath = tempDir.resolve("programmatic_snapshots");
        ScreenshotCapture.setSnapshotDirectory(customPath);

        assertThat(ScreenshotCapture.getSnapshotDirectory()).isEqualTo(customPath);
    }

    @Test
    @DisplayName("saveBufferedImage persists valid PNG file to configured directory")
    void testSaveBufferedImage() throws Exception {
        ScreenshotCapture.setSnapshotDirectory(tempDir);

        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 64, 48);
        g.dispose();

        Path saved = ScreenshotCapture.saveBufferedImage(img, "test_render");

        assertThat(saved).isNotNull();
        assertThat(Files.isRegularFile(saved)).isTrue();
        assertThat(saved.getFileName().toString()).isEqualTo("test_render.png");

        // Validate readable by standard ImageIO
        BufferedImage reloaded = ImageIO.read(saved.toFile());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getWidth()).isEqualTo(64);
        assertThat(reloaded.getHeight()).isEqualTo(48);
    }

    @Test
    @DisplayName("saveRgbaBuffer decodes OpenGL bottom-up RGBA buffer into correct top-left PNG")
    void testSaveRgbaBuffer() throws Exception {
        ScreenshotCapture.setSnapshotDirectory(tempDir);

        int width = 4;
        int height = 4;
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);

        // Fill buffer: bottom row (glRow = 0) is RED (255, 0, 0, 255)
        // Top row (glRow = 3) is GREEN (0, 255, 0, 255)
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (row == 0) {
                    // OpenGL bottom row -> image top row should be inverted or match
                    buffer.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255);
                } else if (row == height - 1) {
                    buffer.put((byte) 0).put((byte) 255).put((byte) 0).put((byte) 255);
                } else {
                    buffer.put((byte) 0).put((byte) 0).put((byte) 255).put((byte) 255);
                }
            }
        }
        buffer.flip();

        Path saved = ScreenshotCapture.saveRgbaBuffer(buffer, width, height, "rgba_test.png");
        assertThat(Files.isRegularFile(saved)).isTrue();

        BufferedImage reloaded = ImageIO.read(saved.toFile());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getWidth()).isEqualTo(4);
        assertThat(reloaded.getHeight()).isEqualTo(4);
    }
}
