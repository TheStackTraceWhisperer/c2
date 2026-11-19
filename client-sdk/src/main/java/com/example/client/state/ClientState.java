package com.example.client.state;

import com.example.client.interp.EntityInterpolationBuffer;
import com.example.client.interp.InterpolatedEntityState;
import com.example.client.interp.InterpolationSnapshot;
import com.example.lobby.dto.CharacterSummary;
import com.example.zone.dto.EntityMovementUpdate;
import com.example.zone.dto.EntitySpawnRecord;
import com.example.zone.dto.ZoneStateDeltaMessage;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Getter
@Singleton
public class ClientState {

  private volatile ClientConnectionState connectionState = ClientConnectionState.DISCONNECTED;
  @Setter private volatile long accountId = 0L;
  @Setter private volatile String authToken = "";
  private volatile Long selectedCharacterId = null;
  @Setter private volatile short lastErrorCode = 0;
  private volatile String lastErrorMessage = "";

  // Local Player Coordinates
  @Setter private volatile int currentWorldId = 0;
  @Setter private volatile int currentZoneId = 0;
  @Setter private volatile float x = 0.0f;
  @Setter private volatile float y = 0.0f;
  @Setter private volatile float z = 0.0f;
  @Setter private volatile float yaw = 0.0f;
  @Setter private volatile float pitch = 0.0f;
  @Setter private volatile long lastServerTick = 0L;
  @Setter private volatile long currentLatencyMs = 0L;

  // Remote World Entities within AoI
  private final Map<Long, RemoteEntityState> remoteEntities = new ConcurrentHashMap<>();
  private final Map<Long, EntityInterpolationBuffer> interpolationBuffers = new ConcurrentHashMap<>();

  private final List<CharacterSummary> characters = new CopyOnWriteArrayList<>();
  private final List<Consumer<ClientState>> stateListeners = new CopyOnWriteArrayList<>();

  public record RemoteEntityState(
    long characterId,
    String name,
    short mainJobId,
    short subJobId,
    int level,
    float x, float y, float z,
    float vx, float vy, float vz,
    float yaw, float pitch,
    int moveFlags
  ) {}

  public void setConnectionState(ClientConnectionState connectionState) {
    setConnectionState(connectionState, true);
  }

  public void setConnectionState(ClientConnectionState connectionState, boolean notify) {
    this.connectionState = connectionState;
    if (notify) {
      notifyListeners();
    }
  }

  public void setSelectedCharacterId(Long selectedCharacterId) {
    this.selectedCharacterId = selectedCharacterId;
    notifyListeners();
  }

  public void setLastErrorMessage(String lastErrorMessage) {
    setLastErrorMessage(lastErrorMessage, true);
  }

  public void setLastErrorMessage(String lastErrorMessage, boolean notify) {
    this.lastErrorMessage = lastErrorMessage != null ? lastErrorMessage : "";
    if (notify) {
      notifyListeners();
    }
  }

  public List<CharacterSummary> getCharacterList() {
    return characters;
  }

  public void addOrUpdateRemoteEntity(EntitySpawnRecord spawn) {
    applySpawn(spawn, System.currentTimeMillis());
    notifyListeners();
  }

  public void updateRemoteEntityMovement(EntityMovementUpdate update) {
    if (applyMovement(update, System.currentTimeMillis())) {
      notifyListeners();
    }
  }

  public void removeRemoteEntity(long characterId) {
    applyDespawn(characterId);
    notifyListeners();
  }

  public void applyZoneStateDelta(ZoneStateDeltaMessage delta) {
    if (delta == null) {
      return;
    }
    long now = System.currentTimeMillis();
    if (delta.spawns() != null) {
      for (EntitySpawnRecord spawn : delta.spawns()) {
        applySpawn(spawn, now);
      }
    }
    if (delta.updates() != null) {
      for (EntityMovementUpdate update : delta.updates()) {
        applyMovement(update, now);
      }
    }
    if (delta.despawns() != null) {
      for (Long despawnId : delta.despawns()) {
        if (despawnId != null) {
          applyDespawn(despawnId);
        }
      }
    }
    notifyListeners();
  }

