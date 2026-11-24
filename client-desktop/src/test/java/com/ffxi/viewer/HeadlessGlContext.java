package com.ffxi.viewer;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.Locale;

/**
 * Reusable hidden OpenGL 4.6 Core GLFW context for headless test verification under Xvfb + Mesa.
 * Initializes GLFW once per JVM and terminates on JVM shutdown so multiple test suites can
 * safely create and destroy offscreen windows within the same Surefire/Failsafe fork.
 */
@Slf4j
public final class HeadlessGlContext implements AutoCloseable {

    private static final Object GLFW_LOCK = new Object();
    private static boolean glfwInitialized = false;
    private static GLFWErrorCallback errorCallback;

    private long windowHandle;
    private boolean closed = false;

    private HeadlessGlContext(long windowHandle) {
        this.windowHandle = windowHandle;
    }

    public static boolean isDisplayAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) {
            return true;
        }
        String display = System.getenv("DISPLAY");
        return display != null && !display.isBlank();
    }

    public static HeadlessGlContext create() {
        return create(1, 1);
    }

    public static HeadlessGlContext create(int width, int height) {
        ensureGlfwInitialized();

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);

        long window = GLFW.glfwCreateWindow(Math.max(1, width), Math.max(1, height), "c2-headless-gl46", 0L, 0L);
        if (window == 0L) {
            throw new IllegalStateException(
                    "Failed to create hidden OpenGL 4.6 Core GLFW window (ensure Xvfb + MESA_GL_VERSION_OVERRIDE=4.6 via scripts/with-xvfb.sh)"
            );
        }

        GLFW.glfwMakeContextCurrent(window);
        if (GLFW.glfwGetCurrentContext() != window) {
            GLFW.glfwDestroyWindow(window);
            throw new IllegalStateException("Failed to make headless OpenGL 4.6 Core context current");
        }

        GL.createCapabilities();

        int major = GL11.glGetInteger(GL30.GL_MAJOR_VERSION);
        int minor = GL11.glGetInteger(GL30.GL_MINOR_VERSION);
        if (major < 4 || (major == 4 && minor < 6)) {
            GLFW.glfwMakeContextCurrent(0L);
            GL.setCapabilities(null);
            GLFW.glfwDestroyWindow(window);
            throw new IllegalStateException("Expected OpenGL >= 4.6 Core context, got " + major + "." + minor);
        }

        log.info("Headless OpenGL context initialized: version='{}', renderer='{}', glsl='{}'",
                GL11.glGetString(GL11.GL_VERSION),
                GL11.glGetString(GL11.GL_RENDERER),
                GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));

        return new HeadlessGlContext(window);
    }

    private static void ensureGlfwInitialized() {
        synchronized (GLFW_LOCK) {
            if (glfwInitialized) {
                return;
            }
            errorCallback = GLFWErrorCallback.create((error, description) ->
                    log.error("[GLFW] code={} description={}", error, GLFWErrorCallback.getDescription(description)));
            errorCallback.set();

            if (!GLFW.glfwInit()) {
                GLFWErrorCallback prev = GLFW.glfwSetErrorCallback(null);
                if (prev != null) {
                    prev.free();
                }
                errorCallback = null;
                throw new IllegalStateException(
                        "Unable to initialize GLFW for headless OpenGL verification (run via scripts/with-xvfb.sh)"
                );
            }

            glfwInitialized = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                synchronized (GLFW_LOCK) {
                    if (glfwInitialized) {
                        GLFW.glfwTerminate();
                        GLFWErrorCallback prev = GLFW.glfwSetErrorCallback(null);
                        if (prev != null) {
                            prev.free();
                        }
                        errorCallback = null;
                        glfwInitialized = false;
                    }
                }
            }, "headless-gl-glfw-shutdown"));
        }
    }

    public long getWindowHandle() {
        return windowHandle;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (windowHandle != 0L) {
            if (GLFW.glfwGetCurrentContext() == windowHandle) {
                GLFW.glfwMakeContextCurrent(0L);
                GL.setCapabilities(null);
            }
            GLFW.glfwDestroyWindow(windowHandle);
            windowHandle = 0L;
        }
    }
}
