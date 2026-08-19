package com.example.zone.dto;

public record EntitySpawnRecord(
  long characterId,
  String name,
  String characterClass,
  int level,
  float x, float y, float z,
  float vx, float vy, float vz,
  float yaw, float pitch
) {}