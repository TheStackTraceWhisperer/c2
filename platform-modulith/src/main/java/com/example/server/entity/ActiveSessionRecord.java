package com.example.server.entity;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.time.Instant;

@MappedEntity(value = "active_sessions", schema = "player")
public record ActiveSessionRecord(
  @Id
  @MappedProperty("account_id")
  long accountId,
  @MappedProperty("session_id")
  long sessionId,
  @MappedProperty("gateway_node")
  int gatewayNode,
  @Nullable
  @MappedProperty("character_id")
  Long characterId,
  @MappedProperty("login_time")
  Instant loginTime,
  @MappedProperty("last_ping")
  Instant lastPing
) {}