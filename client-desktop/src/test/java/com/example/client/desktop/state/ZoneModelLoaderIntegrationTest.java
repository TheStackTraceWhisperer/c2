package com.example.client.desktop.state;

import com.example.asset.config.FfxiPathConfig;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.model.ZoneModelLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ZoneModelLoaderIntegrationTest {

    @Test
    @DisplayName("Verify ZoneModelLoader and CharacterModelLoader load authentic assets from retail FFXI")
    void testLoadZoneAndCharacter() throws Exception {
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        assumeTrue(Files.isDirectory(ffxiRoot), "FFXI retail install not found at " + ffxiRoot);

        // 1. Zone
        LoadedZone zone = ZoneModelLoader.loadZone(ffxiRoot, 230);
        assertNotNull(zone);
        System.out.printf("LoadedZone 230: %s, %d chunk instances, %d textures%n",
                zone.zoneName(), zone.chunkInstances().size(), zone.textureSources().size());
        assertFalse(zone.chunkInstances().isEmpty());

        // 2. Character
        LoadedCharacter character = CharacterModelLoader.loadCharacter(ffxiRoot, RaceGenderConfig.HumeM);
        assertNotNull(character);
        System.out.printf("LoadedCharacter: %d meshes, %d textures, %d slot0 anims, %d slot1 anims%n",
                character.meshes().size(), character.textures().size(),
                character.slot0Animations().size(), character.slot1Animations().size());
        assertFalse(character.meshes().isEmpty());
    }
}
