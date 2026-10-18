package com.example.asset.skeleton;

import com.example.asset.geometry.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkeletonMeshTest {

    @Test
    @DisplayName("Dual-bone linear blend skinning vertex weights")
    void testSkinnedVertexWeights() {
        SkinnedVertex v = new SkinnedVertex(
                new Vector3f(1.0f, 2.0f, 3.0f),
                new Vector3f(0.0f, 1.0f, 0.0f),
                0.5f, 0.5f,
                2, 5,
                0.7f, 0.3f,
                255, 255, 255, 255
        );

        assertEquals(1.0f, v.jointWeight0() + v.jointWeight1(), 1e-5f);
        assertEquals(2, v.jointIndex0());
        assertEquals(5, v.jointIndex1());
    }

    @Test
    @DisplayName("Assemble CompositeCharacterModel with equipment pieces")
    void testCompositeCharacterModel() {
        SkinnedVertex v0 = new SkinnedVertex(new Vector3f(0f, 0f, 0f), Vector3f.UP, 0f, 0f, 0, 0, 1f, 0f, 255, 255, 255, 255);
        SkinnedVertex v1 = new SkinnedVertex(new Vector3f(1f, 0f, 0f), Vector3f.UP, 1f, 0f, 0, 0, 1f, 0f, 255, 255, 255, 255);
        SkinnedVertex v2 = new SkinnedVertex(new Vector3f(0f, 1f, 0f), Vector3f.UP, 0f, 1f, 0, 0, 1f, 0f, 255, 255, 255, 255);

        SkinnedSubMesh bodySub = new SkinnedSubMesh("body_tex", false, true, List.of(v0, v1, v2), List.of(0, 1, 2));
        SkinnedMesh bodyMesh = new SkinnedMesh("body", List.of(bodySub));

        CompositeCharacterModel character = new CompositeCharacterModel(
                null,
                Map.of(CompositeCharacterModel.SLOT_BODY, bodyMesh)
        );

        assertNotNull(character.equipmentSlots().get(CompositeCharacterModel.SLOT_BODY));
        assertEquals(1, character.equipmentSlots().get(CompositeCharacterModel.SLOT_BODY).totalTriangleCount());
        assertEquals(3, character.equipmentSlots().get(CompositeCharacterModel.SLOT_BODY).totalVertexCount());
    }
}
