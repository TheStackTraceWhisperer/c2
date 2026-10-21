package com.ffxi.tables;

import com.ffxi.crypto.DllLookupOffsets;
import com.ffxi.io.ByteReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ZoneMapTable {
    public static final int TABLE_SIZE_BYTES = 0x2D64;
    private static final int ENTRY_SIZE_BYTES = 14;

    private final List<ZoneMap> entries;
    private final Map<Long, ZoneMap> byZoneAndSubZone;

    private ZoneMapTable(List<ZoneMap> entries) {
        this.entries = List.copyOf(entries);
        Map<Long, ZoneMap> lookup = new LinkedHashMap<>();
        for (ZoneMap entry : entries) {
            lookup.put(key(entry.zoneId(), entry.subZoneId()), entry);
        }
        this.byZoneAndSubZone = Map.copyOf(lookup);
    }

    /**
     * Loads the table from raw FFXiMain.dll bytes using the zone-map offset
     * previously discovered through {@link DllLookupOffsets#zoneMapTableOffset()}.
     */
    public static ZoneMapTable load(byte[] dllBytes, int zoneMapTableOffset) {
        if (dllBytes == null || zoneMapTableOffset < 0 || zoneMapTableOffset >= dllBytes.length) {
            return new ZoneMapTable(List.of());
        }

        int tableEnd = Math.min(dllBytes.length, zoneMapTableOffset + TABLE_SIZE_BYTES);
        ByteReader reader = new ByteReader(dllBytes, "FFXiMain.dll");
        reader.setPosition(zoneMapTableOffset);

        List<ZoneMap> entries = new ArrayList<>();
        while (reader.getPosition() + ENTRY_SIZE_BYTES <= tableEnd) {
            int zoneId = reader.next16Signed();
            if (zoneId < 0) {
                break;
            }

            int subZoneId = reader.next8();
            reader.next8();
            int jTableIndex = reader.next8() & 0x0F;
            int sizeDivisor = reader.next8();
            reader.next16();
            int fileTableOffset = reader.next16Signed();
            float xOffset = (float) reader.next16Signed();
            float yOffset = (float) reader.next16Signed();

            int fileId = getFileTableOffset(jTableIndex) + fileTableOffset;
            int mapSizePx = sizeDivisor == 0 ? 0 : 2560 / sizeDivisor;
            entries.add(new ZoneMap(zoneId, subZoneId, fileId, mapSizePx, xOffset, yOffset));
        }

        return new ZoneMapTable(entries);
    }

    public Optional<ZoneMap> get(int zoneId, int subZoneId) {
        return Optional.ofNullable(byZoneAndSubZone.get(key(zoneId, subZoneId)));
    }

    public List<ZoneMap> getAllForZone(int zoneId) {
        return entries.stream().filter(entry -> entry.zoneId() == zoneId).toList();
    }

    public List<ZoneMap> getAll() {
        return entries;
    }

    private static int getFileTableOffset(int jTableIndex) {
        return switch (jTableIndex) {
            case 0 -> 0x14C0;
            case 1 -> 0xD02F;
            case 2 -> 0xD147;
            case 3 -> 0x1592;
            default -> 0;
        };
    }

    private static long key(int zoneId, int subZoneId) {
        return (((long) zoneId) << 32) | (subZoneId & 0xFFFFFFFFL);
    }
}
