package com.example.client.desktop.state;

import com.example.client.desktop.scene.SceneActor;
import com.ffxi.model.SkeletonResource;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.model.LoadedCharacter;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InGameHudOverlayTest {

    @Test
    @DisplayName("resolveActorNameplateOffsetY scales nameplate height by LoadedCharacter effectiveScale and falls back cleanly")
    void testResolveActorNameplateOffsetY() {
        assertEquals(2.0f, InGameHudOverlay.resolveActorNameplateOffsetY(null, 2.0f), 1e-4f);

        SceneActor noModel = new SceneActor(1L, "NoModel", SceneActor.ActorType.NPC);
        assertEquals(1.9f, InGameHudOverlay.resolveActorNameplateOffsetY(noModel, 1.9f), 1e-4f);

        LoadedCharacter scaledChar = new LoadedCharacter(
                new SkeletonResource("skel", "29", 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                1.0f,
                0.80f,
                new Vector3f(),
                Map.of(),
                0.95f
        );
        try (AuthenticCharacterInstance instance = new AuthenticCharacterInstance(scaledChar, List.of(), null, false)) {
            SceneActor scaledActor = new SceneActor(2L, "Raminel", SceneActor.ActorType.NPC);
            scaledActor.setVisualModel(instance);

            float expected = 0.80f * 2.0f * 0.95f + 0.35f;
            assertEquals(expected, InGameHudOverlay.resolveActorNameplateOffsetY(scaledActor, 1.9f), 1e-4f);
        }
    }
}
