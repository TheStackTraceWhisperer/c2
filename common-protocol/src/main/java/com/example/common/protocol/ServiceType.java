package com.example.common.protocol;

public final class ServiceType {
  public static final short GATEWAY = 0; // Internal Gateway Control Plane
  public static final short AUTH = 1;    // Login, Account, Auth Tokens
  public static final short LOBBY = 2;   // Character Selection & Creation
  public static final short WORLD = 3;   // Realm Coordination, Global Chat, Party, Zone Handoffs
  public static final short ZONE = 4;    // Authoritative Spatial Simulation (Movement, Combat, AoI)

  private ServiceType() {}
}