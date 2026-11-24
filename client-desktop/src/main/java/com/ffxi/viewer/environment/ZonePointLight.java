package com.ffxi.viewer.environment;

import com.ffxi.model.ParticleKeyFrameResource;
import org.joml.Vector3f;

/**
 * Static zone point-light emitter linked from {@link com.ffxi.model.ZoneDefResource#pointLightLinks()}
 * and defined by an {@code S05_ParticleGenerator} section with {@code ltype == 0x47}.
 * Color components are stored normalized by {@code 128.0f} matching retail D3D8 point-light modulation.
 */
public record ZonePointLight(
        String id,
        Vector3f position,
        Vector3f color,
        float range,
        float baseTheta,
        float thetaMultiplier,
        ParticleKeyFrameResource todThetaCurve
) {
    public ZonePointLight {
        id = id != null ? id : "";
        position = position != null ? new Vector3f(position) : new Vector3f();
        color = color != null ? new Vector3f(color) : new Vector3f();
    }

    /** Evaluates the point light's effective intensity at {@code normalizedClock} in {@code [0, 1)}. */
    public float evaluateIntensity(float normalizedClock) {
        float theta = (todThetaCurve != null) ? todThetaCurve.sampleNormalized(normalizedClock) : baseTheta;
        return Math.max(0.0f, theta * thetaMultiplier);
    }
}
