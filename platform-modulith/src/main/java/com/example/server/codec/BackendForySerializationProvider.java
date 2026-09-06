package com.example.server.codec;

import com.example.common.codec.ForySerializationProvider;
import com.example.server.protocol.SessionDisconnectedEvent;
import com.example.server.protocol.SetSessionAuthStateControlMessage;
import com.example.server.protocol.SetSessionCharacterControlMessage;
import com.example.server.protocol.SetSessionWorldControlMessage;
import com.example.server.protocol.SetSessionZoneControlMessage;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;

public final class BackendForySerializationProvider {

  private static final ThreadSafeFory FORY = Fory.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .buildThreadSafeFory();

  static {
    // Gateway Control Plane (1 - 49)
    FORY.register(SetSessionAuthStateControlMessage.class, 1);
    FORY.register(SetSessionCharacterControlMessage.class, 2);
    FORY.register(SetSessionWorldControlMessage.class, 3);
    FORY.register(SetSessionZoneControlMessage.class, 4);

    // Common DTOs and System Diagnostics (50 - 499)
    ForySerializationProvider.registerCommon(FORY);

    // Backend-only events (50 - 99 range)
    FORY.register(SessionDisconnectedEvent.class, 99);
  }

  public static ThreadSafeFory get() {
    return FORY;
  }

  private BackendForySerializationProvider() {}
}