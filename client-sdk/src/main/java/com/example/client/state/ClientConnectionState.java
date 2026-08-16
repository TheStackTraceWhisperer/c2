package com.example.client.state;

public enum ClientConnectionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  AUTHENTICATED,
  IN_LOBBY,
  IN_WORLD,
  IN_ZONE
}