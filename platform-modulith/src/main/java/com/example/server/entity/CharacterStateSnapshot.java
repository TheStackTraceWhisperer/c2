package com.example.server.entity;

public record CharacterStateSnapshot(
  long characterId,
  int zoneId,
  float x,
  float y,
  float z,
  float yaw,
  float pitch
) {}