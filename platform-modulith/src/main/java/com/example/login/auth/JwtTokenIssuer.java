package com.example.login.auth;

import com.example.server.auth.RsaKeyProvider;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Singleton
public class JwtTokenIssuer {

  private final JWSSigner signer;
  private final String keyId;

  public JwtTokenIssuer(RsaKeyProvider keyProvider) {
    this.signer = new RSASSASigner(keyProvider.getPrivateKey());
    this.keyId = keyProvider.getRsaJWK().getKeyID();
  }

  public String issueToken(long accountId, String username, long durationMinutes) {
    try {
      Instant now = Instant.now();
      Instant exp = now.plus(durationMinutes, ChronoUnit.MINUTES);

      JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
        .subject(String.valueOf(accountId))
        .claim("username", username)
        .issuer("https://auth.game.internal")
        .issueTime(Date.from(now))
        .expirationTime(Date.from(exp))
        .build();

      JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(keyId)
        .build();

      SignedJWT signedJWT = new SignedJWT(header, claimsSet);
      signedJWT.sign(signer);

      return signedJWT.serialize();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to issue signed JWT token", e);
    }
  }
}