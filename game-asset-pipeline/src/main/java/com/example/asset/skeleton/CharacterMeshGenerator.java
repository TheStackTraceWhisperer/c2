package com.example.asset.skeleton;

import com.example.asset.geometry.Vector3f;
import com.example.common.protocol.JobId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.asset.skeleton.HumanoidRig.*;

/**
 * Procedural authentic 3D humanoid character mesh generator.
 * Produces multi-slot composite equipment models with dual-bone skinning weights.
 */
public final class CharacterMeshGenerator {

    private CharacterMeshGenerator() {
    }

    /**
     * Builds a full composite character model styled according to main job and character role.
     */
    public static CompositeCharacterModel createCharacterModel(short jobId) {
        Skeleton skeleton = HumanoidRig.createDefaultSkeleton();
        Map<Integer, SkinnedMesh> slots = new HashMap<>();

        // Determine job styling palette
        int[] bodyColor;
        int[] armorTrimColor;
        int[] pantsColor;
        int[] bootsColor;
        boolean hasShield = false;
        boolean hasStaff = false;

        if (jobId == JobId.NPC) {
            bodyColor = new int[]{225, 175, 45};     // Golden amber civic tunic
            armorTrimColor = new int[]{160, 110, 20}; // Dark gold trim
            pantsColor = new int[]{65, 75, 95};       // Royal blue slacks
            bootsColor = new int[]{90, 60, 35};       // Brown leather boots
        } else if (jobId == JobId.WARRIOR || jobId == JobId.PALADIN) {
            bodyColor = new int[]{185, 195, 205};    // Steel cuirass
            armorTrimColor = new int[]{180, 40, 40};  // Crimson red trim
            pantsColor = new int[]{135, 145, 155};    // Chainmail greaves
            bootsColor = new int[]{110, 115, 125};    // Steel sabatons
            hasShield = (jobId == JobId.PALADIN);
        } else if (jobId == JobId.WHITE_MAGE) {
            bodyColor = new int[]{245, 245, 248};    // Pristine white robe
            armorTrimColor = new int[]{215, 45, 45};  // Iconic red triangular trim
            pantsColor = new int[]{235, 235, 240};
            bootsColor = new int[]{130, 85, 45};
            hasStaff = true;
        } else if (jobId == JobId.BLACK_MAGE) {
            bodyColor = new int[]{35, 38, 55};       // Midnight wizard coat
            armorTrimColor = new int[]{215, 185, 45}; // Gold runic trim
            pantsColor = new int[]{25, 28, 42};
            bootsColor = new int[]{50, 45, 60};
            hasStaff = true;
        } else if (jobId == JobId.RED_MAGE) {
            bodyColor = new int[]{185, 35, 45};      // Crimson doublet
            armorTrimColor = new int[]{240, 240, 245};// White lace jabot
            pantsColor = new int[]{45, 45, 55};
            bootsColor = new int[]{40, 40, 45};
        } else if (jobId == JobId.THIEF || jobId == JobId.NINJA) {
            bodyColor = new int[]{115, 80, 45};      // Studded leather jerkin
            armorTrimColor = new int[]{60, 45, 30};
            pantsColor = new int[]{55, 60, 65};
            bootsColor = new int[]{75, 55, 35};
        } else {
            // Default adventurer gear
            bodyColor = new int[]{140, 110, 80};     // Leather vest
            armorTrimColor = new int[]{85, 65, 45};
            pantsColor = new int[]{80, 90, 105};     // Denim/breeches
            bootsColor = new int[]{75, 55, 35};
        }

        // 1. Head (Face + Hair/Helmet)
        slots.put(CompositeCharacterModel.SLOT_HEAD, createHeadMesh());

        // 2. Body (Torso, Shoulders, Belt)
        slots.put(CompositeCharacterModel.SLOT_BODY, createBodyMesh(bodyColor, armorTrimColor));

        // 3. Hands (Arms, Forearms, Gauntlets)
        slots.put(CompositeCharacterModel.SLOT_HANDS, createHandsMesh(bodyColor, armorTrimColor));

        // 4. Legs (Breeches / Greaves)
        slots.put(CompositeCharacterModel.SLOT_LEGS, createLegsMesh(pantsColor));

        // 5. Feet (Boots / Sabatons)
        slots.put(CompositeCharacterModel.SLOT_FEET, createFeetMesh(bootsColor));

        // 6. Main Weapon (Sword or Staff)
        slots.put(CompositeCharacterModel.SLOT_MAIN, createMainWeaponMesh(hasStaff));

        // 7. Sub Weapon (Shield if PLD)
        if (hasShield) {
            slots.put(CompositeCharacterModel.SLOT_SUB, createShieldMesh());
        }

        return new CompositeCharacterModel(skeleton, slots);
    }

