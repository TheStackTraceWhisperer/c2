package com.example.server.auth;

import com.nimbusds.jose.JWSAlgorithm;
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

  public static final String EXPECTED_ISSUER = "https://auth.game.internal";

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

      if (!JWSAlgorithm.RS256.equals(signedJWT.getHeader().getAlgorithm())) {
        log.warn("[JWT-VERIFIER] Rejected token with unexpected algorithm: {}", signedJWT.getHeader().getAlgorithm());
        return Optional.empty();
      }

      if (!signedJWT.verify(verifier)) {
        log.warn("[JWT-VERIFIER] Token signature validation failed");
        return Optional.empty();
      }

      JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

      if (!EXPECTED_ISSUER.equals(claims.getIssuer())) {
        log.warn("[JWT-VERIFIER] Token issuer mismatch: {}", claims.getIssuer());
        return Optional.empty();
      }

      Date now = new Date();
      Date notBefore = claims.getNotBeforeTime();
      if (notBefore != null && now.before(notBefore)) {
        log.warn("[JWT-VERIFIER] Token not valid before {}", notBefore);
        return Optional.empty();
      }

      Date expirationTime = claims.getExpirationTime();
      if (expirationTime == null || !now.before(expirationTime)) {
        log.warn("[JWT-VERIFIER] Token expired at {}", expirationTime);
        return Optional.empty();
      }

      long accountId = Long.parseLong(claims.getSubject());
      String username = claims.getStringClaim("username");
      if (username == null || username.isBlank()) {
        log.warn("[JWT-VERIFIER] Token missing username claim");
        return Optional.empty();
      }
      long expSeconds = expirationTime.getTime() / 1000L;

      return Optional.of(new TokenClaims(accountId, username, expSeconds));
    } catch (Exception e) {
      log.warn("[JWT-VERIFIER] Malformed or unparseable JWT: {}", e.getMessage());
      return Optional.empty();
    }
  }
}