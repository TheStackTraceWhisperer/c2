package com.example.common.protocol;

public final class ServiceType {
  public static final short GATEWAY = 0; // Internal Gateway Control Plane
  public static final short AUTH = 1;
  public static final short LOBBY = 2;
  public static final short WORLD = 3;

  private ServiceType() {}
}