    private static SkinnedMesh createHeadMesh() {
        List<SkinnedSubMesh> subs = new ArrayList<>();

        // Face & Cranium (attached to BONE_HEAD)
        subs.add(createBoxMesh("face", 0f, 1.58f, 0f, 0.18f, 0.22f, 0.18f,
                BONE_HEAD, BONE_NECK, 0.85f, 0.15f, 235, 195, 165));

        // Hair / Helmet
        subs.add(createBoxMesh("hair", 0f, 1.68f, -0.02f, 0.20f, 0.10f, 0.20f,
                BONE_HEAD, BONE_NECK, 0.95f, 0.05f, 75, 50, 30));

        return new SkinnedMesh("head", subs);
    }

    private static SkinnedMesh createBodyMesh(int[] primary, int[] trim) {
        List<SkinnedSubMesh> subs = new ArrayList<>();

        // Chest / Upper Torso (blended BONE_CHEST & BONE_SPINE)
        subs.add(createBoxMesh("chest", 0f, 1.34f, 0f, 0.32f, 0.26f, 0.22f,
                BONE_CHEST, BONE_SPINE, 0.70f, 0.30f, primary[0], primary[1], primary[2]));

        // Waist / Lower Torso (blended BONE_SPINE & BONE_ROOT)
        subs.add(createBoxMesh("waist", 0f, 1.12f, 0f, 0.28f, 0.20f, 0.20f,
                BONE_SPINE, BONE_ROOT, 0.60f, 0.40f, primary[0], primary[1], primary[2]));

        // Shoulders / Epaulets
        subs.add(createBoxMesh("l_shoulder_pad", -0.22f, 1.40f, 0f, 0.14f, 0.10f, 0.14f,
                BONE_L_SHOULDER, BONE_CHEST, 0.80f, 0.20f, trim[0], trim[1], trim[2]));
        subs.add(createBoxMesh("r_shoulder_pad", 0.22f, 1.40f, 0f, 0.14f, 0.10f, 0.14f,
                BONE_R_SHOULDER, BONE_CHEST, 0.80f, 0.20f, trim[0], trim[1], trim[2]));

        return new SkinnedMesh("body", subs);
    }

    private static SkinnedMesh createHandsMesh(int[] primary, int[] trim) {
        List<SkinnedSubMesh> subs = new ArrayList<>();

        // Left Arm: Upper arm & Forearm & Hand
        subs.add(createBoxMesh("l_upper_arm", -0.24f, 1.25f, 0f, 0.10f, 0.22f, 0.10f,
                BONE_L_ARM, BONE_L_SHOULDER, 0.85f, 0.15f, primary[0], primary[1], primary[2]));
        subs.add(createBoxMesh("l_forearm", -0.25f, 1.02f, 0f, 0.09f, 0.22f, 0.09f,
                BONE_L_FOREARM, BONE_L_ARM, 0.85f, 0.15f, trim[0], trim[1], trim[2]));
        subs.add(createBoxMesh("l_hand", -0.25f, 0.86f, 0f, 0.08f, 0.10f, 0.08f,
                BONE_L_HAND, BONE_L_FOREARM, 0.90f, 0.10f, 220, 180, 150));

        // Right Arm: Upper arm & Forearm & Hand
        subs.add(createBoxMesh("r_upper_arm", 0.24f, 1.25f, 0f, 0.10f, 0.22f, 0.10f,
                BONE_R_ARM, BONE_R_SHOULDER, 0.85f, 0.15f, primary[0], primary[1], primary[2]));
        subs.add(createBoxMesh("r_forearm", 0.25f, 1.02f, 0f, 0.09f, 0.22f, 0.09f,
                BONE_R_FOREARM, BONE_R_ARM, 0.85f, 0.15f, trim[0], trim[1], trim[2]));
        subs.add(createBoxMesh("r_hand", 0.25f, 0.86f, 0f, 0.08f, 0.10f, 0.08f,
                BONE_R_HAND, BONE_R_FOREARM, 0.90f, 0.10f, 220, 180, 150));

        return new SkinnedMesh("hands", subs);
    }

