package com.example.client.desktop.scene;

import com.example.client.desktop.camera.Camera;
import com.ffxi.viewer.collision.ZoneCollisionSystem;
import imgui.ImGui;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Unified Scene Camera abstraction supporting retail third-person gameplay,
 * directed in-game cutscenes, and free development inspection.
 * Uses the unified client-side right-handed coordinate convention:
 * -Y = Up, North = +Z, East = +X, yaw = 0 deg faces North (+Z), 90 deg faces East (+X).
 */
@Getter
@Singleton
public class SceneCamera implements Camera {

    public static final float DEFAULT_FOV = 50.0f; // Authentic FFXI 50-degree vertical FOV
    public static final float DEFAULT_YAW = 0.0f;  // 0 deg = North (+Z)
    public static final float DEFAULT_PITCH = 25.0f; // 25 deg elevation
    public static final float DEFAULT_DISTANCE = 8.0f;
    public static final float MIN_DISTANCE = 1.0f;
    public static final float MAX_DISTANCE = 100.0f;
    public static final float MIN_PITCH = -85.0f;
    public static final float MAX_PITCH = 85.0f;

    public static final float KEYBOARD_TURN_SPEED = 90.0f;  // degrees / sec
    public static final float KEYBOARD_PITCH_SPEED = 45.0f; // degrees / sec
    public static final float ZOOM_SPEED = 10.0f;           // meters / sec
    public static final float MOUSE_SENSITIVITY = 0.20f;    // degrees / pixel
    public static final float MIN_COLLISION_DISTANCE = 0.75f;
    public static final float CAMERA_COLLISION_MARGIN = 0.25f;

    private CameraMode mode = CameraMode.THIRD_PERSON;
    @Setter private float fov = DEFAULT_FOV;
    @Setter private float nearPlane = 0.1f;
    @Setter private float farPlane = 100000.0f;
    private boolean orthographic = false;
    private float orthoHalfWidth = 100.0f;
    private float orthoHalfHeight = 100.0f;

    // Polar orbit coordinates (Third Person & Free Orbit)
    private float targetX = 0.0f;
    private float targetY = 0.0f;
    private float targetZ = 0.0f;
    @Setter private float targetOffsetY = 1.0f;

    private float yaw = DEFAULT_YAW;
    private float pitch = DEFAULT_PITCH;
    private float distance = DEFAULT_DISTANCE;
    private float effectiveDistance = DEFAULT_DISTANCE;
    @Setter private boolean cameraCollisionEnabled = true;

    // Directed Cutscene coordinates
    private final Vector3f cutsceneEye = new Vector3f(0f, -2f, -5f);
    private final Vector3f cutsceneTarget = new Vector3f(0f, -1f, 0f);
    private final Vector3f cutsceneUp = new Vector3f(0f, -1f, 0f);

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

    public SceneCamera() {
        updateVectors();
    }

    @Override
    public void setTarget(float x, float y, float z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        if (mode != CameraMode.CUTSCENE) {
            updateVectors();
        }
    }

    @Override
    public void setYaw(float yaw) {
        this.yaw = normalizeAngle(yaw);
        if (mode != CameraMode.CUTSCENE) {
            updateVectors();
        }
    }

    @Override
    public void setPitch(float pitch) {
        this.pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
        if (mode != CameraMode.CUTSCENE) {
            updateVectors();
        }
    }

    @Override
    public void setDistance(float distance) {
        this.distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
        if (mode != CameraMode.CUTSCENE) {
            updateVectors();
        }
    }

    @Override
    public void rotate(float deltaYaw, float deltaPitch) {
        setYaw(this.yaw + deltaYaw);
        setPitch(this.pitch + deltaPitch);
    }

    @Override
    public void zoom(float deltaDistance) {
        setDistance(this.distance + deltaDistance);
    }

    public void setMode(CameraMode mode) {
        this.mode = mode;
        if (mode != CameraMode.CUTSCENE) {
            this.orthographic = false;
            updateVectors();
        }
    }

    @Override
    public void reset() {
        this.yaw = DEFAULT_YAW;
        this.pitch = DEFAULT_PITCH;
        this.distance = DEFAULT_DISTANCE;
        this.fov = DEFAULT_FOV;
        this.mode = CameraMode.THIRD_PERSON;
        this.orthographic = false;
        updateVectors();
    }

