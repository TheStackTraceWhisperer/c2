package com.ffxi.model;

/**
 * Parsed {@code S05_ParticleGenerator} section extracting the fields needed for ambient
 * zone mesh effects, nighttime streetlamp cones, point lights, and celestial sky layers.
 */
public record ZoneEffectGeneratorResource(
        String sectionId,
        String sectionType,
        int sectionStart,
        int sectionSize,
        boolean autoRun,
        int attachType,
        int billboardFlags,
        int renderStateFlags,
        String linkedDataId,
        int linkedDataType,
        Vec3f basePosition,
        Vec3f baseRotation,
        Vec3f baseScale,
        int maxLifeSpan,
        boolean hasInitialVelocity,
        boolean hasColor,
        int colorR,
        int colorG,
        int colorB,
        int colorA,
        int blendFuncByte,
        int alphaOverride,
        boolean hasSpecularParams,
        int specularR,
        int specularG,
        int specularB,
        int specularA,
        String lifeAlphaKeyFrameId,
        String todRKeyFrameId,
        String todGKeyFrameId,
        String todBKeyFrameId,
        String todAKeyFrameId,
        boolean hasPointLightParams,
        float pointLightRange,
        float pointLightTheta,
        float pointLightRangeMultiplier,
        float pointLightThetaMultiplier,
        String todPointLightThetaKeyFrameId,
        float uvScrollU,
        float uvScrollV
) implements ResourceNode {

    public static final int LINKED_DATA_TYPE_MESH = 0x0B;
    public static final int LINKED_DATA_TYPE_POINT_LIGHT = 0x47;
    public static final int ATTACH_TYPE_NONE = 0;
    public static final int ATTACH_TYPE_SUN = 14;
    public static final int ATTACH_TYPE_MOON = 15;

    public ZoneEffectGeneratorResource {
        linkedDataId = linkedDataId != null ? linkedDataId : "";
        basePosition = basePosition != null ? basePosition : new Vec3f(0.0f, 0.0f, 0.0f);
        baseRotation = baseRotation != null ? baseRotation : new Vec3f(0.0f, 0.0f, 0.0f);
        baseScale = baseScale != null ? baseScale : new Vec3f(1.0f, 1.0f, 1.0f);
        lifeAlphaKeyFrameId = lifeAlphaKeyFrameId != null ? lifeAlphaKeyFrameId : "";
        todRKeyFrameId = todRKeyFrameId != null ? todRKeyFrameId : "";
        todGKeyFrameId = todGKeyFrameId != null ? todGKeyFrameId : "";
        todBKeyFrameId = todBKeyFrameId != null ? todBKeyFrameId : "";
        todAKeyFrameId = todAKeyFrameId != null ? todAKeyFrameId : "";
        todPointLightThetaKeyFrameId = todPointLightThetaKeyFrameId != null ? todPointLightThetaKeyFrameId : "";
    }

    /** Backwards-compatible constructor preserving the original pre-diurnal parameter signature. */
    public ZoneEffectGeneratorResource(
            String sectionId,
            String sectionType,
            int sectionStart,
            int sectionSize,
            boolean autoRun,
            int billboardFlags,
            int renderStateFlags,
            String linkedDataId,
            int linkedDataType,
            Vec3f basePosition,
            Vec3f baseRotation,
            Vec3f baseScale,
            int maxLifeSpan,
            boolean hasColor,
            int colorR,
            int colorG,
            int colorB,
            int colorA,
            int blendFuncByte,
            int alphaOverride,
            boolean hasSpecularParams,
            int specularR,
            int specularG,
            int specularB,
            int specularA,
            String todRKeyFrameId,
            String todGKeyFrameId,
            String todBKeyFrameId,
            String todAKeyFrameId,
            float uvScrollU,
            float uvScrollV
    ) {
        this(
                sectionId, sectionType, sectionStart, sectionSize,
                autoRun, ATTACH_TYPE_NONE, billboardFlags, renderStateFlags,
                linkedDataId, linkedDataType, basePosition, baseRotation, baseScale,
                maxLifeSpan, false, hasColor, colorR, colorG, colorB, colorA,
                blendFuncByte, alphaOverride, hasSpecularParams, specularR, specularG, specularB, specularA,
                "", todRKeyFrameId, todGKeyFrameId, todBKeyFrameId, todAKeyFrameId,
                false, 0.0f, 0.0f, 1.0f, 1.0f, "",
                uvScrollU, uvScrollV
        );
    }

    /** True for persistent zone effects instantiated once at load (water planes, fountain sprays, sky layers). */
    public boolean isAmbientSingletonMesh() {
        return autoRun && linkedDataType == LINKED_DATA_TYPE_MESH && maxLifeSpan == 0 && !linkedDataId.isEmpty();
    }

    /** True for point-light emitter generators referenced by {@link ZoneDefResource#pointLightLinks()}. */
    public boolean isPointLight() {
        return linkedDataType == LINKED_DATA_TYPE_POINT_LIGHT;
    }

    /** True when the particle mesh tracks the camera origin (clouds, star domes). */
    public boolean followCamera() {
        return (billboardFlags & 0x0004) != 0;
    }

    /**
     * True for stationary cyclic glow/lamp cones under {@code effe/ligh/} that loop in place
     * with finite {@code maxLifeSpan} and zero initial velocity.
     */
    public boolean isStationaryLampConeMesh() {
        return autoRun
                && linkedDataType == LINKED_DATA_TYPE_MESH
                && maxLifeSpan > 0
                && !hasInitialVelocity
                && !linkedDataId.isEmpty()
                && (!lifeAlphaKeyFrameId.isEmpty() || !todAKeyFrameId.isEmpty());
    }

    public boolean depthWrite() {
        return (billboardFlags & 0x1000) != 0;
    }

    public boolean billboardXZ() {
        return (billboardFlags & 0x4000) != 0;
    }

    /** True when retail orientation mode 3 ({@code (billboardFlags & 0x01C0) == 0x00C0}) aligns local +X toward the camera. */
    public boolean billboardCamera() {
        return (billboardFlags & 0x01C0) == 0x00C0;
    }

    public boolean billboardXYZ() {
        return (billboardFlags & 0x0001) != 0 && (billboardFlags & 0x01C0) == 0 && !billboardXZ();
    }

    public boolean specularEnabled() {
        return (renderStateFlags & 0x0100) != 0;
    }

    public boolean fogEnabled() {
        return (renderStateFlags & 0x0200) == 0;
    }

    public boolean ignoreTextureAlpha() {
        return (renderStateFlags & 0x1000) != 0;
    }
}
