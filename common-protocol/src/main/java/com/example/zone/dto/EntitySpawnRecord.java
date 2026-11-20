package com.example.zone.dto;

public record EntitySpawnRecord(
  long characterId,
  String name,
  short mainJobId,
  short subJobId,
  int level,
  float x, float y, float z,
  float vx, float vy, float vz,
  float yaw, float pitch,
  ActorAppearance appearance
) {
  public EntitySpawnRecord {
    if (appearance == null) {
      appearance = ActorAppearance.NONE;
    }
  }

  public EntitySpawnRecord(
    long characterId,
    String name,
    short mainJobId,
    short subJobId,
    int level,
    float x, float y, float z,
    float vx, float vy, float vz,
    float yaw, float pitch
  ) {
    this(
      characterId, name, mainJobId, subJobId, level,
      x, y, z, vx, vy, vz, yaw, pitch,
      ActorAppearance.NONE
    );
  }
}