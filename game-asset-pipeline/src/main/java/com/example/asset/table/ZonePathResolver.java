package com.example.asset.table;

import java.util.Objects;

/**
 * Maps game zone IDs to their canonical DAT asset file paths using FileTableManager.
 */
public final class ZonePathResolver {

    private static final int BASE_FILE_OFFSET = 0x64;          // 100
    private static final int EXPANSION_FILE_OFFSET = 0x147B3;  // 83891

    private final FileTableManager fileTableManager;

    public ZonePathResolver(FileTableManager fileTableManager) {
        this.fileTableManager = Objects.requireNonNull(fileTableManager, "fileTableManager must not be null");
    }

    /**
     * Converts a zoneId into the canonical file table index.
     */
    public static int toFileId(int zoneId) {
        if (zoneId < 0x100) {
            return BASE_FILE_OFFSET + zoneId;
        } else {
            return EXPANSION_FILE_OFFSET + (zoneId - 0x100);
        }
    }

    /**
     * Resolves the relative DAT path for a given zoneId.
     */
    public String resolveZonePath(int zoneId) {
        return fileTableManager.resolvePath(toFileId(zoneId));
    }
}
