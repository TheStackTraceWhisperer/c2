package com.example.server.entity;

public record CharacterRecord(
  long accountId,
  long characterId,
  String name,
  int level,
  String characterClass,
  int worldId,
  int zoneId,
  float x,
  float y,
  float z,
  float yaw,
  float pitch
) {}