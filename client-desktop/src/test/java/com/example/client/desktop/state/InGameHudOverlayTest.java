package com.example.client.desktop.state;

import com.example.client.desktop.scene.SceneActor;
import com.ffxi.model.Quat4f;
import com.ffxi.model.SkeletonBoundingBox;
import com.ffxi.model.SkeletonJoint;
import com.ffxi.model.SkeletonJointReference;
import com.ffxi.model.SkeletonResource;
import com.ffxi.model.Vec3f;
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
    @DisplayName("resolveActorNameplateOffsetY prefers SkeletonJointReference AboveHead, then SkeletonBoundingBox, then feetOffsetY")
    void testResolveActorNameplateOffsetY() {
        assertEquals(2.0f, InGameHudOverlay.resolveActorNameplateOffsetY(null, 2.0f), 1e-4f);

        SceneActor noModel = new SceneActor(1L, "NoModel", SceneActor.ActorType.NPC);
        assertEquals(1.9f, InGameHudOverlay.resolveActorNameplateOffsetY(noModel, 1.9f), 1e-4f);

        // Priority 3: Mesh bounds fallback when no joint references or bounding boxes are present
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

        // Priority 2: SkeletonBoundingBox when joint references are absent (handles retail yMax = -height, yMin = 0)
        SkeletonResource bboxSkeleton = new SkeletonResource(
                "bbox", "29", 0, 0, 0, 0, 1, 30, 0, 0, 1,
                List.of(new SkeletonJoint(-1, 0, new Quat4f(0f, 0f, 0f, 1f), new Vec3f(0f, 0f, 0f))),
                List.of(),
                List.of(new SkeletonBoundingBox(-1.90f, 0.0f, 0.5f, -0.5f, 0.5f, -0.5f))
        );
        LoadedCharacter bboxChar = new LoadedCharacter(
                bboxSkeleton, List.of(), Map.of(), List.of(), List.of(),
                1.0f, 0.0f, new Vector3f(), Map.of(), 0.95f
        );
        try (AuthenticCharacterInstance bboxInstance = new AuthenticCharacterInstance(bboxChar, List.of(), null, false)) {
            SceneActor bboxActor = new SceneActor(3L, "BBoxNpc", SceneActor.ActorType.NPC);
            bboxActor.setVisualModel(bboxInstance);

            float expected = (0.0f - (-1.90f)) * 0.95f + 0.15f;
            assertEquals(expected, InGameHudOverlay.resolveActorNameplateOffsetY(bboxActor, 1.9f), 1e-4f);
        }

        // Priority 1: SkeletonJointReference AboveHead (index 2) takes precedence over bounding box
        SkeletonJointReference refDummy = new SkeletonJointReference(0, new Vec3f(0f, 0f, 0f), new Vec3f(0f, 0f, 0f), 0);
        SkeletonJointReference refAboveHead = new SkeletonJointReference(0, new Vec3f(0f, 0f, 0f), new Vec3f(0f, -1.2f, 0f), 0);
        SkeletonResource jointRefSkeleton = new SkeletonResource(
                "jref", "29", 0, 0, 0, 0, 1, 30, 3, 0, 1,
                List.of(new SkeletonJoint(-1, 0, new Quat4f(0f, 0f, 0f, 1f), new Vec3f(0f, 0f, 0f))),
                List.of(refDummy, refDummy, refAboveHead),
                List.of(new SkeletonBoundingBox(-1.90f, 0.0f, 0.5f, -0.5f, 0.5f, -0.5f))
        );
        LoadedCharacter jointRefChar = new LoadedCharacter(
                jointRefSkeleton, List.of(), Map.of(), List.of(), List.of(),
                1.0f, 0.8f, new Vector3f(), Map.of(), 1.0f
        );
        try (AuthenticCharacterInstance jointRefInstance = new AuthenticCharacterInstance(jointRefChar, List.of(), null, false)) {
            SceneActor jointRefActor = new SceneActor(4L, "HeadRefNpc", SceneActor.ActorType.NPC);
            jointRefActor.setVisualModel(jointRefInstance);

            float expected = (0.8f - (-1.2f)) * 1.0f;
            assertEquals(expected, InGameHudOverlay.resolveActorNameplateOffsetY(jointRefActor, 1.9f), 1e-4f);
        }
    }
}
