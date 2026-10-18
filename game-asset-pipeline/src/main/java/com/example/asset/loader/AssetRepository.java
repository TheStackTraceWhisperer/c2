package com.example.asset.loader;

import com.example.asset.collision.CollisionMap;
import com.example.asset.decoder.DatBlockDecoder;
import com.example.asset.parser.ZoneDefParser;
import com.example.asset.reader.DatByteReader;
import com.example.asset.table.FileTableManager;
import com.example.asset.table.ZonePathResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * High-level facade for resolving, reading, and parsing game assets from an asset root directory.
 */
public final class AssetRepository {

    private final Path assetRootDir;
    private final FileTableManager fileTableManager;
    private final ZonePathResolver zonePathResolver;

    public AssetRepository(Path assetRootDir, FileTableManager fileTableManager) {
        this.assetRootDir = Objects.requireNonNull(assetRootDir, "assetRootDir must not be null");
        this.fileTableManager = Objects.requireNonNull(fileTableManager, "fileTableManager must not be null");
        this.zonePathResolver = new ZonePathResolver(fileTableManager);
    }

    public Path assetRootDir() {
        return assetRootDir;
    }

    public FileTableManager fileTableManager() {
        return fileTableManager;
    }

    public ZonePathResolver zonePathResolver() {
        return zonePathResolver;
    }

    /**
     * Resolves the on-disk Path for a given canonical relative DAT path (e.g. "ROM/1/31.DAT").
     */
    public Path resolvePath(String relativeDatPath) {
        return assetRootDir.resolve(relativeDatPath);
    }

    /**
     * Resolves the on-disk Path for a given file table ID.
     */
    public Optional<Path> resolveFilePath(int fileId) {
        String relativePath = fileTableManager.resolvePath(fileId);
        if (relativePath == null) {
            return Optional.empty();
        }
        return Optional.of(resolvePath(relativePath));
    }

    /**
     * Resolves the on-disk Path for a given zone ID.
     */
    public Optional<Path> resolveZonePath(int zoneId) {
        int fileId = ZonePathResolver.toFileId(zoneId);
        return resolveFilePath(fileId);
    }

    /**
     * Reads and decodes a DAT file from disk. If the file is bit-rotated, it applies DatBlockDecoder.
     */
    public DatByteReader readDat(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new DatByteReader(bytes);
    }

    /**
     * Loads the authoritative 3D CollisionMap for a zone directly from disk.
     */
    public Optional<CollisionMap> loadZoneCollision(int zoneId) throws IOException {
        Optional<Path> zonePathOpt = resolveZonePath(zoneId);
        if (zonePathOpt.isEmpty()) {
            return Optional.empty();
        }

        Path path = zonePathOpt.get();
        if (!Files.exists(path)) {
            return Optional.empty();
        }

        DatByteReader reader = readDat(path);
        return ZoneDefParser.parseFromDat(reader);
    }
}
