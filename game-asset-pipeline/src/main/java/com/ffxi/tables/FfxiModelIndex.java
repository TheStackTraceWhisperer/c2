package com.ffxi.tables;

import com.ffxi.crypto.DllLookupExtractor;
import com.ffxi.crypto.DllLookupOffsets;
import com.ffxi.crypto.KeyTables;
import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import com.ffxi.io.FileUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, per-install cached model lookup index for retail FFXI DATs.
 *
 * <p>Loads {@code FFXiMain.dll}, initializes {@link KeyTables}, extracts lookup offsets, parses
 * {@link EquipmentModelTable}, caches skeleton indices from the race-config table, and loads
 * {@link FileTableManager} once per normalized FFXI install root.
 */
public final class FfxiModelIndex {
    private static final ConcurrentHashMap<Path, FfxiModelIndex> CACHE = new ConcurrentHashMap<>();

    private final Path ffxiRoot;
    private final FileTableManager fileTableManager;
    private final EquipmentModelTable equipmentModelTable;
    private final int raceConfigTableOffset;
    private final Map<RaceGenderConfig, Integer> skeletonFileIndices;

    private FfxiModelIndex(
            Path ffxiRoot,
            FileTableManager fileTableManager,
            EquipmentModelTable equipmentModelTable,
            int raceConfigTableOffset,
            Map<RaceGenderConfig, Integer> skeletonFileIndices
    ) {
        this.ffxiRoot = ffxiRoot;
        this.fileTableManager = fileTableManager;
        this.equipmentModelTable = equipmentModelTable;
        this.raceConfigTableOffset = raceConfigTableOffset;
        this.skeletonFileIndices = skeletonFileIndices;
    }

    /**
     * Returns the cached {@link FfxiModelIndex} for {@code ffxiRoot}, loading and initializing it on first access.
     */
    public static FfxiModelIndex getOrLoad(Path ffxiRoot) throws IOException {
        if (ffxiRoot == null) {
            throw new IllegalArgumentException("ffxiRoot must not be null");
        }
        Path normalized = ffxiRoot.toAbsolutePath().normalize();
        try {
            return CACHE.computeIfAbsent(normalized, key -> {
                try {
                    return load(key);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Clears all cached {@link FfxiModelIndex} instances.
     */
    public static void clearCache() {
        CACHE.clear();
    }

    private static FfxiModelIndex load(Path normalizedRoot) throws IOException {
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IllegalArgumentException("FFXI root directory does not exist: " + normalizedRoot);
        }

        Path dllPath = FileUtil.resolveCaseInsensitive(normalizedRoot, "FFXiMain.dll");
        if (dllPath == null || !Files.isRegularFile(dllPath)) {
            throw new IllegalArgumentException("FFXiMain.dll not found in: " + normalizedRoot);
        }

        byte[] dllBytes = Files.readAllBytes(dllPath);
        String dllName = dllPath.getFileName() != null ? dllPath.getFileName().toString() : "FFXiMain.dll";
        ByteReader dllReader = new ByteReader(dllBytes, dllName);

        KeyTables.loadFromDll(dllReader);

        DllLookupOffsets offsets = DllLookupExtractor.loadFromDll(new ByteReader(dllBytes, dllName));

        int raceConfigOffset = offsets.raceConfigLookupTableOffset();
        if (raceConfigOffset < 0) {
            raceConfigOffset = CharacterIdToResourceId.findRaceConfigTableOffset(dllReader);
        }

        ByteReader equipmentTableReader;
        if (offsets.equipmentLookupTableOffset() >= 0) {
            equipmentTableReader = new ByteReader(
                    dllReader.subBuffer(offsets.equipmentLookupTableOffset(), EquipmentModelTable.TABLE_SIZE_BYTES),
                    dllName
            );
        } else {
            equipmentTableReader = DllLookupExtractor.extractEquipmentLookupTable(new ByteReader(dllBytes, dllName));
        }
        if (equipmentTableReader == null) {
            throw new IllegalStateException("Failed to find equipment lookup table in DLL: " + dllPath);
        }

        EquipmentModelTable equipmentModelTable = EquipmentModelTable.parseTable(equipmentTableReader);

        EnumMap<RaceGenderConfig, Integer> skeletonIndices = new EnumMap<>(RaceGenderConfig.class);
        for (RaceGenderConfig config : RaceGenderConfig.values()) {
            int skeletonIndex = CharacterIdToResourceId.getRaceConfigFileTableIndex(dllReader, raceConfigOffset, config);
            skeletonIndices.put(config, skeletonIndex);
        }

        FileTableManager ftm = new FileTableManager();
        ftm.loadFromPath(normalizedRoot);

        return new FfxiModelIndex(
                normalizedRoot,
                ftm,
                equipmentModelTable,
                raceConfigOffset,
                Collections.unmodifiableMap(skeletonIndices)
        );
    }

    public Path ffxiRoot() {
        return ffxiRoot;
    }

    public FileTableManager fileTableManager() {
        return fileTableManager;
    }

    public EquipmentModelTable equipmentModelTable() {
        return equipmentModelTable;
    }

    public int raceConfigTableOffset() {
        return raceConfigTableOffset;
    }

    public int getSkeletonFileIndex(RaceGenderConfig config) {
        if (config == null) {
            return -1;
        }
        Integer index = skeletonFileIndices.get(config);
        return index != null ? index : -1;
    }

    public String getSkeletonPath(RaceGenderConfig config) {
        int index = getSkeletonFileIndex(config);
        return index >= 0 ? fileTableManager.getFilePath(index) : null;
    }

    /**
     * Returns the upper-body animation DAT path for playable races, or {@code null} for non-playable races
     * (children and chocobos carry animations directly in their skeleton DAT).
     */
    public String getUpperBodyAnimationPath(RaceGenderConfig config) {
        if (config == null || !config.isPlayableRace()) {
            return null;
        }
        int index = getSkeletonFileIndex(config);
        return index >= 0 ? fileTableManager.getFilePath(index + 1) : null;
    }

    public int getEquipmentFileIndex(RaceGenderConfig config, ItemModelSlot slot, int modelId) {
        return equipmentModelTable.getFileTableIndex(config, slot, modelId);
    }

    public String getEquipmentModelPath(RaceGenderConfig config, ItemModelSlot slot, int modelId) {
        return equipmentModelTable.getItemModelPath(config, slot, modelId, fileTableManager);
    }

    public int getNpcModelFileIndex(int modelId) {
        return NpcModelTable.getNpcModelFileIndex(modelId);
    }

    public String getNpcModelPath(int modelId) {
        return NpcModelTable.getNpcModelPath(modelId, fileTableManager);
    }

    public String getFilePath(int fileIndex) {
        if (fileIndex < 0) {
            return null;
        }
        return fileTableManager.getFilePath(fileIndex);
    }
}
