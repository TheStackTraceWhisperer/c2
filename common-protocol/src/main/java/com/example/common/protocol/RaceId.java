package com.example.common.protocol;

public final class RaceId {
    public static final short HUME_MALE = 1;
    public static final short HUME_FEMALE = 2;
    public static final short ELVAAN_MALE = 3;
    public static final short ELVAAN_FEMALE = 4;
    public static final short TARUTARU_MALE = 5;
    public static final short TARUTARU_FEMALE = 6;
    public static final short MITHRA = 7;
    public static final short GALKA = 8;

    public static short genderFor(short raceId) {
        // 0 for Male, 1 for Female
        return (raceId == HUME_FEMALE || raceId == ELVAAN_FEMALE || raceId == TARUTARU_FEMALE || raceId == MITHRA)
                ? (short) 1
                : (short) 0;
    }

    public static int forcedSizeFor(short raceId) {
        if (raceId == TARUTARU_MALE || raceId == TARUTARU_FEMALE) {
            return 0; // Small
        }
        if (raceId == GALKA) {
            return 2; // Large
        }
        return -1; // Not locked
    }

    public static String toLabel(short raceId) {
        return switch (raceId) {
            case HUME_MALE -> "Hume Male";
            case HUME_FEMALE -> "Hume Female";
            case ELVAAN_MALE -> "Elvaan Male";
            case ELVAAN_FEMALE -> "Elvaan Female";
            case TARUTARU_MALE -> "Tarutaru Male";
            case TARUTARU_FEMALE -> "Tarutaru Female";
            case MITHRA -> "Mithra";
            case GALKA -> "Galka";
            default -> "Unknown";
        };
    }

    private RaceId() {}
}
