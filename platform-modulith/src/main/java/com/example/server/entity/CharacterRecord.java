package com.example.server.entity;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.time.Instant;

@MappedEntity(value = "characters", schema = "player")
public record CharacterRecord(
  @MappedProperty("account_id")
  long accountId,
  @Id @GeneratedValue(GeneratedValue.Type.IDENTITY)
  @MappedProperty("id")
  long characterId,
  @MappedProperty("world_id")
  int worldId,
  String name,
  @MappedProperty("race_id")
  short raceId,
  short gender,
  @MappedProperty("face_id")
  short faceId,
  @MappedProperty("hair_id")
  short hairId,
  @MappedProperty("size_id")
  short sizeId,
  @MappedProperty("nation_id")
  short nationId,
  @MappedProperty("main_job_id")
  short mainJobId,
  @MappedProperty("main_job_level")
  short mainJobLevel,
  @MappedProperty("sub_job_id")
  short subJobId,
  @MappedProperty("sub_job_level")
  short subJobLevel,
  @MappedProperty("zone_id")
  int zoneId,
  @MappedProperty("pos_x")
  float posX,
  @MappedProperty("pos_y")
  float posY,
  @MappedProperty("pos_z")
  float posZ,
  @MappedProperty("rot_yaw")
  float rotYaw,
  @MappedProperty("rot_pitch")
  float rotPitch,
  @MappedProperty("hp_zone_id")
  int hpZoneId,
  @MappedProperty("hp_pos_x")
  float hpPosX,
  @MappedProperty("hp_pos_y")
  float hpPosY,
  @MappedProperty("hp_pos_z")
  float hpPosZ,
  @MappedProperty("last_saved_at")
  Instant lastSavedAt
) {}