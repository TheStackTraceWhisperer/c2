package com.example.client.desktop.capture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RenderDocCaptureTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RenderDocCapture.resetRenderDocDirectory();
    }

    @AfterEach
    void tearDown() {
        RenderDocCapture.resetRenderDocDirectory();
        System.clearProperty(RenderDocCapture.PROPERTY_RENDERDOC_DIR);
    }

    @Test
    @DisplayName("Default RenderDoc capture directory is target/renderdoc")
    void testDefaultDirectory() {
        Path defaultDir = RenderDocCapture.getRenderDocDirectory();
        assertThat(defaultDir).isEqualTo(Path.of("target/renderdoc"));
    }

    @Test
    @DisplayName("System property overrides RenderDoc directory")
    void testSystemPropertyDirectoryOverride() {
        Path customPath = tempDir.resolve("custom_renderdoc");
        System.setProperty(RenderDocCapture.PROPERTY_RENDERDOC_DIR, customPath.toString());

        assertThat(RenderDocCapture.getRenderDocDirectory()).isEqualTo(customPath);
    }

    @Test
    @DisplayName("Programmatic configuration overrides RenderDoc directory")
    void testProgrammaticDirectoryOverride() {
        Path customPath = tempDir.resolve("programmatic_renderdoc");
        RenderDocCapture.setRenderDocDirectory(customPath);

        assertThat(RenderDocCapture.getRenderDocDirectory()).isEqualTo(customPath);
    }

    @Test
    @DisplayName("RenderDocCapture methods degrade gracefully when RenderDoc is not attached")
    void testGracefulDegradationWhenNotAttached() {
        // When not run under RenderDoc GUI/CLI or without librenderdoc, initialize returns false
        boolean initialized = RenderDocCapture.initialize();
        // Regardless of whether RenderDoc is installed on host system, calls must not throw exceptions
        boolean start = RenderDocCapture.startFrameCapture(0L);
        boolean end = RenderDocCapture.endFrameCapture(0L);
        boolean trigger = RenderDocCapture.triggerCapture();
        boolean capturing = RenderDocCapture.isFrameCapturing();
        int count = RenderDocCapture.getNumCaptures();

        if (!initialized) {
            assertThat(start).isFalse();
            assertThat(end).isFalse();
            assertThat(trigger).isFalse();
            assertThat(capturing).isFalse();
            assertThat(count).isEqualTo(0);
        }

        // Test captureFrameIfAvailable wrapper executes action safely
        AtomicBoolean actionExecuted = new AtomicBoolean(false);
        RenderDocCapture.captureFrameIfAvailable(0L, () -> actionExecuted.set(true));
        assertThat(actionExecuted.get()).isTrue();
    }
}
