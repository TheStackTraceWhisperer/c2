package com.example.client.desktop.input;

import com.example.client.desktop.camera.Camera;
import com.example.common.protocol.MovementFlags;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;
import org.lwjgl.glfw.GLFW;

@Prototype
public class MovementInputHandler {

    public static final float MOVE_SPEED = 6.0f; // meters per second
    public static final long PACKET_DISPATCH_INTERVAL_MS = 50L; // 20 Hz target

    // Client-side prediction and error-smoothing constants
    public static final float ERROR_DECAY_RATE = 15.0f; // Exponential decay rate (s^-1), decays ~95% in 200ms
    public static final float HARD_SNAP_DISTANCE = 10.0f; // Distance above which we hard snap instead of interpolating
    public static final float DRIFT_TOLERANCE = 0.15f; // Threshold under which server diff is considered jitter and ignored

    public record MovementResult(
            float x, float y, float z, // Rendered smoothed position
            float simX, float simY, float simZ, // Authoritative physics simulated position
            float vx, float vy, float vz,
            float yaw, float pitch,
            int moveFlags,
            boolean isMoving,
            boolean shouldSendPacket
    ) {}

    // Simulated authoritative position (client prediction)
    @Getter private float simX = 0.0f;
    @Getter private float simY = 0.0f;
    @Getter private float simZ = 0.0f;

    // Visual error offset (smoothly decays towards zero to eliminate rubberbanding)
    @Getter private float errorOffsetX = 0.0f;
    @Getter private float errorOffsetY = 0.0f;
    @Getter private float errorOffsetZ = 0.0f;

    @Getter private boolean initialized = false;
    @Getter private long lastPacketTimeMs = -1000L;
    @Getter private boolean wasMoving = false;
    @Getter private long clientTickCounter = 0L;

    public void setPosition(float x, float y, float z) {
        this.simX = x;
        this.simY = y;
        this.simZ = z;
        this.errorOffsetX = 0.0f;
        this.errorOffsetY = 0.0f;
        this.errorOffsetZ = 0.0f;
        this.initialized = true;
    }

    /**
     * Reconciles client position with authoritative server response.
     * Absorbs deviation into visual error offset so visible movement remains completely smooth.
     */
    public void reconcile(float serverX, float serverY, float serverZ, boolean serverReconciled) {
        if (!initialized) {
            setPosition(serverX, serverY, serverZ);
            return;
        }

        float dx = serverX - simX;
        float dy = serverY - simY;
        float dz = serverZ - simZ;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        // If distance is massive (e.g. initial spawn sync or teleport), hard snap immediately
        if (dist > HARD_SNAP_DISTANCE) {
            setPosition(serverX, serverY, serverZ);
            return;
        }

        // If server explicitly flagged reconciliation or drift exceeds tolerance:
        // Update simulated physics state to server position, and absorb difference into error offset.
        // Rendered position (sim - error) remains identical at the moment of update: zero visual pop!
        if (serverReconciled || dist > DRIFT_TOLERANCE) {
            simX = serverX;
            simY = serverY;
            simZ = serverZ;

            errorOffsetX += dx;
            errorOffsetY += dy;
            errorOffsetZ += dz;
        }
    }

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
        if (!initialized || Math.hypot(currentX - simX, currentZ - simZ) > HARD_SNAP_DISTANCE) {
            setPosition(currentX, currentY, currentZ);
        }

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

        // 4. Immediate local prediction: integrate velocity onto simulation state
        simX += vx * dt;
        simY = currentY;
        simZ += vz * dt;

        // 5. Exponentially decay visual error offset towards zero
        if (dt > 0.0f) {
            float decay = (float) Math.exp(-ERROR_DECAY_RATE * dt);
            errorOffsetX *= decay;
            errorOffsetY *= decay;
            errorOffsetZ *= decay;

            if (Math.abs(errorOffsetX) < 0.0005f) errorOffsetX = 0.0f;
            if (Math.abs(errorOffsetY) < 0.0005f) errorOffsetY = 0.0f;
            if (Math.abs(errorOffsetZ) < 0.0005f) errorOffsetZ = 0.0f;
        }

        // 6. Visual smoothed position (simulation minus decaying error offset)
        float visibleX = simX - errorOffsetX;
        float visibleY = simY - errorOffsetY;
        float visibleZ = simZ - errorOffsetZ;

        // 7. Rate-limited 20 Hz dispatch logic
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

        return new MovementResult(
                visibleX, visibleY, visibleZ,
                simX, simY, simZ,
                vx, 0.0f, vz,
                newYaw, currentPitch,
                moveFlags, isMoving, shouldSend
        );
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
