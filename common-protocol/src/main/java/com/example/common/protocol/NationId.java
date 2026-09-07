package com.example.common.protocol;

public final class NationId {
    public static final short SAN_D_ORIA = 0;
    public static final short BASTOK = 1;
    public static final short WINDURST = 2;

    public static String toLabel(short nationId) {
        return switch (nationId) {
            case SAN_D_ORIA -> "San d'Oria";
            case BASTOK -> "Bastok";
            case WINDURST -> "Windurst";
            default -> "Unknown";
        };
    }

    private NationId() {}
}
