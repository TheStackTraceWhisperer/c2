package com.ffxi.viewer.environment;

import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Arrays;
import java.util.List;

/**
 * Authentic retail FFXI weather presets and elemental atmospheric parameters.
 * Encapsulates the 20 canonical weather types recognized by the FFXI engine,
 * along with their 8-slice hemispherical sky dome palettes, celestial sun/moon
 * colors, ambient illumination, distance fog, wind vectors, and elemental particle effects.
 */
public record WeatherPreset(
        String id,
        String displayName,
        ElementalAffinity element,
        WeatherEffectType effectType,
        List<SkySlice> skySlices,
        Vector3f sunColor,
        Vector3f moonColor,
        Vector3f ambientColor,
        Vector4f clearColor,
        float fogNear,
        float fogFar,
        Vector3f fogColor,
        Vector3f windVector,
        float diffuseMultiplier
) {
    public enum ElementalAffinity {
        NONE, FIRE, WATER, EARTH, WIND, ICE, LIGHTNING, LIGHT, DARK
    }

    public enum WeatherEffectType {
        NONE,
        RAIN,
        HEAVY_RAIN,
        SNOW,
        BLIZZARD,
        SANDSTORM,
        DUST_STORM,
        THUNDERSTORM,
        AURORA,
        HEAT_WAVE,
        DARK_GLOOM
    }

    public record SkySlice(float elevation, Vector4f color) {
        public static SkySlice lerp(SkySlice a, SkySlice b, float t) {
            float elev = a.elevation + (b.elevation - a.elevation) * t;
            Vector4f col = new Vector4f(a.color).lerp(b.color, t);
            return new SkySlice(elev, col);
        }
    }

    public static final WeatherPreset FINE = new WeatherPreset(
            "fine", "Clear / Fine", ElementalAffinity.NONE, WeatherEffectType.NONE,
            createStandardSky(new Vector4f(0.55f, 0.75f, 0.95f, 1.0f), new Vector4f(0.20f, 0.45f, 0.85f, 1.0f), new Vector4f(0.08f, 0.25f, 0.65f, 1.0f)),
            new Vector3f(1.0f, 0.96f, 0.90f), new Vector3f(0.25f, 0.35f, 0.55f), new Vector3f(0.48f, 0.50f, 0.55f),
            new Vector4f(0.55f, 0.75f, 0.95f, 1.0f), 80.0f, 650.0f, new Vector3f(0.60f, 0.72f, 0.88f),
            new Vector3f(0.5f, 0.0f, 0.5f), 1.0f
    );

    public static final WeatherPreset SUNNY = new WeatherPreset(
            "suny", "Sunshine", ElementalAffinity.LIGHT, WeatherEffectType.HEAT_WAVE,
            createStandardSky(new Vector4f(0.65f, 0.82f, 1.00f, 1.0f), new Vector4f(0.30f, 0.55f, 0.92f, 1.0f), new Vector4f(0.12f, 0.35f, 0.80f, 1.0f)),
            new Vector3f(1.10f, 1.05f, 0.95f), new Vector3f(0.20f, 0.30f, 0.50f), new Vector3f(0.52f, 0.53f, 0.58f),
            new Vector4f(0.65f, 0.82f, 1.00f, 1.0f), 120.0f, 900.0f, new Vector3f(0.68f, 0.78f, 0.92f),
            new Vector3f(0.2f, 0.0f, 0.2f), 1.15f
    );

    public static final WeatherPreset CLOUDY = new WeatherPreset(
            "clod", "Cloudy", ElementalAffinity.NONE, WeatherEffectType.NONE,
            createStandardSky(new Vector4f(0.60f, 0.62f, 0.65f, 1.0f), new Vector4f(0.45f, 0.48f, 0.52f, 1.0f), new Vector4f(0.32f, 0.35f, 0.40f, 1.0f)),
            new Vector3f(0.70f, 0.70f, 0.72f), new Vector3f(0.18f, 0.20f, 0.28f), new Vector3f(0.40f, 0.42f, 0.45f),
            new Vector4f(0.60f, 0.62f, 0.65f, 1.0f), 40.0f, 450.0f, new Vector3f(0.55f, 0.58f, 0.62f),
            new Vector3f(1.0f, 0.0f, 0.8f), 0.80f
    );

    public static final WeatherPreset FOG = new WeatherPreset(
            "mist", "Fog / Mist", ElementalAffinity.WATER, WeatherEffectType.NONE,
            createStandardSky(new Vector4f(0.70f, 0.72f, 0.75f, 1.0f), new Vector4f(0.62f, 0.65f, 0.68f, 1.0f), new Vector4f(0.55f, 0.58f, 0.62f, 1.0f)),
            new Vector3f(0.55f, 0.58f, 0.60f), new Vector3f(0.15f, 0.18f, 0.22f), new Vector3f(0.45f, 0.48f, 0.52f),
            new Vector4f(0.70f, 0.72f, 0.75f, 1.0f), 10.0f, 180.0f, new Vector3f(0.68f, 0.70f, 0.72f),
            new Vector3f(0.3f, 0.0f, 0.3f), 0.65f
    );

    public static final WeatherPreset HEAT_WAVE = new WeatherPreset(
            "heat", "Heat Wave (Fire)", ElementalAffinity.FIRE, WeatherEffectType.HEAT_WAVE,
            createStandardSky(new Vector4f(0.92f, 0.65f, 0.45f, 1.0f), new Vector4f(0.75f, 0.42f, 0.28f, 1.0f), new Vector4f(0.55f, 0.25f, 0.18f, 1.0f)),
            new Vector3f(1.20f, 0.88f, 0.60f), new Vector3f(0.30f, 0.20f, 0.20f), new Vector3f(0.58f, 0.42f, 0.32f),
            new Vector4f(0.92f, 0.65f, 0.45f, 1.0f), 30.0f, 350.0f, new Vector3f(0.85f, 0.55f, 0.40f),
            new Vector3f(0.8f, 0.0f, 0.2f), 1.25f
    );

    public static final WeatherPreset DRY_WIND = new WeatherPreset(
            "dryw", "Hot Spell / Drought (Fire II)", ElementalAffinity.FIRE, WeatherEffectType.HEAT_WAVE,
            createStandardSky(new Vector4f(0.98f, 0.55f, 0.30f, 1.0f), new Vector4f(0.82f, 0.32f, 0.18f, 1.0f), new Vector4f(0.60f, 0.18f, 0.12f, 1.0f)),
            new Vector3f(1.30f, 0.80f, 0.50f), new Vector3f(0.35f, 0.15f, 0.15f), new Vector3f(0.62f, 0.38f, 0.25f),
            new Vector4f(0.98f, 0.55f, 0.30f, 1.0f), 20.0f, 280.0f, new Vector3f(0.90f, 0.48f, 0.30f),
            new Vector3f(1.5f, 0.0f, 0.5f), 1.35f
    );

    public static final WeatherPreset RAIN = new WeatherPreset(
            "rain", "Rain (Water)", ElementalAffinity.WATER, WeatherEffectType.RAIN,
            createStandardSky(new Vector4f(0.48f, 0.54f, 0.62f, 1.0f), new Vector4f(0.35f, 0.42f, 0.50f, 1.0f), new Vector4f(0.24f, 0.30f, 0.38f, 1.0f)),
            new Vector3f(0.55f, 0.62f, 0.70f), new Vector3f(0.12f, 0.18f, 0.25f), new Vector3f(0.36f, 0.42f, 0.50f),
            new Vector4f(0.48f, 0.54f, 0.62f, 1.0f), 25.0f, 320.0f, new Vector3f(0.42f, 0.48f, 0.56f),
            new Vector3f(2.0f, -8.0f, 1.5f), 0.75f
    );

    public static final WeatherPreset SQUALL = new WeatherPreset(
            "squl", "Squall (Water II)", ElementalAffinity.WATER, WeatherEffectType.HEAVY_RAIN,
            createStandardSky(new Vector4f(0.32f, 0.38f, 0.45f, 1.0f), new Vector4f(0.22f, 0.28f, 0.35f, 1.0f), new Vector4f(0.15f, 0.20f, 0.28f, 1.0f)),
            new Vector3f(0.40f, 0.48f, 0.58f), new Vector3f(0.08f, 0.12f, 0.18f), new Vector3f(0.28f, 0.34f, 0.42f),
            new Vector4f(0.32f, 0.38f, 0.45f, 1.0f), 15.0f, 200.0f, new Vector3f(0.30f, 0.35f, 0.42f),
            new Vector3f(4.5f, -14.0f, 3.0f), 0.60f
    );

    public static final WeatherPreset DUST = new WeatherPreset(
            "dust", "Dust Storm (Earth)", ElementalAffinity.EARTH, WeatherEffectType.DUST_STORM,
            createStandardSky(new Vector4f(0.72f, 0.64f, 0.48f, 1.0f), new Vector4f(0.58f, 0.50f, 0.35f, 1.0f), new Vector4f(0.42f, 0.36f, 0.24f, 1.0f)),
            new Vector3f(0.85f, 0.75f, 0.55f), new Vector3f(0.22f, 0.20f, 0.15f), new Vector3f(0.48f, 0.44f, 0.34f),
            new Vector4f(0.72f, 0.64f, 0.48f, 1.0f), 20.0f, 260.0f, new Vector3f(0.68f, 0.58f, 0.42f),
            new Vector3f(3.0f, -0.5f, 2.0f), 0.85f
    );

    public static final WeatherPreset SANDSTORM = new WeatherPreset(
            "sand", "Sandstorm (Earth II)", ElementalAffinity.EARTH, WeatherEffectType.SANDSTORM,
            createStandardSky(new Vector4f(0.82f, 0.68f, 0.42f, 1.0f), new Vector4f(0.68f, 0.52f, 0.28f, 1.0f), new Vector4f(0.50f, 0.38f, 0.18f, 1.0f)),
            new Vector3f(0.95f, 0.78f, 0.48f), new Vector3f(0.20f, 0.16f, 0.10f), new Vector3f(0.55f, 0.46f, 0.30f),
            new Vector4f(0.82f, 0.68f, 0.42f, 1.0f), 10.0f, 160.0f, new Vector3f(0.76f, 0.62f, 0.38f),
            new Vector3f(6.0f, -1.0f, 4.0f), 0.90f
    );

    public static final WeatherPreset WINDY = new WeatherPreset(
            "wind", "Windy (Wind)", ElementalAffinity.WIND, WeatherEffectType.NONE,
            createStandardSky(new Vector4f(0.58f, 0.78f, 0.85f, 1.0f), new Vector4f(0.32f, 0.58f, 0.75f, 1.0f), new Vector4f(0.18f, 0.40f, 0.62f, 1.0f)),
            new Vector3f(0.95f, 1.00f, 0.92f), new Vector3f(0.22f, 0.32f, 0.42f), new Vector3f(0.45f, 0.52f, 0.50f),
            new Vector4f(0.58f, 0.78f, 0.85f, 1.0f), 60.0f, 500.0f, new Vector3f(0.55f, 0.72f, 0.78f),
            new Vector3f(5.0f, 0.0f, 3.0f), 1.05f
    );

    public static final WeatherPreset GALES = new WeatherPreset(
            "stom", "Gales / Storm (Wind II)", ElementalAffinity.WIND, WeatherEffectType.NONE,
            createStandardSky(new Vector4f(0.45f, 0.65f, 0.72f, 1.0f), new Vector4f(0.28f, 0.48f, 0.58f, 1.0f), new Vector4f(0.15f, 0.32f, 0.45f, 1.0f)),
            new Vector3f(0.82f, 0.92f, 0.85f), new Vector3f(0.18f, 0.25f, 0.35f), new Vector3f(0.38f, 0.45f, 0.42f),
            new Vector4f(0.45f, 0.65f, 0.72f, 1.0f), 30.0f, 320.0f, new Vector3f(0.42f, 0.58f, 0.65f),
            new Vector3f(9.0f, 0.0f, 6.0f), 0.95f
    );

    public static final WeatherPreset SNOW = new WeatherPreset(
            "snow", "Snow (Ice)", ElementalAffinity.ICE, WeatherEffectType.SNOW,
            createStandardSky(new Vector4f(0.72f, 0.78f, 0.88f, 1.0f), new Vector4f(0.55f, 0.62f, 0.75f, 1.0f), new Vector4f(0.38f, 0.45f, 0.62f, 1.0f)),
            new Vector3f(0.82f, 0.90f, 1.05f), new Vector3f(0.28f, 0.38f, 0.58f), new Vector3f(0.50f, 0.55f, 0.65f),
            new Vector4f(0.72f, 0.78f, 0.88f, 1.0f), 35.0f, 380.0f, new Vector3f(0.68f, 0.74f, 0.84f),
            new Vector3f(1.0f, -2.5f, 0.8f), 0.90f
    );

    public static final WeatherPreset BLIZZARD = new WeatherPreset(
            "bliz", "Blizzard (Ice II)", ElementalAffinity.ICE, WeatherEffectType.BLIZZARD,
            createStandardSky(new Vector4f(0.80f, 0.85f, 0.92f, 1.0f), new Vector4f(0.68f, 0.74f, 0.82f, 1.0f), new Vector4f(0.52f, 0.60f, 0.72f, 1.0f)),
            new Vector3f(0.65f, 0.75f, 0.92f), new Vector3f(0.20f, 0.28f, 0.45f), new Vector3f(0.42f, 0.48f, 0.58f),
            new Vector4f(0.80f, 0.85f, 0.92f, 1.0f), 8.0f, 120.0f, new Vector3f(0.78f, 0.82f, 0.90f),
            new Vector3f(5.5f, -6.0f, 4.0f), 0.70f
    );

    public static final WeatherPreset THUNDER = new WeatherPreset(
            "thdr", "Thunder (Lightning)", ElementalAffinity.LIGHTNING, WeatherEffectType.THUNDERSTORM,
            createStandardSky(new Vector4f(0.38f, 0.35f, 0.52f, 1.0f), new Vector4f(0.25f, 0.22f, 0.38f, 1.0f), new Vector4f(0.15f, 0.12f, 0.25f, 1.0f)),
            new Vector3f(0.60f, 0.55f, 0.80f), new Vector3f(0.18f, 0.15f, 0.30f), new Vector3f(0.32f, 0.30f, 0.45f),
            new Vector4f(0.38f, 0.35f, 0.52f, 1.0f), 20.0f, 280.0f, new Vector3f(0.35f, 0.32f, 0.48f),
            new Vector3f(3.0f, -9.0f, 2.0f), 0.80f
    );

    public static final WeatherPreset THUNDERSTORM = new WeatherPreset(
            "bolt", "Thunderstorm / Bolts (Lightning II)", ElementalAffinity.LIGHTNING, WeatherEffectType.THUNDERSTORM,
            createStandardSky(new Vector4f(0.28f, 0.22f, 0.42f, 1.0f), new Vector4f(0.18f, 0.14f, 0.30f, 1.0f), new Vector4f(0.10f, 0.08f, 0.20f, 1.0f)),
            new Vector3f(0.75f, 0.70f, 1.00f), new Vector3f(0.15f, 0.12f, 0.25f), new Vector3f(0.28f, 0.24f, 0.40f),
            new Vector4f(0.28f, 0.22f, 0.42f, 1.0f), 12.0f, 190.0f, new Vector3f(0.25f, 0.20f, 0.38f),
            new Vector3f(6.0f, -14.0f, 4.0f), 0.70f
    );

    public static final WeatherPreset AURORA = new WeatherPreset(
            "aura", "Auroras (Light)", ElementalAffinity.LIGHT, WeatherEffectType.AURORA,
            createStandardSky(new Vector4f(0.25f, 0.45f, 0.65f, 1.0f), new Vector4f(0.15f, 0.35f, 0.55f, 1.0f), new Vector4f(0.08f, 0.22f, 0.42f, 1.0f)),
            new Vector3f(0.95f, 1.10f, 1.05f), new Vector3f(0.45f, 0.65f, 0.75f), new Vector3f(0.42f, 0.55f, 0.62f),
            new Vector4f(0.25f, 0.45f, 0.65f, 1.0f), 60.0f, 600.0f, new Vector3f(0.30f, 0.52f, 0.68f),
            new Vector3f(0.5f, 0.0f, 0.5f), 1.10f
    );

    public static final WeatherPreset STELLAR_GLARE = new WeatherPreset(
            "ligt", "Starlight / Stellar Glare (Light II)", ElementalAffinity.LIGHT, WeatherEffectType.AURORA,
            createStandardSky(new Vector4f(0.40f, 0.60f, 0.85f, 1.0f), new Vector4f(0.25f, 0.48f, 0.75f, 1.0f), new Vector4f(0.15f, 0.35f, 0.62f, 1.0f)),
            new Vector3f(1.15f, 1.25f, 1.30f), new Vector3f(0.60f, 0.75f, 0.90f), new Vector3f(0.55f, 0.65f, 0.78f),
            new Vector4f(0.40f, 0.60f, 0.85f, 1.0f), 80.0f, 750.0f, new Vector3f(0.45f, 0.65f, 0.82f),
            new Vector3f(0.8f, 0.0f, 0.8f), 1.25f
    );

    public static final WeatherPreset GLOOM = new WeatherPreset(
            "fogd", "Dark Fog / Gloom (Dark)", ElementalAffinity.DARK, WeatherEffectType.DARK_GLOOM,
            createStandardSky(new Vector4f(0.25f, 0.22f, 0.28f, 1.0f), new Vector4f(0.18f, 0.15f, 0.20f, 1.0f), new Vector4f(0.10f, 0.08f, 0.12f, 1.0f)),
            new Vector3f(0.45f, 0.40f, 0.48f), new Vector3f(0.10f, 0.08f, 0.12f), new Vector3f(0.24f, 0.20f, 0.26f),
            new Vector4f(0.25f, 0.22f, 0.28f, 1.0f), 15.0f, 200.0f, new Vector3f(0.22f, 0.18f, 0.25f),
            new Vector3f(0.5f, 0.0f, 0.5f), 0.60f
    );

    public static final WeatherPreset DARKNESS = new WeatherPreset(
            "dark", "Darkness / Void (Dark II)", ElementalAffinity.DARK, WeatherEffectType.DARK_GLOOM,
            createStandardSky(new Vector4f(0.15f, 0.12f, 0.18f, 1.0f), new Vector4f(0.08f, 0.06f, 0.10f, 1.0f), new Vector4f(0.04f, 0.02f, 0.05f, 1.0f)),
            new Vector3f(0.30f, 0.25f, 0.35f), new Vector3f(0.05f, 0.04f, 0.08f), new Vector3f(0.16f, 0.12f, 0.18f),
            new Vector4f(0.15f, 0.12f, 0.18f, 1.0f), 8.0f, 110.0f, new Vector3f(0.12f, 0.10f, 0.15f),
            new Vector3f(1.0f, 0.0f, 1.0f), 0.45f
    );

    public static final List<WeatherPreset> ALL = List.of(
            FINE, SUNNY, CLOUDY, FOG,
            HEAT_WAVE, DRY_WIND,
            RAIN, SQUALL,
            DUST, SANDSTORM,
            WINDY, GALES,
            SNOW, BLIZZARD,
            THUNDER, THUNDERSTORM,
            AURORA, STELLAR_GLARE,
            GLOOM, DARKNESS
    );

    public static WeatherPreset getById(String id) {
        if (id == null) return FINE;
        for (WeatherPreset p : ALL) {
            if (p.id.equalsIgnoreCase(id.trim())) {
                return p;
            }
        }
        return FINE;
    }

    /**
     * Constructs the canonical 8-slice FFXI sky hemisphere gradient palette.
     */
    private static List<SkySlice> createStandardSky(Vector4f horizon, Vector4f mid, Vector4f zenith) {
        return Arrays.asList(
                new SkySlice(0.00f, new Vector4f(horizon)),
                new SkySlice(0.12f, new Vector4f(horizon).lerp(mid, 0.25f)),
                new SkySlice(0.25f, new Vector4f(horizon).lerp(mid, 0.50f)),
                new SkySlice(0.38f, new Vector4f(horizon).lerp(mid, 0.75f)),
                new SkySlice(0.50f, new Vector4f(mid)),
                new SkySlice(0.68f, new Vector4f(mid).lerp(zenith, 0.40f)),
                new SkySlice(0.85f, new Vector4f(mid).lerp(zenith, 0.75f)),
                new SkySlice(1.00f, new Vector4f(zenith))
        );
    }

    /**
     * Linearly interpolates between two weather presets across a transition delta t in [0..1].
     */
    public static WeatherPreset interpolate(WeatherPreset a, WeatherPreset b, float t) {
        float ct = Math.max(0.0f, Math.min(1.0f, t));
        List<SkySlice> slices = Arrays.asList(new SkySlice[8]);
        for (int i = 0; i < 8; i++) {
            slices.set(i, SkySlice.lerp(a.skySlices.get(i), b.skySlices.get(i), ct));
        }

        Vector3f sun = new Vector3f(a.sunColor).lerp(b.sunColor, ct);
        Vector3f moon = new Vector3f(a.moonColor).lerp(b.moonColor, ct);
        Vector3f amb = new Vector3f(a.ambientColor).lerp(b.ambientColor, ct);
        Vector4f clear = new Vector4f(a.clearColor).lerp(b.clearColor, ct);
        float near = a.fogNear + (b.fogNear - a.fogNear) * ct;
        float far = a.fogFar + (b.fogFar - a.fogFar) * ct;
        Vector3f fogCol = new Vector3f(a.fogColor).lerp(b.fogColor, ct);
        Vector3f wind = new Vector3f(a.windVector).lerp(b.windVector, ct);
        float diffMult = a.diffuseMultiplier + (b.diffuseMultiplier - a.diffuseMultiplier) * ct;

        return new WeatherPreset(
                b.id, b.displayName, b.element, ct > 0.5f ? b.effectType : a.effectType,
                slices, sun, moon, amb, clear, near, far, fogCol, wind, diffMult
        );
    }
}
