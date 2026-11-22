package com.example.client.desktop.input;

import com.example.asset.collision.CollisionMap;
import com.example.asset.collision.CollisionMesh;
import com.example.asset.collision.CollisionObject;
import com.example.asset.collision.CollisionObjectGroup;
import com.example.asset.collision.CollisionTransform;
import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.Matrix4f;
import com.example.asset.geometry.TerrainType;
import com.example.asset.geometry.TriFlags;
import com.example.asset.geometry.Vector3f;
import com.example.client.desktop.scene.SceneCamera;
import com.example.common.protocol.MovementFlags;
import com.ffxi.viewer.collision.ZoneCollisionSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    @DisplayName("Forward movement with North-facing camera moves North (+Z) and character faces North")
    void testForwardMovementFacingNorth() {
        MovementInputHandler handler = new MovementInputHandler();
        SceneCamera camera = new SceneCamera(); // Default yaw = 0 deg (North +Z)

        var result = handler.compute(
                true, false, false, false, false, camera,
                0.0f, 0.0f, 0.0f, 180.0f, 0.0f,
                0.1f, 100L
        );

        assertTrue(result.isMoving());
        assertTrue(result.shouldSendPacket());
        assertEquals(MovementFlags.FORWARD, result.moveFlags() & MovementFlags.FORWARD);
        assertEquals(0.0f, result.vx(), 0.001f);
        assertEquals(MovementInputHandler.MOVE_SPEED, result.vz(), 0.001f); // Moving North (+Z)
        assertEquals(0.6f, result.z(), 0.001f); // 6 m/s * 0.1s toward +Z
        assertEquals(0.0f, result.yaw(), 0.001f); // Rotates from initial 180 deg to face North (0 deg)
    }

    @Test
    @DisplayName("Strafe right with North-facing camera moves East (+X) and character faces East (90 deg)")
    void testStrafeRightFacingNorth() {
        MovementInputHandler handler = new MovementInputHandler();
        SceneCamera camera = new SceneCamera();

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
    @DisplayName("Camera rotated East (90 deg): W moves East (+X), A moves North (+Z), S moves West (-X), D moves South (-Z)")
    void testMovementWithRotatedCamera() {
        MovementInputHandler handler = new MovementInputHandler();
        SceneCamera camera = new SceneCamera();
        camera.setYaw(90.0f); // Looking East

        // 1. W (forward relative to camera) -> East (+X)
        var resW = handler.compute(true, false, false, false, false, camera, 0, 0, 0, 0, 0, 0.1f, 100L);
        assertEquals(MovementInputHandler.MOVE_SPEED, resW.vx(), 0.001f);
        assertEquals(0.0f, resW.vz(), 0.001f);
        assertEquals(90.0f, resW.yaw(), 0.001f); // Faces East

        // 2. A (left relative to camera) -> North (+Z)
        var resA = handler.compute(false, false, true, false, false, camera, 0, 0, 0, 0, 0, 0.1f, 100L);
        assertEquals(0.0f, resA.vx(), 0.001f);
        assertEquals(MovementInputHandler.MOVE_SPEED, resA.vz(), 0.001f);
        assertEquals(0.0f, resA.yaw(), 0.001f); // Faces North

        // 3. S (backward relative to camera) -> West (-X)
        var resS = handler.compute(false, true, false, false, false, camera, 0, 0, 0, 0, 0, 0.1f, 100L);
        assertEquals(-MovementInputHandler.MOVE_SPEED, resS.vx(), 0.001f);
        assertEquals(0.0f, resS.vz(), 0.001f);
        assertEquals(270.0f, resS.yaw(), 0.001f); // Faces West

        // 4. D (right relative to camera) -> South (-Z)
        var resD = handler.compute(false, false, false, true, false, camera, 0, 0, 0, 0, 0, 0.1f, 100L);
        assertEquals(0.0f, resD.vx(), 0.001f);
        assertEquals(-MovementInputHandler.MOVE_SPEED, resD.vz(), 0.001f);
        assertEquals(180.0f, resD.yaw(), 0.001f); // Faces South
    }

    @Test
    @DisplayName("Diagonal movement normalizes speed to MOVE_SPEED and faces 45 degrees")
    void testDiagonalMovement() {
        MovementInputHandler handler = new MovementInputHandler();
        SceneCamera camera = new SceneCamera(); // North

        // W + D: North-East (+X, +Z)
        var result = handler.compute(
                true, false, false, true, false, camera,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.1f, 100L
        );

        float expectedVel = (float) (MovementInputHandler.MOVE_SPEED / Math.sqrt(2.0));
        assertEquals(expectedVel, result.vx(), 0.001f);
        assertEquals(expectedVel, result.vz(), 0.001f);
        assertEquals(45.0f, result.yaw(), 0.001f);
    }

    @Test
    @DisplayName("Strafing mode locks character facing to camera yaw while translating")
    void testStrafingMode() {
        MovementInputHandler handler = new MovementInputHandler();
        SceneCamera camera = new SceneCamera();
        camera.setYaw(45.0f); // Looking North-East (+X, +Z)

        // Moving S (backward) while strafing: moves South-West (-X, -Z) but facing remains camera yaw (45 deg)
        var result = handler.compute(
                false, true, false, false, true, camera,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.1f, 100L
        );

        assertTrue(result.vx() < 0.0f);
        assertTrue(result.vz() < 0.0f);
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

    @Test
    @DisplayName("Y-reconciliation has zero drift across multiple ticks when fed smoothed visible position")
    void testYReconciliationZeroDriftAcrossMultipleTicks() {
        MovementInputHandler handler = new MovementInputHandler();
        handler.setPosition(0.0f, 0.0f, 0.0f);

        // Server reconciles Y from 0.0 to 2.0
        handler.reconcile(0.0f, 2.0f, 0.0f, true);
        assertEquals(2.0f, handler.getSimY(), 0.0001f);
        assertEquals(2.0f, handler.getErrorOffsetY(), 0.0001f);

        float visibleX = 0.0f;
        float visibleY = 0.0f; // Still at pre-reconcile visible Y when frame 1 starts
        float visibleZ = 0.0f;
        float prevY = -0.001f;

        for (int i = 0; i < 20; i++) {
            var res = handler.compute(
                    true, false, false, false, false, null,
                    visibleX, visibleY, visibleZ,
                    0.0f, 0.0f,
                    0.05f, 50L * (i + 1)
            );
            assertEquals(2.0f, handler.getSimY(), 0.0001f, "simY must not be overwritten by smoothed visibleY");
            assertEquals(2.0f, res.simY(), 0.0001f);
            assertTrue(res.y() >= prevY, "visibleY should monotonically approach serverY without drifting backward");
            assertTrue(res.y() <= 2.0001f, "visibleY must not overshoot serverY");
            prevY = res.y();
            visibleX = res.x();
            visibleY = res.y();
            visibleZ = res.z();
        }

        assertEquals(2.0f, visibleY, 0.001f);
        assertEquals(0.0f, handler.getErrorOffsetY(), 0.001f);
        assertEquals(6.0f, visibleZ, 0.01f); // 1.0s forward north (+Z) at 6 m/s
    }

    @Test
    @DisplayName("Client movement prediction snaps upward along sloped floor within MAX_STEP_HEIGHT")
    void testClientCollisionFloorClamping() {
        // Elevated ramp platform at y = -0.6m (-Y = Up in native FFXI, normal pointing -Y)
        List<CollisionTriangle> rampTris = List.of(
                tri(-20f, -0.6f, -20f, 20f, -0.6f, -20f, 20f, -0.6f, 20f, 0f, -1f, 0f, 0),
                tri(-20f, -0.6f, -20f, 20f, -0.6f, 20f, -20f, -0.6f, 20f, 0f, -1f, 0f, 0)
        );
        ZoneCollisionSystem collisionSystem = singleObjectCollision(rampTris);

        MovementInputHandler handler = new MovementInputHandler();
        handler.setPosition(0.0f, 0.0f, 0.0f);
        SceneCamera camera = new SceneCamera();

        var res = handler.compute(
                true, false, false, false, false, camera,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.1f, 100L, collisionSystem
        );

        assertEquals(-0.6f, res.y(), 0.001f, "Client prediction must follow floor height up within MAX_STEP_HEIGHT");
        assertEquals(-0.6f, res.simY(), 0.001f);
        assertTrue(res.vy() < 0.0f, "Climbing upward in -Y=Up space must emit negative vy");
    }

    @Test
    @DisplayName("Client movement prediction stops before a vertical collision wall")
    void testClientCollisionWallStop() {
        List<CollisionTriangle> wallTris = List.of(
                tri(-10f, -5f, 0.5f, 10f, -5f, 0.5f, 10f, 2f, 0.5f, 0f, 0f, -1f, 0),
                tri(-10f, -5f, 0.5f, 10f, 2f, 0.5f, -10f, 2f, 0.5f, 0f, 0f, -1f, 0)
        );
        ZoneCollisionSystem collisionSystem = singleObjectCollision(wallTris);

        MovementInputHandler handler = new MovementInputHandler();
        handler.setPosition(0.0f, 0.0f, 0.0f);
        SceneCamera camera = new SceneCamera();

        // Moving 0.6m North toward a wall at z = 0.5f must stop at wall - pullback margin (0.4f)
        var res = handler.compute(
                true, false, false, false, false, camera,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.1f, 100L, collisionSystem
        );

        assertEquals(0.5f - MovementInputHandler.WALL_PULLBACK_MARGIN, res.z(), 0.01f);
        assertTrue(res.z() < 0.5f, "Client predicted position must not penetrate wall at z=0.5");
    }

    @Test
    @DisplayName("Client movement prediction honors TriFlags.isHitWall() barriers")
    void testClientCollisionBarrierFlagStop() {
        // Geometrically valid 53-degree ramp/barrier passing through (y=-0.8, z=0.5) with normal (0, -0.6, -0.8);
        // |ny| >= WALL_NORMAL_Y_MAX so only TriFlags(0x40) marks it as an impassable wall barrier.
        List<CollisionTriangle> barrierTris = List.of(
                tri(-10f, -4.8f, 3.5f, 10f, 3.2f, -2.5f, 10f, -4.8f, 3.5f, 0f, -0.6f, -0.8f, 0x40),
                tri(-10f, -4.8f, 3.5f, -10f, 3.2f, -2.5f, 10f, 3.2f, -2.5f, 0f, -0.6f, -0.8f, 0x40)
        );
        ZoneCollisionSystem collisionSystem = singleObjectCollision(barrierTris);

        MovementInputHandler handler = new MovementInputHandler();
        handler.setPosition(0.0f, 0.0f, 0.0f);
        SceneCamera camera = new SceneCamera();

        var res = handler.compute(
                true, false, false, false, false, camera,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.1f, 100L, collisionSystem
        );

        assertEquals(0.5f - MovementInputHandler.WALL_PULLBACK_MARGIN, res.z(), 0.01f);
        assertTrue(res.z() < 0.5f, "Client predicted position must honor TriFlags.isHitWall()");
    }

    private static CollisionTriangle tri(
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float nx, float ny, float nz,
            int flags
    ) {
        return new CollisionTriangle(
                new Vector3f(x0, y0, z0),
                new Vector3f(x1, y1, z1),
                new Vector3f(x2, y2, z2),
                new Vector3f(nx, ny, nz),
                new TriFlags(flags),
                TerrainType.STONE
        );
    }

    private static ZoneCollisionSystem singleObjectCollision(List<CollisionTriangle> tris) {
        BoundingBox.Builder bbox = BoundingBox.builder();
        for (CollisionTriangle tri : tris) {
            bbox.include(tri.v0()).include(tri.v1()).include(tri.v2());
        }
        CollisionMesh mesh = new CollisionMesh(1, tris, bbox.build());
        Matrix4f identity = new Matrix4f();
        CollisionTransform transform = new CollisionTransform(1, identity, identity, 0, 0, null);
        CollisionObject obj = new CollisionObject(1, mesh, transform);
        CollisionObjectGroup group = new CollisionObjectGroup(1, List.of(obj));
        return new ZoneCollisionSystem(new CollisionMap(1, 1, 64, 64, 1, 1, new CollisionObjectGroup[][]{{group}}));
    }
}
