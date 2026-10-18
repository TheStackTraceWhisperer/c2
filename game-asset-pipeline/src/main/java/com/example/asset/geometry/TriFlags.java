package com.example.asset.geometry;

/**
 * Flags associated with a collision triangle.
 */
public record TriFlags(int type) {

    public boolean isHitWall() {
        return (type & 0x40) != 0;
    }
}
