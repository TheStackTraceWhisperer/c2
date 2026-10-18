package com.example.common.time;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The 8 elemental days of the Vana'diel week.
 */
@Getter
@RequiredArgsConstructor
public enum VanadielDayOfWeek {
    FIRE(0, "Firesday", "Fire"),
    EARTH(1, "Earthsday", "Earth"),
    WATER(2, "Watersday", "Water"),
    WIND(3, "Windsday", "Wind"),
    ICE(4, "Iceday", "Ice"),
    LIGHTNING(5, "Lightningsday", "Lightning"),
    LIGHT(6, "Lightsday", "Light"),
    DARK(7, "Darksday", "Dark");

    private final int index;
    private final String displayName;
    private final String element;

    public static VanadielDayOfWeek fromIndex(int index) {
        int idx = (index % 8 + 8) % 8;
        return values()[idx];
    }
}
