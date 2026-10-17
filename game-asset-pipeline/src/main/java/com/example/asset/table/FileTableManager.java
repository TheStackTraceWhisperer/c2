package com.example.asset.table;

import java.util.Objects;

/**
 * Combines VTable and FTable to resolve any FFXI fileId into its relative DAT path:
 * "ROM{version}/{folder}/{file}.DAT" (where version 1 has an empty string suffix).
 */
public final class FileTableManager {

    private final VTable vTable;
    private final FTable fTable;

    public FileTableManager(VTable vTable, FTable fTable) {
        this.vTable = Objects.requireNonNull(vTable, "vTable must not be null");
        this.fTable = Objects.requireNonNull(fTable, "fTable must not be null");
    }

    /**
     * Resolves the canonical relative path for a given fileId.
     * Returns null if the file does not exist (version 0) or is out of bounds.
     */
    public String resolvePath(int fileId) {
        int version = vTable.getVersion(fileId);
        if (version == 0) {
            return null;
        }

        FTableEntry entry = fTable.getFileEntry(fileId);
        if (entry == null) {
            return null;
        }

        String versionStr = (version == 1) ? "" : String.valueOf(version);
        return "ROM" + versionStr + "/" + entry.folderNumber() + "/" + entry.fileNumber() + ".DAT";
    }
}
