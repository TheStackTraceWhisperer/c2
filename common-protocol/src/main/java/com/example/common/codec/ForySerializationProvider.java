package com.example.common.codec;

import com.example.common.dto.GatewayErrorResponse;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.lobby.dto.*;
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
    registerCommon(FORY);
  }

  public static void registerCommon(ThreadSafeFory fory) {
    // System / Diagnostic DTOs (50 - 99)
    fory.register(PingMessage.class, 50);
    fory.register(PongMessage.class, 51);
    fory.register(GatewayErrorResponse.class, 52);

    // Auth Service (100 - 199)
    fory.register(LoginRequest.class, 100);
    fory.register(LoginResponse.class, 101);
    fory.register(ReconnectRequest.class, 102);
    fory.register(ReconnectResponse.class, 103);

    // Lobby Service (200 - 299)
    fory.register(CharacterListRequest.class, 200);
    fory.register(CharacterListResponse.class, 201);
    fory.register(CharacterSummary.class, 202);
    fory.register(SelectCharacterRequest.class, 203);
    fory.register(SelectCharacterResponse.class, 204);
    fory.register(CreateCharacterRequest.class, 205);
    fory.register(CreateCharacterResponse.class, 206);

    // World Service / Realm Orchestration (300 - 399)
    fory.register(JoinWorldRequest.class, 300);
    fory.register(JoinWorldResponse.class, 301);
    fory.register(ZoneTransferNotification.class, 302);

    // Zone Service / Simulation (400 - 499)
    fory.register(PlayerMovementRequest.class, 400);
    fory.register(PlayerMovementResponse.class, 401);
    fory.register(EntitySpawnRecord.class, 402);
    fory.register(EntityMovementUpdate.class, 403);
    fory.register(ZoneStateDeltaMessage.class, 404);
  }

  public static ThreadSafeFory get() {
    return FORY;
  }

  private ForySerializationProvider() {}
}