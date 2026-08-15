package com.example.common.codec;

import com.example.common.backend.protocol.SessionDisconnectedEvent;
import com.example.common.backend.protocol.SetSessionAuthStateControlMessage;
import com.example.common.backend.protocol.SetSessionCharacterControlMessage;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.lobby.dto.*;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.world.dto.PlayerMovementRequest;
import com.example.world.dto.PlayerMovementResponse;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;

public final class ForySerializationProvider {

  private static final ThreadSafeFory FORY = Fory.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .buildThreadSafeFory();

  static {
    // Gateway Control Plane DTOs (1 - 9)
    FORY.register(SetSessionAuthStateControlMessage.class, 1);
    FORY.register(SetSessionCharacterControlMessage.class, 2);

    // System / Diagnostic DTOs (10 - 99)
    FORY.register(PingMessage.class, 10);
    FORY.register(PongMessage.class, 11);
    FORY.register(SessionDisconnectedEvent.class, 99);

    // Auth Service (100 - 199)
    FORY.register(LoginRequest.class, 100);
    FORY.register(LoginResponse.class, 101);

    // Lobby Service (200 - 299)
    FORY.register(CharacterListRequest.class, 200);
    FORY.register(CharacterListResponse.class, 201);
    FORY.register(CharacterSummary.class, 202);
    FORY.register(SelectCharacterRequest.class, 203);
    FORY.register(SelectCharacterResponse.class, 204);

    // World Service (300 - 399)
    FORY.register(PlayerMovementRequest.class, 300);
    FORY.register(PlayerMovementResponse.class, 301);
  }

  public static ThreadSafeFory get() {
    return FORY;
  }

  private ForySerializationProvider() {}
}