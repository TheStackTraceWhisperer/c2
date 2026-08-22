package com.example.server.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

@MappedEntity(value = "zones", schema = "game")
public record ZoneRecord(
  @Id
  int id,
  String name,
  @MappedProperty("dat_path")
  String datPath,
  @MappedProperty("is_seamless")
  boolean isSeamless,
  @MappedProperty("min_x")
  float minX,
  @MappedProperty("min_y")
  float minY,
  @MappedProperty("min_z")
  float minZ,
  @MappedProperty("max_x")
  float maxX,
  @MappedProperty("max_y")
  float maxY,
  @MappedProperty("max_z")
  float maxZ
) {}