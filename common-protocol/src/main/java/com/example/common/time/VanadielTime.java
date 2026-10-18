package com.example.common.time;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * Authentic Vana'diel calendar, clock, and lunar simulation.
 * Runs at 25× real-world speed (1 real second = 25 Vana'diel seconds).
 * Epoch: 2002-01-01 00:00:00 UTC = Year 886, Month 1, Day 1, 00:00:00 (Firesday).
 */
@Getter
@ToString
@EqualsAndHashCode
public final class VanadielTime {

    public static final long EPOCH_UTC_MILLIS = 1009843200000L; // 2002-01-01 00:00:00 UTC
    public static final double TIME_MULTIPLIER = 25.0;
    public static final int DAYS_PER_YEAR = 360;
    public static final int DAYS_PER_MONTH = 30;
    public static final int MONTHS_PER_YEAR = 12;
    public static final int SECONDS_PER_DAY = 86400;
    public static final int MOON_CYCLE_DAYS = 84;

    private final int year;
    private final int month;
    private final int day;
    private final int hour;
    private final int minute;
    private final int second;
    private final VanadielDayOfWeek dayOfWeek;
    private final MoonPhase moonPhase;
    private final int moonPercent;
    private final long totalVanadielDays;
    private final long totalVanadielSeconds;

    public VanadielTime(int year, int month, int day, int hour, int minute, int second,
                        VanadielDayOfWeek dayOfWeek, MoonPhase moonPhase, int moonPercent,
                        long totalVanadielDays, long totalVanadielSeconds) {
        this.year = year;
        this.month = month;
        this.day = day;
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.dayOfWeek = dayOfWeek;
        this.moonPhase = moonPhase;
        this.moonPercent = moonPercent;
        this.totalVanadielDays = totalVanadielDays;
        this.totalVanadielSeconds = totalVanadielSeconds;
    }

    public static VanadielTime ofUtcMillis(long utcMillis) {
        long earthMillis = utcMillis - EPOCH_UTC_MILLIS;
        long totalVanaSeconds = (long) Math.floor((earthMillis * TIME_MULTIPLIER) / 1000.0);

        long totalDays = Math.floorDiv(totalVanaSeconds, SECONDS_PER_DAY);
        long daySeconds = Math.floorMod(totalVanaSeconds, SECONDS_PER_DAY);

        int hour = (int) (daySeconds / 3600);
        int minute = (int) ((daySeconds % 3600) / 60);
        int second = (int) (daySeconds % 60);

        // Days of week: Firesday (0) through Darksday (7)
        int dayOfWeekIdx = (int) Math.floorMod(totalDays, 8);
        VanadielDayOfWeek dayOfWeek = VanadielDayOfWeek.fromIndex(dayOfWeekIdx);

        // Calendar: Year 886 is year 0
        int year = 886 + (int) Math.floorDiv(totalDays, DAYS_PER_YEAR);
        int dayOfYear = (int) Math.floorMod(totalDays, DAYS_PER_YEAR);
        int month = 1 + (dayOfYear / DAYS_PER_MONTH);
        int day = 1 + (dayOfYear % DAYS_PER_MONTH);

        // Moon phase: 84 day cycle, full moon around day 42
        int moonDay = (int) Math.floorMod(totalDays, MOON_CYCLE_DAYS);
        MoonPhase moonPhase = MoonPhase.fromDayOfCycle(moonDay);
        int moonPercent = MoonPhase.calculatePercent(moonDay);

        return new VanadielTime(year, month, day, hour, minute, second, dayOfWeek, moonPhase, moonPercent, totalDays, totalVanaSeconds);
    }

    public static VanadielTime now() {
        return ofUtcMillis(System.currentTimeMillis());
    }

    public float getHourFraction() {
        return hour + (minute / 60.0f) + (second / 3600.0f);
    }

    public boolean isDaytime() {
        return hour >= 6 && hour < 18;
    }

    public boolean isNighttime() {
        return !isDaytime();
    }

    public String formatTime() {
        return String.format("%02d:%02d", hour, minute);
    }

    public String formatDate() {
        return String.format("Year %d, %d/%d (%s)", year, month, day, dayOfWeek.getDisplayName());
    }

    public String formatClockSummary() {
        return String.format("[%s] %02d:%02d | Moon: %s (%d%%) | Y%d %d/%d",
                dayOfWeek.getDisplayName(), hour, minute, moonPhase.getDisplayName(), moonPercent, year, month, day);
    }
}
