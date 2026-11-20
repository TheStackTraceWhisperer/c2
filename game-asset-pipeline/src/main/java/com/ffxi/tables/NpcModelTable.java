package com.ffxi.tables;

import com.ffxi.io.FileTableManager;

/**
 * Maps FFXI NPC/monster model IDs (SubKind 0 / 5 / 6) to FTABLE file indices using the exact
 * {@code FFXiMain.dll} {@code FUN_100c3180} ranges.
 */
public final class NpcModelTable {

    private NpcModelTable() {
    }

    /**
     * Converts an NPC/monster model ID into an FTABLE file index, or {@code -1} if {@code modelId < 0}.
     */
    public static int getNpcModelFileIndex(int modelId) {
        if (modelId < 0) {
            return -1;
        }
        if (modelId < 1500) {
            return 1300 + modelId;
        }
        if (modelId < 3000) {
            return 51795 + (modelId - 1500);
        }
        if (modelId < 3500) {
            return 96907 + modelId;
        }
        return 98239 + modelId;
    }

    /**
     * Resolves an NPC/monster model ID to its DAT file path via {@link FileTableManager}.
     */
    public static String getNpcModelPath(int modelId, FileTableManager ftm) {
        if (ftm == null) {
            return null;
        }
        int fileIndex = getNpcModelFileIndex(modelId);
        if (fileIndex < 0) {
            return null;
        }
        return ftm.getFilePath(fileIndex);
    }
}
