package com.example.asset.model;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enumeration of FFXI DAT file section types and their numeric codes.
 */
public enum SectionType {
    S00_END(0x00, "End"),
    S01_DIRECTORY(0x01, "Directory"),
    S04_TABLE(0x04, "Table"),
    S05_PARTICLE_GENERATOR(0x05, "ParticleGenerator"),
    S06_ROUTE(0x06, "Route"),
    S07_EFFECT_ROUTINE(0x07, "EffectRoutine"),
    S19_PARTICLE_KEYFRAME_DATA(0x19, "ParticleKeyFrameData"),
    S1C_ZONE_DEF(0x1C, "ZoneDef"),
    S1F_PARTICLE_MESH(0x1F, "ParticleMesh"),
    S20_TEXTURE(0x20, "Texture"),
    S21_SPRITESHEET_MESH(0x21, "SpriteSheetMesh"),
    S25_WEIGHTED_MESH(0x25, "WeightedMesh"),
    S29_SKELETON(0x29, "Skeleton"),
    S2A_SKELETON_MESH(0x2A, "SkeletonMesh"),
    S2B_SKELETON_ANIMATION(0x2B, "SkeletonAnimation"),
    S2E_ZONE_MESH(0x2E, "ZoneMesh"),
    S2F_ENVIRONMENT(0x2F, "Environment"),
    S30_UI_MENU(0x30, "UiMenu"),
    S31_UI_ELEMENT_GROUP(0x31, "UiElementGroup"),
    S36_ZONE_INTERACTIONS(0x36, "ZoneInteractions"),
    S3D_SOUND_EFFECT_POINTER(0x3D, "SoundEffectPointer"),
    S3E_POINT_LIST(0x3E, "PointList"),
    S45_INFO(0x45, "Info"),
    S46_UNKNOWN(0x46, "Unknown46"),
    S49_SPELL_LIST(0x49, "SpellList"),
    S4A_PATH(0x4A, "Path"),
    S53_ABILITY_LIST(0x53, "AbilityList"),
    S54_WEAPON_TRACE(0x54, "WeaponTrace"),
    S5D_BUMP_MAP(0x5D, "BumpMap"),
    S5E_BLUR(0x5E, "Blur"),
    S5F_UNKNOWN(0x5F, "Unknown5F"),
    UNHANDLED(-1, "Unhandled");

    private static final Map<Integer, SectionType> BY_CODE = Arrays.stream(values())
            .filter(t -> t.code >= 0)
            .collect(Collectors.toUnmodifiableMap(SectionType::code, Function.identity()));

    private final int code;
    private final String displayName;

    SectionType(int code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public int code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public static SectionType fromCode(int code) {
        return BY_CODE.getOrDefault(code, UNHANDLED);
    }
}
