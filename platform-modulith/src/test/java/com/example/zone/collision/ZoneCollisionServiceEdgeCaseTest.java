package com.example.zone.collision;

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
import com.example.common.protocol.MovementFlags;
import com.example.config.ZoneServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class ZoneCollisionServiceEdgeCaseTest {

  private ZoneCollisionService collisionService;
  private CollisionObject sharedCollisionObject;

  @BeforeEach
  void setUp() {
    ZoneServerProperties props = new ZoneServerProperties();
    props.setHost("127.0.0.1");
    props.setPort(9000);
    props.setZoneId(230);
    props.setCollisionEnabled(true);
    props.setMaxStepHeight(1.2f);
    props.setMaxJumpHeight(3.5f);

    collisionService = new ZoneCollisionService(props);
    CollisionMap syntheticMap = buildSyntheticCollisionMap();
    collisionService.registerCollisionMap(230, syntheticMap);
    sharedCollisionObject = syntheticMap.getGroup(0, 0).collisionObjects().get(0);
  }

  @Test
  @DisplayName("Pure vertical movement skips zero-length wall segment normalization without producing NaN")
  void testPureVerticalMovementAvoidsNaN() {
    ZoneCollisionService.MovementValidationResult res = collisionService.validateAndClamp(
      230,
      1.95f, 0.0f, 0.0f,
      1.95f, -1.5f, 0.0f,
      MovementFlags.JUMP
    );

    assertThat(Float.isFinite(res.x())).isTrue();
    assertThat(Float.isFinite(res.y())).isTrue();
    assertThat(Float.isFinite(res.z())).isTrue();
    assertThat(res.x()).isEqualTo(1.95f);
    assertThat(res.y()).isCloseTo(-1.5f, offset(0.001f));
    assertThat(res.z()).isEqualTo(0.0f);
    assertThat(res.reconciled()).isFalse();
  }

  @Test
  @DisplayName("Airborne jump/falling flags preserve height within maxJump while still clamping floor penetration and ceiling")
  void testJumpMotionNotSnappedDownToFloor() {
    // 1. Upward jump motion (-Y=Up) within maxJump (0.8m <= 3.5m) must NOT snap down to floorY (0.0f)
    ZoneCollisionService.MovementValidationResult jumpRes = collisionService.validateAndClamp(
      230,
      0.0f, 0.0f, 0.0f,
      0.0f, -0.8f, 0.0f,
      MovementFlags.JUMP
    );
    assertThat(jumpRes.reconciled()).isFalse();
    assertThat(jumpRes.y()).isCloseTo(-0.8f, offset(0.001f));

    // 2. Falling motion above floor within maxJump also stays airborne until landing
    ZoneCollisionService.MovementValidationResult fallRes = collisionService.validateAndClamp(
      230,
      0.0f, -1.4f, 0.0f,
      0.0f, -0.9f, 0.0f,
      MovementFlags.FALLING
    );
    assertThat(fallRes.reconciled()).isFalse();
    assertThat(fallRes.y()).isCloseTo(-0.9f, offset(0.001f));

    // 3. Ground movement (no jump/falling flag) with heightAboveFloor <= maxStep still clamps to floorY
    ZoneCollisionService.MovementValidationResult groundRes = collisionService.validateAndClamp(
      230,
      0.0f, 0.0f, 0.0f,
      0.5f, -0.2f, 0.0f
    );
    assertThat(groundRes.reconciled()).isFalse();
    assertThat(groundRes.y()).isCloseTo(0.0f, offset(0.001f));

    // 4. Jumping above maxJump is still clamped to floorY - maxJump
    ZoneCollisionService.MovementValidationResult overJumpRes = collisionService.validateAndClamp(
      230,
      0.0f, 0.0f, 0.0f,
      0.0f, -5.0f, 0.0f,
      MovementFlags.JUMP
    );
    assertThat(overJumpRes.reconciled()).isTrue();
    assertThat(overJumpRes.reason()).isEqualTo("EXCEEDED_MAX_JUMP_HEIGHT");
    assertThat(overJumpRes.y()).isCloseTo(-3.5f, offset(0.001f));

    // 5. Falling below floor (+Y=Down) still snaps up to floorY
    ZoneCollisionService.MovementValidationResult underFloorRes = collisionService.validateAndClamp(
      230,
      0.0f, -0.5f, 0.0f,
      0.0f, 1.0f, 0.0f,
      MovementFlags.FALLING
    );
    assertThat(underFloorRes.reconciled()).isTrue();
    assertThat(underFloorRes.reason()).isEqualTo("FLOOR_PENETRATION");
    assertThat(underFloorRes.y()).isCloseTo(0.0f, offset(0.001f));
  }

  @Test
  @DisplayName("Wall segment check blocks horizontal penetration cleanly")
  void testWallBlockedReconciliation() {
    ZoneCollisionService.MovementValidationResult wallRes = collisionService.validateAndClamp(
      230,
      1.5f, 0.0f, 0.0f,
      2.5f, 0.0f, 0.0f,
      MovementFlags.FORWARD
    );
    assertThat(wallRes.reconciled()).isTrue();
    assertThat(wallRes.reason()).isEqualTo("WALL_BLOCKED");
    assertThat(wallRes.x()).isCloseTo(1.9f, offset(0.01f));
  }

  @Test
  @DisplayName("CollisionObject thread-safe lazy initialization returns consistent worldSpaceTriangles and worldSpaceBoundingBox")
  void testCollisionObjectConcurrentLazyInitialization() throws Exception {
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        tasks.add(() -> {
          var tris = sharedCollisionObject.worldSpaceTriangles();
          var box = sharedCollisionObject.worldSpaceBoundingBox();
          assertThat(tris).hasSize(4);
          assertThat(box.min().y()).isEqualTo(-10.0f);
          assertThat(box.max().y()).isEqualTo(2.0f);
          return tris.size();
        });
      }
      List<Future<Integer>> futures = pool.invokeAll(tasks);
      for (Future<Integer> f : futures) {
        assertThat(f.get()).isEqualTo(4);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static CollisionMap buildSyntheticCollisionMap() {
    List<CollisionTriangle> triangles = List.of(
      new CollisionTriangle(
        new Vector3f(-50f, 0f, -50f),
        new Vector3f(50f, 0f, -50f),
        new Vector3f(50f, 0f, 50f),
        Vector3f.UP,
        new TriFlags(0),
        TerrainType.STONE
      ),
      new CollisionTriangle(
        new Vector3f(-50f, 0f, -50f),
        new Vector3f(50f, 0f, 50f),
        new Vector3f(-50f, 0f, 50f),
        Vector3f.UP,
        new TriFlags(0),
        TerrainType.STONE
      ),
      new CollisionTriangle(
        new Vector3f(2.0f, -10f, -50f),
        new Vector3f(2.0f, 2f, -50f),
        new Vector3f(2.0f, 2f, 50f),
        new Vector3f(-1f, 0f, 0f),
        new TriFlags(0x40),
        TerrainType.STONE
      ),
      new CollisionTriangle(
        new Vector3f(2.0f, -10f, -50f),
        new Vector3f(2.0f, 2f, 50f),
        new Vector3f(2.0f, -10f, 50f),
        new Vector3f(-1f, 0f, 0f),
        new TriFlags(0x40),
        TerrainType.STONE
      )
    );

    BoundingBox box = new BoundingBox(new Vector3f(-50f, -10f, -50f), new Vector3f(50f, 2f, 50f));
    CollisionMesh mesh = new CollisionMesh(1, triangles, box);
    Matrix4f identity = new Matrix4f();
    CollisionTransform transform = new CollisionTransform(1, identity, identity, 0, 0, null);
    CollisionObject obj = new CollisionObject(1, mesh, transform);
    CollisionObjectGroup group = new CollisionObjectGroup(1, List.of(obj));
    CollisionObjectGroup[][] grid = new CollisionObjectGroup[][]{{group}};
    return new CollisionMap(1, 1, 200, 200, 1, 1, grid);
  }
}
