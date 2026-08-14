package com.example.common.protocol;

public final class MessageType {
  // Diagnostics / System (0 - 99)
  public static final short PING = 10;
  public static final short PONG = 11;
  public static final short SESSION_DISCONNECTED = 99; // <--- System Disconnect Event

  // Auth / Login Service (100 - 199)
  public static final short LOGIN_REQUEST = 100;
  public static final short LOGIN_RESPONSE = 101;

  // Lobby Service (200 - 299)
  public static final short CHARACTER_LIST_REQUEST = 200;
  public static final short CHARACTER_LIST_RESPONSE = 201;

  private MessageType() {}
}