package com.example.gateway;

import com.example.common.protocol.ProtocolConstants;
import com.example.common.protocol.ServiceType;
import com.example.config.DemoProperties;
import com.example.config.DemoProperties.EndpointProperties;
import com.example.gateway.session.ClientSession;
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

  /**
   * Generates a 64-bit composite key from serviceId, host hash, and port.
   */
  public static long makeKey(short serviceId, String host, int port) {
    int hostHash = (host != null) ? host.hashCode() : 0;
    return (((long) serviceId) << 48) ^ (((long) (hostHash & 0xFFFF)) << 32) ^ (port & 0xFFFFFFFFL);
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

  public EndpointProperties resolveEndpoint(short serviceId, ClientSession session) {
    return switch (serviceId) {
      case ServiceType.AUTH -> properties.getLogin();
      case ServiceType.LOBBY -> properties.getLobby();
      case ServiceType.WORLD -> (session != null && session.getWorldEndpoint() != null)
        ? session.getWorldEndpoint()
        : properties.getWorld();
      case ServiceType.ZONE -> (session != null && session.getZoneEndpoint() != null)
        ? session.getZoneEndpoint()
        : properties.getGateway().getWorld(1); // Default initial zone fallback
      default -> null;
    };
  }

  public Future<QuicChannel> getConnection(short serviceId, String host, int port, EventLoop eventLoop) {
    long key = makeKey(serviceId, host, port);

    Future<QuicChannel> existingFuture = connectionFutures.get(key);
    if (existingFuture != null && existingFuture.isSuccess() && existingFuture.getNow().isActive()) {
      return existingFuture;
    }

    return connectionFutures.compute(key, (k, currentFuture) -> {
      if (currentFuture != null && (!currentFuture.isDone() || (currentFuture.isSuccess() && currentFuture.getNow().isActive()))) {
        return currentFuture;
      }
      return initiateConnection(serviceId, host, port, key, eventLoop);
    });
  }

  private Future<QuicChannel> initiateConnection(short serviceId, String host, int port, long key, EventLoop eventLoop) {
    Promise<QuicChannel> promise = eventLoop.newPromise();
    log.info("[BACKEND-CONN-MGR] Connecting to service {} at {}:{}...", serviceId, host, port);

    QuicChannel.newBootstrap(outboundDatagramChannel)
      .handler(new ChannelInboundHandlerAdapter() {
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
          log.info("[BACKEND-CONN-MGR] Connection to service {} ({}:{}) closed. Evicting.", serviceId, host, port);
          connectionFutures.remove(key);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
          log.error("[BACKEND-CONN-MGR] Error on connection to service {} ({}:{}): {}",
            serviceId, host, port, cause.getMessage());
          ctx.close();
        }
      })
      .remoteAddress(new InetSocketAddress(host, port))
      .connect()
      .addListener((Future<QuicChannel> future) -> {
        if (future.isSuccess()) {
          log.info("[BACKEND-CONN-MGR] Connected to service {} at {}:{}", serviceId, host, port);
          promise.setSuccess(future.getNow());
        } else {
          log.error("[BACKEND-CONN-MGR] Failed connection to service {} ({}:{}): {}",
            serviceId, host, port, future.cause().getMessage());
          connectionFutures.remove(key);
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