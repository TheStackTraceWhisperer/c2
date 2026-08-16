package com.example.gateway.session;

import com.example.config.DemoProperties.EndpointProperties;
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
  @Setter private volatile long accountId = 0L;
  @Setter private volatile long characterId = 0L;

  @Setter private volatile int currentWorldId = 1;
  @Setter private volatile EndpointProperties worldEndpoint;

  @Setter private volatile int currentZoneId = 1;
  @Setter private volatile EndpointProperties zoneEndpoint;

  // Keyed by BackendConnectionManager.makeKey(serviceId, host, port)
  private final Map<Long, QuicStreamChannel> backendStreams = new ConcurrentHashMap<>();

  public ClientSession(long sessionId, QuicChannel quicChannel, long maxBurst, long tokensPerSecond) {
    this.sessionId = sessionId;
    this.quicChannel = quicChannel;
    this.rateLimiter = new TokenBucket(maxBurst, tokensPerSecond);
  }
}