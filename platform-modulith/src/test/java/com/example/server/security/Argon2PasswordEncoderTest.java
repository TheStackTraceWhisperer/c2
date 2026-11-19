package com.example.server.security;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Argon2PasswordEncoderTest {

  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder();

  @Test
  @DisplayName("Encodes and verifies matching passwords while rejecting mismatches and null inputs")
  void testEncodeMatchesAndNullGuards() {
    assertThatThrownBy(() -> encoder.encode(null)).isInstanceOf(IllegalArgumentException.class);

    String encoded = encoder.encode("correct-horse-battery");
    assertThat(encoder.matches("correct-horse-battery", encoded)).isTrue();
    assertThat(encoder.matches("wrong-password", encoded)).isFalse();
    assertThat(encoder.matches(null, encoded)).isFalse();
    assertThat(encoder.matches("pw", null)).isFalse();
    assertThat(encoder.matches(null, null)).isFalse();
  }

  @Test
  @DisplayName("Parses stored m=, t=, p= parameters dynamically when matching")
  void testParsesStoredCostParameters() {
    byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
      .withVersion(Argon2Parameters.ARGON2_VERSION_13)
      .withMemoryAsKB(1024)
      .withIterations(2)
      .withParallelism(1)
      .withSalt(salt)
      .build();
    Argon2BytesGenerator gen = new Argon2BytesGenerator();
    gen.init(params);
    byte[] hash = new byte[32];
    gen.generateBytes("custom-params-pw".getBytes(StandardCharsets.UTF_8), hash);

    String encoded = String.format("$argon2id$v=19$m=1024,t=2,p=1$%s$%s",
      Base64.getEncoder().withoutPadding().encodeToString(salt),
      Base64.getEncoder().withoutPadding().encodeToString(hash)
    );

    assertThat(encoder.matches("custom-params-pw", encoded)).isTrue();
    assertThat(encoder.matches("wrong", encoded)).isFalse();
  }

  @Test
  @DisplayName("Returns false without throwing on malformed Base64, missing fields, or excessive parameters")
  void testMalformedInputsReturnFalse() {
    assertThat(encoder.matches("pw", "not-argon2")).isFalse();
    assertThat(encoder.matches("pw", "$argon2id$v=19$m=1024,t=2,p=1$!!!notbase64!!!$abc")).isFalse();
    assertThat(encoder.matches("pw", "$argon2id$v=19$m=1024,t=2,p=1$AAAA$!!!badbase64!!!")).isFalse();
    assertThat(encoder.matches("pw", "$argon2id$v=18$m=1024,t=2,p=1$AAAA$AAAA")).isFalse();
    assertThat(encoder.matches("pw", "$argon2id$v=19$m=1024,t=2$AAAA$AAAA")).isFalse();
    assertThat(encoder.matches("pw", "$argon2id$v=19$m=2147483647,t=2,p=1$AAAA$AAAA")).isFalse();
    assertThat(encoder.matches("pw", "$argon2id$v=19$m=abc,t=2,p=1$AAAA$AAAA")).isFalse();
    assertThat(encoder.matches("pw", "$argon2id$v=19$m=1024,t=2,p=1$AAAA$")).isFalse();
  }
}
