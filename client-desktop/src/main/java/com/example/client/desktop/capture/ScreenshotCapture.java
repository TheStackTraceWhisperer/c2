package com.example.client.desktop.capture;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility for capturing in-game and test scene visuals directly from OpenGL framebuffers.
 * Supports active viewport capture, RGBA buffer writing, and offscreen FBO rendering passes.
 * Snapshot destination is configurable via system property 'c2.snapshots.dir', environment variable
 * 'C2_SNAPSHOTS_DIR', or programmatic configuration, defaulting to 'target/snapshots'.
 */
@Slf4j
public final class ScreenshotCapture {

    public static final String PROPERTY_SNAPSHOTS_DIR = "c2.snapshots.dir";
    public static final String ENV_SNAPSHOTS_DIR = "C2_SNAPSHOTS_DIR";
    public static final String DEFAULT_SNAPSHOTS_DIR = "target/snapshots";

    private static volatile Path configuredDirectory = null;

    private ScreenshotCapture() {
    }

    /**
     * Resolves the active snapshot output directory.
     */
    public static Path getSnapshotDirectory() {
        if (configuredDirectory != null) {
            return configuredDirectory;
        }

        String sysProp = System.getProperty(PROPERTY_SNAPSHOTS_DIR);
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp);
        }

        String envVal = System.getenv(ENV_SNAPSHOTS_DIR);
        if (envVal != null && !envVal.isBlank()) {
            return Path.of(envVal);
        }

        return Path.of(DEFAULT_SNAPSHOTS_DIR);
    }

    /**
     * Explicitly sets the snapshot directory for the current runtime.
     */
    public static void setSnapshotDirectory(Path directory) {
        configuredDirectory = directory;
    }

    /**
     * Resets the snapshot directory to use system properties/environment defaults.
     */
    public static void resetSnapshotDirectory() {
        configuredDirectory = null;
    }

    /**
     * Captures the contents of the currently active OpenGL framebuffer using the active viewport dimensions.
     *
     * @param screenshotName Filename without or with .png extension
     * @return Path to the saved PNG image file
     */
    public static Path captureActiveFramebuffer(String screenshotName) {
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int x = viewport[0];
        int y = viewport[1];
        int width = Math.max(1, viewport[2]);
        int height = Math.max(1, viewport[3]);

        return captureFramebuffer(x, y, width, height, screenshotName);
    }

    /**
     * Captures a specified rectangular region of the currently bound OpenGL framebuffer.
     */
    public static Path captureFramebuffer(int x, int y, int width, int height, String screenshotName) {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(x, y, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

        return saveRgbaBuffer(buffer, width, height, screenshotName);
    }

    /**
     * Executes a render pass into an isolated offscreen OpenGL Framebuffer Object (FBO),
     * captures the visual output, and saves it to a PNG image file.
     *
     * @param width Desired viewport width
     * @param height Desired viewport height
     * @param screenshotName Output image name
     * @param renderPass Rendering routine to execute while offscreen FBO is bound
     * @return Path to the saved image file
     */
    public static Path captureOffscreen(int width, int height, String screenshotName, Runnable renderPass) {
        int previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);

        int fbo = GL30.glGenFramebuffers();
        int colorRbo = GL30.glGenRenderbuffers();
        int depthRbo = GL30.glGenRenderbuffers();

        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

            // Color buffer
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, colorRbo);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, width, height);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, colorRbo);

            // Depth/Stencil buffer
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRbo);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depthRbo);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Offscreen FBO incomplete, status: " + status);
            }

            GL11.glViewport(0, 0, width, height);

            // Execute test rendering pass
            renderPass.run();

            // Read pixels directly from FBO
            return captureFramebuffer(0, 0, width, height, screenshotName);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GL30.glDeleteRenderbuffers(depthRbo);
            GL30.glDeleteRenderbuffers(colorRbo);
            GL30.glDeleteFramebuffers(fbo);
        }
    }

    /**
     * Converts a raw RGBA ByteBuffer into an inverted top-left PNG image file.
     */
    public static Path saveRgbaBuffer(ByteBuffer buffer, int width, int height, String screenshotName) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // OpenGL stores pixels bottom-to-top, so invert the row index
                int glRow = height - 1 - y;
                int index = (glRow * width + x) * 4;
                int r = buffer.get(index) & 0xFF;
                int g = buffer.get(index + 1) & 0xFF;
                int b = buffer.get(index + 2) & 0xFF;
                int a = buffer.get(index + 3) & 0xFF;
                int argb = (a << 24) | (r << 16) | (g << 8) | b;
                image.setRGB(x, y, argb);
            }
        }

        return saveBufferedImage(image, screenshotName);
    }

    /**
     * Persists a BufferedImage as a PNG file into the configured snapshot directory.
     */
    public static Path saveBufferedImage(BufferedImage image, String screenshotName) {
        try {
            Path targetDir = getSnapshotDirectory();
            Files.createDirectories(targetDir);

            String filename = screenshotName.endsWith(".png") ? screenshotName : screenshotName + ".png";
            Path targetFile = targetDir.resolve(filename);

            ImageIO.write(image, "PNG", targetFile.toFile());
            log.info("[SCREENSHOT] Saved test visual snapshot to {}", targetFile.toAbsolutePath());
            return targetFile;
        } catch (IOException e) {
            log.error("[SCREENSHOT] Failed to save screenshot {}", screenshotName, e);
            throw new RuntimeException("Failed to save screenshot: " + screenshotName, e);
        }
    }
}
