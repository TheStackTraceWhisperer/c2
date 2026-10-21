package com.ffxi.tables;

import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class EquipmentModelTable {
    public static final int SLOT_TABLE_STRIDE_BYTES = 0x30;
    public static final int RACE_TABLE_SIZE_BYTES = 0x1B0;
    public static final int ENTRY_COUNT_PER_SLOT = 6;
    public static final int ENTRY_SIZE_BYTES = 8;
    public static final int TABLE_SIZE_BYTES = RACE_TABLE_SIZE_BYTES * 17;

    private EquipmentModelTable() {
    }

    /**
     * Resolves the DAT file path for a specific equipment slot and model ID using the DLL equipment lookup table.
     * 
     * How the mapping works:
     * 1. The DLL equipment table contains a race-specific lookup block for each Race/Gender config.
     * 2. Each race block is structured by equipment slot (Head, Body, Hands, Legs, Feet) and contains up to 6 TableEntries.
     * 3. A TableEntry defines a contiguous block of DAT file indices: a starting `tableOffset` in FTABLE and an `entryCount`.
     * 4. For a given equipment modelId, we traverse these entries in sequence. The modelId is cumulative:
     *    we find the entry where modelId falls within its range [cumulativeCount, cumulativeCount + entryCount).
     * 5. The target file table index is calculated as: `tableOffset + (modelId - cumulativeCount)`.
     * 6. Finally, we convert this file table index to a DAT path (e.g., ROM/folder/file.DAT) using FileTableManager.
     *
     * Example mapping (Body slot, modelId = 8):
     * - Mithra (index 7):
     *   The entry starts at FTABLE offset 8333 with a count of 30.
     *   Model ID 8 falls in this entry (since 8 < 30).
     *   File Table Index = 8333 + 8 = 8341 -> Resolves to "ROM/52/21.DAT" (Mithran Separates).
     * - ElvaanF (index 3):
     *   The entry starts at FTABLE offset 6649 with a count of 30.
     *   Model ID 8 falls in this entry (since 8 < 30).
     *   File Table Index = 6649 + 8 = 6657 -> Resolves to "ROM/42/64.DAT".
     */
    public static String getItemModelPath(RaceGenderConfig config, ItemModelSlot slot, int modelId,
            ByteReader equipmentTableReader, FileTableManager ftm) {
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

    private static Map<RaceGenderConfig, Map<ItemModelSlot, List<TableEntry>>> parse(ByteReader byteReader) {
        Map<RaceGenderConfig, Map<ItemModelSlot, List<TableEntry>>> tables = new EnumMap<>(RaceGenderConfig.class);
        for (RaceGenderConfig config : RaceGenderConfig.values()) {
            byteReader.setPosition(RACE_TABLE_SIZE_BYTES * (config.equipmentTableIndex - 1));
            tables.put(config, parseRaceGenderTable(byteReader));
        }
        return tables;
    }

    private static Map<ItemModelSlot, List<TableEntry>> parseRaceGenderTable(ByteReader byteReader) {
        int basePosition = byteReader.getPosition();
        Map<ItemModelSlot, List<TableEntry>> table = new EnumMap<>(ItemModelSlot.class);
        for (ItemModelSlot slot : ItemModelSlot.values()) {
            byteReader.setPosition(basePosition + SLOT_TABLE_STRIDE_BYTES * slot.index);
            List<TableEntry> entries = new ArrayList<>(ENTRY_COUNT_PER_SLOT);
            for (int i = 0; i < ENTRY_COUNT_PER_SLOT; i++) {
                TableEntry entry = new TableEntry(byteReader.next32(), byteReader.next32());
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
