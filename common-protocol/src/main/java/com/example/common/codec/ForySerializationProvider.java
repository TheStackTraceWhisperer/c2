package com.example.common.codec;

import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.lobby.dto.CharacterListRequest;
import com.example.lobby.dto.CharacterListResponse;
import com.example.lobby.dto.CharacterSummary;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.lobby.dto.SelectCharacterResponse;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.login.dto.ReconnectRequest;
import com.example.login.dto.ReconnectResponse;
import com.example.world.dto.JoinWorldRequest;
import com.example.world.dto.JoinWorldResponse;
import com.example.world.dto.ZoneTransferNotification;
import com.example.zone.dto.EntityMovementUpdate;
import com.example.zone.dto.EntitySpawnRecord;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
import com.example.zone.dto.ZoneStateDeltaMessage;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;

public final class ForySerializationProvider {

  private static final ThreadSafeFory FORY = Fory.builder()
    .withXlang(true)
    .requireClassRegistration(false)
    .buildThreadSafeFory();

  static {
    // System / Diagnostic DTOs (10 - 99)
    FORY.register(PingMessage.class, 10);
    FORY.register(PongMessage.class, 11);

    // Auth Service (100 - 199)
    FORY.register(LoginRequest.class, 100);
    FORY.register(LoginResponse.class, 101);
    FORY.register(ReconnectRequest.class, 102);
    FORY.register(ReconnectResponse.class, 103);

    // Lobby Service (200 - 299)
    FORY.register(CharacterListRequest.class, 200);
    FORY.register(CharacterListResponse.class, 201);
    FORY.register(CharacterSummary.class, 202);
    FORY.register(SelectCharacterRequest.class, 203);
    FORY.register(SelectCharacterResponse.class, 204);

    // World Service / Realm Orchestration (300 - 399)
    FORY.register(JoinWorldRequest.class, 300);
    FORY.register(JoinWorldResponse.class, 301);
    FORY.register(ZoneTransferNotification.class, 302);

    // Zone Service / Simulation (400 - 499)
    FORY.register(PlayerMovementRequest.class, 400);
    FORY.register(PlayerMovementResponse.class, 401);
    FORY.register(EntitySpawnRecord.class, 402);
    FORY.register(EntityMovementUpdate.class, 403);
    FORY.register(ZoneStateDeltaMessage.class, 404);
  }

  public static ThreadSafeFory get() {
    return FORY;
  }

  private ForySerializationProvider() {}
}