package com.example.gateway;

import com.example.common.protocol.ProtocolConstants;
import com.example.common.protocol.ServiceType;
import com.example.config.DemoProperties;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class BackendConnectionManager {

  private final DemoProperties properties;
  // Cache the ongoing/active connection Future directly
  private final Map<Short, Future<QuicChannel>> connectionFutures = new ConcurrentHashMap<>();
  private Channel outboundDatagramChannel;

  public BackendConnectionManager(DemoProperties properties) {
    this.properties = properties;
  }

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

  /**
   * Lock-free atomic connection retrieval.
   */
  public Future<QuicChannel> getConnection(short serviceId, EventLoop eventLoop) {
    // Check if we have an existing active connection
    Future<QuicChannel> existingFuture = connectionFutures.get(serviceId);
    if (existingFuture != null && existingFuture.isSuccess() && existingFuture.getNow().isActive()) {
      return existingFuture;
    }

    // Atomic computeIfAbsent prevents duplicate bootstrap attempts without coarse locks
    return connectionFutures.compute(serviceId, (svc, currentFuture) -> {
      if (currentFuture != null && (!currentFuture.isDone() || (currentFuture.isSuccess() && currentFuture.getNow().isActive()))) {
        return currentFuture;
      }
      return initiateConnection(serviceId, eventLoop);
    });
  }

  private Future<QuicChannel> initiateConnection(short serviceId, EventLoop eventLoop) {
    DemoProperties.EndpointProperties endpoint = resolveEndpoint(serviceId);
    if (endpoint == null) {
      return eventLoop.newFailedFuture(new IllegalArgumentException("No backend registered for serviceId: " + serviceId));
    }

    Promise<QuicChannel> promise = eventLoop.newPromise();
    log.info("[BACKEND-CONN-MGR] Connecting to service {} at {}:{}...", serviceId, endpoint.getHostname(), endpoint.getPort());

    QuicChannel.newBootstrap(outboundDatagramChannel)
      .handler(new ChannelInboundHandlerAdapter() {
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
          log.info("[BACKEND-CONN-MGR] Connection to service {} closed. Evicting from cache.", serviceId);
          connectionFutures.remove(serviceId);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
          log.error("[BACKEND-CONN-MGR] Error on service {} connection: {}", serviceId, cause.getMessage());
          ctx.close();
        }
      })
      .remoteAddress(new InetSocketAddress(endpoint.getHostname(), endpoint.getPort()))
      .connect()
      .addListener((Future<QuicChannel> future) -> {
        if (future.isSuccess()) {
          QuicChannel channel = future.getNow();
          log.info("[BACKEND-CONN-MGR] Connected to service {}.", serviceId);
          promise.setSuccess(channel);
        } else {
          log.error("[BACKEND-CONN-MGR] Failed to connect to service {}: {}", serviceId, future.cause().getMessage());
          connectionFutures.remove(serviceId);
          promise.setFailure(future.cause());
        }
      });

    return promise;
  }

  private DemoProperties.EndpointProperties resolveEndpoint(short serviceId) {
    return switch (serviceId) {
      case ServiceType.AUTH -> properties.getLogin();
      case ServiceType.LOBBY -> properties.getLobby();
      default -> null;
    };
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