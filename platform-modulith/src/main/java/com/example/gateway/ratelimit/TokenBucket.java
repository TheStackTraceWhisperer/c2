package com.example.gateway.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, lock-free Token Bucket rate limiter.
 * State packed into a single 64-bit atomic word:
 * - High 32 bits: available fractional tokens (fixed-point Q16.16)
 * - Low 32 bits: last refill timestamp in milliseconds (wrapped to 32-bit offset)
 */
public class TokenBucket {

  private static final long TIME_MASK = 0xFFFFFFFFL;
  private static final long FP_SHIFT = 16L; // Q16.16 fixed-point
  private static final long ONE_TOKEN_FP = 1L << FP_SHIFT; // 65,536

  private final long capacityFp;
  private final long refillPerMsFp; // Tokens refilled per millisecond in Q16.16
  private final AtomicLong state;

  public TokenBucket(long capacity, long refillTokensPerSecond) {
    if (capacity <= 0 || refillTokensPerSecond <= 0) {
      throw new IllegalArgumentException("Capacity and refill rate must be positive");
    }
    this.capacityFp = capacity << FP_SHIFT;
    this.refillPerMsFp = (refillTokensPerSecond << FP_SHIFT) / 1000L;

    long nowMs = System.currentTimeMillis() & TIME_MASK;
    this.state = new AtomicLong((capacityFp << 32) | nowMs);
  }

  /**
   * Attempts to consume 1 token. Returns true if allowed, false if rate limited.
   */
  public boolean tryConsume() {
    while (true) {
      long current = state.get();
      long currentTokensFp = current >>> 32;
      long lastTimeMs = current & TIME_MASK;

      long nowMs = System.currentTimeMillis() & TIME_MASK;
      long elapsedMs = (nowMs >= lastTimeMs) ? (nowMs - lastTimeMs) : (nowMs + (1L << 32) - lastTimeMs);

      long newTokensFp = currentTokensFp;
      if (elapsedMs > 0) {
        long refilledFp = elapsedMs * refillPerMsFp;
        newTokensFp = Math.min(capacityFp, currentTokensFp + refilledFp);
      }

      if (newTokensFp < ONE_TOKEN_FP) {
        return false; // Rate limit exceeded
      }

      long nextTokensFp = newTokensFp - ONE_TOKEN_FP;
      long nextState = (nextTokensFp << 32) | nowMs;

      if (state.compareAndSet(current, nextState)) {
        return true;
      }
      Thread.onSpinWait();
    }
  }
}