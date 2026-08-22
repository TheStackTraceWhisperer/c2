package com.example.server.entity;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.time.Instant;

@MappedEntity(value = "accounts", schema = "player")
public record AccountRecord(
  @Id @GeneratedValue(GeneratedValue.Type.IDENTITY)
  Long id,
  String username,
  String email,
  @MappedProperty("password_hash")
  String passwordHash,
  String status,
  @MappedProperty("created_at")
  Instant createdAt,
  @MappedProperty("updated_at")
  Instant updatedAt
) {
  public static AccountRecord of(String username, String email, String passwordHash) {
    return new AccountRecord(null, username, email, passwordHash, "ACTIVE", Instant.now(), Instant.now());
  }
}