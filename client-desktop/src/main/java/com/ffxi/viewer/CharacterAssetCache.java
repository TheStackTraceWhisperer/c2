package com.ffxi.viewer;

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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches parsed {@link LoadedCharacter} DAT payloads and shared immutable GPU mesh buffers / texture atlases
 * per {@link RaceGenderConfig}. Each spawned actor receives its own {@link AuthenticCharacterInstance} with an
 * independent {@link AuthenticLocomotionController} animation state while sharing the underlying VAO/VBOs and
 * GL textures, avoiding render-thread DAT reload stalls and 600x VRAM duplication across remote actors.
 */
@Slf4j
public final class CharacterAssetCache implements AutoCloseable {

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

    private final Map<RaceGenderConfig, LoadedCharacter> cpuCache = new ConcurrentHashMap<>();
    private final Map<RaceGenderConfig, SharedGpuAssets> gpuCache = new EnumMap<>(RaceGenderConfig.class);

    /**
     * Loads or retrieves the cached CPU {@link LoadedCharacter} for {@code race}. Safe to call off the GL thread.
     */
    public LoadedCharacter getOrLoadCharacter(Path ffxiRoot, RaceGenderConfig race) throws IOException {
        LoadedCharacter existing = cpuCache.get(race);
        if (existing != null) {
            return existing;
        }
        LoadedCharacter loaded = CharacterModelLoader.loadCharacter(ffxiRoot, race);
        LoadedCharacter prev = cpuCache.putIfAbsent(race, loaded);
        return prev != null ? prev : loaded;
    }

    /**
     * Acquires a new {@link AuthenticCharacterInstance} backed by shared GPU mesh buffers and textures
     * for {@code race}, with its own independent per-actor {@link AuthenticLocomotionController}.
     * Must be called on the OpenGL render thread.
     */
    public AuthenticCharacterInstance acquireInstance(Path ffxiRoot, RaceGenderConfig race) throws IOException {
        SharedGpuAssets shared = gpuCache.get(race);
        if (shared == null) {
            LoadedCharacter character = getOrLoadCharacter(ffxiRoot, race);
            List<CharacterMeshBuffer> meshBuffers = new ArrayList<>(character.meshes().size());
            for (SkeletonMeshMesh mesh : character.meshes()) {
                meshBuffers.add(new CharacterMeshBuffer(mesh));
            }
            CharacterTextureLoader textureLoader = new CharacterTextureLoader(character.textures());
            shared = new SharedGpuAssets(character, List.copyOf(meshBuffers), textureLoader);
            gpuCache.put(race, shared);
            log.debug("Uploaded shared GPU character assets for {} ({} meshes)", race, meshBuffers.size());
        }
        return new AuthenticCharacterInstance(
                shared.character(),
                shared.meshBuffers(),
                shared.textureLoader(),
                false
        );
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