    private static SkinnedMesh createLegsMesh(int[] pantsColor) {
        List<SkinnedSubMesh> subs = new ArrayList<>();

        // Left Thigh
        subs.add(createBoxMesh("l_thigh", -0.12f, 0.76f, 0f, 0.13f, 0.36f, 0.14f,
                BONE_L_THIGH, BONE_L_HIP, 0.80f, 0.20f, pantsColor[0], pantsColor[1], pantsColor[2]));

        // Right Thigh
        subs.add(createBoxMesh("r_thigh", 0.12f, 0.76f, 0f, 0.13f, 0.36f, 0.14f,
                BONE_R_THIGH, BONE_R_HIP, 0.80f, 0.20f, pantsColor[0], pantsColor[1], pantsColor[2]));

        return new SkinnedMesh("legs", subs);
    }

    private static SkinnedMesh createFeetMesh(int[] bootsColor) {
        List<SkinnedSubMesh> subs = new ArrayList<>();

        // Left Shin / Calf & Foot
        subs.add(createBoxMesh("l_calf", -0.12f, 0.38f, 0f, 0.12f, 0.36f, 0.13f,
                BONE_L_CALF, BONE_L_THIGH, 0.85f, 0.15f, bootsColor[0], bootsColor[1], bootsColor[2]));
        subs.add(createBoxMesh("l_foot", -0.12f, 0.08f, 0.06f, 0.12f, 0.12f, 0.24f,
                BONE_L_FOOT, BONE_L_CALF, 0.90f, 0.10f, bootsColor[0], bootsColor[1], bootsColor[2]));

        // Right Shin / Calf & Foot
        subs.add(createBoxMesh("r_calf", 0.12f, 0.38f, 0f, 0.12f, 0.36f, 0.13f,
                BONE_R_CALF, BONE_R_THIGH, 0.85f, 0.15f, bootsColor[0], bootsColor[1], bootsColor[2]));
        subs.add(createBoxMesh("r_foot", 0.12f, 0.08f, 0.06f, 0.12f, 0.12f, 0.24f,
                BONE_R_FOOT, BONE_R_CALF, 0.90f, 0.10f, bootsColor[0], bootsColor[1], bootsColor[2]));

        return new SkinnedMesh("feet", subs);
    }

    private static SkinnedMesh createMainWeaponMesh(boolean isStaff) {
        List<SkinnedSubMesh> subs = new ArrayList<>();

        if (isStaff) {
            // Wooden Staff: Shaft + Golden finial attached to right hand
            subs.add(createBoxMesh("staff_shaft", 0.26f, 0.95f, 0f, 0.035f, 1.60f, 0.035f,
                    BONE_R_HAND, BONE_R_FOREARM, 1.0f, 0.0f, 130, 85, 45));
            subs.add(createBoxMesh("staff_head", 0.26f, 1.78f, 0f, 0.12f, 0.14f, 0.12f,
                    BONE_R_HAND, BONE_R_FOREARM, 1.0f, 0.0f, 235, 195, 45));
        } else {
            // Steel Broadsword: Blade, Guard, Grip, Pommel attached to right hand
            subs.add(createBoxMesh("sword_blade", 0.26f, 1.15f, 0f, 0.03f, 0.85f, 0.08f,
                    BONE_R_HAND, BONE_R_FOREARM, 1.0f, 0.0f, 215, 225, 235));
            subs.add(createBoxMesh("sword_guard", 0.26f, 0.76f, 0f, 0.04f, 0.04f, 0.20f,
                    BONE_R_HAND, BONE_R_FOREARM, 1.0f, 0.0f, 185, 150, 40));
            subs.add(createBoxMesh("sword_hilt", 0.26f, 0.68f, 0f, 0.03f, 0.14f, 0.03f,
                    BONE_R_HAND, BONE_R_FOREARM, 1.0f, 0.0f, 95, 55, 30));
        }

        return new SkinnedMesh("main_weapon", subs);
    }

    private static SkinnedMesh createShieldMesh() {
        List<SkinnedSubMesh> subs = new ArrayList<>();
        // Knight's Heater Shield attached to left forearm
        subs.add(createBoxMesh("shield_face", -0.32f, 1.02f, 0.04f, 0.04f, 0.45f, 0.32f,
                BONE_L_FOREARM, BONE_L_HAND, 0.90f, 0.10f, 50, 85, 160));
        subs.add(createBoxMesh("shield_rim", -0.31f, 1.02f, 0.04f, 0.045f, 0.47f, 0.34f,
                BONE_L_FOREARM, BONE_L_HAND, 0.90f, 0.10f, 215, 185, 45));
        return new SkinnedMesh("sub_shield", subs);
    }

