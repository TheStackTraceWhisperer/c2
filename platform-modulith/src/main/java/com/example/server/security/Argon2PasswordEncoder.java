package com.example.server.security;

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

  private static final int MAX_MEMORY_KB = 1024 * 1024; // 1 GB cap against DoS params
  private static final int MAX_ITERATIONS = 100;
  private static final int MAX_PARALLELISM = 16;
  private static final int MAX_HASH_LENGTH = 1024;

  private final SecureRandom secureRandom = new SecureRandom();

  public String encode(String rawPassword) {
    if (rawPassword == null) {
      throw new IllegalArgumentException("rawPassword must not be null");
    }
    byte[] salt = new byte[SALT_LENGTH];
    secureRandom.nextBytes(salt);

    byte[] hash = generateHash(rawPassword, salt, MEMORY_KB, ITERATIONS, PARALLELISM, HASH_LENGTH);

    return String.format("$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s",
      MEMORY_KB,
      ITERATIONS,
      PARALLELISM,
      Base64.getEncoder().withoutPadding().encodeToString(salt),
      Base64.getEncoder().withoutPadding().encodeToString(hash)
    );
  }

  public boolean matches(String rawPassword, String encodedPassword) {
    if (rawPassword == null || encodedPassword == null || !encodedPassword.startsWith("$argon2id$")) {
      return false;
    }

    String[] parts = encodedPassword.split("\\$", -1);
    if (parts.length != 6 || !"v=19".equals(parts[2])) {
      return false;
    }

    Integer memoryKb = null;
    Integer iterations = null;
    Integer parallelism = null;
    for (String param : parts[3].split(",", -1)) {
      String[] kv = param.split("=", 2);
      if (kv.length != 2) {
        return false;
      }
      int val;
      try {
        val = Integer.parseInt(kv[1]);
      } catch (NumberFormatException e) {
        return false;
      }
      switch (kv[0]) {
        case "m" -> memoryKb = val;
        case "t" -> iterations = val;
        case "p" -> parallelism = val;
        default -> {
          return false;
        }
      }
    }

    if (memoryKb == null || iterations == null || parallelism == null
      || memoryKb <= 0 || memoryKb > MAX_MEMORY_KB
      || iterations <= 0 || iterations > MAX_ITERATIONS
      || parallelism <= 0 || parallelism > MAX_PARALLELISM) {
      return false;
    }

    byte[] salt;
    byte[] expectedHash;
    try {
      salt = Base64.getDecoder().decode(parts[4]);
      expectedHash = Base64.getDecoder().decode(parts[5]);
    } catch (IllegalArgumentException e) {
      return false;
    }

    if (salt.length == 0 || expectedHash.length == 0 || expectedHash.length > MAX_HASH_LENGTH) {
      return false;
    }

    byte[] actualHash = generateHash(rawPassword, salt, memoryKb, iterations, parallelism, expectedHash.length);
    return MessageDigest.isEqual(expectedHash, actualHash);
  }

  private byte[] generateHash(
    String password,
    byte[] salt,
    int memoryKb,
    int iterations,
    int parallelism,
    int hashLength) {
    Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
      .withVersion(Argon2Parameters.ARGON2_VERSION_13)
      .withIterations(iterations)
      .withMemoryAsKB(memoryKb)
      .withParallelism(parallelism)
      .withSalt(salt)
      .build();

    Argon2BytesGenerator generator = new Argon2BytesGenerator();
    generator.init(params);

    byte[] hash = new byte[hashLength];
    generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), hash);
    return hash;
  }
}