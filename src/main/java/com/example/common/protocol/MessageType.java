package com.example.common.protocol;

public final class MessageType {
  // Gateway Control Plane (1 - 9)
  public static final short CTRL_SET_AUTH_STATE = 1;
  public static final short CTRL_SET_CHARACTER = 2;

  // Diagnostics / System (10 - 99)
  public static final short PING = 10;
  public static final short PONG = 11;
  public static final short SESSION_DISCONNECTED = 99;

  // Auth / Login Service (100 - 199)
  public static final short LOGIN_REQUEST = 100;
  public static final short LOGIN_RESPONSE = 101;

  // Lobby Service (200 - 299)
  public static final short CHARACTER_LIST_REQUEST = 200;
  public static final short CHARACTER_LIST_RESPONSE = 201;
  public static final short SELECT_CHARACTER_REQUEST = 202;
  public static final short SELECT_CHARACTER_RESPONSE = 203;

  // World Service (300 - 399)
  public static final short PLAYER_MOVEMENT_REQUEST = 300;
  public static final short PLAYER_MOVEMENT_RESPONSE = 301;

  private MessageType() {}
}