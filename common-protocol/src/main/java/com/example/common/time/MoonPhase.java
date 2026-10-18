package com.example.common.time;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The 12 authentic lunar phases of Vana'diel across an 84-day cycle.
 */
@Getter
@RequiredArgsConstructor
public enum MoonPhase {
    NEW_MOON(0, "New Moon", 0, 10),
    WAXING_CRESCENT_1(1, "Waxing Crescent", 11, 24),
    WAXING_CRESCENT_2(2, "Waxing Crescent", 25, 38),
    FIRST_QUARTER(3, "First Quarter", 39, 58),
    WAXING_GIBBOUS_1(4, "Waxing Gibbous", 59, 74),
    WAXING_GIBBOUS_2(5, "Waxing Gibbous", 75, 88),
    FULL_MOON(6, "Full Moon", 89, 100),
    WANING_GIBBOUS_1(7, "Waning Gibbous", 88, 75),
    WANING_GIBBOUS_2(8, "Waning Gibbous", 74, 59),
    LAST_QUARTER(9, "Last Quarter", 58, 39),
    WANING_CRESCENT_1(10, "Waning Crescent", 38, 25),
    WANING_CRESCENT_2(11, "Waning Crescent", 24, 11);

    private final int index;
    private final String displayName;
    private final int minPercent;
    private final int maxPercent;

    public static MoonPhase fromDayOfCycle(int dayOfCycle84) {
        int day = (dayOfCycle84 % 84 + 84) % 84;
        // 84 days divided across 12 distinct phase brackets of 7 days each
        int phaseIdx = day / 7;
        return values()[Math.min(phaseIdx, values().length - 1)];
    }

    public static int calculatePercent(int dayOfCycle84) {
        int day = (dayOfCycle84 % 84 + 84) % 84;
        // Full Moon occurs at day 42 (100%), New Moon at day 0 and day 84 (0%)
        if (day <= 42) {
            return (int) Math.round((day / 42.0) * 100.0);
        } else {
            return (int) Math.round(((84 - day) / 42.0) * 100.0);
        }
    }
}
