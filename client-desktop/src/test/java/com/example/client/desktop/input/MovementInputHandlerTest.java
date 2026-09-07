package com.example.client.desktop.input;

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
                false, false, false, false, false, false,
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
    @DisplayName("Forward movement facing North (+Z) produces positive Z velocity and FORWARD flag")
    void testForwardMovementFacingNorth() {
        MovementInputHandler handler = new MovementInputHandler();

        var result = handler.compute(
                true, false, false, false, false, false,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.1f, 100L
        );

        assertTrue(result.isMoving());
        assertTrue(result.shouldSendPacket());
        assertEquals(MovementFlags.FORWARD, result.moveFlags() & MovementFlags.FORWARD);
        assertEquals(0.0f, result.vx(), 0.001f);
        assertEquals(MovementInputHandler.MOVE_SPEED, result.vz(), 0.001f); // Moving North (+Z)
        assertEquals(0.6f, result.z(), 0.001f); // 6 m/s * 0.1s
    }

    @Test
    @DisplayName("Turning rotates yaw angle smoothly and wraps within 0-360 degrees")
    void testTurning() {
        MovementInputHandler handler = new MovementInputHandler();

        // Turn right by 90 degrees (180 deg/s * 0.5s)
        var resultRight = handler.compute(
                false, false, false, true, false, false,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.5f, 50L
        );
        assertEquals(90.0f, resultRight.yaw(), 0.001f);

        // Turn left from 0 degrees wraps to 270 degrees
        var resultLeft = handler.compute(
                false, false, true, false, false, false,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.5f, 100L
        );
        assertEquals(270.0f, resultLeft.yaw(), 0.001f);
    }

    @Test
    @DisplayName("Throttling logic enforces 20 Hz cadence and flushes on movement stop")
    void testThrottlingAndStopFlush() {
        MovementInputHandler handler = new MovementInputHandler();

        // 1. Initial movement at t=0ms -> sends packet
        var r1 = handler.compute(true, false, false, false, false, false, 0, 0, 0, 0, 0, 0.016f, 0L);
        assertTrue(r1.shouldSendPacket());
        assertEquals(1L, handler.getClientTickCounter());

        // 2. Next frame at t=20ms -> throttled (too soon)
        var r2 = handler.compute(true, false, false, false, false, false, 0, 0, 0, 0, 0, 0.016f, 20L);
        assertFalse(r2.shouldSendPacket());
        assertEquals(1L, handler.getClientTickCounter());

        // 3. Next frame at t=50ms -> 20 Hz threshold reached, sends packet
        var r3 = handler.compute(true, false, false, false, false, false, 0, 0, 0, 0, 0, 0.016f, 50L);
        assertTrue(r3.shouldSendPacket());
        assertEquals(2L, handler.getClientTickCounter());

        // 4. Stopped moving at t=70ms -> immediate stop flush of IDLE packet
        var r4 = handler.compute(false, false, false, false, false, false, 0, 0, 0, 0, 0, 0.016f, 70L);
        assertTrue(r4.shouldSendPacket());
        assertEquals(MovementFlags.IDLE, r4.moveFlags());
        assertEquals(3L, handler.getClientTickCounter());

        // 5. Still idle at t=120ms -> does not keep sending
        var r5 = handler.compute(false, false, false, false, false, false, 0, 0, 0, 0, 0, 0.016f, 120L);
        assertFalse(r5.shouldSendPacket());
        assertEquals(3L, handler.getClientTickCounter());
    }
}
