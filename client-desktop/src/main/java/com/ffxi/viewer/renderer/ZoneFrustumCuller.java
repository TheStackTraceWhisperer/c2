package com.ffxi.viewer.renderer;

import com.ffxi.model.ZoneSpacePartitionBounds;
import com.ffxi.model.ZoneSpacePartitionNode;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.BitSet;

/**
 * Evaluates retail FFXI {@link ZoneSpacePartitionNode} quadtrees parsed from zone DAT sections against a camera
 * projection-view matrix to cull off-screen zone chunks on the CPU before issuing OpenGL draw calls.
 */
public final class ZoneFrustumCuller {

    private static final float BOUNDS_MARGIN = 0.5f;

    private ZoneFrustumCuller() {
    }

    /**
     * Collects every {@code ZoneObject} index referenced by any leaf node of the space partitioning tree.
     * Chunks whose object index is not present in this set (or {@code < 0}) are treated as unindexed and always drawn.
     */
    public static BitSet collectIndexedObjectIds(ZoneSpacePartitionNode root) {
        BitSet indexed = new BitSet();
        collectIndexedRecursive(root, indexed);
        return indexed;
    }

    private static void collectIndexedRecursive(ZoneSpacePartitionNode node, BitSet indexed) {
        if (node == null) {
            return;
        }
        if (node.leafNode()) {
            for (Integer id : node.containedObjects()) {
                if (id != null && id >= 0) {
                    indexed.set(id);
                }
            }
            return;
        }
        for (ZoneSpacePartitionNode child : node.children()) {
            collectIndexedRecursive(child, indexed);
        }
    }

    /**
     * Traverses the zone space partitioning tree and collects object indices visible within {@code projView}.
     */
    public static BitSet collectVisibleObjectIds(ZoneSpacePartitionNode root, Matrix4f projView) {
        BitSet visible = new BitSet();
        if (root == null || projView == null) {
            return visible;
        }
        FrustumIntersection frustum = new FrustumIntersection(projView);
        traverseFrustum(root, frustum, visible);
        return visible;
    }

    private static void traverseFrustum(ZoneSpacePartitionNode node, FrustumIntersection frustum, BitSet visible) {
        if (node == null) {
            return;
        }
        ZoneSpacePartitionBounds bounds = node.boundingBox();
        if (bounds != null && isFiniteBounds(bounds)) {
            if (!frustum.testAab(
                    bounds.minX() - BOUNDS_MARGIN,
                    bounds.minY() - BOUNDS_MARGIN,
                    bounds.minZ() - BOUNDS_MARGIN,
                    bounds.maxX() + BOUNDS_MARGIN,
                    bounds.maxY() + BOUNDS_MARGIN,
                    bounds.maxZ() + BOUNDS_MARGIN
            )) {
                return;
            }
        }
        if (node.leafNode()) {
            for (Integer id : node.containedObjects()) {
                if (id != null && id >= 0) {
                    visible.set(id);
                }
            }
            return;
        }
        for (ZoneSpacePartitionNode child : node.children()) {
            traverseFrustum(child, frustum, visible);
        }
    }

    /**
     * Returns whether a chunk with {@code zoneObjectIndex} should be drawn for the current frame.
     */
    public static boolean isChunkVisible(int zoneObjectIndex, BitSet indexedObjectIds, BitSet visibleObjectIds) {
        if (visibleObjectIds == null || indexedObjectIds == null || zoneObjectIndex < 0) {
            return true;
        }
        if (!indexedObjectIds.get(zoneObjectIndex)) {
            return true;
        }
        return visibleObjectIds.get(zoneObjectIndex);
    }

    private static boolean isFiniteBounds(ZoneSpacePartitionBounds b) {
        return Float.isFinite(b.minX()) && Float.isFinite(b.minY()) && Float.isFinite(b.minZ())
                && Float.isFinite(b.maxX()) && Float.isFinite(b.maxY()) && Float.isFinite(b.maxZ())
                && b.minX() <= b.maxX() && b.minY() <= b.maxY() && b.minZ() <= b.maxZ();
    }
}
