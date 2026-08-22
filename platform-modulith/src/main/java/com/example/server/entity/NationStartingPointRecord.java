package com.example.server.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

@MappedEntity(value = "nation_starting_points", schema = "game")
public record NationStartingPointRecord(
  @Id
  @MappedProperty("nation_id")
  int nationId,
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
  float rotPitch
) {}