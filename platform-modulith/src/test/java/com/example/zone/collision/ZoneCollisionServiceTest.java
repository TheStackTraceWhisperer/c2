package com.example.zone.collision;

import com.example.config.ZoneServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneCollisionServiceTest {

    private ZoneServerProperties props;
    private ZoneCollisionService collisionService;

    @BeforeEach
    void setUp() {
        props = new ZoneServerProperties();
        props.setHost("127.0.0.1");
        props.setPort(9000);
        props.setZoneId(230); // South San d'Oria
        props.setAssetPath("game-asset-pipeline/src/test/resources/dats");
        props.setCollisionEnabled(true);
        props.setMaxStepHeight(1.2f);
        props.setMaxJumpHeight(3.5f);

        collisionService = new ZoneCollisionService(props);
        collisionService.init();
    }

    @Test
    @DisplayName("ZoneCollisionService loads authoritative South San d'Oria (Zone 230) collision mesh")
    void testLoadZoneCollision() {
        assertTrue(collisionService.hasCollision(230), "Zone 230 should be loaded and present in collision cache");
        assertTrue(collisionService.getCollisionMap(230).isPresent());
        assertEquals(40, collisionService.getCollisionMap(230).get().numBlocksWide());
    }

    @Test
    @DisplayName("Normal ground movement clamps to floor height without reconciliation flag")
    void testGroundMovementClamping() {
        // Spawn near (-50, -30), where floor is at y = -2.0f
        ZoneCollisionService.MovementValidationResult res = collisionService.validateAndClamp(
                230,
                -50.0f, -2.0f, -30.0f,
                -49.5f, -1.8f, -30.0f
        );

        assertNotNull(res);
        assertFalse(res.reconciled(), "Small step within maxStepHeight should not flag reconciliation");
        assertEquals(-2.0f, res.y(), 0.1f, "Height should be clamped to floor terrain level");
    }

    @Test
    @DisplayName("Falling through the floor triggers reconciliation and snaps to ground")
    void testFloorPenetrationReconciliation() {
        // Target Y is far below floor at y = -10.0f
        ZoneCollisionService.MovementValidationResult res = collisionService.validateAndClamp(
                230,
                -50.0f, -2.0f, -30.0f,
                -50.0f, -10.0f, -30.0f
        );

        assertTrue(res.reconciled(), "Penetrating below floor must flag reconciliation");
        assertEquals("FLOOR_PENETRATION", res.reason());
        assertEquals(-2.0f, res.y(), 0.1f, "Height must be snapped back to floor level");
    }

    @Test
    @DisplayName("Exceeding max jump/flight height triggers reconciliation and clamps to jump ceiling")
    void testFlyHackReconciliation() {
        // Target Y is 40m in the air (floor is at y = -2.0f, maxJump is 3.5f -> ceiling = 1.5f)
        ZoneCollisionService.MovementValidationResult res = collisionService.validateAndClamp(
                230,
                -50.0f, -2.0f, -30.0f,
                -50.0f, 40.0f, -30.0f
        );

        assertTrue(res.reconciled(), "Flying above max jump height must flag reconciliation");
        assertEquals("EXCEEDED_MAX_JUMP_HEIGHT", res.reason());
        assertEquals(1.5f, res.y(), 0.15f, "Height must be clamped to floor + maxJumpHeight");
    }

    @Test
    @DisplayName("Disabling collision in properties allows unconstrained movement")
    void testCollisionDisabledPassThrough() {
        props.setCollisionEnabled(false);
        ZoneCollisionService unconstrainedService = new ZoneCollisionService(props);
        unconstrainedService.init();

        ZoneCollisionService.MovementValidationResult res = unconstrainedService.validateAndClamp(
                230,
                -50.0f, 2.0f, -30.0f,
                -50.0f, 100.0f, -30.0f
        );

        assertFalse(res.reconciled());
        assertEquals(100.0f, res.y(), 0.001f);
    }
}
