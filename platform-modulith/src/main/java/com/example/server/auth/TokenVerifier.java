package com.example.server.auth;

import java.util.Optional;

public interface TokenVerifier {
  Optional<TokenClaims> verify(String jwtToken);
}