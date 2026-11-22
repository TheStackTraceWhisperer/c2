package com.ffxi.viewer;

import com.ffxi.model.ZoneSpacePartitionBounds;
import com.ffxi.model.ZoneSpacePartitionNode;
import com.ffxi.viewer.renderer.ZoneFrustumCuller;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneFrustumCullerTest {

    @Test
    @DisplayName("ZoneFrustumCuller includes in-frustum objects, culls behind-camera objects, and keeps unindexed chunks visible")
    void testQuadtreeFrustumCulling() {
        ZoneSpacePartitionNode northLeaf = new ZoneSpacePartitionNode(
                true,
                List.of(10, 11),
                new ZoneSpacePartitionBounds(-5f, -5f, 15f, 5f, 5f, 25f),
                List.of()
        );
        ZoneSpacePartitionNode southLeaf = new ZoneSpacePartitionNode(
                true,
                List.of(20, 21),
                new ZoneSpacePartitionBounds(-5f, -5f, -35f, 5f, 5f, -25f),
                List.of()
        );
        ZoneSpacePartitionNode root = new ZoneSpacePartitionNode(
                false,
                List.of(),
                new ZoneSpacePartitionBounds(-50f, -10f, -50f, 50f, 10f, 50f),
                List.of(northLeaf, southLeaf)
        );

        BitSet indexed = ZoneFrustumCuller.collectIndexedObjectIds(root);
        assertTrue(indexed.get(10));
        assertTrue(indexed.get(11));
        assertTrue(indexed.get(20));
        assertTrue(indexed.get(21));

        // Camera at origin looking toward North (+Z) with -Y = Up
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(50.0), 16.0f / 9.0f, 0.1f, 200.0f);
        Matrix4f view = new Matrix4f().lookAt(
                new Vector3f(0f, 0f, 0f),
                new Vector3f(0f, 0f, 20f),
                new Vector3f(0f, -1f, 0f)
        );
        BitSet visible = ZoneFrustumCuller.collectVisibleObjectIds(root, new Matrix4f(proj).mul(view));

        assertTrue(ZoneFrustumCuller.isChunkVisible(10, indexed, visible), "In-frustum North leaf object should be visible");
        assertTrue(ZoneFrustumCuller.isChunkVisible(11, indexed, visible), "In-frustum North leaf object should be visible");
        assertFalse(ZoneFrustumCuller.isChunkVisible(20, indexed, visible), "Behind-camera South leaf object should be culled");
        assertFalse(ZoneFrustumCuller.isChunkVisible(21, indexed, visible), "Behind-camera South leaf object should be culled");

        // Unindexed or standalone world water chunks (-1 or absent from space tree) must never be culled
        assertTrue(ZoneFrustumCuller.isChunkVisible(-1, indexed, visible));
        assertTrue(ZoneFrustumCuller.isChunkVisible(999, indexed, visible));
    }
}
