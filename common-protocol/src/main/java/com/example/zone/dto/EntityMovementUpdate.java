package com.example.zone.dto;

public record EntityMovementUpdate(
  long characterId,
  float x, float y, float z,
  float vx, float vy, float vz,
  float yaw, float pitch,
  int moveFlags
) {}