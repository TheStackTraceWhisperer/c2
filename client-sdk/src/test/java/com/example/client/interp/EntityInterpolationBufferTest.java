package com.example.client.interp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntityInterpolationBufferTest {

    @Test
    @DisplayName("Interpolates smoothly between two 20 Hz snapshots at 50% time alpha")
    void testLinearInterpolation() {
        EntityInterpolationBuffer buffer = new EntityInterpolationBuffer();

        buffer.pushSnapshot(new InterpolationSnapshot(
                1000L, 0.0f, 0.0f, 0.0f, 10.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1
        ));
        buffer.pushSnapshot(new InterpolationSnapshot(
                1050L, 10.0f, 2.0f, 0.0f, 10.0f, 0.0f, 0.0f, 90.0f, 10.0f, 1
        ));

        // Sample at 50% time between t=1000 and t=1050 (targetTime = 1025)
        InterpolatedEntityState state = buffer.sample(1125L, 100L); // 1125 - 100 = 1025

        assertNotNull(state);
        assertEquals(5.0f, state.x(), 0.001f);
        assertEquals(1.0f, state.y(), 0.001f);
        assertEquals(0.0f, state.z(), 0.001f);
        assertEquals(45.0f, state.yaw(), 0.001f);
        assertEquals(5.0f, state.pitch(), 0.001f);
        assertFalse(state.extrapolating());
    }

    @Test
    @DisplayName("Shortest-arc angular interpolation wraps correctly around 360-degree boundary")
    void testAngularInterpolationWrapping() {
        // From 350 deg to 10 deg: shortest arc is +20 deg, midpoint is 0 deg (or 360)
        float mid = EntityInterpolationBuffer.interpolateAngleDegrees(350.0f, 10.0f, 0.5f);
        assertEquals(0.0f, mid, 0.001f);

        // From 10 deg to 350 deg: shortest arc is -20 deg, midpoint is 0 deg
        float mid2 = EntityInterpolationBuffer.interpolateAngleDegrees(10.0f, 350.0f, 0.5f);
        assertEquals(0.0f, mid2, 0.001f);
    }

    @Test
    @DisplayName("Teleport threshold snaps position immediately without intermediate rubber-banding")
    void testTeleportSnap() {
        EntityInterpolationBuffer buffer = new EntityInterpolationBuffer();

        buffer.pushSnapshot(new InterpolationSnapshot(
                1000L, 0.0f, 0.0f, 0.0f, 0f, 0f, 0f, 0f, 0f, 0
        ));
        // Teleport 50 meters away (> 10m threshold)
        buffer.pushSnapshot(new InterpolationSnapshot(
                1050L, 50.0f, 0.0f, 0.0f, 0f, 0f, 0f, 180f, 0f, 0
        ));

        InterpolatedEntityState state = buffer.sample(1125L, 100L); // midpoint 1025
        assertEquals(50.0f, state.x(), 0.001f, "Teleport must snap directly to destination");
        assertEquals(180.0f, state.yaw(), 0.001f);
    }

    @Test
    @DisplayName("Extrapolates along velocity when network packets are delayed, decaying gracefully")
    void testExtrapolationOnPacketDelay() {
        EntityInterpolationBuffer buffer = new EntityInterpolationBuffer();

        buffer.pushSnapshot(new InterpolationSnapshot(
                1000L, 0.0f, 0.0f, 0.0f, 10.0f, 0.0f, 0.0f, 90.0f, 0.0f, 1
        ));
        buffer.pushSnapshot(new InterpolationSnapshot(
                1050L, 10.0f, 0.0f, 0.0f, 10.0f, 0.0f, 0.0f, 90.0f, 0.0f, 1
        ));

        // Target time is 1100 (50ms ahead of newest snapshot 1050)
        InterpolatedEntityState state = buffer.sample(1200L, 100L); // 1200 - 100 = 1100

        assertTrue(state.extrapolating());
        assertTrue(state.x() > 10.0f, "Should extrapolate forward along positive X velocity");
    }
}
