package com.example.asset.table;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.reader.DatByteReader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Combines VTable and FTable to resolve any FFXI fileId into its relative or absolute DAT path:
 * "ROM{version}/{folder}/{file}.DAT" (where version 1 has an empty string suffix).
 * Supports loading and bitwise OR combining across all 9 expansion table sets (VTABLE1..9 / FTABLE1..9).
 */
public final class FileTableManager {

    public static final int NUM_TABLES = 9;

    private byte[] vTableBytes;
    private byte[] fTableBytes;
    private int loadedVTables = 0;
    private int loadedFTables = 0;
    private Path ffxiBasePath = null;

    public FileTableManager() {
    }

    public FileTableManager(VTable vTable, FTable fTable) {
        Objects.requireNonNull(vTable, "vTable must not be null");
        Objects.requireNonNull(fTable, "fTable must not be null");
        this.vTableBytes = new byte[vTable.size()];
        for (int i = 0; i < vTable.size(); i++) {
            vTableBytes[i] = (byte) vTable.getVersion(i);
        }
        this.fTableBytes = new byte[fTable.size() * 2];
        for (int i = 0; i < fTable.size(); i++) {
            FTableEntry entry = fTable.getFileEntry(i);
            if (entry != null) {
                int word = (entry.folderNumber() << 7) | (entry.fileNumber() & 0x7F);
                fTableBytes[i * 2] = (byte) (word & 0xFF);
                fTableBytes[i * 2 + 1] = (byte) ((word >>> 8) & 0xFF);
            }
        }
        this.loadedVTables = 1;
        this.loadedFTables = 1;
    }

    public void setBasePath(Path basePath) {
        this.ffxiBasePath = basePath;
    }

    public Path getBasePath() {
        return ffxiBasePath;
    }

    public void loadFromDirectory(File rootDir) throws IOException {
        if (rootDir != null) {
            loadFromPath(rootDir.toPath());
        }
    }

    public void loadFromPath(Path rootPath) throws IOException {
        setBasePath(rootPath);
        for (int i = 1; i <= NUM_TABLES; i++) {
            loadTablePair(rootPath, i);
        }
    }

    private void loadTablePair(Path rootDir, int tableIndex) throws IOException {
        String prefix = (tableIndex == 1) ? "" : ("ROM" + tableIndex + "/");
        String postfix = (tableIndex == 1) ? "" : String.valueOf(tableIndex);

        Path vFile = FfxiPathConfig.resolveRelativeFile(rootDir, prefix + "VTABLE" + postfix + ".DAT");
        Path fFile = FfxiPathConfig.resolveRelativeFile(rootDir, prefix + "FTABLE" + postfix + ".DAT");

        if (vFile != null && Files.exists(vFile)) {
            byte[] vData = Files.readAllBytes(vFile);
            if (vTableBytes == null) {
                vTableBytes = Arrays.copyOf(vData, vData.length);
            } else {
                combineBytes(vData, vTableBytes);
            }
            loadedVTables++;
        }

        if (fFile != null && Files.exists(fFile)) {
            byte[] fData = Files.readAllBytes(fFile);
            if (fTableBytes == null) {
                fTableBytes = Arrays.copyOf(fData, fData.length);
            } else {
                combineBytes(fData, fTableBytes);
            }
            loadedFTables++;
        }
    }

    private static void combineBytes(byte[] source, byte[] destination) {
        int len = Math.min(source.length, destination.length);
        for (int i = 0; i < len; i++) {
            destination[i] = (byte) ((destination[i] & 0xFF) | (source[i] & 0xFF));
        }
    }

    public boolean isFullyLoaded() {
        return loadedVTables > 0 && loadedFTables > 0;
    }

    public int getLoadedVTables() {
        return loadedVTables;
    }

    public int getLoadedFTables() {
        return loadedFTables;
    }

    public Integer getVersion(int fileId) {
        if (vTableBytes == null || fileId < 0 || fileId >= vTableBytes.length) {
            return null;
        }
        return vTableBytes[fileId] & 0xFF;
    }

    public FTableEntry getFileLocation(int fileId) {
        if (fTableBytes == null || fileId < 0 || (fileId * 2 + 1) >= fTableBytes.length) {
            return null;
        }
        int b0 = fTableBytes[fileId * 2] & 0xFF;
        int b1 = fTableBytes[fileId * 2 + 1] & 0xFF;
        int word = b0 | (b1 << 8);
        int fileNumber = word & 0x7F;
        int folderNumber = word >>> 7;
        return new FTableEntry(folderNumber, fileNumber);
    }

    /**
     * Resolves the canonical relative path for a given fileId.
     * Returns null if the file does not exist (version 0) or is out of bounds.
     */
    public String resolvePath(int fileId) {
        Integer version = getVersion(fileId);
        if (version == null || version == 0) {
            return null;
        }

        FTableEntry entry = getFileLocation(fileId);
        if (entry == null) {
            return null;
        }

        String versionStr = (version == 1) ? "" : String.valueOf(version);
        return "ROM" + versionStr + "/" + entry.folderNumber() + "/" + entry.fileNumber() + ".DAT";
    }

    /**
     * Resolves the absolute path as a String (or relative if no base path is set).
     */
    public String getFilePath(int fileId) {
        String relPath = resolvePath(fileId);
        if (relPath == null) {
            return null;
        }
        if (ffxiBasePath != null) {
            Path resolved = FfxiPathConfig.resolveRelativeFile(ffxiBasePath, relPath);
            if (resolved != null) {
                return resolved.toAbsolutePath().toString();
            }
            return ffxiBasePath.resolve(relPath).toAbsolutePath().toString();
        }
        return relPath;
    }
}
