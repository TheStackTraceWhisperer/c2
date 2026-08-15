package com.example.gateway.session;

import com.example.gateway.ratelimit.TokenBucket;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class ClientSession {

  public static final AttributeKey<ClientSession> KEY = AttributeKey.valueOf("CLIENT_SESSION");

  private final long sessionId;
  private final QuicChannel quicChannel;
  private final TokenBucket rateLimiter;

  @Setter private volatile boolean authenticated = false;
  @Setter private volatile long accountId = 0;
  @Setter private volatile long characterId = 0;

  private final Map<Short, QuicStreamChannel> backendStreams = new ConcurrentHashMap<>();

  public ClientSession(long sessionId, QuicChannel quicChannel, long maxBurst, long tokensPerSecond) {
    this.sessionId = sessionId;
    this.quicChannel = quicChannel;
    this.rateLimiter = new TokenBucket(maxBurst, tokensPerSecond);
  }
}