    /**
     * Direct configuration for cutscenes, establishing eye position, target focus, up vector, and FOV.
     */
    public void setCutsceneCamera(Vector3f eye, Vector3f target, Vector3f up, float fovDegrees) {
        this.mode = CameraMode.CUTSCENE;
        this.orthographic = false;
        this.cutsceneEye.set(eye);
        this.cutsceneTarget.set(target);
        this.cutsceneUp.set(up);
        this.fov = fovDegrees;

        this.eyeX = eye.x;
        this.eyeY = eye.y;
        this.eyeZ = eye.z;
        this.targetX = target.x;
        this.targetY = target.y;
        this.targetZ = target.z;

        Vector3f dir = new Vector3f(target).sub(eye).normalize();
        this.viewX = dir.x;
        this.viewY = dir.y;
        this.viewZ = dir.z;

        float lenXZ = (float) Math.hypot(dir.x, dir.z);
        if (lenXZ > 1e-4f) {
            this.forwardX = dir.x / lenXZ;
            this.forwardZ = dir.z / lenXZ;
            this.rightX = this.forwardZ;
            this.rightZ = -this.forwardX;
        }
    }

    /**
     * Direct configuration for orthographic top-down overview captures.
     */
    public void setOrthographicCamera(
            Vector3f eye,
            Vector3f target,
            Vector3f up,
            float halfWidth,
            float halfHeight,
            float nearPlane,
            float farPlane
    ) {
        this.mode = CameraMode.CUTSCENE;
        this.orthographic = true;
        this.orthoHalfWidth = halfWidth;
        this.orthoHalfHeight = halfHeight;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
        this.cutsceneEye.set(eye);
        this.cutsceneTarget.set(target);
        this.cutsceneUp.set(up);

        this.eyeX = eye.x;
        this.eyeY = eye.y;
        this.eyeZ = eye.z;
        this.targetX = target.x;
        this.targetY = target.y;
        this.targetZ = target.z;

        Vector3f dir = new Vector3f(target).sub(eye);
        if (dir.lengthSquared() > 1e-8f) {
            dir.normalize();
            this.viewX = dir.x;
            this.viewY = dir.y;
            this.viewZ = dir.z;

            float lenXZ = (float) Math.hypot(dir.x, dir.z);
            if (lenXZ > 1e-4f) {
                this.forwardX = dir.x / lenXZ;
                this.forwardZ = dir.z / lenXZ;
                this.rightX = this.forwardZ;
                this.rightZ = -this.forwardX;
            }
        }
    }

    /**
     * Interpolates smoothly between cutscene camera states for cinematic tracking shots.
     */
    public void interpolateCutscene(
            Vector3f fromEye, Vector3f toEye,
            Vector3f fromTarget, Vector3f toTarget,
            float fromFov, float toFov,
            float t
    ) {
        float clampedT = Math.max(0.0f, Math.min(1.0f, t));
        Vector3f curEye = new Vector3f(fromEye).lerp(toEye, clampedT);
        Vector3f curTarget = new Vector3f(fromTarget).lerp(toTarget, clampedT);
        float curFov = fromFov + (toFov - fromFov) * clampedT;
        setCutsceneCamera(curEye, curTarget, new Vector3f(0f, -1f, 0f), curFov);
    }

