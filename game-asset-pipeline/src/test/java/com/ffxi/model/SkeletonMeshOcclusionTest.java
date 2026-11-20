package com.ffxi.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkeletonMeshOcclusionTest {

    private static SkeletonMeshMesh meshWithDisplayType(String textureName, int displayType) {
        SkeletonMeshRenderProperties props = new SkeletonMeshRenderProperties(
                new BgraColor(255, 255, 255, 255),
                0f, 0f,
                0, displayType, 0, 0,
                1.0f,
                0, 0, 0,
                0f, 0,
                0f, false
        );
        return new SkeletonMeshMesh(
                0x54, "TRI", textureName, 1, 3,
                true, false, false, false,
                props, List.of(), List.of()
        );
    }

    private static SkeletonMeshResource resource(String sectionId, int occludeType, SkeletonMeshMesh... meshes) {
        return new SkeletonMeshResource(
                sectionId, "S2A_SkeletonMesh", 0, 0x40, 0x30,
                0, 0, 0, occludeType, 0, 0,
                false, false, false, true,
                occludeType,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                null, List.of(), 0, 0, List.of(), List.of(meshes)
        );
    }

    @Test
    void displayType0IsNeverOccluded() {
        SkeletonMeshMesh mesh = meshWithDisplayType("base", 0);
        Set<Integer> allOcclusions = Set.of(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x12, 0x22, 0x32);
        assertThat(SkeletonMeshOcclusion.isOccluded(mesh, allOcclusions)).isFalse();
    }

    @Test
    void displayType1Hair1OccludedBy02Through06() {
        SkeletonMeshMesh hair1 = meshWithDisplayType("hair1", 1);
        for (int occlude : List.of(0x02, 0x03, 0x04, 0x05, 0x06)) {
            assertThat(SkeletonMeshOcclusion.isOccluded(hair1, Set.of(occlude)))
                    .as("hair1 vs occlude 0x%02x", occlude)
                    .isTrue();
        }
        for (int nonOcclude : List.of(0x00, 0x01, 0x07, 0x12, 0x22, 0x32)) {
            assertThat(SkeletonMeshOcclusion.isOccluded(hair1, Set.of(nonOcclude)))
                    .as("hair1 vs non-occlude 0x%02x", nonOcclude)
                    .isFalse();
        }
    }

    @Test
    void displayType2And3Hair2Hair3OccludedBy04Through06() {
        for (int displayType : List.of(2, 3)) {
            SkeletonMeshMesh hair = meshWithDisplayType("hair" + displayType, displayType);
            for (int occlude : List.of(0x04, 0x05, 0x06)) {
                assertThat(SkeletonMeshOcclusion.isOccluded(hair, Set.of(occlude)))
                        .as("displayType %d vs occlude 0x%02x", displayType, occlude)
                        .isTrue();
            }
            for (int nonOcclude : List.of(0x00, 0x01, 0x02, 0x03, 0x12, 0x22, 0x32)) {
                assertThat(SkeletonMeshOcclusion.isOccluded(hair, Set.of(nonOcclude)))
                        .as("displayType %d vs non-occlude 0x%02x", displayType, nonOcclude)
                        .isFalse();
            }
        }
    }

    @Test
    void displayType4FaceOccludedOnlyBy05() {
        SkeletonMeshMesh face = meshWithDisplayType("face", 4);
        assertThat(SkeletonMeshOcclusion.isOccluded(face, Set.of(0x05))).isTrue();
        for (int nonOcclude : List.of(0x00, 0x02, 0x03, 0x04, 0x06, 0x12, 0x22, 0x32)) {
            assertThat(SkeletonMeshOcclusion.isOccluded(face, Set.of(nonOcclude))).isFalse();
        }
    }

    @Test
    void displayType5WristOccludedOnlyBy12() {
        SkeletonMeshMesh wrist = meshWithDisplayType("wrist", 5);
        assertThat(SkeletonMeshOcclusion.isOccluded(wrist, Set.of(0x12))).isTrue();
        for (int nonOcclude : List.of(0x00, 0x02, 0x05, 0x11, 0x22, 0x32)) {
            assertThat(SkeletonMeshOcclusion.isOccluded(wrist, Set.of(nonOcclude))).isFalse();
        }
    }

    @Test
    void displayType6PantsOccludedOnlyBy32() {
        SkeletonMeshMesh pants = meshWithDisplayType("pants", 6);
        assertThat(SkeletonMeshOcclusion.isOccluded(pants, Set.of(0x32))).isTrue();
        for (int nonOcclude : List.of(0x00, 0x05, 0x12, 0x22, 0x31)) {
            assertThat(SkeletonMeshOcclusion.isOccluded(pants, Set.of(nonOcclude))).isFalse();
        }
    }

    @Test
    void displayType7ShinsOccludedOnlyBy22() {
        SkeletonMeshMesh shins = meshWithDisplayType("shins", 7);
        assertThat(SkeletonMeshOcclusion.isOccluded(shins, Set.of(0x22))).isTrue();
        for (int nonOcclude : List.of(0x00, 0x05, 0x12, 0x21, 0x32)) {
            assertThat(SkeletonMeshOcclusion.isOccluded(shins, Set.of(nonOcclude))).isFalse();
        }
    }

    @Test
    void unknownDisplayTypeAndNullPropertiesDoNotThrowAndReturnFalse() {
        SkeletonMeshMesh unknown = meshWithDisplayType("weird", 99);
        SkeletonMeshMesh noProps = new SkeletonMeshMesh(
                0x54, "TRI", "noprops", 1, 3,
                true, false, false, false,
                null, List.of(), List.of()
        );
        Set<Integer> all = Set.of(0x02, 0x04, 0x05, 0x12, 0x22, 0x32);
        assertThat(SkeletonMeshOcclusion.isOccluded(unknown, all)).isFalse();
        assertThat(SkeletonMeshOcclusion.isOccluded(noProps, all)).isFalse();
        assertThat(SkeletonMeshOcclusion.isOccluded(null, all)).isFalse();
        assertThat(SkeletonMeshOcclusion.isOccluded(unknown, null)).isFalse();
    }

    @Test
    void defaultMaskHidesWep2RangedWeapon() {
        assertThat(SkeletonMeshOcclusion.DEFAULT_HIDDEN_WEAPON_MASK).isEqualTo(1 << 2);

        SkeletonMeshMesh bodyMesh = meshWithDisplayType("body", 0);
        SkeletonMeshMesh mainMesh = meshWithDisplayType("main", 0);
        SkeletonMeshMesh rangeMesh = meshWithDisplayType("bow", 0);

        List<SkeletonMeshResource> resources = List.of(
                resource("body", 0, bodyMesh),
                resource("wep0", 0, mainMesh),
                resource("wep2", 0, rangeMesh)
        );

        List<SkeletonMeshMesh> visibleDefault = SkeletonMeshOcclusion.filterVisibleMeshes(
                resources, SkeletonMeshOcclusion.DEFAULT_HIDDEN_WEAPON_MASK);
        assertThat(visibleDefault).containsExactly(bodyMesh, mainMesh);

        List<SkeletonMeshMesh> visibleAll = SkeletonMeshOcclusion.filterVisibleMeshes(resources, 0);
        assertThat(visibleAll).containsExactly(bodyMesh, mainMesh, rangeMesh);
    }

    @Test
    void trionWep4HideExcludesWep4AndHandlesTrimmingAndCase() {
        SkeletonMeshMesh armorMesh = meshWithDisplayType("armor", 0);
        SkeletonMeshMesh swordMesh = meshWithDisplayType("sword", 0);
        SkeletonMeshMesh scabbardMesh = meshWithDisplayType("scabbard", 0);

        List<SkeletonMeshResource> resources = List.of(
                resource("body", 0, armorMesh),
                resource("wep4", 0, swordMesh),
                resource("WEP4\0 ", 0, scabbardMesh)
        );

        List<SkeletonMeshMesh> visible = SkeletonMeshOcclusion.filterVisibleMeshes(resources, 1 << 4);
        assertThat(visible).containsExactly(armorMesh);
    }

    @Test
    void hiddenWeaponResourcesDoNotContributeOcclusionTypes() {
        SkeletonMeshMesh hair1Mesh = meshWithDisplayType("hair1", 1);
        SkeletonMeshMesh faceMesh = meshWithDisplayType("face", 4);
        SkeletonMeshMesh hiddenWepMesh = meshWithDisplayType("hidden_wep", 0);

        // Hidden wep4 carries occludeType 0x05 (full helmet/face), which must NOT occlude hair1/face once hidden
        List<SkeletonMeshResource> resources = List.of(
                resource("face", 0, hair1Mesh, faceMesh),
                resource("wep4", 0x05, hiddenWepMesh)
        );

        List<SkeletonMeshMesh> whenWep4Hidden = SkeletonMeshOcclusion.filterVisibleMeshes(resources, 1 << 4);
        assertThat(whenWep4Hidden).containsExactly(hair1Mesh, faceMesh);

        List<SkeletonMeshMesh> whenWep4Visible = SkeletonMeshOcclusion.filterVisibleMeshes(resources, 0);
        assertThat(whenWep4Visible).containsExactly(hiddenWepMesh);
    }
}
