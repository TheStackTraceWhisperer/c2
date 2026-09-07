package com.example.client.desktop.input;

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

    public MovementResult pollAndCompute(long windowHandle,
                                         float currentX, float currentY, float currentZ,
                                         float currentYaw, float currentPitch,
                                         float dt, long nowMs) {
        boolean forward = isKeyDown(windowHandle, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP);
        boolean backward = isKeyDown(windowHandle, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN);
        boolean turnLeft = isKeyDown(windowHandle, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT);
        boolean turnRight = isKeyDown(windowHandle, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT);
        boolean strafeLeft = isKeyDown(windowHandle, GLFW.GLFW_KEY_Q);
        boolean strafeRight = isKeyDown(windowHandle, GLFW.GLFW_KEY_E);

        return compute(forward, backward, turnLeft, turnRight, strafeLeft, strafeRight,
                currentX, currentY, currentZ, currentYaw, currentPitch, dt, nowMs);
    }

    public MovementResult compute(boolean forward, boolean backward,
                                  boolean turnLeft, boolean turnRight,
                                  boolean strafeLeft, boolean strafeRight,
                                  float currentX, float currentY, float currentZ,
                                  float currentYaw, float currentPitch,
                                  float dt, long nowMs) {
        // 1. Yaw rotation
        float newYaw = currentYaw;
        if (turnLeft && !turnRight) {
            newYaw = (newYaw - TURN_SPEED * dt) % 360.0f;
            if (newYaw < 0.0f) newYaw += 360.0f;
        } else if (turnRight && !turnLeft) {
            newYaw = (newYaw + TURN_SPEED * dt) % 360.0f;
        }

        // 2. Direction vectors (FFXI: 0=North/+Z, 90=East/+X)
        double yawRad = Math.toRadians(newYaw);
        float sinYaw = (float) Math.sin(yawRad);
        float cosYaw = (float) Math.cos(yawRad);

        float vx = 0.0f;
        float vz = 0.0f;
        int moveFlags = MovementFlags.IDLE;

        if (forward && !backward) {
            vx += sinYaw * MOVE_SPEED;
            vz += cosYaw * MOVE_SPEED;
            moveFlags |= MovementFlags.FORWARD;
        } else if (backward && !forward) {
            vx -= sinYaw * MOVE_SPEED;
            vz -= cosYaw * MOVE_SPEED;
            moveFlags |= MovementFlags.BACKWARD;
        }

        if (strafeRight && !strafeLeft) {
            vx += cosYaw * MOVE_SPEED;
            vz -= sinYaw * MOVE_SPEED;
            moveFlags |= MovementFlags.STRAFE_RIGHT;
        } else if (strafeLeft && !strafeRight) {
            vx -= cosYaw * MOVE_SPEED;
            vz += sinYaw * MOVE_SPEED;
            moveFlags |= MovementFlags.STRAFE_LEFT;
        }

        boolean isMoving = (moveFlags != MovementFlags.IDLE) || (turnLeft != turnRight);

        // 3. Local position integration
        float newX = currentX + vx * dt;
        float newY = currentY;
        float newZ = currentZ + vz * dt;

        // 4. Rate-limited 20 Hz dispatch logic
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

    private boolean isKeyDown(long windowHandle, int... keys) {
        if (windowHandle == 0L) return false;
        for (int key : keys) {
            if (GLFW.glfwGetKey(windowHandle, key) == GLFW.GLFW_PRESS) {
                return true;
            }
        }
        return false;
    }
}
