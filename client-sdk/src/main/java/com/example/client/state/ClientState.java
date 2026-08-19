package com.example.client.state;

import com.example.lobby.dto.CharacterSummary;
import com.example.zone.dto.EntityMovementUpdate;
import com.example.zone.dto.EntitySpawnRecord;
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

  @Setter private volatile ClientConnectionState connectionState = ClientConnectionState.DISCONNECTED;
  @Setter private volatile long accountId = 0L;
  @Setter private volatile String authToken = "";
  @Setter private volatile long selectedCharacterId = 0L;

  // Local Player Coordinates
  @Setter private volatile int currentWorldId = 0;
  @Setter private volatile int currentZoneId = 0;
  @Setter private volatile float x = 0.0f;
  @Setter private volatile float y = 0.0f;
  @Setter private volatile float z = 0.0f;
  @Setter private volatile long lastServerTick = 0L;

  // Remote World Entities within AoI
  private final Map<Long, RemoteEntityState> remoteEntities = new ConcurrentHashMap<>();

  private final List<CharacterSummary> characters = new CopyOnWriteArrayList<>();
  private final List<Consumer<ClientState>> stateListeners = new CopyOnWriteArrayList<>();

  public record RemoteEntityState(
    long characterId,
    String name,
    String characterClass,
    int level,
    float x, float y, float z,
    float vx, float vy, float vz,
    float yaw, float pitch,
    int moveFlags
  ) {}

  public void addOrUpdateRemoteEntity(EntitySpawnRecord spawn) {
    remoteEntities.put(spawn.characterId(), new RemoteEntityState(
      spawn.characterId(), spawn.name(), spawn.characterClass(), spawn.level(),
      spawn.x(), spawn.y(), spawn.z(),
      spawn.vx(), spawn.vy(), spawn.vz(),
      spawn.yaw(), spawn.pitch(), 0
    ));
  }

  public void updateRemoteEntityMovement(EntityMovementUpdate update) {
    remoteEntities.computeIfPresent(update.characterId(), (id, old) -> new RemoteEntityState(
      old.characterId(), old.name(), old.characterClass(), old.level(),
      update.x(), update.y(), update.z(),
      update.vx(), update.vy(), update.vz(),
      update.yaw(), update.pitch(), update.moveFlags()
    ));
  }

  public void removeRemoteEntity(long characterId) {
    remoteEntities.remove(characterId);
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

  public void onStateChange(Consumer<ClientState> listener) {
    stateListeners.add(listener);
  }

  public void notifyListeners() {
    for (Consumer<ClientState> listener : stateListeners) {
      listener.accept(this);
    }
  }

  public void reset() {
    this.connectionState = ClientConnectionState.DISCONNECTED;
    this.accountId = 0L;
    this.authToken = "";
    this.selectedCharacterId = 0L;
    this.currentWorldId = 0;
    this.currentZoneId = 0;
    this.x = 0.0f;
    this.y = 0.0f;
    this.z = 0.0f;
    this.lastServerTick = 0L;
    this.characters.clear();
    this.remoteEntities.clear();
    notifyListeners();
  }
}