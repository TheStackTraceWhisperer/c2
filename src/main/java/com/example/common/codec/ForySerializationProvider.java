package com.example.common.codec;

import com.example.common.backend.protocol.SessionDisconnectedEvent;
import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.lobby.dto.CharacterListRequest;
import com.example.lobby.dto.CharacterListResponse;
import com.example.lobby.dto.CharacterSummary;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;

public final class ForySerializationProvider {

  private static final ThreadSafeFory FORY = Fory.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .buildThreadSafeFory();

  static {
    // System / Diagnostic DTOs
    FORY.register(PingMessage.class, 10);
    FORY.register(PongMessage.class, 11);
    FORY.register(SessionDisconnectedEvent.class, 99);

    // Auth Service
    FORY.register(LoginRequest.class, 100);
    FORY.register(LoginResponse.class, 101);

    // Lobby Service
    FORY.register(CharacterListRequest.class, 200);
    FORY.register(CharacterListResponse.class, 201);
    FORY.register(CharacterSummary.class, 202);
  }

  public static ThreadSafeFory get() {
    return FORY;
  }

  private ForySerializationProvider() {}
}