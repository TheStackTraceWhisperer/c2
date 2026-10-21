package com.example.asset.table;

import com.example.asset.reader.DatByteReader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the DAT file path for a specific equipment slot and model ID using the DLL equipment lookup table.
 */
public final class EquipmentModelTable {
    public static final int SLOT_TABLE_STRIDE_BYTES = 0x30;
    public static final int RACE_TABLE_SIZE_BYTES = 0x1B0;
    public static final int ENTRY_COUNT_PER_SLOT = 6;
    public static final int ENTRY_SIZE_BYTES = 8;
    public static final int TABLE_SIZE_BYTES = RACE_TABLE_SIZE_BYTES * 17;

    private EquipmentModelTable() {
    }

    public static String getItemModelPath(RaceGenderConfig config, ItemModelSlot slot, int modelId,
            DatByteReader equipmentTableReader, FileTableManager ftm) {
        if (config == null || slot == null || modelId < 0 || equipmentTableReader == null || ftm == null) {
            return null;
        }

        Map<RaceGenderConfig, Map<ItemModelSlot, List<TableEntry>>> tables = parse(equipmentTableReader);
        Map<ItemModelSlot, List<TableEntry>> raceTable = tables.get(config);
        if (raceTable == null) {
            return null;
        }

        List<TableEntry> slotEntries = raceTable.get(slot);
        if (slotEntries == null) {
            return null;
        }

        int cumulativeCount = 0;
        for (TableEntry entry : slotEntries) {
            if (modelId >= cumulativeCount + entry.entryCount()) {
                cumulativeCount += entry.entryCount();
                continue;
            }
            int fileIndex = entry.tableOffset() + (modelId - cumulativeCount);
            return ftm.getFilePath(fileIndex);
        }
        return null;
    }

    private static Map<RaceGenderConfig, Map<ItemModelSlot, List<TableEntry>>> parse(DatByteReader byteReader) {
        Map<RaceGenderConfig, Map<ItemModelSlot, List<TableEntry>>> tables = new EnumMap<>(RaceGenderConfig.class);
        for (RaceGenderConfig config : RaceGenderConfig.values()) {
            byteReader.position(RACE_TABLE_SIZE_BYTES * (config.equipmentTableIndex - 1));
            tables.put(config, parseRaceGenderTable(byteReader));
        }
        return tables;
    }

    private static Map<ItemModelSlot, List<TableEntry>> parseRaceGenderTable(DatByteReader byteReader) {
        int basePosition = byteReader.position();
        Map<ItemModelSlot, List<TableEntry>> table = new EnumMap<>(ItemModelSlot.class);
        for (ItemModelSlot slot : ItemModelSlot.values()) {
            byteReader.position(basePosition + SLOT_TABLE_STRIDE_BYTES * slot.index);
            List<TableEntry> entries = new ArrayList<>(ENTRY_COUNT_PER_SLOT);
            for (int i = 0; i < ENTRY_COUNT_PER_SLOT; i++) {
                TableEntry entry = new TableEntry(byteReader.nextI32(), byteReader.nextI32());
                if (entry.tableOffset() == 0) {
                    continue;
                }
                entries.add(entry);
            }
            table.put(slot, List.copyOf(entries));
        }
        return table;
    }

    private record TableEntry(int tableOffset, int entryCount) {
    }
}
