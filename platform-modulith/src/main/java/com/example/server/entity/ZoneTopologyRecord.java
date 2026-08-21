package com.example.server.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.time.Instant;

@MappedEntity("zones")
public record ZoneTopologyRecord(
  @Id
  int id,
  @MappedProperty("realm_id")
  int realmId,
  String name,
  String host,
  int port,
  String status,
  @MappedProperty("is_static")
  boolean isStatic,
  @MappedProperty("last_heartbeat")
  Instant lastHeartbeat
) {}