package com.example.gateway.session;

import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicLong;

@Singleton
public class SessionIdGenerator {

  private static final long NODE_ID_BITS = 10L;
  private static final long SEQUENCE_BITS = 12L;
  private static final long MAX_NODE_ID = (1L << NODE_ID_BITS) - 1; // 1023
  private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1; // 4095

  private static final long NODE_ID_SHIFT = SEQUENCE_BITS;
  private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + NODE_ID_BITS;

  // Custom epoch (Jan 1, 2026 UTC = 1767225600000L)
  private static final long CUSTOM_EPOCH = 1767225600000L;

  private final long nodeId;

  // High 52 bits: timestamp offset, Low 12 bits: sequence
  private final AtomicLong state = new AtomicLong(0L);

  public SessionIdGenerator(long nodeId) {
    if (nodeId < 0 || nodeId > MAX_NODE_ID) {
      throw new IllegalArgumentException("nodeId must be between 0 and " + MAX_NODE_ID);
    }
    this.nodeId = nodeId;
  }

  /**
   * Generates a 64-bit Snowflake session ID via lock-free atomic CAS.
   */
  public long nextSessionId() {
    while (true) {
      long current = state.get();
      long lastTimestamp = current >>> SEQUENCE_BITS;
      long currentSequence = current & MAX_SEQUENCE;

      long now = System.currentTimeMillis() - CUSTOM_EPOCH;
      if (now < lastTimestamp) {
        now = lastTimestamp; // Guard against clock skew
      }

      long nextTimestamp = now;
      long nextSequence;

      if (now == lastTimestamp) {
        nextSequence = (currentSequence + 1) & MAX_SEQUENCE;
        if (nextSequence == 0) {
          // Sequence exhausted in this millisecond; spin until next millisecond
          while ((now = System.currentTimeMillis() - CUSTOM_EPOCH) <= lastTimestamp) {
            Thread.onSpinWait();
          }
          nextTimestamp = now;
        }
      } else {
        nextSequence = 0L;
      }

      long nextState = (nextTimestamp << SEQUENCE_BITS) | nextSequence;
      if (state.compareAndSet(current, nextState)) {
        return (nextTimestamp << TIMESTAMP_SHIFT)
          | (nodeId << NODE_ID_SHIFT)
          | nextSequence;
      }

      Thread.onSpinWait();
    }
  }
}