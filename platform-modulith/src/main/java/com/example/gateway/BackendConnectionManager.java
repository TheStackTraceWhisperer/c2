package com.example.gateway;

import com.example.common.protocol.ProtocolConstants;
import com.example.common.protocol.ServiceType;
import com.example.config.LobbyServerProperties;
import com.example.config.LoginServerProperties;
import com.example.gateway.session.ClientSession;
import com.example.server.protocol.EndpointTarget;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class BackendConnectionManager {

  public record EndpointKey(short serviceId, String host, int port) {}

  private final LoginServerProperties loginProperties;
  private final LobbyServerProperties lobbyProperties;
  private final Map<EndpointKey, Future<QuicChannel>> connectionFutures = new ConcurrentHashMap<>();
  private Channel outboundDatagramChannel;

  public void init(EventLoopGroup group) throws Exception {
    QuicSslContext clientSslContext = QuicSslContextBuilder.forClient()
      .trustManager(InsecureTrustManagerFactory.INSTANCE)
      .applicationProtocols(ProtocolConstants.ALPN_PROTOCOL)
      .build();

    ChannelHandler clientCodec = new QuicClientCodecBuilder()
      .sslContext(clientSslContext)
      .maxIdleTimeout(30, TimeUnit.SECONDS)
      .initialMaxData(10000000)
      .initialMaxStreamDataBidirectionalLocal(1000000)
      .initialMaxStreamDataBidirectionalRemote(1000000)
      .build();

    outboundDatagramChannel = new Bootstrap()
      .group(group)
      .channel(NioDatagramChannel.class)
      .handler(clientCodec)
      .bind(0)
      .sync()
      .channel();

    log.info("[BACKEND-CONN-MGR] Outbound QUIC datagram channel initialized.");
  }

  public EndpointTarget resolveEndpoint(short serviceId, ClientSession session) {
    return switch (serviceId) {
      case ServiceType.AUTH -> new EndpointTarget(loginProperties.getHost(), loginProperties.getPort());
      case ServiceType.LOBBY -> new EndpointTarget(lobbyProperties.getHost(), lobbyProperties.getPort());
      case ServiceType.WORLD -> (session != null) ? session.getWorldEndpoint() : null;
      case ServiceType.ZONE -> (session != null) ? session.getZoneEndpoint() : null;
      default -> null;
    };
  }

  public Future<QuicChannel> getConnection(short serviceId, String host, int port, EventLoop eventLoop) {
    EndpointKey key = new EndpointKey(serviceId, host, port);

    Future<QuicChannel> existingFuture = connectionFutures.get(key);
    if (existingFuture != null && existingFuture.isSuccess() && existingFuture.getNow().isActive()) {
      return existingFuture;
    }

    return connectionFutures.compute(key, (k, currentFuture) -> {
      if (currentFuture != null && (!currentFuture.isDone() || (currentFuture.isSuccess() && currentFuture.getNow().isActive()))) {
        return currentFuture;
      }
      return initiateConnection(key, eventLoop);
    });
  }

  private Future<QuicChannel> initiateConnection(EndpointKey key, EventLoop eventLoop) {
    Promise<QuicChannel> promise = eventLoop.newPromise();
    log.info("[BACKEND-CONN-MGR] Connecting to service {} at {}:{}...", key.serviceId(), key.host(), key.port());

    if (outboundDatagramChannel == null || !outboundDatagramChannel.isActive()) {
      connectionFutures.remove(key, promise);
      promise.setFailure(new IllegalStateException("Outbound QUIC datagram channel is not active"));
      return promise;
    }

    QuicChannel.newBootstrap(outboundDatagramChannel)
      .handler(new ChannelInboundHandlerAdapter() {
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
          log.info("[BACKEND-CONN-MGR] Connection to service {} ({}:{}) closed. Evicting.",
            key.serviceId(), key.host(), key.port());
          connectionFutures.remove(key, promise);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
          log.error("[BACKEND-CONN-MGR] Error on service {} connection ({}:{}): {}",
            key.serviceId(), key.host(), key.port(), cause.getMessage());
          ctx.close();
        }
      })
      .remoteAddress(new InetSocketAddress(key.host(), key.port()))
      .connect()
      .addListener((Future<QuicChannel> future) -> {
        if (future.isSuccess()) {
          log.info("[BACKEND-CONN-MGR] Connected to service {} at {}:{}", key.serviceId(), key.host(), key.port());
          promise.setSuccess(future.getNow());
        } else {
          log.error("[BACKEND-CONN-MGR] Failed connection to service {} ({}:{}): {}",
            key.serviceId(), key.host(), key.port(), future.cause().getMessage());
          connectionFutures.remove(key, promise);
          promise.setFailure(future.cause());
        }
      });

    return promise;
  }

  @PreDestroy
  public void stop() {
    connectionFutures.values().forEach(future -> {
      if (future.isSuccess() && future.getNow().isActive()) {
        future.getNow().close();
      }
    });
    connectionFutures.clear();
    if (outboundDatagramChannel != null) {
      outboundDatagramChannel.close();
    }
  }
}