package com.example.server.entity;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.time.Instant;

@MappedEntity(value = "zone_instances", schema = "infra")
public record ZoneInstanceRecord(
  @Id @GeneratedValue(GeneratedValue.Type.IDENTITY)
  int id,
  @MappedProperty("world_id")
  int worldId,
  @MappedProperty("zone_id")
  int zoneId,
  @MappedProperty("instance_tag")
  String instanceTag,
  String host,
  int port,
  String status,
  @MappedProperty("is_static")
  boolean isStatic,
  @MappedProperty("last_heartbeat")
  Instant lastHeartbeat
) {}