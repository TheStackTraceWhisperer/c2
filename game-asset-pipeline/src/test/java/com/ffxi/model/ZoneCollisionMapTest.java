package com.ffxi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;

class ZoneCollisionMapTest {

    private static ZoneCollisionObject obj(float tx) {
        float[] m = new float[16];
        m[0] = 1f;
        m[5] = 1f;
        m[10] = 1f;
        m[15] = 1f;
        m[12] = tx;
        return new ZoneCollisionObject(
                List.of(new ZoneCollisionTriangle(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1)),
                m);
    }

    @Test
    void allObjectsDeduplicatesSameInstanceAcrossMultipleCellsPreservingOrder() {
        ZoneCollisionObject shared = obj(10f);
        ZoneCollisionObject second = obj(20f);
        ZoneCollisionObjectGroup g0 = new ZoneCollisionObjectGroup(List.of(shared, second));
        ZoneCollisionObjectGroup g1 = new ZoneCollisionObjectGroup(List.of(shared));
        ZoneCollisionObjectGroup[][] grid = {{g0, g1}, {g1, null}};

        ZoneCollisionMap map = new ZoneCollisionMap(2, 2, 4, 4, 1, 1, grid);

        assertThat(map.allObjects()).containsExactly(shared, second);
    }

    @Test
    void allObjectsKeepsDistinctInstancesWithIdenticalFloatArrayContents() {
        ZoneCollisionObject a = obj(5f);
        ZoneCollisionObject b = obj(5f);
        assertThat(a).isNotSameAs(b);
        ZoneCollisionObjectGroup g = new ZoneCollisionObjectGroup(List.of(a, b, a));
        ZoneCollisionObjectGroup[][] grid = {{g}};

        ZoneCollisionMap map = new ZoneCollisionMap(1, 1, 4, 4, 1, 1, grid);

        List<ZoneCollisionObject> all = map.allObjects();
        assertThat(all).hasSize(2);
        assertThat(all.get(0)).isSameAs(a);
        assertThat(all.get(1)).isSameAs(b);
    }

    @Test
    void zeroSubBlocksAndNullGridDoNotDivideByZeroOrThrow() {
        ZoneCollisionObject o = obj(1f);
        ZoneCollisionObjectGroup g = new ZoneCollisionObjectGroup(List.of(o));
        ZoneCollisionObjectGroup[][] grid = {{g, null}, null};

        // subBlocksX == 0 and subBlocksZ == 0 happen when blockWidth / 4 == 0
        ZoneCollisionMap map = new ZoneCollisionMap(2, 2, 2, 2, 0, 0, grid);
        assertThat(map.getNumBlocksX()).isEqualTo(2);
        assertThat(map.getNumBlocksZ()).isEqualTo(2);
        assertThat(map.allObjects()).containsExactly(o);
        assertThat(map.getGroups(0f, 0f)).containsExactly(g);
        assertThat(map.getBlockCenterXZ(0, 0)).containsExactly(-1f, -1f);
        assertThat(map.getBlockCenterXZ(-1, 0)).isNull();
        assertThat(map.getBlockCenterXZ(2, 0)).isNull();

        ZoneCollisionMap empty = new ZoneCollisionMap(0, 0, 0, 0, 0, 0, null);
        assertThatCode(() -> {
            assertThat(empty.allObjects()).isEmpty();
            assertThat(empty.getGroups(0f, 0f)).isEmpty();
            assertThat(empty.getObjectsAlongSegment(0f, 0f, 10f, 10f)).isEmpty();
            assertThat(empty.getBlockCenterXZ(0, 0)).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    void getObjectsAlongSegmentCollectsObjectsAcrossMultipleCellsWithoutDuplicates() {
        ZoneCollisionObject shared = obj(1f);
        ZoneCollisionObject endOnly = obj(2f);
        ZoneCollisionObjectGroup cellStart = new ZoneCollisionObjectGroup(List.of(shared));
        ZoneCollisionObjectGroup cellEnd = new ZoneCollisionObjectGroup(List.of(shared, endOnly));
        ZoneCollisionObjectGroup[][] grid = new ZoneCollisionObjectGroup[1][8];
        grid[0][0] = cellStart;
        grid[0][7] = cellEnd;

        ZoneCollisionMap map = new ZoneCollisionMap(8, 1, 10, 10, 1, 1, grid);

        List<ZoneCollisionObject> collected = map.getObjectsAlongSegment(-35f, 0f, 35f, 0f);
        assertThat(collected).containsExactly(shared, endOnly);
    }
}
