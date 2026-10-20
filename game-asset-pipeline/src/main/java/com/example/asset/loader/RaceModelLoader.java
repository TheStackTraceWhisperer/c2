package com.example.asset.loader;

import com.example.asset.skeleton.CompositeCharacterModel;
import com.example.asset.skeleton.Skeleton;
import com.example.asset.skeleton.SkeletonAnimation;
import com.example.asset.skeleton.SkinnedMesh;
import com.example.asset.table.FileTableManager;
import com.example.asset.texture.DecodedTexture;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads authentic character models and skeletal animations directly from game ROM files.
 * Supports all playable races (Hume Male/Female, Elvaan Male/Female, TaruTaru Male/Female, Mithra, Galka)
 * and authentic NPC models.
 */
@Slf4j
public class RaceModelLoader {

    public enum CharacterRace {
        HUME_MALE(1, "Hume Male", "ROM/27/82.DAT", "ROM/27/87.DAT", "ROM/28/7.DAT", "ROM/28/52.DAT", "ROM/28/84.DAT", "ROM/28/116.DAT"),
        HUME_FEMALE(2, "Hume Female", "ROM/32/58.DAT", "ROM/32/63.DAT", "ROM/32/111.DAT", "ROM/33/28.DAT", "ROM/33/60.DAT", "ROM/33/92.DAT"),
        ELVAAN_MALE(3, "Elvaan Male", "ROM/37/31.DAT", "ROM/37/36.DAT", "ROM/37/83.DAT", "ROM/37/127.DAT", "ROM/38/30.DAT", "ROM/38/61.DAT"),
        ELVAAN_FEMALE(4, "Elvaan Female", "ROM/42/4.DAT", "ROM/42/9.DAT", "ROM/42/56.DAT", "ROM/42/100.DAT", "ROM/43/3.DAT", "ROM/43/34.DAT"),
        TARUTARU_MALE(5, "TaruTaru Male", "ROM/46/93.DAT", "ROM/46/98.DAT", "ROM/47/17.DAT", "ROM/47/61.DAT", "ROM/47/92.DAT", "ROM/47/123.DAT"),
        TARUTARU_FEMALE(6, "TaruTaru Female", "ROM/46/93.DAT", "ROM/46/98.DAT", "ROM/47/17.DAT", "ROM/47/61.DAT", "ROM/47/92.DAT", "ROM/47/123.DAT"),
        MITHRA(7, "Mithra", "ROM/51/89.DAT", "ROM/51/94.DAT", "ROM/52/13.DAT", "ROM/52/57.DAT", "ROM/52/88.DAT", "ROM/52/119.DAT"),
        GALKA(8, "Galka", "ROM/56/59.DAT", "ROM/56/64.DAT", "ROM/56/111.DAT", "ROM/57/27.DAT", "ROM/57/58.DAT", "ROM/57/89.DAT");

        private final int id;
        private final String displayName;
        private final String baseDatPath;
        private final String headDatPath;
        private final String bodyDatPath;
        private final String handsDatPath;
        private final String legsDatPath;
        private final String feetDatPath;

        CharacterRace(int id, String displayName, String baseDatPath, String headDatPath,
                      String bodyDatPath, String handsDatPath, String legsDatPath, String feetDatPath) {
            this.id = id;
            this.displayName = displayName;
            this.baseDatPath = baseDatPath;
            this.headDatPath = headDatPath;
            this.bodyDatPath = bodyDatPath;
            this.handsDatPath = handsDatPath;
            this.legsDatPath = legsDatPath;
            this.feetDatPath = feetDatPath;
        }

        public int id() { return id; }
        public String displayName() { return displayName; }
        public String baseDatPath() { return baseDatPath; }
        public String headDatPath() { return headDatPath; }
        public String bodyDatPath() { return bodyDatPath; }
        public String handsDatPath() { return handsDatPath; }
        public String legsDatPath() { return legsDatPath; }
        public String feetDatPath() { return feetDatPath; }

        public static CharacterRace fromId(int id) {
            for (CharacterRace race : values()) {
                if (race.id == id) {
                    return race;
                }
            }
            return HUME_MALE;
        }
    }

    private final RomAssetLoader romLoader;
    private final Map<CharacterRace, CompositeCharacterModel> modelCache = new HashMap<>();
    private final Map<CharacterRace, Map<String, SkeletonAnimation>> animCache = new HashMap<>();

