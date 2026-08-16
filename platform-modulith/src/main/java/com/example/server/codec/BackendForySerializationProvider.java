package com.example.server.codec;

import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.lobby.dto.*;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.login.dto.ReconnectRequest;
import com.example.login.dto.ReconnectResponse;
import com.example.server.protocol.SessionDisconnectedEvent;
import com.example.server.protocol.SetSessionAuthStateControlMessage;
import com.example.server.protocol.SetSessionCharacterControlMessage;
import com.example.server.protocol.SetSessionWorldControlMessage;
import com.example.world.dto.PlayerMovementRequest;
import com.example.world.dto.PlayerMovementResponse;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;

public final class BackendForySerializationProvider {

  private static final ThreadSafeFory FORY = Fory.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .buildThreadSafeFory();

  static {
    FORY.register(SetSessionAuthStateControlMessage.class, 1);
    FORY.register(SetSessionCharacterControlMessage.class, 2);
    FORY.register(SetSessionWorldControlMessage.class, 3);
    FORY.register(PingMessage.class, 10);
    FORY.register(PongMessage.class, 11);
    FORY.register(SessionDisconnectedEvent.class, 99);
    FORY.register(LoginRequest.class, 100);
    FORY.register(LoginResponse.class, 101);
    FORY.register(ReconnectRequest.class, 102);
    FORY.register(ReconnectResponse.class, 103);
    FORY.register(CharacterListRequest.class, 200);
    FORY.register(CharacterListResponse.class, 201);
    FORY.register(CharacterSummary.class, 202);
    FORY.register(SelectCharacterRequest.class, 203);
    FORY.register(SelectCharacterResponse.class, 204);
    FORY.register(PlayerMovementRequest.class, 300);
    FORY.register(PlayerMovementResponse.class, 301);
  }

  public static ThreadSafeFory get() {
    return FORY;
  }

  private BackendForySerializationProvider() {}
}
