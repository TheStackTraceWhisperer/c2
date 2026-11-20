package com.example.zone.patrol;

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
import com.example.config.ZoneServerProperties;
import com.example.zone.collision.ZoneCollisionService;
import com.example.zone.spatial.SpatialGrid;
import com.example.zone.state.ZoneEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class NpcPatrolSimulationTest {

  @Test
  @DisplayName("Empty waypoint list does not walk NPC to (0,0,0) and clears non-zero velocity")
  void testEmptyWaypointListGuard() {
    NpcPatrolSimulation sim = new NpcPatrolSimulation();
    SpatialGrid grid = new SpatialGrid();
    Map<Long, ZoneEntityState> entities = new ConcurrentHashMap<>();

    ZoneEntityState npc = ZoneEntityState.createNpc(101L, 230, "Guard", 25.0f, 1.0f, -15.0f, 90.0f);
    npc.setVx(2.0f);
    npc.setMoveFlags(1);
    entities.put(101L, npc);

    sim.registerPatrol(101L, new NpcPatrolRoute("empty-route", List.of(), true), 3.0f);
    sim.tick(0.05f, 1L, 230, entities, grid, null);

    assertThat(npc.getX()).isEqualTo(25.0f);
    assertThat(npc.getY()).isEqualTo(1.0f);
    assertThat(npc.getZ()).isEqualTo(-15.0f);
    assertThat(npc.getVx()).isEqualTo(0.0f);
    assertThat(npc.getVy()).isEqualTo(0.0f);
    assertThat(npc.getVz()).isEqualTo(0.0f);
    assertThat(npc.getMoveFlags()).isEqualTo(0);
    assertThat(npc.isNetworkDirty()).isTrue();
    assertThat(npc.isDirty()).isFalse();
  }

  @Test
  @DisplayName("Smoothly interpolates Y when collision is disabled and marks networkDirty (not persistence dirty)")
  void testSmoothYInterpolationWithoutCollision() {
    NpcPatrolSimulation sim = new NpcPatrolSimulation();
    SpatialGrid grid = new SpatialGrid();
    Map<Long, ZoneEntityState> entities = new ConcurrentHashMap<>();

    ZoneEntityState npc = ZoneEntityState.createNpc(102L, 230, "Climber", 0.0f, 0.0f, 0.0f, 0.0f);
    entities.put(102L, npc);

    // Distance = 10m along +X, waypoint Y = 4.0f, speed = 10 m/s, dt = 0.05s => step = 0.5m (5% of distance)
    sim.registerPatrol(102L, new NpcPatrolRoute(
      "slope-route",
      List.of(new NpcPatrolWaypoint(10.0f, 4.0f, 0.0f)),
      false
    ), 10.0f);

    sim.tick(0.05f, 1L, 230, entities, grid, null);

    assertThat(npc.getX()).isCloseTo(0.5f, offset(0.001f));
    assertThat(npc.getY()).isCloseTo(0.2f, offset(0.001f));
    assertThat(npc.getYaw()).isCloseTo(90.0f, offset(0.001f)); // +X = East = 90 deg
    assertThat(npc.isNetworkDirty()).isTrue();
    assertThat(npc.isDirty()).isFalse();

    // Verify +Z (North) produces 0 deg, -Z (South) produces 180 deg, -X (West) produces 270 deg
    ZoneEntityState northWalker = ZoneEntityState.createNpc(104L, 230, "NorthWalker", 0.0f, 0.0f, 0.0f, 180.0f);
    entities.put(104L, northWalker);
    sim.registerPatrol(104L, new NpcPatrolRoute("north-route", List.of(new NpcPatrolWaypoint(0.0f, 0.0f, 10.0f)), false), 10.0f);
    sim.tick(0.05f, 2L, 230, entities, grid, null);
    assertThat(northWalker.getYaw()).isCloseTo(0.0f, offset(0.001f));

    // Verify NpcRecord.yawDegrees() maps LSB pos_rot byte (0=E, 64=S, 128=W, 192=N) to compass yawDegrees
    assertThat(sampleNpc((short) 0).yawDegrees()).isCloseTo(90.0f, offset(0.001f));
    assertThat(sampleNpc((short) 64).yawDegrees()).isCloseTo(180.0f, offset(0.001f));
    assertThat(sampleNpc((short) 128).yawDegrees()).isCloseTo(270.0f, offset(0.001f));
    assertThat(sampleNpc((short) 192).yawDegrees()).isCloseTo(0.0f, offset(0.001f));
  }

  private static com.example.server.entity.NpcRecord sampleNpc(short posRot) {
    return new com.example.server.entity.NpcRecord(
      1L, "TestNpc", 0f, 0f, 0f, posRot, 230, "normal", 0, (short) 0, (short) 0, (short) 0,
      "equipped", (short) 1, (short) 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, true
    );
  }

  @Test
  @DisplayName("Wall-blocked patrolling NPC zeros velocity and moveFlags")
  void testWallBlockedZerosVelocity() {
    ZoneServerProperties props = new ZoneServerProperties();
    props.setHost("127.0.0.1");
    props.setPort(9000);
    props.setZoneId(230);
    props.setCollisionEnabled(true);
    props.setMaxStepHeight(1.2f);
    props.setMaxJumpHeight(3.5f);

    ZoneCollisionService collisionService = new ZoneCollisionService(props);
    collisionService.registerCollisionMap(230, buildWallMap(2.0f));

    NpcPatrolSimulation sim = new NpcPatrolSimulation();
    SpatialGrid grid = new SpatialGrid();
    Map<Long, ZoneEntityState> entities = new ConcurrentHashMap<>();

    ZoneEntityState npc = ZoneEntityState.createNpc(103L, 230, "WallWalker", 1.8f, 0.0f, 0.0f, 90.0f);
    entities.put(103L, npc);

    // Patrol target is beyond the wall at x = 2.0f
    sim.registerPatrol(103L, new NpcPatrolRoute(
      "blocked-route",
      List.of(new NpcPatrolWaypoint(10.0f, 0.0f, 0.0f)),
      true
    ), 10.0f);

    sim.tick(0.05f, 1L, 230, entities, grid, collisionService);

    assertThat(npc.getX()).isLessThan(2.0f);
    assertThat(npc.getVx()).isEqualTo(0.0f);
    assertThat(npc.getVy()).isEqualTo(0.0f);
    assertThat(npc.getVz()).isEqualTo(0.0f);
    assertThat(npc.getMoveFlags()).isEqualTo(0);
    assertThat(npc.isNetworkDirty()).isTrue();
    assertThat(npc.isDirty()).isFalse();
  }

  private static CollisionMap buildWallMap(float wallX) {
    List<CollisionTriangle> triangles = List.of(
      // Floor quad at y = 0 with native FFXI upward normal (0, -1, 0)
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
      // Wall quad at x = wallX spanning y from -10 (10m up) to +2 (2m down)
      new CollisionTriangle(
        new Vector3f(wallX, -10f, -50f),
        new Vector3f(wallX, 2f, -50f),
        new Vector3f(wallX, 2f, 50f),
        new Vector3f(-1f, 0f, 0f),
        new TriFlags(0x40),
        TerrainType.STONE
      ),
      new CollisionTriangle(
        new Vector3f(wallX, -10f, -50f),
        new Vector3f(wallX, 2f, 50f),
        new Vector3f(wallX, -10f, 50f),
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
