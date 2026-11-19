package com.example.server.auth;

import com.example.login.auth.JwtTokenIssuer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAndKeyProviderTest {

  @Test
  @DisplayName("JwtTokenVerifier accepts valid RS256 token and rejects wrong alg, wrong iss, future nbf, and expired tokens")
  void testJwtTokenVerifierHardening() throws Exception {
    RsaKeyProvider keyProvider = new RsaKeyProvider(null, null, null, null);
    JwtTokenIssuer issuer = new JwtTokenIssuer(keyProvider);
    JwtTokenVerifier verifier = new JwtTokenVerifier(keyProvider);

    // Valid token
    String validToken = issuer.issueToken(42L, "adventurer", 15);
    Optional<TokenClaims> claimsOpt = verifier.verify(validToken);
    assertThat(claimsOpt).isPresent();
    assertThat(claimsOpt.get().accountId()).isEqualTo(42L);
    assertThat(claimsOpt.get().username()).isEqualTo("adventurer");

    // Wrong algorithm: RS512 signed with same RSA key must be rejected
    Instant now = Instant.now();
    SignedJWT rs512 = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.RS512).build(),
      new JWTClaimsSet.Builder()
        .subject("42")
        .claim("username", "adventurer")
        .issuer(JwtTokenVerifier.EXPECTED_ISSUER)
        .expirationTime(Date.from(now.plus(5, ChronoUnit.MINUTES)))
        .build()
    );
    rs512.sign(new RSASSASigner(keyProvider.getPrivateKey()));
    assertThat(verifier.verify(rs512.serialize())).isEmpty();

    // Wrong algorithm: HS256 must be rejected
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    SignedJWT hs256 = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
      new JWTClaimsSet.Builder()
        .subject("42")
        .claim("username", "adventurer")
        .issuer(JwtTokenVerifier.EXPECTED_ISSUER)
        .expirationTime(Date.from(now.plus(5, ChronoUnit.MINUTES)))
        .build()
    );
    hs256.sign(new MACSigner(secret));
    assertThat(verifier.verify(hs256.serialize())).isEmpty();

    // Wrong issuer
    SignedJWT wrongIssuer = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
      new JWTClaimsSet.Builder()
        .subject("42")
        .claim("username", "adventurer")
        .issuer("https://untrusted.example")
        .expirationTime(Date.from(now.plus(5, ChronoUnit.MINUTES)))
        .build()
    );
    wrongIssuer.sign(new RSASSASigner(keyProvider.getPrivateKey()));
    assertThat(verifier.verify(wrongIssuer.serialize())).isEmpty();

    // Future nbf
    SignedJWT futureNbf = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
      new JWTClaimsSet.Builder()
        .subject("42")
        .claim("username", "adventurer")
        .issuer(JwtTokenVerifier.EXPECTED_ISSUER)
        .notBeforeTime(Date.from(now.plus(2, ChronoUnit.MINUTES)))
        .expirationTime(Date.from(now.plus(10, ChronoUnit.MINUTES)))
        .build()
    );
    futureNbf.sign(new RSASSASigner(keyProvider.getPrivateKey()));
    assertThat(verifier.verify(futureNbf.serialize())).isEmpty();

    // Expired token
    SignedJWT expired = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
      new JWTClaimsSet.Builder()
        .subject("42")
        .claim("username", "adventurer")
        .issuer(JwtTokenVerifier.EXPECTED_ISSUER)
        .expirationTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
        .build()
    );
    expired.sign(new RSASSASigner(keyProvider.getPrivateKey()));
    assertThat(verifier.verify(expired.serialize())).isEmpty();
  }

  @Test
  @DisplayName("RsaKeyProvider supports verifier-only mode and fails fast on invalid configured keys")
  void testRsaKeyProviderVerifierOnlyAndFailFast(@TempDir Path tempDir) throws Exception {
    RsaKeyProvider generator = new RsaKeyProvider(null, null, null, null);
    String pubPem = "-----BEGIN PUBLIC KEY-----\n"
      + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(generator.getPublicKey().getEncoded())
      + "\n-----END PUBLIC KEY-----\n";

    Path pubFile = tempDir.resolve("tls.crt");
    Files.writeString(pubFile, pubPem);

    // Verifier-only mode from public key file even when private key path does not exist (K8s World/Zone pod pattern)
    RsaKeyProvider verifierOnly = new RsaKeyProvider(
      tempDir.resolve("missing-tls.key").toString(),
      pubFile.toString(),
      null,
      null
    );
    assertThat(verifierOnly.hasPrivateKey()).isFalse();
    assertThat(verifierOnly.getPublicKey()).isEqualTo(generator.getPublicKey());
    assertThatThrownBy(verifierOnly::getPrivateKey)
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("verifier-only");

    // Token signed with generator verifies cleanly with verifierOnly
    String token = new JwtTokenIssuer(generator).issueToken(77L, "hero", 10);
    assertThat(new JwtTokenVerifier(verifierOnly).verify(token)).isPresent();

    // Fail fast when configured key file fails to parse
    Path badPubFile = tempDir.resolve("corrupt.pub");
    Files.writeString(badPubFile, "NOT A VALID PEM");
    assertThatThrownBy(() -> new RsaKeyProvider(null, badPubFile.toString(), null, null))
      .isInstanceOf(IllegalStateException.class);

    // Fail fast when configured key path does not exist at all
    assertThatThrownBy(() -> new RsaKeyProvider(null, tempDir.resolve("absent.pub").toString(), null, null))
      .isInstanceOf(IllegalStateException.class);

    // Fail fast when PEM env var is invalid
    assertThatThrownBy(() -> new RsaKeyProvider(null, null, null, "invalid-pem"))
      .isInstanceOf(IllegalStateException.class);
  }
}
