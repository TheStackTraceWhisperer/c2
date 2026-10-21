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
        Map<ItemModelSlot, Integer> loadedModelIds
) {
}
