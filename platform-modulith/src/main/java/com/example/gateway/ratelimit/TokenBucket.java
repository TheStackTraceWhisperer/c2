package com.example.gateway.ratelimit;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Thread-safe Token Bucket rate limiter backed by monotonic {@link System#nanoTime()}.
 * Tracks tokens in sub-nanosecond precision (1 token = 1_000_000_000 nano-tokens)
 * so refill calculations remain overflow-safe across arbitrary idle intervals.
 */
public class TokenBucket {

  public static final long MAX_CAPACITY_EXCLUSIVE = 65536L;
  private static final long NANO_PER_TOKEN = 1_000_000_000L;

  private final long capacityNanoTokens;
  private final long refillTokensPerSecond;
  private final LongSupplier nanoClock;

  private long availableNanoTokens;
  private long lastRefillNanos;

  public TokenBucket(long capacity, long refillTokensPerSecond) {
    this(capacity, refillTokensPerSecond, System::nanoTime);
  }

  TokenBucket(long capacity, long refillTokensPerSecond, LongSupplier nanoClock) {
    if (capacity <= 0 || capacity >= MAX_CAPACITY_EXCLUSIVE) {
      throw new IllegalArgumentException("Capacity must be in (0, 65536): " + capacity);
    }
    if (refillTokensPerSecond <= 0) {
      throw new IllegalArgumentException("Refill rate must be positive: " + refillTokensPerSecond);
    }
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.capacityNanoTokens = capacity * NANO_PER_TOKEN;
    this.refillTokensPerSecond = refillTokensPerSecond;
    this.availableNanoTokens = this.capacityNanoTokens;
    this.lastRefillNanos = this.nanoClock.getAsLong();
  }

  /**
   * Attempts to consume 1 token. Returns true if allowed, false if rate limited.
   */
  public synchronized boolean tryConsume() {
    long now = nanoClock.getAsLong();
    long elapsedNanos = now - lastRefillNanos;

    if (elapsedNanos > 0) {
      long deficit = capacityNanoTokens - availableNanoTokens;
      if (deficit > 0) {
        long fullRefillThresholdNanos = deficit / refillTokensPerSecond;
        if (elapsedNanos > fullRefillThresholdNanos) {
          availableNanoTokens = capacityNanoTokens;
        } else {
          availableNanoTokens += elapsedNanos * refillTokensPerSecond;
        }
      }
      lastRefillNanos = now;
    }

    if (availableNanoTokens < NANO_PER_TOKEN) {
      return false;
    }

    availableNanoTokens -= NANO_PER_TOKEN;
    return true;
  }
}