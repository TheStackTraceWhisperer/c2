package com.example.client.desktop.input;

import com.example.client.desktop.camera.PolarCamera;
import com.example.common.protocol.MovementFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MovementInputHandlerTest {

    @Test
    @DisplayName("Idle input produces zero velocity and IDLE movement flags")
    void testIdleInput() {
        MovementInputHandler handler = new MovementInputHandler();

        var result = handler.compute(
                false, false, false, false, false, null,
                10.0f, 0.0f, 20.0f, 0.0f, 0.0f,
                0.016f, 0L
        );

        assertFalse(result.isMoving());
        assertFalse(result.shouldSendPacket());
        assertEquals(MovementFlags.IDLE, result.moveFlags());
        assertEquals(0.0f, result.vx(), 0.001f);
        assertEquals(0.0f, result.vz(), 0.001f);
        assertEquals(10.0f, result.x(), 0.001f);
        assertEquals(20.0f, result.z(), 0.001f);
    }

    @Test
    @DisplayName("Forward movement with North-facing camera moves North (-Z) and character faces North")
    void testForwardMovementFacingNorth() {
        MovementInputHandler handler = new MovementInputHandler();
        PolarCamera camera = new PolarCamera(); // Default yaw = 0 deg (North)

        var result = handler.compute(
                true, false, false, false, false, camera,
                0.0f, 0.0f, 0.0f, 180.0f, 0.0f,
                0.1f, 100L
        );

        assertTrue(result.isMoving());
        assertTrue(result.shouldSendPacket());
        assertEquals(MovementFlags.FORWARD, result.moveFlags() & MovementFlags.FORWARD);
        assertEquals(0.0f, result.vx(), 0.001f);
        assertEquals(-MovementInputHandler.MOVE_SPEED, result.vz(), 0.001f); // Moving North (-Z)
        assertEquals(-0.6f, result.z(), 0.001f); // 6 m/s * 0.1s toward -Z
        assertEquals(0.0f, result.yaw(), 0.001f); // Rotates from initial 180 deg to face North (0 deg)
    }

    @Test
    @DisplayName("Strafe right with North-facing camera moves East (+X) and character faces East (90 deg)")
    void testStrafeRightFacingNorth() {
        MovementInputHandler handler = new MovementInputHandler();
        PolarCamera camera = new PolarCamera();

        var result = handler.compute(
                false, false, false, true, false, camera,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.1f, 100L
        );

        assertTrue(result.isMoving());
        assertEquals(MovementFlags.STRAFE_RIGHT, result.moveFlags() & MovementFlags.STRAFE_RIGHT);
        assertEquals(MovementInputHandler.MOVE_SPEED, result.vx(), 0.001f); // Moving East (+X)
        assertEquals(0.0f, result.vz(), 0.001f);
        assertEquals(90.0f, result.yaw(), 0.001f); // Facing East
    }

    @Test
    @DisplayName("Camera rotated East (90 deg): W moves East (+X), A moves North (-Z), S moves West (-X), D moves South (+Z)")
    void testMovementWithRotatedCamera() {
        MovementInputHandler handler = new MovementInputHandler();
        PolarCamera camera = new PolarCamera();
        camera.setYaw(90.0f); // Looking East

        // 1. W (forward relative to camera) -> East (+X)
        var resW = handler.compute(true, false, false, false, false, camera, 0, 0, 0, 0, 0, 0.1f, 100L);
        assertEquals(MovementInputHandler.MOVE_SPEED, resW.vx(), 0.001f);
        assertEquals(0.0f, resW.vz(), 0.001f);
        assertEquals(90.0f, resW.yaw(), 0.001f); // Faces East

        // 2. A (left relative to camera) -> North (-Z)
        var resA = handler.compute(false, false, true, false, false, camera, 0, 0, 0, 0, 0, 0.1f, 100L);
        assertEquals(0.0f, resA.vx(), 0.001f);
        assertEquals(-MovementInputHandler.MOVE_SPEED, resA.vz(), 0.001f);
        assertEquals(0.0f, resA.yaw(), 0.001f); // Faces North

        // 3. S (backward relative to camera) -> West (-X)
        var resS = handler.compute(false, true, false, false, false, camera, 0, 0, 0, 0, 0, 0.1f, 100L);
        assertEquals(-MovementInputHandler.MOVE_SPEED, resS.vx(), 0.001f);
        assertEquals(0.0f, resS.vz(), 0.001f);
        assertEquals(270.0f, resS.yaw(), 0.001f); // Faces West

        // 4. D (right relative to camera) -> South (+Z)
        var resD = handler.compute(false, false, false, true, false, camera, 0, 0, 0, 0, 0, 0.1f, 100L);
        assertEquals(0.0f, resD.vx(), 0.001f);
        assertEquals(MovementInputHandler.MOVE_SPEED, resD.vz(), 0.001f);
        assertEquals(180.0f, resD.yaw(), 0.001f); // Faces South
    }

    @Test
    @DisplayName("Diagonal movement normalizes speed to MOVE_SPEED and faces 45 degrees")
    void testDiagonalMovement() {
        MovementInputHandler handler = new MovementInputHandler();
        PolarCamera camera = new PolarCamera(); // North

        // W + D: North-East (+X, -Z)
        var result = handler.compute(
                true, false, false, true, false, camera,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.1f, 100L
        );

        float expectedVel = (float) (MovementInputHandler.MOVE_SPEED / Math.sqrt(2.0));
        assertEquals(expectedVel, result.vx(), 0.001f);
        assertEquals(-expectedVel, result.vz(), 0.001f);
        assertEquals(45.0f, result.yaw(), 0.001f);
    }

    @Test
    @DisplayName("Strafing mode locks character facing to camera yaw while translating")
    void testStrafingMode() {
        MovementInputHandler handler = new MovementInputHandler();
        PolarCamera camera = new PolarCamera();
        camera.setYaw(45.0f); // Looking North-East

        // Moving S (backward) while strafing: moves South-West (-X, +Z) but facing remains camera yaw (45 deg)
        var result = handler.compute(
                false, true, false, false, true, camera,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.1f, 100L
        );

        assertTrue(result.vx() < 0.0f);
        assertTrue(result.vz() > 0.0f);
        assertEquals(45.0f, result.yaw(), 0.001f); // Locked to camera yaw
    }

    @Test
    @DisplayName("Throttling logic enforces 20 Hz cadence and flushes on movement stop")
    void testThrottlingAndStopFlush() {
        MovementInputHandler handler = new MovementInputHandler();

        // 1. Initial movement at t=0ms -> sends packet
        var r1 = handler.compute(true, false, false, false, false, null, 0, 0, 0, 0, 0, 0.016f, 0L);
        assertTrue(r1.shouldSendPacket());
        assertEquals(1L, handler.getClientTickCounter());

        // 2. Next frame at t=20ms -> throttled (too soon)
        var r2 = handler.compute(true, false, false, false, false, null, 0, 0, 0, 0, 0, 0.016f, 20L);
        assertFalse(r2.shouldSendPacket());
        assertEquals(1L, handler.getClientTickCounter());

        // 3. Next frame at t=50ms -> 20 Hz threshold reached, sends packet
        var r3 = handler.compute(true, false, false, false, false, null, 0, 0, 0, 0, 0, 0.016f, 50L);
        assertTrue(r3.shouldSendPacket());
        assertEquals(2L, handler.getClientTickCounter());

        // 4. Stopped moving at t=70ms -> immediate stop flush of IDLE packet
        var r4 = handler.compute(false, false, false, false, false, null, 0, 0, 0, 0, 0, 0.016f, 70L);
        assertTrue(r4.shouldSendPacket());
        assertEquals(MovementFlags.IDLE, r4.moveFlags());
        assertEquals(3L, handler.getClientTickCounter());

        // 5. Still idle at t=120ms -> does not keep sending
        var r5 = handler.compute(false, false, false, false, false, null, 0, 0, 0, 0, 0, 0.016f, 120L);
        assertFalse(r5.shouldSendPacket());
        assertEquals(3L, handler.getClientTickCounter());
    }

    @Test
    @DisplayName("Server drift within tolerance is ignored to avoid jitter")
    void testDriftWithinToleranceIgnored() {
        MovementInputHandler handler = new MovementInputHandler();
        handler.setPosition(10.0f, 0.0f, 20.0f);

        // Drift delta is ~0.094m, less than DRIFT_TOLERANCE (0.15m)
        handler.reconcile(10.05f, 0.0f, 20.08f, false);

        assertEquals(10.0f, handler.getSimX(), 0.001f);
        assertEquals(20.0f, handler.getSimZ(), 0.001f);
        assertEquals(0.0f, handler.getErrorOffsetX(), 0.001f);
        assertEquals(0.0f, handler.getErrorOffsetZ(), 0.001f);
    }

    @Test
    @DisplayName("Authoritative reconciliation produces zero visual pop and decays smoothly")
    void testAuthoritativeReconciliationSmoothDecay() {
        MovementInputHandler handler = new MovementInputHandler();
        handler.setPosition(10.0f, 0.0f, 20.0f);

        // Server reconciles position to (9.0, 0, 19.0)
        handler.reconcile(9.0f, 0.0f, 19.0f, true);

        // Authoritative physics state instantly updates to server
        assertEquals(9.0f, handler.getSimX(), 0.001f);
        assertEquals(19.0f, handler.getSimZ(), 0.001f);

        // Visual error offset absorbs difference: dx = 9 - 10 = -1, dz = 19 - 20 = -1
        assertEquals(-1.0f, handler.getErrorOffsetX(), 0.001f);
        assertEquals(-1.0f, handler.getErrorOffsetZ(), 0.001f);

        // Immediate frame (dt ~ 0): rendered visible position (sim - error) is STILL (10.0, 20.0) -> ZERO POP!
        var immediate = handler.compute(false, false, false, false, false, null,
                handler.getSimX(), handler.getSimY(), handler.getSimZ(), 0, 0, 0.00001f, 0L);
        assertEquals(10.0f, immediate.x(), 0.005f);
        assertEquals(20.0f, immediate.z(), 0.005f);

        // Over time (dt = 0.05s * 4 = 0.2s), error offset decays exponentially towards 0
        for (int i = 0; i < 4; i++) {
            handler.compute(false, false, false, false, false, null,
                    handler.getSimX(), handler.getSimY(), handler.getSimZ(), 0, 0, 0.05f, 50L * (i + 1));
        }

        // After 200ms with decay rate 15.0: exp(-15 * 0.2) = exp(-3) ~ 0.0498
        assertTrue(Math.abs(handler.getErrorOffsetX()) < 0.1f);
        assertTrue(Math.abs(handler.getErrorOffsetZ()) < 0.1f);

        // Run until fully decayed (e.g. another 0.3s)
        var settled = handler.compute(false, false, false, false, false, null,
                handler.getSimX(), handler.getSimY(), handler.getSimZ(), 0, 0, 0.3f, 500L);
        assertEquals(0.0f, handler.getErrorOffsetX(), 0.001f);
        assertEquals(0.0f, handler.getErrorOffsetZ(), 0.001f);
        assertEquals(9.0f, settled.x(), 0.001f);
        assertEquals(19.0f, settled.z(), 0.001f);
    }

    @Test
    @DisplayName("Massive position deviation (teleport/spawn) hard-snaps without interpolation")
    void testMassiveDriftHardSnaps() {
        MovementInputHandler handler = new MovementInputHandler();
        handler.setPosition(0.0f, 0.0f, 0.0f);

        // Teleport to (50, 0, 50) - distance ~ 70.7m > HARD_SNAP_DISTANCE (10m)
        handler.reconcile(50.0f, 0.0f, 50.0f, true);

        assertEquals(50.0f, handler.getSimX(), 0.001f);
        assertEquals(50.0f, handler.getSimZ(), 0.001f);
        assertEquals(0.0f, handler.getErrorOffsetX(), 0.001f);
        assertEquals(0.0f, handler.getErrorOffsetZ(), 0.001f);
    }
}
