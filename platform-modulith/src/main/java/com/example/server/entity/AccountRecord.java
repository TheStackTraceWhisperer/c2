package com.example.server.entity;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.time.Instant;

@MappedEntity("accounts")
public record AccountRecord(
  @Id @GeneratedValue(GeneratedValue.Type.IDENTITY)
  @MappedProperty("id")
  Long id,
  String username,
  @MappedProperty("password_hash")
  String passwordHash,
  @MappedProperty("created_at")
  Instant createdAt,
  @MappedProperty("updated_at")
  Instant updatedAt
) {
  public static AccountRecord of(String username, String passwordHash) {
    return new AccountRecord(null, username, passwordHash, Instant.now(), Instant.now());
  }
}