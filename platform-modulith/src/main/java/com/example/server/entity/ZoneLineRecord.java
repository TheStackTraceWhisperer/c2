package com.example.server.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

@MappedEntity(value = "zone_lines", schema = "game")
public record ZoneLineRecord(
  @Id
  int id,
  @MappedProperty("source_zone_id")
  int sourceZoneId,
  @MappedProperty("target_zone_id")
  int targetZoneId,
  String name,
  @MappedProperty("transition_mode")
  String transitionMode,
  @MappedProperty("center_x")
  float centerX,
  @MappedProperty("center_y")
  float centerY,
  @MappedProperty("center_z")
  float centerZ,
  @MappedProperty("extent_x")
  float extentX,
  @MappedProperty("extent_y")
  float extentY,
  @MappedProperty("extent_z")
  float extentZ,
  @MappedProperty("rotation_yaw")
  float rotationYaw,
  @MappedProperty("dest_x")
  float destX,
  @MappedProperty("dest_y")
  float destY,
  @MappedProperty("dest_z")
  float destZ,
  @MappedProperty("dest_yaw")
  float destYaw,
  @MappedProperty("dest_pitch")
  float destPitch,
  @MappedProperty("offset_dx")
  float offsetDx,
  @MappedProperty("offset_dy")
  float offsetDy,
  @MappedProperty("offset_dz")
  float offsetDz,
  @MappedProperty("fade_duration_ms")
  int fadeDurationMs,
  @MappedProperty("buffer_margin_m")
  float bufferMarginM
) {}