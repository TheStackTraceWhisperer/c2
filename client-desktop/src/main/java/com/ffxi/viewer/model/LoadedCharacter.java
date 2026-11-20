package com.ffxi.viewer.model;

import com.ffxi.model.SkeletonAnimationResource;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.model.SkeletonResource;
import com.ffxi.tables.ItemModelSlot;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

public record LoadedCharacter(
        SkeletonResource skeleton,
        List<SkeletonMeshMesh> meshes,
        Map<String, CharacterTextureLoader.TextureSource> textures,
        List<SkeletonAnimationResource> slot0Animations,
        List<SkeletonAnimationResource> slot1Animations,
        float radius,
        float feetOffsetY,
        Vector3f modelOffset,
        Map<ItemModelSlot, Integer> loadedModelIds,
        float modelScale
) {
    public LoadedCharacter(
            SkeletonResource skeleton,
            List<SkeletonMeshMesh> meshes,
            Map<String, CharacterTextureLoader.TextureSource> textures,
            List<SkeletonAnimationResource> slot0Animations,
            List<SkeletonAnimationResource> slot1Animations,
            float radius,
            float feetOffsetY,
            Vector3f modelOffset,
            Map<ItemModelSlot, Integer> loadedModelIds
    ) {
        this(
                skeleton,
                meshes,
                textures,
                slot0Animations,
                slot1Animations,
                radius,
                feetOffsetY,
                modelOffset,
                loadedModelIds,
                1.0f
        );
    }

    /**
     * Returns the Y coordinate of the character's feet in FFXI skeleton space (max.y = center.y + feetOffsetY).
     */
    public float feetLocalY() {
        return feetOffsetY - (modelOffset != null ? modelOffset.y() : 0.0f);
    }

    /**
     * Returns the safe uniform scale factor derived from retail {@code 0x45 size_info}, defaulting to {@code 1.0f}.
     */
    public float effectiveScale() {
        return Float.isFinite(modelScale) && modelScale > 0.0f ? modelScale : 1.0f;
    }
}
