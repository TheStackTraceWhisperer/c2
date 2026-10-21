package com.example.asset.table;

import com.example.asset.reader.DatByteReader;

public final class DllLookupExtractor {
    private static final int SEARCH_START = 0x30000;
    private static final int SEARCH32_LENGTH = 0xC000;
    private static final int SEARCH64_LENGTH = 0x6000;
    public static final int EQUIPMENT_LOOKUP_TABLE_OFFSET_HINT = 0xA81B0000;
    public static final int RACE_CONFIG_LOOKUP_TABLE_OFFSET_HINT = 0xA01BA01B;
    public static final int EQUIPMENT_LOOKUP_TABLE_SIZE_BYTES = 0x1B0 * 17;

    private DllLookupExtractor() {
    }

    public static DllLookupOffsets loadFromDll(DatByteReader dllReader) {
        if (dllReader == null) {
            return new DllLookupOffsets(-1, -1, -1, -1, -1, -1, -1, -1, -1);
        }

        int equipmentLookupTableOffset = findOffset32BE(dllReader, EQUIPMENT_LOOKUP_TABLE_OFFSET_HINT);
        int raceConfigLookupTableOffset = findOffset32BE(dllReader, RACE_CONFIG_LOOKUP_TABLE_OFFSET_HINT);
        int weaponSkillAnimationFileTableOffset = findOffset32BE(dllReader, 0xCB81CB81);
        int danceSkillAnimationFileTableOffset = findOffset32BE(dllReader, 0xB9E2B9E2);
        int actionAnimationFileTableOffset = findOffset32BE(dllReader, 0xCB96CB96);
        int fishingRodFileTableOffset = findOffset32BE(dllReader, 0x8B998B99);
        int zoneDecryptTable1Offset = findOffset32BE(dllReader, 0xE2E506A9);
        int zoneDecryptTable2Offset = findOffset32BE(dllReader, (int) 0xB8C5F784L);
        int zoneMapTableOffset = findOffset64BE(dllReader, 0x6400000100010100L);

        return new DllLookupOffsets(
                equipmentLookupTableOffset,
                raceConfigLookupTableOffset,
                weaponSkillAnimationFileTableOffset,
                danceSkillAnimationFileTableOffset,
                actionAnimationFileTableOffset,
                fishingRodFileTableOffset,
                zoneDecryptTable1Offset,
                zoneDecryptTable2Offset,
                zoneMapTableOffset
        );
    }

    public static DatByteReader extractEquipmentLookupTable(DatByteReader dllReader) {
        if (dllReader == null) {
            return null;
        }
        int offset = findOffset32BE(dllReader, EQUIPMENT_LOOKUP_TABLE_OFFSET_HINT);
        if (offset < 0) {
            return null;
        }
        return dllReader.slice(offset, EQUIPMENT_LOOKUP_TABLE_SIZE_BYTES);
    }

    public static int findOffset32BE(DatByteReader reader, int hint) {
        reader.position(SEARCH_START);
        for (int i = 0; i < SEARCH32_LENGTH && reader.remaining() >= 4; i++) {
            if (reader.next32BE() == hint) {
                return reader.position() - 4;
            }
        }
        return -1;
    }

    private static int findOffset64BE(DatByteReader reader, long hint) {
        reader.position(SEARCH_START);
        for (int i = 0; i < SEARCH64_LENGTH && reader.remaining() >= 8; i++) {
            if (reader.next64BE() == hint) {
                return reader.position() - 8;
            }
        }
        return -1;
    }
}
