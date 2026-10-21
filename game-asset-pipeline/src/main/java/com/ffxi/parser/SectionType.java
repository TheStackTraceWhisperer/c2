package com.ffxi.parser;

public enum SectionType {
    S00_End(0x00),
    S01_Directory(0x01),
    S04_Table(0x04),
    S05_ParticleGenerator(0x05),
    S06_Route(0x06),
    S07_EffectRoutine(0x07),
    S19_ParticleKeyFrameData(0x19),
    S1C_ZoneDef(0x1C),
    S1F_ParticleMesh(0x1F),
    S20_Texture(0x20),
    S21_SpriteSheetMesh(0x21),
    S25_WeightedMesh(0x25),
    S29_Skeleton(0x29),
    S2A_SkeletonMesh(0x2A),
    S2B_SkeletonAnimation(0x2B),
    S2E_ZoneMesh(0x2E),
    S2F_Environment(0x2F),
    S30_UiMenu(0x30),
    S31_UiElementGroup(0x31),
    S36_ZoneInteractions(0x36),
    S3D_SoundEffectPointer(0x3D),
    S3E_PointList(0x3E),
    S45_Info(0x45),
    S46_Unknown(0x46),
    S48_Unknown(0x48),
    S49_SpellList(0x49),
    S4A_Path(0x4A),
    S53_AbilityList(0x53),
    S54_WeaponTrace(0x54),
    S5D_BumpMap(0x5D),
    S5E_Blur(0x5E),
    S5F_Unknown(0x5F),
    S78_Unknown(0x78);

    private final int code;

    SectionType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static SectionType fromCode(int code) {
        for (SectionType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new java.util.NoSuchElementException("Unknown section type: 0x" + Integer.toHexString(code));
    }
}
