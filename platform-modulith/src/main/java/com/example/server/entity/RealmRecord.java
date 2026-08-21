package com.example.server.entity;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.time.Instant;

@MappedEntity("realms")
public record RealmRecord(
  @Id
  int id,
  String name,
  @MappedProperty("realm_type")
  String realmType,
  String host,
  int port,
  String status,
  @MappedProperty("max_capacity")
  int maxCapacity,
  @MappedProperty("last_updated")
  Instant lastUpdated
) {}