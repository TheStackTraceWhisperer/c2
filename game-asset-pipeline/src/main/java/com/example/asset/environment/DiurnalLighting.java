package com.example.asset.environment;

import com.example.asset.geometry.Vector3f;
import com.example.common.time.VanadielTime;
import com.example.common.time.WeatherType;
import lombok.Getter;
import lombok.ToString;

/**
 * Computes diurnal celestial lighting, directional sun/moon vectors,
 * ambient illumination, skybox clear colors, and distance fog based on Vana'diel time and weather.
 */
@Getter
@ToString
public final class DiurnalLighting {

    private final Vector3f sunDirection;
    private final float[] sunColor;       // RGB [0..1]
    private final float[] ambientColor;   // RGB [0..1]
    private final float[] skyColor;       // RGBA [0..1]
    private final float fogStart;
    private final float fogEnd;
    private final float[] fogColor;       // RGB [0..1]
    private final boolean isDay;

    public DiurnalLighting(Vector3f sunDirection, float[] sunColor, float[] ambientColor,
                           float[] skyColor, float fogStart, float fogEnd, float[] fogColor, boolean isDay) {
        this.sunDirection = sunDirection;
        this.sunColor = sunColor;
        this.ambientColor = ambientColor;
        this.skyColor = skyColor;
        this.fogStart = fogStart;
        this.fogEnd = fogEnd;
        this.fogColor = fogColor;
        this.isDay = isDay;
    }

    public static DiurnalLighting compute(VanadielTime time, WeatherType weather) {
        float hour = time.getHourFraction();
        boolean day = hour >= 6.0f && hour < 18.0f;

        // 1. Celestial Orbit Direction Vector
        // Solar arc: rises at 4:00 (azimuth 0, elevation 0), peaks at 12:00 (zenith), sets at 19:30
        float celestialHour = (hour >= 4.0f && hour <= 19.5f) ? hour : ((hour + 24.0f) % 24.0f);
        float sunAngleRad;
        Vector3f lightDir;

        if (hour >= 4.0f && hour <= 19.5f) {
            // Daytime solar track from East to West
            float progress = (hour - 4.0f) / 15.5f; // 0.0 at dawn to 1.0 at dusk
            sunAngleRad = (float) (progress * Math.PI);
            float dirX = (float) -Math.cos(sunAngleRad);
            float dirY = (float) Math.sin(sunAngleRad);
            float dirZ = 0.25f;
            lightDir = new Vector3f(dirX, Math.max(0.05f, dirY), dirZ).normalize();
        } else {
            // Nighttime lunar track
            float nightProgress = (hour > 19.5f) ? (hour - 19.5f) / 8.5f : (hour + 4.5f) / 8.5f;
            sunAngleRad = (float) (nightProgress * Math.PI);
            float dirX = (float) -Math.cos(sunAngleRad);
            float dirY = (float) Math.sin(sunAngleRad);
            float dirZ = -0.2f;
            lightDir = new Vector3f(dirX, Math.max(0.1f, dirY), dirZ).normalize();
        }

        // 2. Base Diurnal Lighting Colors (Dawn, Day, Dusk, Night)
        float[] baseSun;
        float[] baseAmb;
        float[] baseSky;

        if (hour >= 4.0f && hour < 7.0f) {
            // Dawn: warm amber / rose sunrise
            float t = (hour - 4.0f) / 3.0f;
            baseSun = lerp3(new float[]{0.8f, 0.45f, 0.25f}, new float[]{1.0f, 0.95f, 0.85f}, t);
            baseAmb = lerp3(new float[]{0.15f, 0.14f, 0.22f}, new float[]{0.42f, 0.42f, 0.48f}, t);
            baseSky = lerp3(new float[]{0.18f, 0.12f, 0.25f}, new float[]{0.35f, 0.50f, 0.72f}, t);
        } else if (hour >= 7.0f && hour < 17.0f) {
            // Midday: bright neutral sunlight, clear blue sky
            baseSun = new float[]{1.0f, 0.98f, 0.92f};
            baseAmb = new float[]{0.48f, 0.50f, 0.54f};
            baseSky = new float[]{0.22f, 0.45f, 0.78f};
        } else if (hour >= 17.0f && hour < 19.5f) {
            // Dusk / Sunset: fiery crimson-gold glow
            float t = (hour - 17.0f) / 2.5f;
            baseSun = lerp3(new float[]{1.0f, 0.95f, 0.85f}, new float[]{0.85f, 0.35f, 0.15f}, t);
            baseAmb = lerp3(new float[]{0.48f, 0.50f, 0.54f}, new float[]{0.22f, 0.18f, 0.28f}, t);
            baseSky = lerp3(new float[]{0.22f, 0.45f, 0.78f}, new float[]{0.28f, 0.14f, 0.22f}, t);
        } else {
            // Night: deep midnight blue, cool moonlight
            baseSun = new float[]{0.20f, 0.28f, 0.45f}; // Moonlight
            baseAmb = new float[]{0.10f, 0.12f, 0.18f};
            baseSky = new float[]{0.05f, 0.07f, 0.12f};
        }

        // 3. Apply Weather Scaling & Density
        WeatherType w = (weather != null) ? weather : WeatherType.CLEAR;
        float[] sunColor = new float[]{
                baseSun[0] * w.getAmbientRedScale(),
                baseSun[1] * w.getAmbientGreenScale(),
                baseSun[2] * w.getAmbientBlueScale()
        };
        float[] ambientColor = new float[]{
                baseAmb[0] * w.getAmbientRedScale(),
                baseAmb[1] * w.getAmbientGreenScale(),
                baseAmb[2] * w.getAmbientBlueScale()
        };
        float[] skyColor = new float[]{
                baseSky[0] * w.getAmbientRedScale(),
                baseSky[1] * w.getAmbientGreenScale(),
                baseSky[2] * w.getAmbientBlueScale(),
                1.0f
        };

        // 4. Distance Fog Parameters
        float baseFogStart = 200.0f;
        float baseFogEnd = 950.0f;

        // If fog or rain, contract fog distance significantly
        float fogMult = w.getFogDensityMultiplier();
        float fogStart = Math.max(10.0f, baseFogStart * (1.0f - fogMult * 0.85f));
        float fogEnd = Math.max(80.0f, baseFogEnd * (1.0f - fogMult * 0.75f));

        float[] fogColor = lerp3(skyColor, ambientColor, 0.5f);

        return new DiurnalLighting(lightDir, sunColor, ambientColor, skyColor, fogStart, fogEnd, fogColor, day);
    }

    private static float[] lerp3(float[] a, float[] b, float t) {
        float clampedT = Math.max(0.0f, Math.min(1.0f, t));
        return new float[]{
                a[0] + (b[0] - a[0]) * clampedT,
                a[1] + (b[1] - a[1]) * clampedT,
                a[2] + (b[2] - a[2]) * clampedT
        };
    }
}
