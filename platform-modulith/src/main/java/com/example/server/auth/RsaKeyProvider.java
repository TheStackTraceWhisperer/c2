package com.example.server.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import jakarta.inject.Singleton;
import lombok.Getter;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Singleton
public class RsaKeyProvider {

  @Getter
  private final RSAKey rsaJWK;

  public RsaKeyProvider() {
    try {
      this.rsaJWK = new RSAKeyGenerator(2048)
        .keyID("auth-key-1")
        .generate();
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to generate RSA keypair", e);
    }
  }

  public RSAPublicKey getPublicKey() {
    try {
      return rsaJWK.toRSAPublicKey();
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to extract public key", e);
    }
  }

  public RSAPrivateKey getPrivateKey() {
    try {
      return rsaJWK.toRSAPrivateKey();
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to extract private key", e);
    }
  }
}