package com.ffxi.model;

public record BgraColor(
        int b,
        int g,
        int r,
        int a
) {
    public BgraColor {
        b = clampByte(b);
        g = clampByte(g);
        r = clampByte(r);
        a = clampByte(a);
    }

    public BgraColor multiply(int factor) {
        return new BgraColor(b * factor, g * factor, r * factor, a * factor);
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(0xFF, value));
    }
}
