package com.example.client.state;

import com.example.lobby.dto.CharacterSummary;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Getter
@Singleton
public class ClientState {

  @Setter private volatile ClientConnectionState connectionState = ClientConnectionState.DISCONNECTED;
  @Setter private volatile long accountId = 0L;
  @Setter private volatile String authToken = "";
  @Setter private volatile long selectedCharacterId = 0L;

  // World / Zone Simulation Coordinates & State
  @Setter private volatile int currentWorldId = 0;
  @Setter private volatile int currentZoneId = 0;
  @Setter private volatile float x = 0.0f;
  @Setter private volatile float y = 0.0f;
  @Setter private volatile float z = 0.0f;
  @Setter private volatile long lastServerTick = 0L;

  private final List<CharacterSummary> characters = new CopyOnWriteArrayList<>();
  private final List<Consumer<ClientState>> stateListeners = new CopyOnWriteArrayList<>();

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
    notifyListeners();
  }
}