    /**
     * Constructs a 12-triangle 3D box submesh with dual-bone skinning weights and normal vectors.
     */
    private static SkinnedSubMesh createBoxMesh(
            String name,
            float cx, float cy, float cz,
            float sx, float sy, float sz,
            int j0, int j1,
            float w0, float w1,
            int r, int g, int b
    ) {
        float hx = sx * 0.5f;
        float hy = sy * 0.5f;
        float hz = sz * 0.5f;

        List<SkinnedVertex> verts = new ArrayList<>(24);
        List<Integer> indices = new ArrayList<>(36);

        // Face 0: Front (+Z)
        addQuad(verts, indices,
                new Vector3f(cx - hx, cy - hy, cz + hz),
                new Vector3f(cx + hx, cy - hy, cz + hz),
                new Vector3f(cx + hx, cy + hy, cz + hz),
                new Vector3f(cx - hx, cy + hy, cz + hz),
                new Vector3f(0f, 0f, 1f),
                j0, j1, w0, w1, r, g, b);

        // Face 1: Back (-Z)
        addQuad(verts, indices,
                new Vector3f(cx + hx, cy - hy, cz - hz),
                new Vector3f(cx - hx, cy - hy, cz - hz),
                new Vector3f(cx - hx, cy + hy, cz - hz),
                new Vector3f(cx + hx, cy + hy, cz - hz),
                new Vector3f(0f, 0f, -1f),
                j0, j1, w0, w1, r, g, b);

        // Face 2: Left (-X)
        addQuad(verts, indices,
                new Vector3f(cx - hx, cy - hy, cz - hz),
                new Vector3f(cx - hx, cy - hy, cz + hz),
                new Vector3f(cx - hx, cy + hy, cz + hz),
                new Vector3f(cx - hx, cy + hy, cz - hz),
                new Vector3f(-1f, 0f, 0f),
                j0, j1, w0, w1, r, g, b);

        // Face 3: Right (+X)
        addQuad(verts, indices,
                new Vector3f(cx + hx, cy - hy, cz + hz),
                new Vector3f(cx + hx, cy - hy, cz - hz),
                new Vector3f(cx + hx, cy + hy, cz - hz),
                new Vector3f(cx + hx, cy + hy, cz + hz),
                new Vector3f(1f, 0f, 0f),
                j0, j1, w0, w1, r, g, b);

        // Face 4: Top (+Y)
        addQuad(verts, indices,
                new Vector3f(cx - hx, cy + hy, cz + hz),
                new Vector3f(cx + hx, cy + hy, cz + hz),
                new Vector3f(cx + hx, cy + hy, cz - hz),
                new Vector3f(cx - hx, cy + hy, cz - hz),
                new Vector3f(0f, 1f, 0f),
                j0, j1, w0, w1, r, g, b);

        // Face 5: Bottom (-Y)
        addQuad(verts, indices,
                new Vector3f(cx - hx, cy - hy, cz - hz),
                new Vector3f(cx + hx, cy - hy, cz - hz),
                new Vector3f(cx + hx, cy - hy, cz + hz),
                new Vector3f(cx - hx, cy - hy, cz + hz),
                new Vector3f(0f, -1f, 0f),
                j0, j1, w0, w1, r, g, b);

        return new SkinnedSubMesh(name, false, true, verts, indices);
    }

    private static void addQuad(
            List<SkinnedVertex> verts,
            List<Integer> indices,
            Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3,
            Vector3f normal,
            int j0, int j1,
            float w0, float w1,
            int r, int g, int b
    ) {
        int base = verts.size();
        verts.add(new SkinnedVertex(p0, normal, 0f, 0f, j0, j1, w0, w1, r, g, b, 255));
        verts.add(new SkinnedVertex(p1, normal, 1f, 0f, j0, j1, w0, w1, r, g, b, 255));
        verts.add(new SkinnedVertex(p2, normal, 1f, 1f, j0, j1, w0, w1, r, g, b, 255));
        verts.add(new SkinnedVertex(p3, normal, 0f, 1f, j0, j1, w0, w1, r, g, b, 255));

        // Two counter-clockwise triangles
        indices.add(base);
        indices.add(base + 1);
        indices.add(base + 2);

        indices.add(base);
        indices.add(base + 2);
        indices.add(base + 3);
    }
}
