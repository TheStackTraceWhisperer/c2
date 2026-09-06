package com.example.common.protocol;

public final class MessageType {
  // Gateway Control Plane (1 - 9)
  public static final short CTRL_SET_AUTH_STATE = 1;
  public static final short CTRL_SET_CHARACTER = 2;
  public static final short CTRL_SET_WORLD = 3;
  public static final short CTRL_SET_ZONE = 4;

  // Diagnostics / System (10 - 99)
  public static final short PING = 10;
  public static final short PONG = 11;
  public static final short SESSION_DISCONNECTED = 99;

  // Auth / Login Service (100 - 199)
  public static final short LOGIN_REQUEST = 100;
  public static final short LOGIN_RESPONSE = 101;
  public static final short RECONNECT_REQUEST = 102;
  public static final short RECONNECT_RESPONSE = 103;

  // Lobby Service (200 - 299)
  public static final short CHARACTER_LIST_REQUEST = 200;
  public static final short CHARACTER_LIST_RESPONSE = 201;
  public static final short SELECT_CHARACTER_REQUEST = 202;
  public static final short SELECT_CHARACTER_RESPONSE = 203;
  public static final short CREATE_CHARACTER_REQUEST = 204;
  public static final short CREATE_CHARACTER_RESPONSE = 205;

  // World Service / Realm Orchestration (300 - 399)
  public static final short JOIN_WORLD_REQUEST = 300;
  public static final short JOIN_WORLD_RESPONSE = 301;
  public static final short ZONE_TRANSFER_NOTIFICATION = 302;

  // Zone Service / Authoritative Simulation (400 - 499)
  public static final short PLAYER_MOVEMENT_REQUEST = 400;
  public static final short PLAYER_MOVEMENT_RESPONSE = 401;
  public static final short ZONE_STATE_DELTA_BROADCAST = 402;

  private MessageType() {}
}