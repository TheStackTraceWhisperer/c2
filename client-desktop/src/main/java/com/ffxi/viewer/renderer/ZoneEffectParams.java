package com.ffxi.viewer.renderer;

import com.ffxi.model.ParticleKeyFrameResource;
import org.joml.Vector4f;

/**
 * Render state and material parameters for ambient {@code S05_ParticleGenerator} zone mesh effects
 * (water surfaces, fountains, cascading spouts, nighttime streetlamp cones, and celestial sky layers).
 */
public record ZoneEffectParams(
        String generatorId,
        BlendMode blendMode,
        BillboardMode billboardMode,
        boolean depthWrite,
        boolean specularEnabled,
        boolean fogEnabled,
        boolean ignoreTextureAlpha,
        float colorR,
        float colorG,
        float colorB,
        float colorA,
        float specularR,
        float specularG,
        float specularB,
        float specularA,
        float uvScrollU,
        float uvScrollV,
        ParticleKeyFrameResource todRCurve,
        ParticleKeyFrameResource todGCurve,
        ParticleKeyFrameResource todBCurve,
        ParticleKeyFrameResource todACurve,
        int attachType,
        boolean followCamera,
        boolean skyLayer
) {

    public enum BlendMode {
        OPAQUE(0),
        ALPHA(1),
        ZERO_INV_SRC(2),
        ADDITIVE(3),
        SUBTRACTIVE(4);

        private final int subPassOrder;

        BlendMode(int subPassOrder) {
            this.subPassOrder = subPassOrder;
        }

        public int subPassOrder() {
            return subPassOrder;
        }
    }

    public enum BillboardMode {
        NONE,
        XZ,
        XYZ,
        CAMERA
    }

    public ZoneEffectParams {
        generatorId = generatorId != null ? generatorId : "";
        blendMode = blendMode != null ? blendMode : BlendMode.ALPHA;
        billboardMode = billboardMode != null ? billboardMode : BillboardMode.NONE;
    }

    /** Backwards-compatible 17-argument constructor without live ToD curves or sky attachment metadata. */
    public ZoneEffectParams(
            String generatorId,
            BlendMode blendMode,
            BillboardMode billboardMode,
            boolean depthWrite,
            boolean specularEnabled,
            boolean fogEnabled,
            boolean ignoreTextureAlpha,
            float colorR,
            float colorG,
            float colorB,
            float colorA,
            float specularR,
            float specularG,
            float specularB,
            float specularA,
            float uvScrollU,
            float uvScrollV
    ) {
        this(
                generatorId, blendMode, billboardMode, depthWrite, specularEnabled, fogEnabled,
                ignoreTextureAlpha, colorR, colorG, colorB, colorA,
                specularR, specularG, specularB, specularA, uvScrollU, uvScrollV,
                null, null, null, null, 0, false, false
        );
    }

    /** Evaluates retail ToD RGBA color modulation at {@code normalizedClock} in {@code [0, 1)}. */
    public void evaluateLiveColor(float normalizedClock, Vector4f out) {
        float r = (todRCurve != null) ? todRCurve.sampleNormalized(normalizedClock) : colorR;
        float g = (todGCurve != null) ? todGCurve.sampleNormalized(normalizedClock) : colorG;
        float b = (todBCurve != null) ? todBCurve.sampleNormalized(normalizedClock) : colorB;
        float a = (todACurve != null) ? colorA * todACurve.sampleNormalized(normalizedClock) : colorA;
        out.set(r, g, b, a);
    }

    /** Maps to {@code ZoneShader}'s {@code uEffectMode}: 1 = alpha/opaque, 2 = additive, 3 = subtractive. */
    public int shaderEffectMode() {
        return switch (blendMode) {
            case ADDITIVE -> 2;
            case SUBTRACTIVE -> 3;
            case OPAQUE, ALPHA, ZERO_INV_SRC -> 1;
        };
    }

    public static BlendMode blendModeFromByte(int blendFuncByte) {
        if (blendFuncByte < 0) {
            return BlendMode.ADDITIVE;
        }
        int highNibble = (blendFuncByte >>> 4) & 0x0D;
        if ((highNibble & 0x01) != 0) {
            return BlendMode.OPAQUE;
        }
        return switch (blendFuncByte & 0x0F) {
            case 0x1, 0x2 -> BlendMode.SUBTRACTIVE;
            case 0x4 -> BlendMode.ALPHA;
            case 0x6 -> BlendMode.ZERO_INV_SRC;
            case 0x8 -> BlendMode.ADDITIVE;
            default -> BlendMode.ADDITIVE;
        };
    }

    public static BlendMode blendModeFromByte(int blendFuncByte, boolean fallbackRenderBlend) {
        return blendModeFromByte(blendFuncByte);
    }
}
