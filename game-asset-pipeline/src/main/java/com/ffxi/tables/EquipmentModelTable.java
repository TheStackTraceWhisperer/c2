package com.ffxi.tables;

import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class EquipmentModelTable {
    public static final int SLOT_TABLE_STRIDE_BYTES = 0x30;
    public static final int RACE_TABLE_SIZE_BYTES = 0x1B0;
    public static final int ENTRY_COUNT_PER_SLOT = 6;
    public static final int ENTRY_SIZE_BYTES = 8;
    public static final int TABLE_SIZE_BYTES = RACE_TABLE_SIZE_BYTES * 17;

    private final Map<RaceGenderConfig, Map<ItemModelSlot, List<TableEntry>>> tables;

    private EquipmentModelTable(Map<RaceGenderConfig, Map<ItemModelSlot, List<TableEntry>>> tables) {
        this.tables = tables;
    }

    /**
     * Parses the DLL equipment lookup table bytes into an immutable reusable {@link EquipmentModelTable}.
     */
    public static EquipmentModelTable parseTable(ByteReader equipmentTableReader) {
        if (equipmentTableReader == null) {
            throw new IllegalArgumentException("equipmentTableReader must not be null");
        }
        ByteReader local = new ByteReader(equipmentTableReader.getBytes(), equipmentTableReader.getResourceName());
        return new EquipmentModelTable(parse(local));
    }

    /**
     * Resolves the FTABLE file index for the given race, equipment slot, and model ID.
     *
     * @return non-negative FTABLE file index, or {@code -1} if invalid, disallowed for the race/slot,
     *         out of range, or hitting an unpopulated hole ({@code tableOffset == 0}).
     */
    public int getFileTableIndex(RaceGenderConfig config, ItemModelSlot slot, int modelId) {
        if (config == null || slot == null || modelId < 0) {
            return -1;
        }
        if (isDisallowedSlot(config, slot)) {
            return -1;
        }

        Map<ItemModelSlot, List<TableEntry>> raceTable = tables.get(config);
        if (raceTable == null) {
            return -1;
        }

        List<TableEntry> slotEntries = raceTable.get(slot);
        if (slotEntries == null) {
            return -1;
        }

        int cumulativeCount = 0;
        for (TableEntry entry : slotEntries) {
            int count = entry.entryCount();
            if (count <= 0) {
                continue;
            }
            if (modelId >= cumulativeCount + count) {
                cumulativeCount += count;
                continue;
            }
            if (entry.tableOffset() == 0) {
                return -1;
            }
            return entry.tableOffset() + (modelId - cumulativeCount);
        }
        return -1;
    }

    /**
     * Resolves the DAT file path for a specific race, equipment slot, and model ID using this parsed table.
     */
    public String getItemModelPath(RaceGenderConfig config, ItemModelSlot slot, int modelId, FileTableManager ftm) {
        if (ftm == null) {
            return null;
        }
        int fileIndex = getFileTableIndex(config, slot, modelId);
        if (fileIndex < 0) {
            return null;
        }
        return ftm.getFilePath(fileIndex);
    }

    /**
     * Returns the total cumulative model-ID entry count available for the given race and slot,
     * or {@code 0} if the race/slot is null or disallowed.
     */
    public int getEntryCount(RaceGenderConfig config, ItemModelSlot slot) {
        if (config == null || slot == null || isDisallowedSlot(config, slot)) {
            return 0;
        }
        Map<ItemModelSlot, List<TableEntry>> raceTable = tables.get(config);
        if (raceTable == null) {
            return 0;
        }
        List<TableEntry> slotEntries = raceTable.get(slot);
        if (slotEntries == null) {
            return 0;
        }
        int total = 0;
        for (TableEntry entry : slotEntries) {
            if (entry.entryCount() > 0) {
                total += entry.entryCount();
            }
        }
        return total;
    }

    /**
     * Resolves the DAT file path for a specific equipment slot and model ID using the DLL equipment lookup table.
     *
     * <p>Backwards-compatible static helper that parses the supplied reader on each call. Prefer
     * {@link #parseTable(ByteReader)} or {@link FfxiModelIndex} for repeated lookups.
     */
    public static String getItemModelPath(RaceGenderConfig config, ItemModelSlot slot, int modelId,
            ByteReader equipmentTableReader, FileTableManager ftm) {
        if (config == null || slot == null || modelId < 0 || equipmentTableReader == null || ftm == null) {
            return null;
        }
        return parseTable(equipmentTableReader).getItemModelPath(config, slot, modelId, ftm);
    }

    private static boolean isDisallowedSlot(RaceGenderConfig config, ItemModelSlot slot) {
        if (config.isChocobo() && slot == ItemModelSlot.Face) {
            return true;
        }
        return config.isNpcRace() && slot.index >= ItemModelSlot.Main.index;
    }

    private static Map<RaceGenderConfig, Map<ItemModelSlot, List<TableEntry>>> parse(ByteReader byteReader) {
        Map<RaceGenderConfig, Map<ItemModelSlot, List<TableEntry>>> tables = new EnumMap<>(RaceGenderConfig.class);
        for (RaceGenderConfig config : RaceGenderConfig.values()) {
            byteReader.setPosition(RACE_TABLE_SIZE_BYTES * (config.equipmentTableIndex - 1));
            tables.put(config, parseRaceGenderTable(byteReader));
        }
        return Collections.unmodifiableMap(tables);
    }

    private static Map<ItemModelSlot, List<TableEntry>> parseRaceGenderTable(ByteReader byteReader) {
        int basePosition = byteReader.getPosition();
        Map<ItemModelSlot, List<TableEntry>> table = new EnumMap<>(ItemModelSlot.class);
        for (ItemModelSlot slot : ItemModelSlot.values()) {
            byteReader.setPosition(basePosition + SLOT_TABLE_STRIDE_BYTES * slot.index);
            List<TableEntry> entries = new ArrayList<>(ENTRY_COUNT_PER_SLOT);
            for (int i = 0; i < ENTRY_COUNT_PER_SLOT; i++) {
                TableEntry entry = new TableEntry(byteReader.next32(), byteReader.next32());
                if (entry.tableOffset() == 0 && entry.entryCount() == 0) {
                    continue;
                }
                entries.add(entry);
            }
            table.put(slot, List.copyOf(entries));
        }
        return Collections.unmodifiableMap(table);
    }

    private record TableEntry(int tableOffset, int entryCount) {
    }
}
