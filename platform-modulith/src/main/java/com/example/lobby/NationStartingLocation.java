package com.example.lobby;

public enum NationStartingLocation {
  SANDORIA((short) 0, 230,  66.0f, -1.0f,  -40.0f, 180.0f, 0.0f), // Southern San d'Oria
  BASTOK(  (short) 1, 234, 110.0f,  0.0f,  -60.0f,  90.0f, 0.0f), // Bastok Mines
  WINDURST((short) 2, 238, -60.0f, -5.0f,  -20.0f,   0.0f, 0.0f); // Windurst Waters

  public final short nationId;
  public final int zoneId;
  public final float posX;
  public final float posY;
  public final float posZ;
  public final float rotYaw;
  public final float rotPitch;

  NationStartingLocation(short nationId, int zoneId, float posX, float posY, float posZ, float rotYaw, float rotPitch) {
    this.nationId = nationId;
    this.zoneId = zoneId;
    this.posX = posX;
    this.posY = posY;
    this.posZ = posZ;
    this.rotYaw = rotYaw;
    this.rotPitch = rotPitch;
  }

  public static NationStartingLocation fromNationId(short nationId) {
    return switch (nationId) {
      case 1 -> BASTOK;
      case 2 -> WINDURST;
      default -> SANDORIA;
    };
  }
}