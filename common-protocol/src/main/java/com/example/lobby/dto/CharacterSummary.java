package com.example.lobby.dto;

public record CharacterSummary(
  long characterId,
  String name,
  int worldId,
  short raceId,
  short gender,
  int zoneId,
  int level,
  short nationId,
  short mainJobId,
  short subJobId
) {}