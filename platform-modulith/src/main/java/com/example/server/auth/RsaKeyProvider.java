package com.example.server.auth;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Slf4j
@Singleton
public class RsaKeyProvider {

  @Getter
  private final RSAKey rsaJWK;

  public RsaKeyProvider(
    @Nullable @Value("${app.auth.private-key-path}") String privPath,
    @Nullable @Value("${app.auth.public-key-path}") String pubPath,
    @Nullable @Value("${app.auth.private-key}") String privKeyPem,
    @Nullable @Value("${app.auth.public-key}") String pubKeyPem) {

    boolean hasPrivPath = privPath != null && !privPath.isBlank();
    boolean hasPubPath = pubPath != null && !pubPath.isBlank();
    boolean hasPrivPem = privKeyPem != null && !privKeyPem.isBlank();
    boolean hasPubPem = pubKeyPem != null && !pubKeyPem.isBlank();

    RSAKey key = null;

    // 1. Try file mounts (/certs/jwt/tls.key, /certs/jwt/tls.crt)
    if (hasPubPath || hasPrivPath) {
      File pubFile = hasPubPath ? new File(pubPath) : null;
      File privFile = hasPrivPath ? new File(privPath) : null;

      if (pubFile != null && pubFile.exists()) {
        try {
          RSAPublicKey publicKey = parsePublicKey(Files.readString(pubFile.toPath()));
          if (privFile != null && privFile.exists()) {
            log.info("[AUTH] Loading RSA keypair from mounted files: {} and {}", privPath, pubPath);
            RSAPrivateKey privateKey = parsePrivateKey(Files.readString(privFile.toPath()));
            key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("cluster-auth-key-1").build();
          } else {
            log.info("[AUTH] Loading RSA public key in verifier-only mode from mounted file: {}", pubPath);
            key = new RSAKey.Builder(publicKey).keyID("cluster-auth-key-1").build();
          }
        } catch (Exception e) {
          throw new IllegalStateException("Failed to load RSA key(s) from configured path(s)", e);
        }
      } else if (privFile != null && privFile.exists()) {
        throw new IllegalStateException("Private key file exists at '" + privPath
          + "' but public key file is missing at '" + pubPath + "'");
      } else if (!hasPubPem && !hasPrivPem) {
        throw new IllegalStateException("Configured RSA key path(s) do not exist: pub=" + pubPath + ", priv=" + privPath);
      }
    }

    // 2. Try raw PEM strings passed via env variables
    if (key == null && (hasPubPem || hasPrivPem)) {
      if (!hasPubPem) {
        throw new IllegalStateException("Configured private key PEM without public key PEM");
      }
      try {
        RSAPublicKey publicKey = parsePublicKey(pubKeyPem);
        if (hasPrivPem) {
          log.info("[AUTH] Loading RSA keypair from environment string variables.");
          RSAPrivateKey privateKey = parsePrivateKey(privKeyPem);
          key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("cluster-auth-key-1").build();
        } else {
          log.info("[AUTH] Loading RSA public key in verifier-only mode from environment string variable.");
          key = new RSAKey.Builder(publicKey).keyID("cluster-auth-key-1").build();
        }
      } catch (Exception e) {
        throw new IllegalStateException("Failed to parse RSA key(s) from configured PEM strings", e);
      }
    }

    // 3. Fallback: Ephemeral in-memory generation ONLY when no key paths/PEMs are configured
    if (key == null) {
      try {
        log.warn("[AUTH] No RSA key paths or PEMs configured; generating ephemeral in-memory RSA keypair.");
        key = new RSAKeyGenerator(2048).keyID("cluster-auth-key-1").generate();
      } catch (Exception e) {
        throw new IllegalStateException("Failed to generate fallback RSA keypair", e);
      }
    }

    this.rsaJWK = key;
  }

  private static RSAPrivateKey parsePrivateKey(String pem) throws Exception {
    String clean = pem
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("-----END PRIVATE KEY-----", "")
      .replace("-----BEGIN RSA PRIVATE KEY-----", "")
      .replace("-----END RSA PRIVATE KEY-----", "")
      .replaceAll("\\s+", "");

    byte[] decoded = Base64.getDecoder().decode(clean);
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
    KeyFactory kf = KeyFactory.getInstance("RSA");
    return (RSAPrivateKey) kf.generatePrivate(spec);
  }

  private static RSAPublicKey parsePublicKey(String pem) throws Exception {
    if (pem.contains("-----BEGIN CERTIFICATE-----")) {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      X509Certificate cert = (X509Certificate) cf.generateCertificate(
        new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
      return (RSAPublicKey) cert.getPublicKey();
    }

    String clean = pem
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replace("-----END PUBLIC KEY-----", "")
      .replace("-----BEGIN RSA PUBLIC KEY-----", "")
      .replace("-----END RSA PUBLIC KEY-----", "")
      .replaceAll("\\s+", "");

    byte[] decoded = Base64.getDecoder().decode(clean);
    X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
    KeyFactory kf = KeyFactory.getInstance("RSA");
    return (RSAPublicKey) kf.generatePublic(spec);
  }

  public RSAPublicKey getPublicKey() {
    try {
      return rsaJWK.toRSAPublicKey();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to extract public key", e);
    }
  }

  public RSAPrivateKey getPrivateKey() {
    try {
      RSAPrivateKey privateKey = rsaJWK.toRSAPrivateKey();
      if (privateKey == null) {
        throw new IllegalStateException("RsaKeyProvider is in verifier-only mode (no private key configured)");
      }
      return privateKey;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to extract private key", e);
    }
  }

  public boolean hasPrivateKey() {
    return rsaJWK.isPrivate();
  }
}