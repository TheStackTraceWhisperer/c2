package com.example.asset.geometry;

/**
 * Terrain surface type decoded from triangle flag bits.
 */
public enum TerrainType {
    OBJECT(0, false),
    PATH(1, false),
    GRASS(2, false),
    SAND(3, true),
    SNOW(4, true),
    STONE(5, false),
    METAL(6, false),
    WOOD(7, false),
    SHALLOW_WATER(8, false),
    DEEP_WATER(9, false),
    UNKNOWN(10, false);

    private final int index;
    private final boolean footMark;

    TerrainType(int index, boolean footMark) {
        this.index = index;
        this.footMark = footMark;
    }

    public int index() {
        return index;
    }

    public boolean hasFootMark() {
        return footMark;
    }

    public static TerrainType fromFlags(int f0, int f1, int f2, int f3) {
        int index = ((f0 & 0x8) >>> 3)
                + ((f1 & 0x8) >>> 2)
                + ((f2 & 0x8) >>> 1)
                + (f3 & 0x8);

        for (TerrainType type : values()) {
            if (type.index == index) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
