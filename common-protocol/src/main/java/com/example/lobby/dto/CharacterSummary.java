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
  short subJobId,
  short faceId,
  short hairId,
  short sizeId
) {
  public CharacterSummary(
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
  ) {
    this(
      characterId, name, worldId, raceId, gender, zoneId, level, nationId, mainJobId, subJobId,
      (short) 0, (short) 0, (short) 1
    );
  }
}