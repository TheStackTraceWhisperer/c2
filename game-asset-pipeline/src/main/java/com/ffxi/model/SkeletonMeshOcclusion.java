package com.ffxi.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Filters {@link SkeletonMeshResource} submeshes by weapon visibility mask ({@code wep0..wep9})
 * and FFXI equipment-driven mesh occlusion rules (hair/face/wrist/pants/shins).
 */
public final class SkeletonMeshOcclusion {

    /**
     * Default hidden weapon mask: hides {@code wep2} (ranged weapon) when idle/not firing,
     * matching XIM {@code ActorModel.kt}.
     */
    public static final int DEFAULT_HIDDEN_WEAPON_MASK = 1 << 2;

    private SkeletonMeshOcclusion() {
    }

    /**
     * Filters visible submeshes using {@link #DEFAULT_HIDDEN_WEAPON_MASK}.
     */
    public static List<SkeletonMeshMesh> filterVisibleMeshes(List<SkeletonMeshResource> resources) {
        return filterVisibleMeshes(resources, DEFAULT_HIDDEN_WEAPON_MASK);
    }

    /**
     * Filters visible submeshes from {@code resources} in three steps:
     * <ol>
     *   <li>Excludes any resource whose normalized {@code sectionId} is {@code "wepN"} ({@code 0 <= N <= 9})
     *       with bit {@code N} set in {@code hiddenWeaponMask}.</li>
     *   <li>Collects {@link SkeletonMeshResource#occludeType()} from the remaining visible resources.</li>
     *   <li>Returns all {@link SkeletonMeshMesh} entries from remaining resources not occluded by that set.</li>
     * </ol>
     */
    public static List<SkeletonMeshMesh> filterVisibleMeshes(
            List<SkeletonMeshResource> resources,
            int hiddenWeaponMask
    ) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }

        List<SkeletonMeshResource> remaining = new ArrayList<>(resources.size());
        Set<Integer> occlusionTypes = new HashSet<>();

        for (SkeletonMeshResource res : resources) {
            if (res == null) {
                continue;
            }
            if (isHiddenWeaponSection(res.sectionId(), hiddenWeaponMask)) {
                continue;
            }
            remaining.add(res);
            occlusionTypes.add(res.occludeType());
        }

        List<SkeletonMeshMesh> visible = new ArrayList<>();
        for (SkeletonMeshResource res : remaining) {
            for (SkeletonMeshMesh mesh : res.meshes()) {
                if (mesh != null && !isOccluded(mesh, occlusionTypes)) {
                    visible.add(mesh);
                }
            }
        }
        return visible;
    }

    /**
     * Evaluates whether {@code mesh} is occluded by {@code occlusionTypes} according to FFXI/XIM
     * {@code displayType} vs {@code occludeType} rules. Unknown {@code displayType} values return {@code false}.
     */
    public static boolean isOccluded(SkeletonMeshMesh mesh, Set<Integer> occlusionTypes) {
        if (mesh == null || mesh.renderProperties() == null || occlusionTypes == null || occlusionTypes.isEmpty()) {
            return false;
        }
        int displayType = mesh.renderProperties().displayType();
        return switch (displayType) {
            case 0 -> false;
            case 1 -> occlusionTypes.contains(0x02)
                    || occlusionTypes.contains(0x03)
                    || occlusionTypes.contains(0x04)
                    || occlusionTypes.contains(0x05)
                    || occlusionTypes.contains(0x06);
            case 2, 3 -> occlusionTypes.contains(0x04)
                    || occlusionTypes.contains(0x05)
                    || occlusionTypes.contains(0x06);
            case 4 -> occlusionTypes.contains(0x05);
            case 5 -> occlusionTypes.contains(0x12);
            case 6 -> occlusionTypes.contains(0x32);
            case 7 -> occlusionTypes.contains(0x22);
            default -> false;
        };
    }

    static boolean isHiddenWeaponSection(String sectionId, int hiddenWeaponMask) {
        if (sectionId == null || hiddenWeaponMask == 0) {
            return false;
        }
        String normalized = normalizeSectionId(sectionId);
        if (normalized.length() == 4 && normalized.startsWith("wep")) {
            char digit = normalized.charAt(3);
            if (digit >= '0' && digit <= '9') {
                int wepIndex = digit - '0';
                return ((hiddenWeaponMask >>> wepIndex) & 1) != 0;
            }
        }
        return false;
    }

    private static String normalizeSectionId(String sectionId) {
        int start = 0;
        int end = sectionId.length();
        while (start < end && (sectionId.charAt(start) == '\0' || sectionId.charAt(start) == ' ')) {
            start++;
        }
        while (end > start && (sectionId.charAt(end - 1) == '\0' || sectionId.charAt(end - 1) == ' ')) {
            end--;
        }
        return sectionId.substring(start, end).toLowerCase(Locale.ROOT);
    }
}
