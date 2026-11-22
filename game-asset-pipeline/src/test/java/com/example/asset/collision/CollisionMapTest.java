package com.example.asset.collision;

import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.Matrix4f;
import com.example.asset.geometry.RayHit;
import com.example.asset.geometry.TerrainType;
import com.example.asset.geometry.TriFlags;
import com.example.asset.geometry.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.data.Offset.offset;

class CollisionMapTest {

    private static CollisionObject obj(float tx) {
        return obj(0, List.of(new CollisionTriangle(
                new Vector3f(0f, 0f, 0f),
                new Vector3f(1f, 0f, 0f),
                new Vector3f(0f, 1f, 0f),
                new Vector3f(0f, 0f, 1f),
                new TriFlags(0),
                TerrainType.STONE
        )), tx);
    }

    private static CollisionObject obj(int fileOffset, List<CollisionTriangle> triangles, float tx) {
        BoundingBox.Builder bbox = BoundingBox.builder();
        for (CollisionTriangle tri : triangles) {
            bbox.include(tri.v0()).include(tri.v1()).include(tri.v2());
        }
        CollisionMesh mesh = new CollisionMesh(fileOffset, triangles, bbox.build());
        Matrix4f toWorld = Matrix4f.translation(tx, 0f, 0f);
        Matrix4f toLocal = Matrix4f.translation(-tx, 0f, 0f);
        CollisionTransform transform = new CollisionTransform(fileOffset, toWorld, toLocal, 0, 0, null);
        return new CollisionObject(fileOffset, mesh, transform);
    }

    @Test
    void allObjectsDeduplicatesSameInstanceAcrossMultipleCellsPreservingOrder() {
        CollisionObject shared = obj(10f);
        CollisionObject second = obj(20f);
        CollisionObjectGroup g0 = new CollisionObjectGroup(1, List.of(shared, second));
        CollisionObjectGroup g1 = new CollisionObjectGroup(2, List.of(shared));
        CollisionObjectGroup[][] grid = {{g0, g1}, {g1, null}};

        CollisionMap map = new CollisionMap(2, 2, 4, 4, 1, 1, grid);

        assertThat(map.allObjects()).containsExactly(shared, second);
    }

    @Test
    void allObjectsKeepsDistinctInstancesWithIdenticalDataAndZeroFileOffset() {
        CollisionObject a = obj(5f);
        CollisionObject b = obj(5f);
        assertThat(a).isNotSameAs(b);
        CollisionObjectGroup g = new CollisionObjectGroup(0, List.of(a, b, a));
        CollisionObjectGroup[][] grid = {{g}};

        CollisionMap map = new CollisionMap(1, 1, 4, 4, 1, 1, grid);

        List<CollisionObject> all = map.allObjects();
        assertThat(all).hasSize(2);
        assertThat(all.get(0)).isSameAs(a);
        assertThat(all.get(1)).isSameAs(b);
        assertThat(map.allWorldSpaceTriangles()).hasSize(2);
    }

