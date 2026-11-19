package com.example.gateway.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketTest {

  @Test
  @DisplayName("Constructor enforces bounds (0 < capacity < 65536, refillTokensPerSecond > 0)")
  void testConstructorBounds() {
    assertThatThrownBy(() -> new TokenBucket(0, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucket(-5, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucket(65536, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucket(10, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucket(10, -1)).isInstanceOf(IllegalArgumentException.class);

    TokenBucket maxValid = new TokenBucket(65535, 1);
    assertThat(maxValid.tryConsume()).isTrue();
  }

  @Test
  @DisplayName("Monotonic nanoTime refill works across negative start, partial seconds, and huge idle gaps without overflow")
  void testMonotonicRefillAndOverflowSafety() {
    AtomicLong clock = new AtomicLong(-10_000_000_000L);
    TokenBucket bucket = new TokenBucket(5, 2, clock::get);

    // Exhaust initial burst of 5
    for (int i = 0; i < 5; i++) {
      assertThat(bucket.tryConsume()).isTrue();
    }
    assertThat(bucket.tryConsume()).isFalse();

    // Advance 499 ms -> still < 1 token (0.998 tokens)
    clock.addAndGet(499_000_000L);
    assertThat(bucket.tryConsume()).isFalse();

    // Advance 1 ms more -> 500 ms total at 2 tokens/sec = 1 full token
    clock.addAndGet(1_000_000L);
    assertThat(bucket.tryConsume()).isTrue();
    assertThat(bucket.tryConsume()).isFalse();

    // Advance by a massive elapsed duration (near Long.MAX_VALUE / 4) -> caps cleanly at capacity=5 without overflow
    clock.addAndGet(Long.MAX_VALUE / 4);
    for (int i = 0; i < 5; i++) {
      assertThat(bucket.tryConsume()).isTrue();
    }
    assertThat(bucket.tryConsume()).isFalse();
  }
}
