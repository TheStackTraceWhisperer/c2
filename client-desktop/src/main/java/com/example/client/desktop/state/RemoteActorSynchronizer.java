package com.example.client.desktop.state;

import com.example.client.desktop.scene.SceneActor;
import com.example.client.desktop.scene.WorldScene;
import com.example.client.interp.InterpolatedEntityState;
import com.example.client.state.ClientState;
import com.example.common.protocol.JobId;
import com.example.common.protocol.MovementFlags;
import com.example.zone.dto.ActorAppearance;
import com.example.zone.dto.ActorLookType;
import com.ffxi.tables.RaceGenderConfig;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Synchronizes local and remote AOI entities from {@link ClientState} into {@link WorldScene} actors.
 * Acquires character visual models through {@link WorldScene#createCharacterInstance(Path, RaceGenderConfig)}
 * (backed by {@code CharacterAssetCache}) and disposes despawned remote actors cleanly via {@link WorldScene#removeActor(long)}.
 */
@Slf4j
public final class RemoteActorSynchronizer {

    private RemoteActorSynchronizer() {
    }

    public static void syncActors(
            WorldScene worldScene,
            ClientState clientState,
            Path ffxiRoot,
            float localMoveSpeed,
            int localMoveFlags,
            long nowMillis
    ) {
        SceneActor localActor = worldScene.getLocalPlayerActor();
        if (localActor != null) {
            localActor.setPosition(clientState.getX(), clientState.getY(), clientState.getZ());
            localActor.setRotation(clientState.getYaw());
            localActor.setMovementSpeed(localMoveSpeed);
            localActor.setMovementFlags(localMoveFlags);
        }

        boolean sceneInitialized = worldScene.isInitialized();

        for (var entity : clientState.getRemoteEntities().values()) {
            InterpolatedEntityState interp = clientState.sampleInterpolatedEntity(entity.characterId(), nowMillis);
            SceneActor actor = worldScene.getActor(entity.characterId());
            boolean isNew = (actor == null);
            if (actor == null) {
                SceneActor.ActorType aType = (entity.mainJobId() == JobId.NPC)
                        ? SceneActor.ActorType.NPC
                        : SceneActor.ActorType.PLAYER;
                actor = new SceneActor(entity.characterId(), entity.name(), aType);
                if (sceneInitialized) {
                    ActorAppearance appearance = entity.appearance();
                    if (appearance != null && appearance.lookType() != ActorLookType.NONE) {
                        try {
                            actor.setVisualModel(worldScene.createCharacterInstance(ffxiRoot, appearance));
                        } catch (Exception e) {
                            log.warn("Failed to load appearance {} for entity {} ({}); falling back to default race: {}",
                                    appearance, entity.characterId(), entity.name(), e.getMessage());
                            try {
                                RaceGenderConfig fallbackRace = resolveRaceForEntity(entity.mainJobId());
                                actor.setVisualModel(worldScene.createCharacterInstance(ffxiRoot, fallbackRace));
                            } catch (Exception fallbackEx) {
                                log.debug("Unable to create fallback character model for entity {}: {}",
                                        entity.characterId(), fallbackEx.toString());
                            }
                        }
                    } else {
                        try {
                            RaceGenderConfig race = resolveRaceForEntity(entity.mainJobId());
                            actor.setVisualModel(worldScene.createCharacterInstance(ffxiRoot, race));
                        } catch (Exception e) {
                            log.debug("Unable to create character model for entity {}: {}", entity.characterId(), e.toString());
                        }
                    }
                }
                worldScene.addActor(actor);
            }
            if (isNew) {
                actor.setPosition(interp.x(), interp.y(), interp.z());
                actor.setRotation(interp.yaw());
            }
            actor.setTargetTransform(interp.x(), interp.y(), interp.z(), interp.yaw(), interp.vx(), interp.vy(), interp.vz());
            float speed = interp.speedHorizontal();
            actor.setMovementSpeed(speed);
            actor.setMovementFlags(interp.moveFlags() != 0 ? interp.moveFlags() : (speed > 0.1f ? MovementFlags.FORWARD : MovementFlags.IDLE));
        }

        List<Long> despawnedIds = new ArrayList<>();
        for (SceneActor a : worldScene.getActors()) {
            if (!a.isLocalPlayer() && !clientState.getRemoteEntities().containsKey(a.getId())) {
                despawnedIds.add(a.getId());
            }
        }
        for (long id : despawnedIds) {
            worldScene.removeActor(id);
        }
    }

    public static RaceGenderConfig resolveRaceForEntity(short jobId) {
        if (jobId == JobId.NPC) return RaceGenderConfig.ElvaanM;
        if (jobId == JobId.THIEF) return RaceGenderConfig.Mithra;
        if (jobId == JobId.WHITE_MAGE) return RaceGenderConfig.TaruM;
        if (jobId == JobId.BLACK_MAGE) return RaceGenderConfig.TaruF;
        if (jobId == JobId.PALADIN) return RaceGenderConfig.ElvaanM;
        if (jobId == JobId.RED_MAGE) return RaceGenderConfig.HumeF;
        if (jobId == JobId.MONK) return RaceGenderConfig.Galka;
        return RaceGenderConfig.HumeM;
    }
}
