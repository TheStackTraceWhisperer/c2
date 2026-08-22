package com.example.server.entity;

public record CharacterStateSnapshot(
  long characterId,
  int zoneId,
  float posX,
  float posY,
  float posZ,
  float rotYaw,
  float rotPitch
) {}