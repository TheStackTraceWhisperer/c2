package com.example.server.security;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Singleton
public class Argon2PasswordEncoder {

  private static final int ITERATIONS = 3;
  private static final int MEMORY_KB = 65536; // 64 MB
  private static final int PARALLELISM = 1;
  private static final int HASH_LENGTH = 32;
  private static final int SALT_LENGTH = 16;

  private final SecureRandom secureRandom = new SecureRandom();

  public String encode(String rawPassword) {
    byte[] salt = new byte[SALT_LENGTH];
    secureRandom.nextBytes(salt);

    byte[] hash = generateHash(rawPassword, salt);

    return String.format("$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s",
      MEMORY_KB,
      ITERATIONS,
      PARALLELISM,
      Base64.getEncoder().withoutPadding().encodeToString(salt),
      Base64.getEncoder().withoutPadding().encodeToString(hash)
    );
  }

  public boolean matches(String rawPassword, String encodedPassword) {
    if (encodedPassword == null || !encodedPassword.startsWith("$argon2id$")) {
      return false;
    }

    String[] parts = encodedPassword.split("\\$");
    if (parts.length != 6) {
      return false;
    }

    byte[] salt = Base64.getDecoder().decode(parts[4]);
    byte[] expectedHash = Base64.getDecoder().decode(parts[5]);
    byte[] actualHash = generateHash(rawPassword, salt);

    return MessageDigest.isEqual(expectedHash, actualHash);
  }

  private byte[] generateHash(String password, byte[] salt) {
    Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
      .withVersion(Argon2Parameters.ARGON2_VERSION_13)
      .withIterations(ITERATIONS)
      .withMemoryAsKB(MEMORY_KB)
      .withParallelism(PARALLELISM)
      .withSalt(salt)
      .build();

    Argon2BytesGenerator generator = new Argon2BytesGenerator();
    generator.init(params);

    byte[] hash = new byte[HASH_LENGTH];
    generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), hash);
    return hash;
  }
}