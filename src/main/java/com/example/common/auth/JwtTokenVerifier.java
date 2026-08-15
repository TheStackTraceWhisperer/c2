package com.example.common.auth;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.Optional;

@Slf4j
@Singleton
public class JwtTokenVerifier implements TokenVerifier {

  private final JWSVerifier verifier;

  public JwtTokenVerifier(RsaKeyProvider keyProvider) {
    this.verifier = new RSASSAVerifier(keyProvider.getPublicKey());
  }

  @Override
  public Optional<TokenClaims> verify(String tokenString) {
    if (tokenString == null || tokenString.isBlank()) {
      return Optional.empty();
    }

    try {
      SignedJWT signedJWT = SignedJWT.parse(tokenString);

      if (!signedJWT.verify(verifier)) {
        log.warn("[JWT-VERIFIER] Token signature validation failed");
        return Optional.empty();
      }

      JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
      Date expirationTime = claims.getExpirationTime();

      if (expirationTime == null || new Date().after(expirationTime)) {
        log.warn("[JWT-VERIFIER] Token expired at {}", expirationTime);
        return Optional.empty();
      }

      long accountId = Long.parseLong(claims.getSubject());
      String username = claims.getStringClaim("username");
      long expSeconds = expirationTime.getTime() / 1000L;

      return Optional.of(new TokenClaims(accountId, username, expSeconds));
    } catch (Exception e) {
      log.warn("[JWT-VERIFIER] Malformed or unparseable JWT: {}", e.getMessage());
      return Optional.empty();
    }
  }
}