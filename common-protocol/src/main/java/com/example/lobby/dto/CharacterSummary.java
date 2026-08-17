package com.example.lobby.dto;

public record CharacterSummary(
  long characterId,
  String name,
  int level,
  String characterClass,
  int worldId
) {}