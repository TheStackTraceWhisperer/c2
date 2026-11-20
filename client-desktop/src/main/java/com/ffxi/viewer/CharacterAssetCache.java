package com.ffxi.viewer;

import com.example.zone.dto.ActorAppearance;
import com.example.zone.dto.ActorLookType;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.renderer.CharacterMeshBuffer;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches parsed {@link LoadedCharacter} DAT payloads and shared immutable GPU mesh buffers / texture atlases
 * per {@link AppearanceCacheKey}. Each spawned actor receives its own {@link AuthenticCharacterInstance} with an
 * independent {@link AuthenticLocomotionController} animation state while sharing the underlying VAO/VBOs and
 * GL textures, avoiding render-thread DAT reload stalls and VRAM duplication across identical guards and NPCs.
 */
@Slf4j
public final class CharacterAssetCache implements AutoCloseable {

    public record AppearanceCacheKey(
            ActorLookType lookType,
            int raceId,
            int faceId,
            int head,
            int body,
            int hands,
            int legs,
            int feet,
            int mainWeapon,
            int subShield,
            int ranged,
            int modelId,
            int size
    ) {
        /**
         * Normalizes an {@link ActorAppearance} into a canonical cache key, excluding per-entity state fields
         * ({@code animationSub}, {@code entityFlags}, {@code namePrefix}) so identical guards/NPCs share one
         * CPU and GPU asset bundle.
         */
        public static AppearanceCacheKey from(ActorAppearance appearance) {
            if (appearance == null || appearance.lookType() == ActorLookType.NONE) {
                return from(ActorAppearance.forPlayer(RaceGenderConfig.HumeM.index, 0, 1));
            }
            return switch (appearance.lookType()) {
                case EQUIPPED -> new AppearanceCacheKey(
                        ActorLookType.EQUIPPED,
                        RaceGenderConfig.fromRaceIdOrDefault(appearance.raceId(), RaceGenderConfig.HumeM).index,
                        ActorAppearance.stripSlotPrefix(appearance.faceId()),
                        ActorAppearance.stripSlotPrefix(appearance.head()),
                        ActorAppearance.stripSlotPrefix(appearance.body()),
                        ActorAppearance.stripSlotPrefix(appearance.hands()),
                        ActorAppearance.stripSlotPrefix(appearance.legs()),
                        ActorAppearance.stripSlotPrefix(appearance.feet()),
                        ActorAppearance.stripSlotPrefix(appearance.mainWeapon()),
                        ActorAppearance.stripSlotPrefix(appearance.subShield()),
                        ActorAppearance.stripSlotPrefix(appearance.ranged()),
                        0,
                        appearance.size()
                );
                case CHOCOBO -> new AppearanceCacheKey(
                        ActorLookType.CHOCOBO,
                        RaceGenderConfig.fromRaceIdOrDefault(appearance.raceId(), RaceGenderConfig.Chocobo).index,
                        0,
                        ActorAppearance.stripSlotPrefix(appearance.head()),
                        ActorAppearance.stripSlotPrefix(appearance.body()),
                        ActorAppearance.stripSlotPrefix(appearance.hands()),
                        ActorAppearance.stripSlotPrefix(appearance.legs()),
                        ActorAppearance.stripSlotPrefix(appearance.feet()),
                        0,
                        0,
                        0,
                        0,
                        appearance.size()
                );
                case STANDARD, AUTOMATON -> new AppearanceCacheKey(
                        appearance.lookType(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        appearance.modelId(),
                        appearance.size()
                );
                case NONE -> from(ActorAppearance.forPlayer(RaceGenderConfig.HumeM.index, 0, 1));
            };
        }
    }

    public record SharedGpuAssets(
            LoadedCharacter character,
            List<CharacterMeshBuffer> meshBuffers,
            CharacterTextureLoader textureLoader
    ) implements AutoCloseable {
        @Override
        public void close() {
            for (CharacterMeshBuffer mb : meshBuffers) {
                try {
                    mb.close();
                } catch (Exception ignored) {
                }
            }
            try {
                textureLoader.close();
            } catch (Exception ignored) {
            }
        }
    }

    private final Map<AppearanceCacheKey, LoadedCharacter> cpuCache = new ConcurrentHashMap<>();
    private final Map<AppearanceCacheKey, SharedGpuAssets> gpuCache = new LinkedHashMap<>();

    /**
     * Loads or retrieves the cached CPU {@link LoadedCharacter} for {@code appearance}. Safe to call off the GL thread.
     */
    public LoadedCharacter getOrLoadActor(Path ffxiRoot, ActorAppearance appearance) throws IOException {
        AppearanceCacheKey key = AppearanceCacheKey.from(appearance);
        LoadedCharacter existing = cpuCache.get(key);
        if (existing != null) {
            return existing;
        }
        LoadedCharacter loaded = CharacterModelLoader.loadActor(ffxiRoot, appearance);
        LoadedCharacter prev = cpuCache.putIfAbsent(key, loaded);
        return prev != null ? prev : loaded;
    }

    /**
     * Acquires a new {@link AuthenticCharacterInstance} backed by shared GPU mesh buffers and textures
     * for {@code appearance}, with its own independent per-actor {@link AuthenticLocomotionController}.
     * Must be called on the OpenGL render thread.
     */
    public AuthenticCharacterInstance acquireInstance(Path ffxiRoot, ActorAppearance appearance) throws IOException {
        AppearanceCacheKey key = AppearanceCacheKey.from(appearance);
        SharedGpuAssets shared = gpuCache.get(key);
        if (shared == null) {
            LoadedCharacter character = getOrLoadActor(ffxiRoot, appearance);
            List<CharacterMeshBuffer> meshBuffers = new ArrayList<>(character.meshes().size());
            for (SkeletonMeshMesh mesh : character.meshes()) {
                meshBuffers.add(new CharacterMeshBuffer(mesh));
            }
            CharacterTextureLoader textureLoader = new CharacterTextureLoader(character.textures());
            shared = new SharedGpuAssets(character, List.copyOf(meshBuffers), textureLoader);
            gpuCache.put(key, shared);
            log.debug("Uploaded shared GPU character assets for {} ({} meshes)", key, meshBuffers.size());
        }
        return new AuthenticCharacterInstance(
                shared.character(),
                shared.meshBuffers(),
                shared.textureLoader(),
                false
        );
    }

    /**
     * Loads or retrieves the cached CPU {@link LoadedCharacter} for {@code race}. Safe to call off the GL thread.
     */
    public LoadedCharacter getOrLoadCharacter(Path ffxiRoot, RaceGenderConfig race) throws IOException {
        RaceGenderConfig resolved = race != null ? race : RaceGenderConfig.HumeM;
        return getOrLoadActor(ffxiRoot, ActorAppearance.forPlayer(resolved.index, 0, 1));
    }

    /**
     * Acquires a new {@link AuthenticCharacterInstance} backed by shared GPU mesh buffers and textures
     * for {@code race}, with its own independent per-actor {@link AuthenticLocomotionController}.
     * Must be called on the OpenGL render thread.
     */
    public AuthenticCharacterInstance acquireInstance(Path ffxiRoot, RaceGenderConfig race) throws IOException {
        RaceGenderConfig resolved = race != null ? race : RaceGenderConfig.HumeM;
        return acquireInstance(ffxiRoot, ActorAppearance.forPlayer(resolved.index, 0, 1));
    }

    public int cachedCpuRaceCount() {
        return cpuCache.size();
    }

    public int cachedGpuRaceCount() {
        return gpuCache.size();
    }

    @Override
    public void close() {
        for (SharedGpuAssets assets : gpuCache.values()) {
            assets.close();
        }
        gpuCache.clear();
        cpuCache.clear();
    }
}
