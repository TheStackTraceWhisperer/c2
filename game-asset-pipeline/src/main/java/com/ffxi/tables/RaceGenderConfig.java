package com.ffxi.tables;

import java.util.Locale;

public enum RaceGenderConfig {
    HumeM(1),
    HumeF(2),
    ElvaanM(3),
    ElvaanF(4),
    TaruM(5),
    TaruF(6),
    Mithra(7),
    Galka(8),
    MithraChild(29, 9),
    HumeChildF(30, 10),
    HumeChildM(31, 11),
    Chocobo(32, 12),
    ChocoboBlack(33, 13),
    ChocoboBlue(34, 14),
    ChocoboRed(35, 15),
    ChocoboGreen(36, 16);

    public final int index;
    public final int equipmentTableIndex;

    RaceGenderConfig(int index) {
        this(index, index);
    }

    RaceGenderConfig(int index, int equipmentTableIndex) {
        this.index = index;
        this.equipmentTableIndex = equipmentTableIndex;
    }

    public static RaceGenderConfig fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Race/gender name must not be blank");
        }

        String normalizedName = normalize(name);
        for (RaceGenderConfig config : values()) {
            if (normalize(config.name()).equals(normalizedName)) {
                return config;
            }
        }

        throw new IllegalArgumentException("Unknown race/gender: " + name);
    }

    private static String normalize(String value) {
        return value.replaceAll("[^A-Za-z0-9]+", "").toLowerCase(Locale.ROOT);
    }
}
