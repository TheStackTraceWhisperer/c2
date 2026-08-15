package com.example.gateway.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance, allocation-free Token Bucket rate limiter.
 * State packed into a single 64-bit atomic word:
 * - High 32 bits: available tokens (fixed-point integer)
 * - Low 32 bits: last refill timestamp in seconds (offset from custom epoch)
 */
public class TokenBucket {

  private static final long TIME_MASK = 0xFFFFFFFFL;
  private static final long TOKEN_SHIFT = 32L;

  private final long capacity;
  private final long refillTokensPerSecond;
  private final AtomicLong state;

  public TokenBucket(long capacity, long refillTokensPerSecond) {
    if (capacity <= 0 || refillTokensPerSecond <= 0) {
      throw new IllegalArgumentException("Capacity and refill rate must be positive");
    }
    this.capacity = capacity;
    this.refillTokensPerSecond = refillTokensPerSecond;

    long initialTime = (System.currentTimeMillis() / 1000L) & TIME_MASK;
    this.state = new AtomicLong((capacity << TOKEN_SHIFT) | initialTime);
  }

  /**
   * Attempts to consume 1 token. Returns true if allowed, false if rate limited.
   */
  public boolean tryConsume() {
    while (true) {
      long current = state.get();
      long currentTokens = current >>> TOKEN_SHIFT;
      long lastTime = current & TIME_MASK;

      long nowTime = (System.currentTimeMillis() / 1000L) & TIME_MASK;
      long elapsedSeconds = (nowTime >= lastTime) ? (nowTime - lastTime) : 0L;

      long newTokens = currentTokens;
      if (elapsedSeconds > 0) {
        long refilled = elapsedSeconds * refillTokensPerSecond;
        newTokens = Math.min(capacity, currentTokens + refilled);
      }

      if (newTokens < 1) {
        return false; // Rate limit exceeded
      }

      long nextTokens = newTokens - 1;
      long nextState = (nextTokens << TOKEN_SHIFT) | nowTime;

      if (state.compareAndSet(current, nextState)) {
        return true;
      }
      Thread.onSpinWait();
    }
  }
}