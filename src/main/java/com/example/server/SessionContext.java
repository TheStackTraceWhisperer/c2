package com.example.server;

import io.netty.util.AttributeKey;

public final class SessionContext {
  public static final AttributeKey<Long> SESSION_ID = AttributeKey.valueOf("SERVER_SESSION_ID");
  public static final AttributeKey<Short> SERVICE_ID = AttributeKey.valueOf("SERVER_SERVICE_ID");

  private SessionContext() {}
}