package com.example.client.desktop.capture;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.util.renderdoc.RenderDoc;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Controller for triggering RenderDoc graphics debugger frame dumps mid-test or during gameplay.
 * Integrates with RenderDoc's in-application API via LWJGL.
 * Gracefully degrades if RenderDoc is not attached or present in the current process.
 * Destination directory is configurable via system property 'c2.renderdoc.dir', environment variable
 * 'C2_RENDERDOC_DIR', or programmatic configuration, defaulting to 'target/renderdoc'.
 */
@Slf4j
public final class RenderDocCapture {

    public static final String PROPERTY_RENDERDOC_DIR = "c2.renderdoc.dir";
    public static final String ENV_RENDERDOC_DIR = "C2_RENDERDOC_DIR";
    public static final String DEFAULT_RENDERDOC_DIR = "target/renderdoc";

    private static volatile Path configuredDirectory = null;
    private static volatile boolean initialized = false;
    private static volatile boolean available = false;

    private RenderDocCapture() {
    }

    /**
     * Resolves the active RenderDoc capture directory.
     */
    public static Path getRenderDocDirectory() {
        if (configuredDirectory != null) {
            return configuredDirectory;
        }

        String sysProp = System.getProperty(PROPERTY_RENDERDOC_DIR);
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp);
        }

        String envVal = System.getenv(ENV_RENDERDOC_DIR);
        if (envVal != null && !envVal.isBlank()) {
            return Path.of(envVal);
        }

        return Path.of(DEFAULT_RENDERDOC_DIR);
    }

    /**
     * Explicitly sets the RenderDoc capture destination directory.
     */
    public static void setRenderDocDirectory(Path directory) {
        configuredDirectory = directory;
        if (available) {
            updateCaptureTemplate();
        }
    }

    /**
     * Resets the RenderDoc capture directory to use system properties/environment defaults.
     */
    public static void resetRenderDocDirectory() {
        configuredDirectory = null;
        if (available) {
            updateCaptureTemplate();
        }
    }

    /**
     * Initializes the RenderDoc in-application API if the RenderDoc library is loaded or attached.
     * Safe to call multiple times.
     *
     * @return true if RenderDoc API is bound and ready for frame captures; false otherwise
     */
    public static synchronized boolean initialize() {
        if (initialized) {
            return available;
        }

        initialized = true;
        try {
            RenderDoc.create();
            available = true;
            updateCaptureTemplate();
            log.info("[RENDERDOC] RenderDoc in-application API bound successfully. Capture dir: {}",
                    getRenderDocDirectory().toAbsolutePath());
            return true;
        } catch (Throwable t) {
            available = false;
            log.info("[RENDERDOC] RenderDoc is not loaded/attached ({}); frame dump triggers will be no-ops",
                    t.getMessage());
            return false;
        }
    }

    /**
     * Returns true if RenderDoc is attached and ready to capture frames.
     */
    public static boolean isAvailable() {
        if (!initialized) {
            initialize();
        }
        return available;
    }

    /**
     * Starts capturing GPU commands for the specified window on the next frame boundary.
     *
     * @param windowHandle GLFW window handle or 0L for NULL handle
     * @return true if frame capture was initiated, false if RenderDoc is unavailable
     */
    public static boolean startFrameCapture(long windowHandle) {
        if (!isAvailable()) {
            return false;
        }

        try {
            RenderDoc.RENDERDOC_StartFrameCapture(0L, windowHandle);
            log.info("[RENDERDOC] Started frame capture for window={}", windowHandle);
            return true;
        } catch (Throwable t) {
            log.warn("[RENDERDOC] Failed to start frame capture", t);
            return false;
        }
    }

    /**
     * Ends an active frame capture, generating a .rdc capture file in the configured directory.
     *
     * @param windowHandle GLFW window handle or 0L for NULL handle
     * @return true if frame capture completed successfully, false otherwise
     */
    public static boolean endFrameCapture(long windowHandle) {
        if (!isAvailable()) {
            return false;
        }

        try {
            boolean success = RenderDoc.RENDERDOC_EndFrameCapture(0L, windowHandle);
            log.info("[RENDERDOC] Ended frame capture for window={}, captureSuccess={}", windowHandle, success);
            return success;
        } catch (Throwable t) {
            log.warn("[RENDERDOC] Failed to end frame capture", t);
            return false;
        }
    }

    /**
     * Triggers an immediate one-frame capture across the active graphics context.
     *
     * @return true if trigger succeeded, false if RenderDoc is unavailable
     */
    public static boolean triggerCapture() {
        if (!isAvailable()) {
            return false;
        }

        try {
            RenderDoc.RENDERDOC_TriggerCapture();
            log.info("[RENDERDOC] Triggered one-frame capture");
            return true;
        } catch (Throwable t) {
            log.warn("[RENDERDOC] Failed to trigger capture", t);
            return false;
        }
    }

    /**
     * Returns true if a frame capture is currently in progress.
     */
    public static boolean isFrameCapturing() {
        if (!isAvailable()) {
            return false;
        }
        try {
            return RenderDoc.RENDERDOC_IsFrameCapturing();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns total number of frame captures made by RenderDoc during this process.
     */
    public static int getNumCaptures() {
        if (!isAvailable()) {
            return 0;
        }
        try {
            return RenderDoc.RENDERDOC_GetNumCaptures();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Executes a rendering block enclosed inside a RenderDoc frame capture if RenderDoc is available.
     *
     * @param windowHandle GLFW window handle or 0L
     * @param renderAction Runnable code block performing rendering
     */
    public static void captureFrameIfAvailable(long windowHandle, Runnable renderAction) {
        boolean started = startFrameCapture(windowHandle);
        try {
            renderAction.run();
        } finally {
            if (started) {
                endFrameCapture(windowHandle);
            }
        }
    }

    private static void updateCaptureTemplate() {
        try {
            Path targetDir = getRenderDocDirectory();
            Files.createDirectories(targetDir);
            String template = targetDir.resolve("capture").toAbsolutePath().toString();
            RenderDoc.RENDERDOC_SetCaptureFilePathTemplate(template);
        } catch (Throwable t) {
            log.warn("[RENDERDOC] Failed to set capture file path template", t);
        }
    }
}
