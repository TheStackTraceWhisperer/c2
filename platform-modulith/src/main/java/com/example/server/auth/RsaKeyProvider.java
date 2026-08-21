package com.example.server.auth;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyFactory;
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

    RSAKey key = null;

    // 1. Try file mounts (/certs/jwt/tls.key, /certs/jwt/tls.crt)
    if (privPath != null && pubPath != null) {
      File privFile = new File(privPath);
      File pubFile = new File(pubPath);
      if (privFile.exists() && pubFile.exists()) {
        try {
          log.info("[AUTH] Loading RSA keypair from mounted files: {} and {}", privPath, pubPath);
          String privContent = Files.readString(privFile.toPath());
          String pubContent = Files.readString(pubFile.toPath());
          RSAPrivateKey privateKey = parsePrivateKey(privContent);
          RSAPublicKey publicKey = parsePublicKey(pubContent);
          key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("cluster-auth-key-1").build();
        } catch (Exception e) {
          log.error("[AUTH] Failed to load keys from files: {}", e.getMessage(), e);
        }
      }
    }

    // 2. Try raw PEM strings passed via env variables
    if (key == null && privKeyPem != null && pubKeyPem != null && !privKeyPem.isBlank() && !pubKeyPem.isBlank()) {
      try {
        log.info("[AUTH] Loading RSA keypair from environment string variables.");
        RSAPrivateKey privateKey = parsePrivateKey(privKeyPem);
        RSAPublicKey publicKey = parsePublicKey(pubKeyPem);
        key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("cluster-auth-key-1").build();
      } catch (Exception e) {
        log.error("[AUTH] Failed to parse keys from environment strings: {}", e.getMessage(), e);
      }
    }

    // 3. Fallback: Ephemeral in-memory generation
    if (key == null) {
      try {
        log.warn("[AUTH] Generating ephemeral in-memory RSA keypair.");
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
      return rsaJWK.toRSAPrivateKey();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to extract private key", e);
    }
  }
}