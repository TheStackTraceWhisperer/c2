package com.example.client.desktop.camera;

import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import org.lwjgl.glfw.GLFW;

/**
 * Spherical polar orbit camera implementing XIM-style polar camera kinematics.
 * Maps 0 deg yaw to North (+Z) and 90 deg yaw to East (+X), matching FFXI world coordinates.
 */
@Getter
@Singleton
public class PolarCamera implements Camera {

    public static final float DEFAULT_YAW = 0.0f; // 0 deg = North (+Z)
    public static final float DEFAULT_PITCH = 25.0f; // 25 deg elevation above target
    public static final float DEFAULT_DISTANCE = 8.0f;
    public static final float MIN_DISTANCE = 2.0f;
    public static final float MAX_DISTANCE = 50.0f;
    public static final float MIN_PITCH = -80.0f;
    public static final float MAX_PITCH = 80.0f;

    public static final float KEYBOARD_TURN_SPEED = 90.0f; // degrees / sec
    public static final float KEYBOARD_PITCH_SPEED = 45.0f; // degrees / sec
    public static final float ZOOM_SPEED = 10.0f; // meters / sec
    public static final float MOUSE_SENSITIVITY = 0.20f; // degrees / pixel

    private float targetX = 0.0f;
    private float targetY = 0.0f;
    private float targetZ = 0.0f;
    @Setter private float targetOffsetY = 1.0f; // Eye height above player base

    private float yaw = DEFAULT_YAW;
    private float pitch = DEFAULT_PITCH;
    private float distance = DEFAULT_DISTANCE;

    // Derived spatial vectors
    private float eyeX;
    private float eyeY;
    private float eyeZ;
    private float viewX;
    private float viewY;
    private float viewZ;
    private float forwardX;
    private float forwardZ;
    private float rightX;
    private float rightZ;

    // Mouse drag state
    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    private boolean mouseDragging = false;

    public PolarCamera() {
        updateVectors();
    }

    @Override
    public void setTarget(float x, float y, float z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        updateVectors();
    }

    @Override
    public void setYaw(float yaw) {
        this.yaw = normalizeAngle(yaw);
        updateVectors();
    }

    @Override
    public void setPitch(float pitch) {
        this.pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
        updateVectors();
    }

    @Override
    public void setDistance(float distance) {
        this.distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
        updateVectors();
    }

    @Override
    public void rotate(float deltaYaw, float deltaPitch) {
        this.yaw = normalizeAngle(this.yaw + deltaYaw);
        this.pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, this.pitch + deltaPitch));
        updateVectors();
    }

    @Override
    public void zoom(float deltaDistance) {
        this.distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, this.distance + deltaDistance));
        updateVectors();
    }

    @Override
    public void reset() {
        this.yaw = DEFAULT_YAW;
        this.pitch = DEFAULT_PITCH;
        this.distance = DEFAULT_DISTANCE;
        updateVectors();
    }

    @Override
    public void update(long windowHandle, float dt) {
        if (windowHandle == 0L) {
            return;
        }

        // 1. Keyboard rotation (Q/E or Arrow Left/Right for yaw, Arrow Up/Down for pitch, PageUp/PageDown for zoom, Home to reset)
        float dYaw = 0.0f;
        float dPitch = 0.0f;

        if (isKeyDown(windowHandle, GLFW.GLFW_KEY_Q) || isKeyDown(windowHandle, GLFW.GLFW_KEY_LEFT)) {
            dYaw -= KEYBOARD_TURN_SPEED * dt;
        }
        if (isKeyDown(windowHandle, GLFW.GLFW_KEY_E) || isKeyDown(windowHandle, GLFW.GLFW_KEY_RIGHT)) {
            dYaw += KEYBOARD_TURN_SPEED * dt;
        }
        if (isKeyDown(windowHandle, GLFW.GLFW_KEY_UP)) {
            dPitch += KEYBOARD_PITCH_SPEED * dt;
        }
        if (isKeyDown(windowHandle, GLFW.GLFW_KEY_DOWN)) {
            dPitch -= KEYBOARD_PITCH_SPEED * dt;
        }
        if (isKeyDown(windowHandle, GLFW.GLFW_KEY_PAGE_UP)) {
            zoom(-ZOOM_SPEED * dt);
        }
        if (isKeyDown(windowHandle, GLFW.GLFW_KEY_PAGE_DOWN)) {
            zoom(ZOOM_SPEED * dt);
        }
        if (isKeyDown(windowHandle, GLFW.GLFW_KEY_HOME)) {
            reset();
        }

        if (dYaw != 0.0f || dPitch != 0.0f) {
            rotate(dYaw, dPitch);
        }

        // 2. Mouse drag rotation (Left or Right Mouse Button free orbit when not interacting with UI)
        boolean uiWantsMouse = false;
        try {
            uiWantsMouse = imgui.ImGui.getIO().getWantCaptureMouse();
        } catch (Throwable ignored) {}

        boolean mouseDown = !uiWantsMouse && (
                GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        );
        double[] xpos = new double[1];
        double[] ypos = new double[1];
        GLFW.glfwGetCursorPos(windowHandle, xpos, ypos);

        if (mouseDown) {
            if (mouseDragging) {
                double dx = xpos[0] - lastMouseX;
                double dy = ypos[0] - lastMouseY;
                if (dx != 0.0 || dy != 0.0) {
                    rotate((float) (dx * MOUSE_SENSITIVITY), (float) (-dy * MOUSE_SENSITIVITY));
                }
            } else {
                mouseDragging = true;
            }
            lastMouseX = xpos[0];
            lastMouseY = ypos[0];
        } else {
            mouseDragging = false;
        }
    }

    private void updateVectors() {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        // Horizontal forward vector (FFXI: 0 deg = North/+Z, 90 deg = East/+X)
        forwardX = (float) Math.sin(yawRad);
        forwardZ = (float) Math.cos(yawRad);

        // Horizontal right vector (perpendicular, 90 deg clockwise in XZ plane)
        rightX = (float) Math.cos(yawRad);
        rightZ = (float) -Math.sin(yawRad);

        // Eye position in spherical coordinates relative to target
        float horizDist = distance * (float) Math.cos(pitchRad);
        float vertDist = distance * (float) Math.sin(pitchRad);

        eyeX = targetX - forwardX * horizDist;
        eyeY = targetY + targetOffsetY + vertDist;
        eyeZ = targetZ - forwardZ * horizDist;

        // Normalized 3D view vector (eye looking toward target)
        viewX = forwardX * (float) Math.cos(pitchRad);
        viewY = -(float) Math.sin(pitchRad);
        viewZ = forwardZ * (float) Math.cos(pitchRad);
    }

    private static float normalizeAngle(float angle) {
        float a = angle % 360.0f;
        if (a < 0.0f) a += 360.0f;
        return a;
    }

    private static boolean isKeyDown(long windowHandle, int key) {
        return GLFW.glfwGetKey(windowHandle, key) == GLFW.GLFW_PRESS;
    }
}
