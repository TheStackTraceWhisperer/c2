package com.example.asset.table;

import com.example.asset.reader.DatByteReader;

public final class CharacterIdToResourceId {
    private static final int RACE_CONFIG_LOOKUP_TABLE_OFFSET_HINT = 0xA01BA01B;

    private CharacterIdToResourceId() {
    }

    public static int getRaceConfigFileTableIndex(DatByteReader dllReader, RaceGenderConfig config) {
        int offset = DllLookupExtractor.findOffset32BE(dllReader, RACE_CONFIG_LOOKUP_TABLE_OFFSET_HINT);
        if (offset < 0) {
            throw new IllegalStateException("Failed to find raceConfig table in DLL");
        }
        return dllReader.readU16(offset + config.index * 2);
    }

    public static String getRaceConfigPath(RaceGenderConfig config, DatByteReader dllReader, FileTableManager ftm) {
        int fileTableIndex = getRaceConfigFileTableIndex(dllReader, config);
        return ftm.getFilePath(fileTableIndex);
    }

    /** Returns the upper body animation DAT path (fileTableIndex + 0x01). Contains idl1 and other upper body animations. */
    public static String getUpperBodyAnimationPath(RaceGenderConfig config, DatByteReader dllReader, FileTableManager ftm) {
        int fileTableIndex = getRaceConfigFileTableIndex(dllReader, config);
        return ftm.getFilePath(fileTableIndex + 1);
    }

    public static String getEquipmentModelPath(RaceGenderConfig config, ItemModelSlot slot, int modelId,
            DatByteReader dllReader, FileTableManager ftm) {
        DatByteReader equipmentTableReader = DllLookupExtractor.extractEquipmentLookupTable(dllReader);
        if (equipmentTableReader == null) {
            return null;
        }
        return EquipmentModelTable.getItemModelPath(config, slot, modelId, equipmentTableReader, ftm);
    }
}
