package com.example.asset.loader;

import com.example.asset.collision.CollisionMap;
import com.example.asset.table.FTable;
import com.example.asset.table.FileTableManager;
import com.example.asset.table.VTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AssetRepositoryTest {

    @Test
    @DisplayName("Should resolve zone paths and load zone collision through AssetRepository facade")
    void testAssetRepositoryPathResolutionAndLoading(@TempDir Path tempDir) throws Exception {
        InputStream vStream = getClass().getResourceAsStream("/dats/tables/VTABLE.DAT");
        InputStream fStream = getClass().getResourceAsStream("/dats/tables/FTABLE.DAT");
        InputStream zoneStream = getClass().getResourceAsStream("/dats/zones/south_san_doria_230.DAT");

        assumeTrue(vStream != null && fStream != null && zoneStream != null, "Fixtures missing; skipping test.");

        VTable vTable = VTable.load(new com.example.asset.reader.DatByteReader(vStream.readAllBytes()));
        FTable fTable = FTable.load(new com.example.asset.reader.DatByteReader(fStream.readAllBytes()));
        FileTableManager tableManager = new FileTableManager(vTable, fTable);

        AssetRepository repo = new AssetRepository(tempDir, tableManager);

        // Zone 230 -> fileId 330 -> ROM/1/31.DAT
        Optional<Path> zonePathOpt = repo.resolveZonePath(230);
        assertTrue(zonePathOpt.isPresent());
        assertTrue(zonePathOpt.get().endsWith("ROM/1/31.DAT"));

        // Copy South San d'Oria DAT to the expected ROM/1/31.DAT location in tempDir
        Path targetDatPath = zonePathOpt.get();
        Files.createDirectories(targetDatPath.getParent());
        Files.write(targetDatPath, zoneStream.readAllBytes());

        // Load authoritative CollisionMap through repository facade
        Optional<CollisionMap> collisionMapOpt = repo.loadZoneCollision(230);
        assertTrue(collisionMapOpt.isPresent(), "Repository should load South San d'Oria collision map");

        CollisionMap map = collisionMapOpt.get();
        assertTrue(map.allWorldSpaceTriangles().size() >= 100_000);
    }
}
