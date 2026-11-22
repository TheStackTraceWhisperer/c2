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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caches parsed {@link LoadedCharacter} DAT payloads and shared immutable GPU mesh buffers / texture atlases
 * per {@link AppearanceCacheKey} with reference-counted LRU eviction. Each spawned actor receives its own
 * {@link AuthenticCharacterInstance} with an independent {@link AuthenticLocomotionController} animation state
 * while sharing the underlying VAO/VBOs and GL textures, avoiding render-thread DAT reload stalls and VRAM
 * duplication across identical guards and NPCs.
 */
@Slf4j
public final class CharacterAssetCache implements AutoCloseable {

    public static final int DEFAULT_MAX_CPU_ENTRIES = 256;
    public static final int DEFAULT_MAX_GPU_ENTRIES = 128;

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
            if (textureLoader != null) {
                try {
                    textureLoader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static final class GpuCacheEntry {
        final LoadedCharacter character;
        final SharedGpuAssets assets;
        int activeRefs = 0;

        GpuCacheEntry(LoadedCharacter character, SharedGpuAssets assets) {
            this.character = character;
            this.assets = assets;
        }
    }

    private final int maxCpuEntries;
    private final int maxGpuEntries;
    private final ReentrantLock cpuLock = new ReentrantLock();
    private final ReentrantLock gpuLock = new ReentrantLock();
    private final Map<AppearanceCacheKey, LoadedCharacter> cpuCache = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<AppearanceCacheKey, GpuCacheEntry> gpuCache = new LinkedHashMap<>(64, 0.75f, true);

    public CharacterAssetCache() {
        this(DEFAULT_MAX_CPU_ENTRIES, DEFAULT_MAX_GPU_ENTRIES);
    }

    public CharacterAssetCache(int maxCpuEntries, int maxGpuEntries) {
        this.maxCpuEntries = Math.max(1, maxCpuEntries);
        this.maxGpuEntries = Math.max(1, maxGpuEntries);
    }

    /**
     * Loads or retrieves the cached CPU {@link LoadedCharacter} for {@code appearance}. Safe to call off the GL thread.
     */
    public LoadedCharacter getOrLoadActor(Path ffxiRoot, ActorAppearance appearance) throws IOException {
        AppearanceCacheKey key = AppearanceCacheKey.from(appearance);
        cpuLock.lock();
        try {
            LoadedCharacter existing = cpuCache.get(key);
            if (existing != null) {
                return existing;
            }
        } finally {
            cpuLock.unlock();
        }

        LoadedCharacter loaded = CharacterModelLoader.loadActor(ffxiRoot, appearance);

        cpuLock.lock();
        try {
            LoadedCharacter existing = cpuCache.get(key);
            if (existing != null) {
                return existing;
            }
            cpuCache.put(key, loaded);
            trimCpuCacheLocked();
            return loaded;
        } finally {
            cpuLock.unlock();
        }
    }

    /**
     * Acquires a new {@link AuthenticCharacterInstance} backed by shared GPU mesh buffers and textures
     * for {@code appearance}, with its own independent per-actor {@link AuthenticLocomotionController}.
     * Must be called on the OpenGL render thread.
     */
    public AuthenticCharacterInstance acquireInstance(Path ffxiRoot, ActorAppearance appearance) throws IOException {
        AppearanceCacheKey key = AppearanceCacheKey.from(appearance);
        List<SharedGpuAssets> toClose = new ArrayList<>();
        GpuCacheEntry entry;

        gpuLock.lock();
        try {
            entry = gpuCache.get(key);
        } finally {
            gpuLock.unlock();
        }

        if (entry == null) {
            LoadedCharacter character = getOrLoadActor(ffxiRoot, appearance);
            List<CharacterMeshBuffer> meshBuffers = new ArrayList<>(character.meshes().size());
            for (SkeletonMeshMesh mesh : character.meshes()) {
                meshBuffers.add(new CharacterMeshBuffer(mesh));
            }
            CharacterTextureLoader textureLoader = new CharacterTextureLoader(character.textures());
            SharedGpuAssets created = new SharedGpuAssets(character, List.copyOf(meshBuffers), textureLoader);

            gpuLock.lock();
            try {
                entry = gpuCache.get(key);
                if (entry == null) {
                    entry = new GpuCacheEntry(character, created);
                    gpuCache.put(key, entry);
                    log.debug("Uploaded shared GPU character assets for {} ({} meshes)", key, meshBuffers.size());
                } else {
                    toClose.add(created);
                }
            } finally {
                gpuLock.unlock();
            }
        }

        final GpuCacheEntry boundEntry = entry;
        gpuLock.lock();
        try {
            boundEntry.activeRefs++;
            collectLruEvictionsLocked(toClose);
        } finally {
            gpuLock.unlock();
        }

        for (SharedGpuAssets unused : toClose) {
            unused.close();
        }

        return new AuthenticCharacterInstance(
                boundEntry.character,
                boundEntry.assets.meshBuffers(),
                boundEntry.assets.textureLoader(),
                false,
                () -> releaseGpuReference(key, boundEntry)
        );
    }

    AuthenticCharacterInstance acquireSharedForKey(
            AppearanceCacheKey key,
            LoadedCharacter character,
            List<CharacterMeshBuffer> meshBuffers,
            CharacterTextureLoader textureLoader
    ) {
        cpuLock.lock();
        try {
            cpuCache.put(key, character);
            trimCpuCacheLocked();
        } finally {
            cpuLock.unlock();
        }

        List<SharedGpuAssets> toClose = new ArrayList<>();
        GpuCacheEntry entry;
        gpuLock.lock();
        try {
            entry = gpuCache.get(key);
            if (entry == null) {
                SharedGpuAssets shared = new SharedGpuAssets(
                        character,
                        meshBuffers != null ? List.copyOf(meshBuffers) : List.of(),
                        textureLoader
                );
                entry = new GpuCacheEntry(character, shared);
                gpuCache.put(key, entry);
            }
            entry.activeRefs++;
            collectLruEvictionsLocked(toClose);
        } finally {
            gpuLock.unlock();
        }

        for (SharedGpuAssets unused : toClose) {
            unused.close();
        }

        final GpuCacheEntry boundEntry = entry;
        return new AuthenticCharacterInstance(
                boundEntry.character,
                boundEntry.assets.meshBuffers(),
                boundEntry.assets.textureLoader(),
                false,
                () -> releaseGpuReference(key, boundEntry)
        );
    }

    private void releaseGpuReference(AppearanceCacheKey key, GpuCacheEntry entry) {
        gpuLock.lock();
        try {
            if (entry.activeRefs > 0) {
                entry.activeRefs--;
            }
        } finally {
            gpuLock.unlock();
        }
    }

    private void collectLruEvictionsLocked(List<SharedGpuAssets> toClose) {
        if (gpuCache.size() <= maxGpuEntries) {
            return;
        }
        Iterator<Map.Entry<AppearanceCacheKey, GpuCacheEntry>> it = gpuCache.entrySet().iterator();
        while (gpuCache.size() > maxGpuEntries && it.hasNext()) {
            GpuCacheEntry candidate = it.next().getValue();
            if (candidate.activeRefs <= 0) {
                it.remove();
                if (candidate.assets != null) {
                    toClose.add(candidate.assets);
                }
            }
        }
    }

    private void trimCpuCacheLocked() {
        Iterator<Map.Entry<AppearanceCacheKey, LoadedCharacter>> it = cpuCache.entrySet().iterator();
        while (cpuCache.size() > maxCpuEntries && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /**
     * Evicts and disposes all cached GPU bundles with zero active actor references.
     * Must be called on the OpenGL render thread.
     */
    public int evictUnusedGpuAssets() {
        List<SharedGpuAssets> toClose = new ArrayList<>();
        gpuLock.lock();
        try {
            Iterator<Map.Entry<AppearanceCacheKey, GpuCacheEntry>> it = gpuCache.entrySet().iterator();
            while (it.hasNext()) {
                GpuCacheEntry candidate = it.next().getValue();
                if (candidate.activeRefs <= 0) {
                    it.remove();
                    if (candidate.assets != null) {
                        toClose.add(candidate.assets);
                    }
                }
            }
        } finally {
            gpuLock.unlock();
        }

        for (SharedGpuAssets assets : toClose) {
            assets.close();
        }
        return toClose.size();
    }

    /**
     * Evicts unused GPU assets and drops CPU cache entries not pinned by any remaining GPU bundle.
     * Must be called on the OpenGL render thread (for example on zone transitions).
     */
    public int evictUnusedAssets() {
        int evictedGpu = evictUnusedGpuAssets();
        Set<AppearanceCacheKey> pinnedKeys;
        gpuLock.lock();
        try {
            pinnedKeys = new HashSet<>(gpuCache.keySet());
        } finally {
            gpuLock.unlock();
        }

        cpuLock.lock();
        try {
            cpuCache.keySet().retainAll(pinnedKeys);
        } finally {
            cpuLock.unlock();
        }
        return evictedGpu;
    }

    public int activeGpuReferenceCount() {
        gpuLock.lock();
        try {
            int total = 0;
            for (GpuCacheEntry entry : gpuCache.values()) {
                total += entry.activeRefs;
            }
            return total;
        } finally {
            gpuLock.unlock();
        }
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
        cpuLock.lock();
        try {
            return cpuCache.size();
        } finally {
            cpuLock.unlock();
        }
    }

    public int cachedGpuRaceCount() {
        gpuLock.lock();
        try {
            return gpuCache.size();
        } finally {
            gpuLock.unlock();
        }
    }

    @Override
    public void close() {
        List<SharedGpuAssets> toClose = new ArrayList<>();
        gpuLock.lock();
        try {
            for (GpuCacheEntry entry : gpuCache.values()) {
                if (entry.assets != null) {
                    toClose.add(entry.assets);
                }
            }
            gpuCache.clear();
        } finally {
            gpuLock.unlock();
        }

        cpuLock.lock();
        try {
            cpuCache.clear();
        } finally {
            cpuLock.unlock();
        }

        for (SharedGpuAssets assets : toClose) {
            assets.close();
        }
    }
}
