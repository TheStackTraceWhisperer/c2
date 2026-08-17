package com.example.server.auth;

public record TokenClaims(
  long accountId,
  String username,
  long expiresAtEpochSeconds
) {}