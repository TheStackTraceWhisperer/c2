package com.ffxi.tables;

public record ZoneMap(
        int zoneId,
        int subZoneId,
        int fileId,
        int mapSizePx,
        float xOffset,
        float yOffset
) {
}
