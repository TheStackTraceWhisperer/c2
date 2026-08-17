package com.example.server.entity;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

@MappedEntity("characters")
public record CharacterRecord(
  long accountId,
  @Id @GeneratedValue(GeneratedValue.Type.IDENTITY)
  @MappedProperty("id")
  long characterId,
  String name,
  int level,
  String characterClass,
  @MappedProperty("realm_id")
  int worldId,
  int zoneId,
  @MappedProperty("pos_x") float posX,
  @MappedProperty("pos_y") float posY,
  @MappedProperty("pos_z") float posZ,
  float yaw,
  float pitch
) {}