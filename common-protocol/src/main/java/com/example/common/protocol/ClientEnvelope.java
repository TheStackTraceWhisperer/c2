package com.example.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;

/**
 * Wire envelope for client-gateway communication, delegating Netty reference counting to its payload ByteBuf.
 */
public record ClientEnvelope(
  short serviceId,
  short messageId,
  ByteBuf payload
) implements ReferenceCounted {

  @Override
  public int refCnt() {
    return payload != null ? payload.refCnt() : 0;
  }

  @Override
  public ClientEnvelope retain() {
    if (payload != null) {
      payload.retain();
    }
    return this;
  }

  @Override
  public ClientEnvelope retain(int increment) {
    if (payload != null) {
      payload.retain(increment);
    }
    return this;
  }

  @Override
  public ClientEnvelope touch() {
    if (payload != null) {
      payload.touch();
    }
    return this;
  }

  @Override
  public ClientEnvelope touch(Object hint) {
    if (payload != null) {
      payload.touch(hint);
    }
    return this;
  }

  @Override
  public boolean release() {
    return payload == null || payload.release();
  }

  @Override
  public boolean release(int decrement) {
    return payload == null || payload.release(decrement);
  }
}