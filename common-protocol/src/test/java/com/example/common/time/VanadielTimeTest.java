package com.example.common.time;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VanadielTimeTest {

    @Test
    void testEpochOrigin() {
        // Epoch at exactly 2002-01-01 00:00:00 UTC
        VanadielTime time = VanadielTime.ofUtcMillis(VanadielTime.EPOCH_UTC_MILLIS);

        assertEquals(886, time.getYear());
        assertEquals(1, time.getMonth());
        assertEquals(1, time.getDay());
        assertEquals(0, time.getHour());
        assertEquals(0, time.getMinute());
        assertEquals(0, time.getSecond());
        assertEquals(VanadielDayOfWeek.FIRE, time.getDayOfWeek());
        assertEquals(MoonPhase.NEW_MOON, time.getMoonPhase());
        assertEquals(0, time.getMoonPercent());
    }

    @Test
    void testTimeProgressionMultiplier() {
        // Advance by 1 Earth day = 86400 seconds
        // At 25x, this is 2,160,000 Vana'diel seconds = 25 Vana'diel days
        long oneDayMillis = VanadielTime.EPOCH_UTC_MILLIS + (86400L * 1000L);
        VanadielTime time = VanadielTime.ofUtcMillis(oneDayMillis);

        assertEquals(886, time.getYear());
        assertEquals(1, time.getMonth());
        assertEquals(26, time.getDay()); // 1 + 25 days
        assertEquals(0, time.getHour());
        assertEquals(0, time.getMinute());
        // 25 mod 8 = 1 -> Earthsday
        assertEquals(VanadielDayOfWeek.EARTH, time.getDayOfWeek());
    }

    @Test
    void testFullMoonCycle() {
        // Moon cycle is 84 days. Half cycle (42 days) is Full Moon (100%).
        // 42 Vana'diel days = 42 * 86400 / 25 = 145152 Earth seconds.
        long fullMoonMillis = VanadielTime.EPOCH_UTC_MILLIS + (145152L * 1000L);
        VanadielTime time = VanadielTime.ofUtcMillis(fullMoonMillis);

        assertEquals(MoonPhase.FULL_MOON, time.getMoonPhase());
        assertEquals(100, time.getMoonPercent());
    }

    @Test
    void testFormatting() {
        VanadielTime time = VanadielTime.ofUtcMillis(VanadielTime.EPOCH_UTC_MILLIS + (144000L * 1000L));
        assertNotNull(time.formatClockSummary());
        assertNotNull(time.formatDate());
        assertNotNull(time.formatTime());
        assertTrue(time.getHourFraction() >= 0.0f && time.getHourFraction() <= 24.0f);
    }
}
