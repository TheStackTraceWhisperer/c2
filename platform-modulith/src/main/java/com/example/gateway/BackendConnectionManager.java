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
  private final Map<Long, Future<QuicChannel>> connectionFutures = new ConcurrentHashMap<>();
  private Channel outboundDatagramChannel;

  public BackendConnectionManager(DemoProperties properties) {
    this.properties = properties;
  }

  public static long makeKey(short serviceId, int instanceId) {
    return (((long) serviceId) << 32) | (instanceId & 0xFFFFFFFFL);
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

  public Future<QuicChannel> getConnection(short serviceId, int instanceId, EventLoop eventLoop) {
    long key = makeKey(serviceId, instanceId);

    Future<QuicChannel> existingFuture = connectionFutures.get(key);
    if (existingFuture != null && existingFuture.isSuccess() && existingFuture.getNow().isActive()) {
      return existingFuture;
    }

    return connectionFutures.compute(key, (k, currentFuture) -> {
      if (currentFuture != null && (!currentFuture.isDone() || (currentFuture.isSuccess() && currentFuture.getNow().isActive()))) {
        return currentFuture;
      }
      return initiateConnection(serviceId, instanceId, key, eventLoop);
    });
  }

  private Future<QuicChannel> initiateConnection(short serviceId, int instanceId, long key, EventLoop eventLoop) {
    DemoProperties.EndpointProperties endpoint = resolveEndpoint(serviceId, instanceId);
    if (endpoint == null) {
      return eventLoop.newFailedFuture(new IllegalArgumentException(
        "No backend registered for serviceId: " + serviceId + ", instanceId: " + instanceId));
    }

    Promise<QuicChannel> promise = eventLoop.newPromise();
    log.info("[BACKEND-CONN-MGR] Connecting to service {} (instance {}) at {}:{}...",
      serviceId, instanceId, endpoint.getHostname(), endpoint.getPort());

    QuicChannel.newBootstrap(outboundDatagramChannel)
      .handler(new ChannelInboundHandlerAdapter() {
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
          log.info("[BACKEND-CONN-MGR] Connection to service {} (instance {}) closed. Evicting.", serviceId, instanceId);
          connectionFutures.remove(key);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
          log.error("[BACKEND-CONN-MGR] Error on connection to service {} (instance {}): {}",
            serviceId, instanceId, cause.getMessage());
          ctx.close();
        }
      })
      .remoteAddress(new InetSocketAddress(endpoint.getHostname(), endpoint.getPort()))
      .connect()
      .addListener((Future<QuicChannel> future) -> {
        if (future.isSuccess()) {
          log.info("[BACKEND-CONN-MGR] Connected to service {} (instance {}).", serviceId, instanceId);
          promise.setSuccess(future.getNow());
        } else {
          log.error("[BACKEND-CONN-MGR] Failed connection to service {} (instance {}): {}",
            serviceId, instanceId, future.cause().getMessage());
          connectionFutures.remove(key);
          promise.setFailure(future.cause());
        }
      });

    return promise;
  }

  private DemoProperties.EndpointProperties resolveEndpoint(short serviceId, int instanceId) {
    return switch (serviceId) {
      case ServiceType.AUTH -> properties.getLogin();
      case ServiceType.LOBBY -> properties.getLobby();
      case ServiceType.WORLD -> properties.getGateway().getWorld(instanceId);
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