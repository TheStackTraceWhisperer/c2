package com.example.client.desktop.net;

import com.example.common.dto.GatewayErrorResponse;
import com.example.common.dto.PongMessage;
import com.example.lobby.dto.*;
import com.example.login.dto.LoginResponse;
import com.example.world.dto.JoinWorldResponse;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
@Getter
@Singleton
public class ClientState {

    public enum AppPhase {
        DISCONNECTED,
        AUTHENTICATING,
        LOBBY_CHAR_SELECT,
        LOBBY_CHAR_CREATE,
        ENTERING_WORLD,
        IN_WORLD
    }

    private volatile AppPhase phase = AppPhase.DISCONNECTED;
    private volatile Long accountId;
    private volatile String authToken;
    private volatile int currentWorldId = 1;
    private volatile Long selectedCharacterId;
    private volatile Integer currentZoneId;
    private volatile String lastErrorMessage;
    private volatile long currentLatencyMs = 0;

    private final List<CharacterSummary> characterList = new CopyOnWriteArrayList<>();
    private final List<Consumer<ClientState>> stateListeners = new CopyOnWriteArrayList<>();

    @Setter
    private DesktopNetworkAdapter networkAdapter;

    @Setter
    private volatile Consumer<PongMessage> pongListener;

    public void onStateChange(Consumer<ClientState> listener) {
        stateListeners.add(listener);
    }

    public void notifyListeners() {
        for (Consumer<ClientState> listener : stateListeners) {
            listener.accept(this);
        }
    }

    public void setPhase(AppPhase phase) {
        this.phase = phase;
        notifyListeners();
    }

    public void setSelectedCharacterId(Long characterId) {
        this.selectedCharacterId = characterId;
        notifyListeners();
    }

    public void setLastErrorMessage(String message) {
        this.lastErrorMessage = message;
        notifyListeners();
    }

    // --- Outbound Actions ---

    public void sendLogin(String username, String password) {
        this.phase = AppPhase.AUTHENTICATING;
        this.lastErrorMessage = null;
        notifyListeners();
        if (networkAdapter != null) {
            networkAdapter.sendLogin(username, password);
        }
    }

    public void sendCharacterList() {
        if (networkAdapter != null && accountId != null && authToken != null) {
            networkAdapter.sendCharacterList(accountId, authToken);
        }
    }

    public void sendCharacterCreate(String name, short raceId, short gender, short faceId, short hairId, short sizeId, short nationId, short mainJobId) {
        if (networkAdapter != null && authToken != null) {
            networkAdapter.sendCharacterCreate(authToken, currentWorldId, name, raceId, gender, faceId, hairId, sizeId, nationId, mainJobId);
        }
    }

    public void sendCharacterDelete(long characterId) {
        characterList.removeIf(c -> c.characterId() == characterId);
        if (selectedCharacterId != null && selectedCharacterId == characterId) {
            selectedCharacterId = null;
        }
        notifyListeners();
    }

    public void sendCharacterSelect(long characterId) {
        this.selectedCharacterId = characterId;
        if (networkAdapter != null && authToken != null) {
            networkAdapter.sendCharacterSelect(authToken, characterId);
        }
    }

    public void sendPlay() {
        this.phase = AppPhase.ENTERING_WORLD;
        notifyListeners();
        if (networkAdapter != null && authToken != null) {
            networkAdapter.sendPlay(authToken);
        }
    }

    public void sendPing(String message) {
        if (networkAdapter != null) {
            networkAdapter.sendPing(System.currentTimeMillis(), message);
        }
    }

    public void sendLogout() {
        if (networkAdapter != null) {
            networkAdapter.sendLogout();
        }
        reset();
    }

    // --- Inbound Main-Thread Response Handlers ---

    public void onLoginResponse(LoginResponse resp) {
        if (resp.success()) {
            this.accountId = resp.accountId();
            this.authToken = resp.token();
            this.lastErrorMessage = null;
            this.phase = AppPhase.LOBBY_CHAR_SELECT;
            log.info("ClientState -> AUTHENTICATED accountId={}", accountId);
            sendCharacterList();
        } else {
            this.lastErrorMessage = resp.errorMessage() != null ? resp.errorMessage() : "Authentication failed";
            this.phase = AppPhase.DISCONNECTED;
            log.warn("ClientState -> AUTH_FAILED: {}", lastErrorMessage);
        }
        notifyListeners();
    }

    public void onCharacterListResponse(CharacterListResponse resp) {
        this.characterList.clear();
        if (resp.characters() != null) {
            this.characterList.addAll(resp.characters());
        }
        this.lastErrorMessage = null;
        log.info("ClientState -> Received {} characters", this.characterList.size());
        notifyListeners();
    }

    public void onCharacterCreateResponse(CreateCharacterResponse resp) {
        if (resp.success()) {
            this.lastErrorMessage = null;
            this.phase = AppPhase.LOBBY_CHAR_SELECT;
            log.info("ClientState -> Character created id={}", resp.characterId());
            sendCharacterList();
        } else {
            this.lastErrorMessage = resp.errorMessage();
            log.warn("ClientState -> Character creation failed: {}", lastErrorMessage);
        }
        notifyListeners();
    }

    public void onCharacterSelectResponse(SelectCharacterResponse resp) {
        if (resp.success()) {
            this.selectedCharacterId = resp.characterId();
            this.lastErrorMessage = null;
            log.info("ClientState -> Character selected id={}", resp.characterId());
        } else {
            this.lastErrorMessage = resp.errorMessage();
            log.warn("ClientState -> Character selection failed: {}", lastErrorMessage);
        }
        notifyListeners();
    }

    public void onJoinWorldResponse(JoinWorldResponse resp) {
        if (resp.success()) {
            this.currentZoneId = resp.initialZoneId();
            this.lastErrorMessage = null;
            this.phase = AppPhase.IN_WORLD;
            log.info("ClientState -> IN_WORLD zoneId={}", currentZoneId);
        } else {
            this.lastErrorMessage = resp.errorMessage();
            this.phase = AppPhase.LOBBY_CHAR_SELECT;
            log.error("ClientState -> Join world failed: {}", lastErrorMessage);
        }
        notifyListeners();
    }

    public void onPongResponse(PongMessage resp) {
        this.currentLatencyMs = Math.max(0, System.currentTimeMillis() - resp.timestamp());
        log.debug("ClientState -> Pong received, latency={}ms", currentLatencyMs);
        if (pongListener != null) {
            pongListener.accept(resp);
        }
        notifyListeners();
    }

    public void onErrorResponse(GatewayErrorResponse err) {
        this.lastErrorMessage = err.message();
        log.error("ClientState -> Gateway error code={}, msg={}", err.errorCode(), err.message());
        if (err.errorCode() != GatewayErrorResponse.ERROR_SERVICE_UNAVAILABLE) {
            this.phase = AppPhase.DISCONNECTED;
        }
        notifyListeners();
    }

    public void reset() {
        this.phase = AppPhase.DISCONNECTED;
        this.accountId = null;
        this.authToken = null;
        this.selectedCharacterId = null;
        this.currentZoneId = null;
        this.lastErrorMessage = null;
        this.characterList.clear();
        notifyListeners();
    }
}
