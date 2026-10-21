package com.ffxi.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileTableManager {
    public static final int NUM_TABLES = 9;

    private VTable vTable;
    private int loadedVTables = 0;

    private FTable fTable;
    private int loadedFTables = 0;

    private Path ffxiBasePath = null;

    public void setBasePath(String basePath) {
        this.ffxiBasePath = (basePath != null && !basePath.trim().isEmpty()) ? Paths.get(basePath) : null;
    }

    public void setBasePath(Path basePath) {
        this.ffxiBasePath = basePath;
    }

    public String getBasePath() {
        return ffxiBasePath != null ? ffxiBasePath.toString() : "";
    }

    public Path getBasePathNio() {
        return ffxiBasePath;
    }

    public Path getFileNioPath(Integer fileId) {
        if (fileId == null || vTable == null || fTable == null) return null;

        Integer versionNumber = vTable.getVersion(fileId);
        if (versionNumber == null || versionNumber == 0) return null;

        String versionString = (versionNumber == 1) ? "" : String.valueOf(versionNumber);
        FTable.FileLocation loc = fTable.getFile(fileId);

        String relPath = String.format("ROM%s/%d/%d.DAT", versionString, loc.folderNumber, loc.fileNumber);
        if (ffxiBasePath != null) {
            Path resolved = FileUtil.resolveCaseInsensitive(ffxiBasePath, relPath);
            if (resolved != null) {
                return resolved;
            }
            return ffxiBasePath.resolve(relPath);
        }
        return Paths.get(relPath);
    }

    public String getFilePath(Integer fileId) {
        Path path = getFileNioPath(fileId);
        return path != null ? path.toString() : null;
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

        Path vFile = FileUtil.resolveCaseInsensitive(rootDir, prefix + "VTABLE" + postfix + ".DAT");
        Path fFile = FileUtil.resolveCaseInsensitive(rootDir, prefix + "FTABLE" + postfix + ".DAT");

        if (vFile != null && Files.exists(vFile)) {
            byte[] vData = Files.readAllBytes(vFile);
            String fileName = vFile.getFileName() != null ? vFile.getFileName().toString() : "VTABLE.DAT";
            ByteReader reader = new ByteReader(vData, fileName);
            if (vTable == null) {
                vTable = new VTable(reader);
            } else {
                combine(reader, vTable.getByteReader());
            }
            loadedVTables++;
        }

        if (fFile != null && Files.exists(fFile)) {
            byte[] fData = Files.readAllBytes(fFile);
            String fileName = fFile.getFileName() != null ? fFile.getFileName().toString() : "FTABLE.DAT";
            ByteReader reader = new ByteReader(fData, fileName);
            if (fTable == null) {
                fTable = new FTable(reader);
            } else {
                combine(reader, fTable.getByteReader());
            }
            loadedFTables++;
        }
    }

    public void loadRaw(byte[] vTableBytes, byte[] fTableBytes) {
        ByteReader vReader = new ByteReader(vTableBytes, "VTABLE.DAT");
        if (vTable == null) {
            vTable = new VTable(vReader);
        } else {
            combine(vReader, vTable.getByteReader());
        }
        loadedVTables++;

        ByteReader fReader = new ByteReader(fTableBytes, "FTABLE.DAT");
        if (fTable == null) {
            fTable = new FTable(fReader);
        } else {
            combine(fReader, fTable.getByteReader());
        }
        loadedFTables++;
    }

    private void combine(ByteReader source, ByteReader destination) {
        byte[] src = source.getBytes();
        byte[] dest = destination.getBytes();
        int len = Math.min(src.length, dest.length);
        for (int i = 0; i < len; i++) {
            dest[i] = (byte) ((dest[i] & 0xFF) | (src[i] & 0xFF));
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
}