  private void applySpawn(EntitySpawnRecord spawn, long now) {
    remoteEntities.put(spawn.characterId(), new RemoteEntityState(
      spawn.characterId(), spawn.name(), spawn.mainJobId(), spawn.subJobId(), spawn.level(),
      spawn.x(), spawn.y(), spawn.z(),
      spawn.vx(), spawn.vy(), spawn.vz(),
      spawn.yaw(), spawn.pitch(), 0
    ));

    EntityInterpolationBuffer buffer = interpolationBuffers.computeIfAbsent(spawn.characterId(), id -> new EntityInterpolationBuffer());
    buffer.pushSnapshot(new InterpolationSnapshot(
      now, spawn.x(), spawn.y(), spawn.z(),
      spawn.vx(), spawn.vy(), spawn.vz(),
      spawn.yaw(), spawn.pitch(), 0
    ));
  }

  private boolean applyMovement(EntityMovementUpdate update, long now) {
    RemoteEntityState updated = remoteEntities.computeIfPresent(update.characterId(), (id, old) -> new RemoteEntityState(
      old.characterId(), old.name(), old.mainJobId(), old.subJobId(), old.level(),
      update.x(), update.y(), update.z(),
      update.vx(), update.vy(), update.vz(),
      update.yaw(), update.pitch(), update.moveFlags()
    ));

    if (updated == null) {
      return false;
    }

    EntityInterpolationBuffer buffer = interpolationBuffers.computeIfAbsent(update.characterId(), id -> new EntityInterpolationBuffer());
    buffer.pushSnapshot(new InterpolationSnapshot(
      now, update.x(), update.y(), update.z(),
      update.vx(), update.vy(), update.vz(),
      update.yaw(), update.pitch(), update.moveFlags()
    ));
    return true;
  }

  private void applyDespawn(long characterId) {
    remoteEntities.remove(characterId);
    interpolationBuffers.remove(characterId);
  }

  public InterpolatedEntityState sampleInterpolatedEntity(long characterId, long currentClientTimeMs) {
    EntityInterpolationBuffer buffer = interpolationBuffers.get(characterId);
    if (buffer != null) {
      return buffer.sample(currentClientTimeMs);
    }
    RemoteEntityState raw = remoteEntities.get(characterId);
    if (raw != null) {
      return new InterpolatedEntityState(
          raw.x(), raw.y(), raw.z(),
          raw.vx(), raw.vy(), raw.vz(),
          raw.yaw(), raw.pitch(),
          raw.moveFlags(), false
      );
    }
    return null;
  }

  public void updateCharacters(List<CharacterSummary> newCharacters) {
    this.characters.clear();
    if (newCharacters != null) {
      this.characters.addAll(newCharacters);
    }
    notifyListeners();
  }

  public void updatePosition(float x, float y, float z, long serverTick) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.lastServerTick = serverTick;
    notifyListeners();
  }

  public void updatePosition(float x, float y, float z, float yaw, float pitch, long serverTick) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.yaw = yaw;
    this.pitch = pitch;
    this.lastServerTick = serverTick;
    notifyListeners();
  }

  public void updateLatency(long latencyMs) {
    this.currentLatencyMs = latencyMs;
    notifyListeners();
  }

  public void onStateChange(Consumer<ClientState> listener) {
    stateListeners.add(listener);
  }

  public void notifyListeners() {
    for (Consumer<ClientState> listener : stateListeners) {
      listener.accept(this);
    }
  }

  public void clearRemoteEntities() {
    this.remoteEntities.clear();
    this.interpolationBuffers.clear();
    notifyListeners();
  }

  public void resetZoneSession() {
    this.currentZoneId = 0;
    this.x = 0.0f;
    this.y = 0.0f;
    this.z = 0.0f;
    this.yaw = 0.0f;
    this.pitch = 0.0f;
    this.lastServerTick = 0L;
    this.remoteEntities.clear();
    this.interpolationBuffers.clear();
    notifyListeners();
  }

  public void reset() {
    this.connectionState = ClientConnectionState.DISCONNECTED;
    this.accountId = 0L;
    this.authToken = "";
    this.selectedCharacterId = null;
    this.lastErrorCode = 0;
    this.lastErrorMessage = "";
    this.currentWorldId = 0;
    this.currentZoneId = 0;
    this.x = 0.0f;
    this.y = 0.0f;
    this.z = 0.0f;
    this.yaw = 0.0f;
    this.pitch = 0.0f;
    this.lastServerTick = 0L;
    this.currentLatencyMs = 0L;
    this.characters.clear();
    this.remoteEntities.clear();
    this.interpolationBuffers.clear();
    notifyListeners();
  }
}