package com.example.gateway.session;

import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@RequiredArgsConstructor
public class ClientSession {

  public static final AttributeKey<ClientSession> KEY = AttributeKey.valueOf("CLIENT_SESSION");

  private final long sessionId;
  private final QuicChannel quicChannel;

  @Setter private volatile boolean authenticated = false;
  @Setter private volatile long accountId = 0;
  @Setter private volatile long characterId = 0;

  // Active backend streams associated specifically with THIS session
  private final Map<Short, QuicStreamChannel> backendStreams = new ConcurrentHashMap<>();
}