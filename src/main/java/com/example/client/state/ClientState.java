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

  private final List<CharacterSummary> characters = new CopyOnWriteArrayList<>();
  private final List<Consumer<ClientState>> stateListeners = new CopyOnWriteArrayList<>();

  public void updateCharacters(List<CharacterSummary> newCharacters) {
    this.characters.clear();
    this.characters.addAll(newCharacters);
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
    this.characters.clear();
  }
}