    public RaceModelLoader() {
        this(RomAssetLoader.getInstance());
    }

    public RaceModelLoader(RomAssetLoader romLoader) {
        this.romLoader = romLoader;
    }

    /**
     * Loads the authentic character model for a given race, assembling skeleton, all default
     * equipment pieces (head, body, hands, legs, feet), and authentic decoded textures.
     */
    public synchronized CompositeCharacterModel loadRaceModel(CharacterRace race) {
        if (modelCache.containsKey(race)) {
            return modelCache.get(race);
        }

        log.info("Loading authentic character model for race: {}", race.displayName());

        // 1. Skeleton
        Skeleton skeleton = romLoader.loadSkeleton(race.baseDatPath()).orElse(null);

        // 2. Equipment slots & textures
        Map<Integer, SkinnedMesh> slots = new HashMap<>();
        Map<String, DecodedTexture> textures = new HashMap<>();

        loadSlot(race.headDatPath(), CompositeCharacterModel.SLOT_HEAD, slots, textures);
        loadSlot(race.bodyDatPath(), CompositeCharacterModel.SLOT_BODY, slots, textures);
        loadSlot(race.handsDatPath(), CompositeCharacterModel.SLOT_HANDS, slots, textures);
        loadSlot(race.legsDatPath(), CompositeCharacterModel.SLOT_LEGS, slots, textures);
        loadSlot(race.feetDatPath(), CompositeCharacterModel.SLOT_FEET, slots, textures);

        CompositeCharacterModel composite = new CompositeCharacterModel(skeleton, slots, textures);
        modelCache.put(race, composite);
        return composite;
    }

    /**
     * Loads authentic animations for a character race (e.g. "idl0", "wlk0", "run0").
     */
    public synchronized Map<String, SkeletonAnimation> loadRaceAnimations(CharacterRace race) {
        if (animCache.containsKey(race)) {
            return animCache.get(race);
        }

        Map<String, SkeletonAnimation> anims = romLoader.loadAnimations(race.baseDatPath());
        animCache.put(race, anims);
        return anims;
    }

    /**
     * Loads an authentic NPC model by model ID or falls back to an authentic race actor model.
     */
    public synchronized CompositeCharacterModel loadNpcModel(int modelId, FileTableManager tableManager) {
        // If high byte is non-zero, this is a PC race model (Type 1 NPC)
        int raceId = (modelId >> 8) & 0xFF;
        if (raceId >= 1 && raceId <= 8) {
            return loadRaceModel(CharacterRace.fromId(raceId));
        }

        // Town NPC / Guard model
        String npcDat = "ROM/3/116.DAT";
        if (tableManager != null && modelId > 0 && modelId < 0x5DC) {
            int fileId = 0x514 + modelId;
            String resolved = tableManager.resolvePath(fileId);
            if (resolved != null) {
                npcDat = resolved;
            }
        }

        log.info("Loading authentic NPC model (id={}, dat={})", modelId, npcDat);
        Optional<SkinnedMesh> meshOpt = romLoader.loadSkinnedMesh(npcDat);
        Map<String, DecodedTexture> textures = romLoader.loadTextures(npcDat);

        // Town guard / NPC models use the Elvaan Male or Hume Male rig
        Skeleton rig = loadRaceModel(CharacterRace.ELVAAN_MALE).skeleton();

        Map<Integer, SkinnedMesh> slots = new HashMap<>();
        meshOpt.ifPresent(skinnedMesh -> slots.put(CompositeCharacterModel.SLOT_BODY, skinnedMesh));

        // Also merge base race textures
        Map<String, DecodedTexture> mergedTextures = new HashMap<>(loadRaceModel(CharacterRace.ELVAAN_MALE).textures());
        mergedTextures.putAll(textures);

        return new CompositeCharacterModel(rig, slots, mergedTextures);
    }

    private void loadSlot(String datPath, int slotId, Map<Integer, SkinnedMesh> slots, Map<String, DecodedTexture> textures) {
        if (datPath == null || datPath.isBlank()) {
            return;
        }
        Optional<SkinnedMesh> mesh = romLoader.loadSkinnedMesh(datPath);
        mesh.ifPresent(m -> slots.put(slotId, m));
        textures.putAll(romLoader.loadTextures(datPath));
    }
}
