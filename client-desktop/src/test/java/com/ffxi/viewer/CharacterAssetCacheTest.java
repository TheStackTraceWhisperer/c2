package com.ffxi.viewer;

import com.example.zone.dto.ActorAppearance;
import com.ffxi.model.SkeletonResource;
import com.ffxi.viewer.model.LoadedCharacter;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterAssetCacheTest {

    private static LoadedCharacter dummyCharacter() {
        return new LoadedCharacter(
                new SkeletonResource("skel", "29", 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                1.0f,
                0.8f,
                new Vector3f(),
                Map.of()
        );
    }

    @Test
    @DisplayName("LRU capacity evicts unreferenced GPU bundles while preserving active actor bundles")
    void testLruEvictionRespectsActiveReferences() {
        CharacterAssetCache cache = new CharacterAssetCache(4, 2);
        LoadedCharacter dummy = dummyCharacter();

        var key1 = CharacterAssetCache.AppearanceCacheKey.from(ActorAppearance.forStandardModel(1, 0, 0, 0));
        var key2 = CharacterAssetCache.AppearanceCacheKey.from(ActorAppearance.forStandardModel(2, 0, 0, 0));
        var key3 = CharacterAssetCache.AppearanceCacheKey.from(ActorAppearance.forStandardModel(3, 0, 0, 0));

        AuthenticCharacterInstance inst1 = cache.acquireSharedForKey(key1, dummy, List.of(), null);
        AuthenticCharacterInstance inst2 = cache.acquireSharedForKey(key2, dummy, List.of(), null);
        assertEquals(2, cache.cachedGpuRaceCount());
        assertEquals(2, cache.activeGpuReferenceCount());

        // Release inst1 so key1 has 0 active references and becomes evictable LRU candidate
        inst1.close();
        assertEquals(1, cache.activeGpuReferenceCount());

        // Acquiring key3 triggers LRU eviction of key1 while keeping pinned key2 alive
        AuthenticCharacterInstance inst3 = cache.acquireSharedForKey(key3, dummy, List.of(), null);
        assertEquals(2, cache.cachedGpuRaceCount());
        assertEquals(2, cache.activeGpuReferenceCount());

        // Manual eviction while both inst2 & inst3 remain active must evict 0
        assertEquals(0, cache.evictUnusedAssets());
        assertEquals(2, cache.cachedGpuRaceCount());

        // Closing inst2 leaves only inst3 pinned; evictUnusedAssets drops unreferenced key2 from GPU & CPU caches
        inst2.close();
        int evicted = cache.evictUnusedAssets();
        assertEquals(1, evicted);
        assertEquals(1, cache.cachedGpuRaceCount());
        assertEquals(1, cache.cachedCpuRaceCount());
        assertEquals(1, cache.activeGpuReferenceCount());

        inst3.close();
        assertEquals(1, cache.evictUnusedAssets());
        assertEquals(0, cache.cachedGpuRaceCount());
        assertEquals(0, cache.cachedCpuRaceCount());
    }
}
