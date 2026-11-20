package com.ffxi.tables;

import com.ffxi.crypto.DllLookupExtractor;
import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;

public final class CharacterIdToResourceId {
    private static final int RACE_CONFIG_LOOKUP_TABLE_OFFSET_HINT = 0xA01BA01B;

    private CharacterIdToResourceId() {
    }

    public static int findRaceConfigTableOffset(ByteReader dllReader) {
        if (dllReader == null) {
            throw new IllegalArgumentException("dllReader must not be null");
        }
        ByteReader local = new ByteReader(dllReader.getBytes(), dllReader.getResourceName());
        int offset = DllLookupExtractor.findOffset32BE(local, RACE_CONFIG_LOOKUP_TABLE_OFFSET_HINT);
        if (offset < 0) {
            throw new IllegalStateException("Failed to find raceConfig table in DLL");
        }
        return offset;
    }

    public static int getRaceConfigFileTableIndex(ByteReader dllReader, int raceConfigOffset, RaceGenderConfig config) {
        if (dllReader == null) {
            throw new IllegalArgumentException("dllReader must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (raceConfigOffset < 0) {
            throw new IllegalArgumentException("raceConfigOffset must be non-negative: " + raceConfigOffset);
        }
        return dllReader.read16(raceConfigOffset + config.index * 2);
    }

    public static int getRaceConfigFileTableIndex(ByteReader dllReader, RaceGenderConfig config) {
        int offset = findRaceConfigTableOffset(dllReader);
        return getRaceConfigFileTableIndex(dllReader, offset, config);
    }

    public static String getRaceConfigPath(RaceGenderConfig config, ByteReader dllReader, FileTableManager ftm) {
        if (config == null || dllReader == null || ftm == null) {
            return null;
        }
        int fileTableIndex = getRaceConfigFileTableIndex(dllReader, config);
        return ftm.getFilePath(fileTableIndex);
    }

    /**
     * Returns the upper body animation DAT path ({@code fileTableIndex + 0x01}) for playable races.
     * Returns {@code null} for non-playable races (child races 29..31 have 208-byte dummy files at
     * {@code fileTableIndex + 1}, and children/chocobos carry animations directly inside their skeleton DAT).
     */
    public static String getUpperBodyAnimationPath(RaceGenderConfig config, ByteReader dllReader, FileTableManager ftm) {
        if (config == null || !config.isPlayableRace() || dllReader == null || ftm == null) {
            return null;
        }
        int fileTableIndex = getRaceConfigFileTableIndex(dllReader, config);
        return ftm.getFilePath(fileTableIndex + 1);
    }

    public static String getEquipmentModelPath(RaceGenderConfig config, ItemModelSlot slot, int modelId,
            ByteReader dllReader, FileTableManager ftm) {
        ByteReader equipmentTableReader = DllLookupExtractor.extractEquipmentLookupTable(dllReader);
        if (equipmentTableReader == null) {
            return null;
        }
        return EquipmentModelTable.getItemModelPath(config, slot, modelId, equipmentTableReader, ftm);
    }
}
