package com.example.common.auth;

import java.util.Optional;

public interface TokenVerifier {
  Optional<TokenClaims> verify(String jwtToken);
}