    @Override
    public void update(long windowHandle, float dt) {
        if (windowHandle == 0L || mode == CameraMode.CUTSCENE) {
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

        // 2. Mouse drag rotation (Left or Right Mouse Button free orbit when not interacting with ImGui UI)
        boolean uiWantsMouse = false;
        try {
            var imguiCtx = ImGui.getCurrentContext();
            if (imguiCtx != null && imguiCtx.isValidPtr()) {
                uiWantsMouse = ImGui.getIO().getWantCaptureMouse();
            }
        } catch (Throwable ignored) {}

        boolean mouseDown = !uiWantsMouse && (
                GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        );
        double[] xpos = new double[1];
        double[] ypos = new double[1];
        GLFW.glfwGetCursorPos(windowHandle, xpos, ypos);

        if (mouseDown) {
            if (!mouseDragging) {
                mouseDragging = true;
                lastMouseX = xpos[0];
                lastMouseY = ypos[0];
            } else {
                double dx = xpos[0] - lastMouseX;
                double dy = ypos[0] - lastMouseY;
                if (dx != 0.0 || dy != 0.0) {
                    rotate((float) (dx * MOUSE_SENSITIVITY), (float) (-dy * MOUSE_SENSITIVITY));
                }
                lastMouseX = xpos[0];
                lastMouseY = ypos[0];
            }
        } else {
            mouseDragging = false;
        }
    }

    /**
     * Pulls the camera eye inward along its orbit ray when zone geometry lies between the target focus
     * and the desired orbit eye, and prevents the camera eye from dropping below the local floor surface.
     */
    public void applyCollisionClamp(ZoneCollisionSystem collisionSystem) {
        if (collisionSystem == null || !cameraCollisionEnabled || mode == CameraMode.CUTSCENE) {
            return;
        }
        updateVectors();

        float focusY = targetY - targetOffsetY;
        float hitDist = collisionSystem.raycastSegmentDistance(
                targetX, focusY, targetZ,
                eyeX, eyeY, eyeZ,
                0.05f
        );
        if (Float.isFinite(hitDist) && hitDist < distance) {
            float clamped = Math.max(MIN_COLLISION_DISTANCE, Math.min(distance, hitDist - CAMERA_COLLISION_MARGIN));
            this.effectiveDistance = clamped;
            double yawRad = Math.toRadians(yaw);
            double pitchRad = Math.toRadians(pitch);
            double cosPitch = Math.cos(pitchRad);
            this.eyeX = (float) (targetX - clamped * cosPitch * Math.sin(yawRad));
            this.eyeY = (float) (focusY - clamped * Math.sin(pitchRad));
            this.eyeZ = (float) (targetZ - clamped * cosPitch * Math.cos(yawRad));
        }

        float floorY = collisionSystem.findFloorY(eyeX, eyeZ, focusY - 0.5f);
        if (Float.isFinite(floorY) && eyeY > floorY - CAMERA_COLLISION_MARGIN) {
            this.eyeY = floorY - CAMERA_COLLISION_MARGIN;
        }
    }

    public Matrix4f getProjectionMatrix(float aspect) {
        if (orthographic) {
            return new Matrix4f().ortho(-orthoHalfWidth, orthoHalfWidth, -orthoHalfHeight, orthoHalfHeight, nearPlane, farPlane);
        }
        return new Matrix4f().perspective((float) Math.toRadians(fov), aspect, nearPlane, farPlane);
    }

    public Matrix4f getViewMatrix() {
        if (mode == CameraMode.CUTSCENE) {
            return new Matrix4f().lookAt(cutsceneEye, cutsceneTarget, cutsceneUp);
        }
        return new Matrix4f().lookAt(
                new Vector3f(eyeX, eyeY, eyeZ),
                new Vector3f(targetX, targetY - targetOffsetY, targetZ),
                new Vector3f(0f, -1f, 0f)
        );
    }

    private void updateVectors() {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        double cosPitch = Math.cos(pitchRad);
        double sinPitch = Math.sin(pitchRad);
        double sinYaw = Math.sin(yawRad);
        double cosYaw = Math.cos(yawRad);

        // Unified native FFXI coordinate system:
        // -Y = Up, North = +Z, East = +X; yaw = 0 deg faces North (+Z), 90 deg faces East (+X).
        this.forwardX = (float) sinYaw;
        this.forwardZ = (float) cosYaw;

        this.rightX = (float) cosYaw;
        this.rightZ = (float) -sinYaw;

        this.effectiveDistance = this.distance;
        float focusY = targetY - targetOffsetY;
        this.eyeX = (float) (targetX - distance * cosPitch * sinYaw);
        this.eyeY = (float) (focusY - distance * sinPitch);
        this.eyeZ = (float) (targetZ - distance * cosPitch * cosYaw);

        this.viewX = (float) (forwardX * cosPitch);
        this.viewY = (float) sinPitch;
        this.viewZ = (float) (forwardZ * cosPitch);
    }

    private static boolean isKeyDown(long windowHandle, int key) {
        return GLFW.glfwGetKey(windowHandle, key) == GLFW.GLFW_PRESS;
    }

    private static float normalizeAngle(float degrees) {
        degrees %= 360.0f;
        if (degrees < 0.0f) {
            degrees += 360.0f;
        }
        return degrees;
    }
}
