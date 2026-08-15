package com.example.common.auth;

public record TokenClaims(long accountId, String username, long expiresAtEpochSeconds) {}