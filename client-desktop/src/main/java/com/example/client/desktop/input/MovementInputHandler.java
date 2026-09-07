package com.example.client.desktop.input;

import com.example.client.desktop.camera.Camera;
import com.example.common.protocol.MovementFlags;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;
import org.lwjgl.glfw.GLFW;

@Prototype
public class MovementInputHandler {

    public static final float MOVE_SPEED = 6.0f; // meters per second
    public static final float TURN_SPEED = 180.0f; // degrees per second
    public static final long PACKET_DISPATCH_INTERVAL_MS = 50L; // 20 Hz target

    public record MovementResult(
            float x, float y, float z,
            float vx, float vy, float vz,
            float yaw, float pitch,
            int moveFlags,
            boolean isMoving,
            boolean shouldSendPacket
    ) {}

    @Getter private long lastPacketTimeMs = -1000L;
    @Getter private boolean wasMoving = false;
    @Getter private long clientTickCounter = 0L;

    public MovementResult pollAndCompute(long windowHandle, Camera camera,
                                         float currentX, float currentY, float currentZ,
                                         float currentYaw, float currentPitch,
                                         float dt, long nowMs) {
        boolean forward = isKeyDown(windowHandle, GLFW.GLFW_KEY_W);
        boolean backward = isKeyDown(windowHandle, GLFW.GLFW_KEY_S);
        boolean moveLeft = isKeyDown(windowHandle, GLFW.GLFW_KEY_A);
        boolean moveRight = isKeyDown(windowHandle, GLFW.GLFW_KEY_D);
        boolean strafing = windowHandle != 0L && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        return compute(forward, backward, moveLeft, moveRight, strafing, camera,
                currentX, currentY, currentZ, currentYaw, currentPitch, dt, nowMs);
    }

    public MovementResult compute(boolean forward, boolean backward,
                                  boolean moveLeft, boolean moveRight,
                                  boolean strafing, Camera camera,
                                  float currentX, float currentY, float currentZ,
                                  float currentYaw, float currentPitch,
                                  float dt, long nowMs) {
        // 1. Calculate normalized horizontal input axes
        float inputForward = (forward ? 1.0f : 0.0f) - (backward ? 1.0f : 0.0f);
        float inputStrafe = (moveRight ? 1.0f : 0.0f) - (moveLeft ? 1.0f : 0.0f);

        // 2. Camera-relative horizontal orientation vectors
        float camFwdX;
        float camFwdZ;
        float camRightX;
        float camRightZ;

        if (camera != null) {
            camFwdX = camera.getForwardX();
            camFwdZ = camera.getForwardZ();
            camRightX = camera.getRightX();
            camRightZ = camera.getRightZ();
        } else {
            // Fallback to character facing orientation if camera not provided
            double yawRad = Math.toRadians(currentYaw);
            camFwdX = (float) Math.sin(yawRad);
            camFwdZ = (float) Math.cos(yawRad);
            camRightX = (float) Math.cos(yawRad);
            camRightZ = (float) -Math.sin(yawRad);
        }

        // 3. World space direction vector
        float dirX = camFwdX * inputForward + camRightX * inputStrafe;
        float dirZ = camFwdZ * inputForward + camRightZ * inputStrafe;
        float dirLenSq = dirX * dirX + dirZ * dirZ;

        float vx = 0.0f;
        float vz = 0.0f;
        float newYaw = currentYaw;
        int moveFlags = MovementFlags.IDLE;
        boolean isMoving = false;

        if (dirLenSq > 1e-6f) {
            isMoving = true;
            float dirLen = (float) Math.sqrt(dirLenSq);
            float normX = dirX / dirLen;
            float normZ = dirZ / dirLen;

            vx = normX * MOVE_SPEED;
            vz = normZ * MOVE_SPEED;

            // Character facing rotation: align with movement velocity, or lock to camera yaw if strafing
            if (strafing && camera != null) {
                newYaw = camera.getYaw();
            } else {
                double targetRad = Math.atan2(normX, normZ);
                newYaw = (float) Math.toDegrees(targetRad);
                if (newYaw < 0.0f) newYaw += 360.0f;
                if (newYaw >= 360.0f) newYaw -= 360.0f;
            }

            // Movement flags
            if (inputForward > 0.0f) moveFlags |= MovementFlags.FORWARD;
            else if (inputForward < 0.0f) moveFlags |= MovementFlags.BACKWARD;
            if (inputStrafe > 0.0f) moveFlags |= MovementFlags.STRAFE_RIGHT;
            else if (inputStrafe < 0.0f) moveFlags |= MovementFlags.STRAFE_LEFT;
        } else if (strafing && camera != null) {
            // Idle in-place rotation while strafing
            newYaw = camera.getYaw();
        }

        // 4. Local position integration
        float newX = currentX + vx * dt;
        float newY = currentY;
        float newZ = currentZ + vz * dt;

        // 5. Rate-limited 20 Hz dispatch logic
        boolean shouldSend = false;
        if (wasMoving && !isMoving) {
            // Immediate packet when stopping movement (IDLE)
            shouldSend = true;
            lastPacketTimeMs = nowMs;
        } else if (isMoving && (nowMs - lastPacketTimeMs >= PACKET_DISPATCH_INTERVAL_MS)) {
            shouldSend = true;
            lastPacketTimeMs = nowMs;
        }

        wasMoving = isMoving;
        if (shouldSend) {
            clientTickCounter++;
        }

        return new MovementResult(newX, newY, newZ, vx, 0.0f, vz, newYaw, currentPitch, moveFlags, isMoving, shouldSend);
    }

    /**
     * Backward-compatible overload without camera.
     */
    public MovementResult compute(boolean forward, boolean backward,
                                  boolean turnLeft, boolean turnRight,
                                  boolean strafeLeft, boolean strafeRight,
                                  float currentX, float currentY, float currentZ,
                                  float currentYaw, float currentPitch,
                                  float dt, long nowMs) {
        boolean left = turnLeft || strafeLeft;
        boolean right = turnRight || strafeRight;
        return compute(forward, backward, left, right, false, null,
                currentX, currentY, currentZ, currentYaw, currentPitch, dt, nowMs);
    }

    private static boolean isKeyDown(long windowHandle, int... keys) {
        if (windowHandle == 0L) return false;
        for (int key : keys) {
            if (GLFW.glfwGetKey(windowHandle, key) == GLFW.GLFW_PRESS) {
                return true;
            }
        }
        return false;
    }
}