    @Test
    void zeroSubBlocksAndNullGridDoNotDivideByZeroOrThrow() {
        CollisionObject o = obj(1f);
        CollisionObjectGroup g = new CollisionObjectGroup(1, List.of(o));
        CollisionObjectGroup[][] grid = {{g, null}, null};

        // subBlocksX == 0 and subBlocksZ == 0 happen when blockWidth / 4 == 0
        CollisionMap map = new CollisionMap(2, 2, 2, 2, 0, 0, grid);
        assertThat(map.totalCellsX()).isEqualTo(2);
        assertThat(map.totalCellsZ()).isEqualTo(2);
        assertThat(map.allObjects()).containsExactly(o);
        assertThat(map.getNearbyGroups(Vector3f.ZERO, 1)).containsExactly(g);
        assertThat(map.getBlockCenterXZ(0, 0)).containsExactly(-1f, -1f);
        assertThat(map.getBlockCenterXZ(-1, 0)).isNull();
        assertThat(map.getBlockCenterXZ(2, 0)).isNull();

        CollisionMap empty = new CollisionMap(0, 0, 0, 0, 0, 0, null);
        assertThatCode(() -> {
            assertThat(empty.allObjects()).isEmpty();
            assertThat(empty.allWorldSpaceTriangles()).isEmpty();
            assertThat(empty.getNearbyGroups(Vector3f.ZERO, 1)).isEmpty();
            assertThat(empty.getObjectsAlongSegment(0f, 0f, 10f, 10f)).isEmpty();
            assertThat(empty.getBlockCenterXZ(0, 0)).isNull();
            assertThat(empty.raycastFloor(Vector3f.ZERO, 2.0f, 50.0f, true)).isEmpty();
            assertThat(empty.raycastSegment(Vector3f.ZERO, new Vector3f(10f, 0f, 0f), true)).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void getObjectsAlongSegmentCollectsObjectsAcrossMultipleCellsWithoutDuplicates() {
        CollisionObject shared = obj(1f);
        CollisionObject endOnly = obj(2f);
        CollisionObjectGroup cellStart = new CollisionObjectGroup(1, List.of(shared));
        CollisionObjectGroup cellEnd = new CollisionObjectGroup(2, List.of(shared, endOnly));
        CollisionObjectGroup[][] grid = new CollisionObjectGroup[1][8];
        grid[0][0] = cellStart;
        grid[0][7] = cellEnd;

        CollisionMap map = new CollisionMap(8, 1, 10, 10, 1, 1, grid);

        List<CollisionObject> collected = map.getObjectsAlongSegment(-35f, 0f, 35f, 0f);
        assertThat(collected).containsExactly(shared, endOnly);
    }

    @Test
    void raycastSegmentRespectsMinDistanceAndTriangleFilter() {
        // Near wall at x = 2 (no TriFlags wall bit, geometric vertical wall) and far tagged barrier at x = 25
        List<CollisionTriangle> nearTris = List.of(
                new CollisionTriangle(
                        new Vector3f(2f, -5f, -5f),
                        new Vector3f(2f, 5f, -5f),
                        new Vector3f(2f, 5f, 5f),
                        new Vector3f(-1f, 0f, 0f),
                        new TriFlags(0),
                        TerrainType.STONE
                )
        );
        List<CollisionTriangle> farTris = List.of(
                new CollisionTriangle(
                        new Vector3f(25f, -5f, -5f),
                        new Vector3f(25f, 5f, -5f),
                        new Vector3f(25f, 5f, 5f),
                        new Vector3f(-1f, 0f, 0f),
                        new TriFlags(0x40),
                        TerrainType.STONE
                )
        );
        CollisionObject nearObj = obj(1, nearTris, 0f);
        CollisionObject farObj = obj(2, farTris, 0f);

        CollisionObjectGroup[][] grid = new CollisionObjectGroup[1][8];
        grid[0][4] = new CollisionObjectGroup(1, List.of(nearObj)); // x in [0, 10]
        grid[0][6] = new CollisionObjectGroup(2, List.of(farObj));  // x in [20, 30]
        CollisionMap map = new CollisionMap(8, 1, 10, 10, 1, 1, grid);

        Vector3f start = new Vector3f(0f, 0f, 0f);
        Vector3f end = new Vector3f(30f, 0f, 0f);

        // wallsOnly=true checks TriFlags.isHitWall() across full multi-cell segment
        Optional<RayHit> taggedHit = map.raycastSegment(start, end, true);
        assertThat(taggedHit).isPresent();
        assertThat(taggedHit.get().distance()).isCloseTo(25.0f, offset(0.001f));

        // Custom geometric wall filter catches nearObj despite TriFlags(0)
        Optional<RayHit> geoHit = map.raycastSegment(start, end, 0.0f, tri -> Math.abs(tri.normal().y()) < 0.3f);
        assertThat(geoHit).isPresent();
        assertThat(geoHit.get().distance()).isCloseTo(2.0f, offset(0.001f));

        // minDistance=5.0 skips nearObj (dist=2.0) and hits farObj (dist=25.0)
        Optional<RayHit> skippedNearHit = map.raycastSegment(start, end, 5.0f, null);
        assertThat(skippedNearHit).isPresent();
        assertThat(skippedNearHit.get().distance()).isCloseTo(25.0f, offset(0.001f));
    }

    @Test
    void raycastFloorSupportsUnboundedMaxDistanceAndRejectsDegenerateTriangles() {
        List<CollisionTriangle> tris = List.of(
                // Degenerate zero-area triangle at y = -100 should never intercept
                new CollisionTriangle(
                        new Vector3f(0f, -100f, 0f),
                        new Vector3f(0f, -100f, 0f),
                        new Vector3f(0f, -100f, 0f),
                        Vector3f.UP,
                        new TriFlags(0),
                        TerrainType.GRASS
                ),
                // Valid floor at y = 12.5
                new CollisionTriangle(
                        new Vector3f(-10f, 12.5f, -10f),
                        new Vector3f(10f, 12.5f, -10f),
                        new Vector3f(10f, 12.5f, 10f),
                        Vector3f.UP,
                        new TriFlags(0),
                        TerrainType.GRASS
                ),
                new CollisionTriangle(
                        new Vector3f(-10f, 12.5f, -10f),
                        new Vector3f(10f, 12.5f, 10f),
                        new Vector3f(-10f, 12.5f, 10f),
                        Vector3f.UP,
                        new TriFlags(0),
                        TerrainType.GRASS
                )
        );
        CollisionObject floorObj = obj(1, tris, 0f);
        CollisionObjectGroup[][] grid = {{new CollisionObjectGroup(1, List.of(floorObj))}};
        CollisionMap map = new CollisionMap(1, 1, 64, 64, 1, 1, grid);

        Optional<RayHit> hit = map.raycastFloor(new Vector3f(0f, -2000f, 0f), 0.0f, Float.POSITIVE_INFINITY, true);
        assertThat(hit).isPresent();
        assertThat(hit.get().point().y()).isCloseTo(12.5f, offset(0.001f));
    }
}
