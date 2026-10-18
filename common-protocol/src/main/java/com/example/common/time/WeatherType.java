package com.example.common.time;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Authentic FFXI weather conditions and DAT environment codes.
 */
@Getter
@RequiredArgsConstructor
public enum WeatherType {
    CLEAR(0, "Clear", "fine", 1.0f, 1.0f, 1.0f, 0.0f),
    SUNNY(1, "Sunshine", "suny", 1.05f, 1.0f, 0.95f, 0.0f),
    CLOUDY(2, "Cloudy", "clod", 0.75f, 0.75f, 0.80f, 0.25f),
    FOG(3, "Fog", "mist", 0.65f, 0.68f, 0.70f, 0.60f),
    RAIN(4, "Rain", "rain", 0.55f, 0.58f, 0.65f, 0.40f),
    SQUALL(5, "Squall", "squl", 0.45f, 0.48f, 0.55f, 0.55f),
    THUNDER(6, "Thunder", "thdr", 0.40f, 0.40f, 0.50f, 0.50f),
    WIND(7, "Windy", "wind", 0.80f, 0.80f, 0.80f, 0.20f),
    DUST(8, "Dust Storm", "dust", 0.70f, 0.65f, 0.50f, 0.45f),
    SNOW(9, "Snow", "snow", 0.85f, 0.88f, 0.95f, 0.35f);

    private final int id;
    private final String displayName;
    private final String datCode;
    private final float ambientRedScale;
    private final float ambientGreenScale;
    private final float ambientBlueScale;
    private final float fogDensityMultiplier;

    public static WeatherType fromId(int id) {
        for (WeatherType w : values()) {
            if (w.id == id) return w;
        }
        return CLEAR;
    }
}
