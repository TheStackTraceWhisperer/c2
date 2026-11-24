package com.example.client;

import com.example.client.handler.*;
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.handler.InboundDispatchHandler;
import com.example.common.handler.IndexedPacketRouter;
import com.example.common.handler.PacketHandler;
import com.example.common.protocol.ClientEnvelopeCodec;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ProtocolConstants;
import com.example.login.dto.ReconnectRequest;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.*;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class QuicClient implements AutoCloseable {

  private final ClientConfiguration properties;
  @Getter private final ClientState state;
  @Getter private final IndexedPacketRouter router;

  private final LoginResponseHandler defaultLoginHandler;
  private final CharacterListResponseHandler defaultCharacterListHandler;
  private final CreateCharacterResponseHandler defaultCreateCharacterHandler;
  private final PongPacketHandler defaultPongHandler;
  private final SelectCharacterResponseHandler defaultSelectCharacterHandler;
  private final ReconnectResponseHandler defaultReconnectHandler;
  private final JoinWorldResponseHandler defaultJoinWorldHandler;
  private final ZoneTransferNotificationHandler defaultZoneTransferHandler;
  private final PlayerMovementResponseHandler defaultPlayerMovementHandler;
  private final ZoneStateDeltaHandler defaultZoneStateDeltaHandler;
  private final GatewayErrorResponseHandler defaultGatewayErrorHandler;

  private volatile EventLoopGroup group;
  private volatile Channel datagramChannel;
  private volatile QuicChannel quicChannel;
  private volatile QuicStreamChannel streamChannel;

  public QuicClient(
    ClientConfiguration properties,
    ClientState state,
    LoginResponseHandler loginHandler,
    CharacterListResponseHandler characterListHandler,
    CreateCharacterResponseHandler createCharacterHandler,
    PongPacketHandler pongHandler,
    SelectCharacterResponseHandler selectCharacterHandler,
    ReconnectResponseHandler reconnectHandler,
    JoinWorldResponseHandler joinWorldHandler,
    ZoneTransferNotificationHandler zoneTransferHandler,
    PlayerMovementResponseHandler playerMovementHandler,
    ZoneStateDeltaHandler zoneStateDeltaHandler,
    GatewayErrorResponseHandler gatewayErrorHandler) {
    this.properties = properties;
    this.state = state;
    this.router = new IndexedPacketRouter();

    this.defaultLoginHandler = loginHandler;
    this.defaultCharacterListHandler = characterListHandler;
    this.defaultCreateCharacterHandler = createCharacterHandler;
    this.defaultPongHandler = pongHandler;
    this.defaultSelectCharacterHandler = selectCharacterHandler;
    this.defaultReconnectHandler = reconnectHandler;
    this.defaultJoinWorldHandler = joinWorldHandler;
    this.defaultZoneTransferHandler = zoneTransferHandler;
    this.defaultPlayerMovementHandler = playerMovementHandler;
    this.defaultZoneStateDeltaHandler = zoneStateDeltaHandler;
    this.defaultGatewayErrorHandler = gatewayErrorHandler;

    registerDefaultHandlers();
  }

  public void registerDefaultHandlers() {
    this.router.register(MessageType.LOGIN_RESPONSE, defaultLoginHandler);
    this.router.register(MessageType.CHARACTER_LIST_RESPONSE, defaultCharacterListHandler);
    this.router.register(MessageType.CREATE_CHARACTER_RESPONSE, defaultCreateCharacterHandler);
    this.router.register(MessageType.PONG, defaultPongHandler);
    this.router.register(MessageType.GATEWAY_ERROR_RESPONSE, defaultGatewayErrorHandler);
    this.router.register(MessageType.SELECT_CHARACTER_RESPONSE, defaultSelectCharacterHandler);
    this.router.register(MessageType.RECONNECT_RESPONSE, defaultReconnectHandler);
    this.router.register(MessageType.JOIN_WORLD_RESPONSE, defaultJoinWorldHandler);
    this.router.register(MessageType.ZONE_TRANSFER_NOTIFICATION, defaultZoneTransferHandler);
    this.router.register(MessageType.PLAYER_MOVEMENT_RESPONSE, defaultPlayerMovementHandler);
    this.router.register(MessageType.ZONE_STATE_DELTA_BROADCAST, defaultZoneStateDeltaHandler);
  }

  public <T extends PacketMessage> QuicClient on(short messageId, PacketHandler<T> handler) {
    router.register(messageId, handler);
    return this;
  }

  public Future<Void> connect() throws Exception {
    if (group != null || datagramChannel != null || quicChannel != null || streamChannel != null) {
      close();
    }

    EventLoopGroup localGroup = new NioEventLoopGroup(1);
    this.group = localGroup;

    try {
      QuicSslContext sslContext = QuicSslContextBuilder.forClient()
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .applicationProtocols(ProtocolConstants.ALPN_PROTOCOL)
        .build();

      ChannelHandler codec = new QuicClientCodecBuilder()
        .sslContext(sslContext)
        .maxIdleTimeout(30, TimeUnit.SECONDS)
        .initialMaxData(10000000)
        .initialMaxStreamDataBidirectionalLocal(1000000)
        .initialMaxStreamDataBidirectionalRemote(1000000)
        .build();

      Channel localDatagram = new Bootstrap()
        .group(localGroup)
        .channel(NioDatagramChannel.class)
        .handler(codec)
        .bind(0)
        .sync()
        .channel();
      this.datagramChannel = localDatagram;

      ClientConfiguration.GatewayEndpoint gatewayProperties = properties.getGateway();
      int port = gatewayProperties.getPort();
      String host = gatewayProperties.getHostname();
      log.info("[CLIENT] Connecting to QUIC gateway at {}:{}...", host, port);

      Promise<Void> connectPromise = localGroup.next().newPromise();

      QuicChannel.newBootstrap(localDatagram)
        .handler(new ChannelInboundHandlerAdapter() {
          @Override
          public void channelInactive(ChannelHandlerContext ctx) {
            log.info("[CLIENT] QuicChannel inactive.");
            QuicStreamChannel stream = streamChannel;
            if (stream != null && stream.isActive()) {
              stream.close();
            }
            state.setConnectionState(ClientConnectionState.DISCONNECTED);
            ctx.fireChannelInactive();
          }

          @Override
          public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("[CLIENT] Connection error: {}", cause.getMessage());
            ctx.close();
          }
        })
        .remoteAddress(new InetSocketAddress(host, port))
        .connect()
        .addListener((Future<QuicChannel> future) -> {
          if (!future.isSuccess()) {
            cleanupFailedConnect(null, localDatagram, localGroup);
            connectPromise.tryFailure(future.cause());
            return;
          }

          QuicChannel localQuic = future.getNow();
          this.quicChannel = localQuic;
          log.info("[CLIENT] QUIC Handshake successful. Opening bidirectional stream...");

          localQuic.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel ch) {
              ch.config().setAllowHalfClosure(false);
              ch.pipeline().addLast(
                new LengthFieldBasedFrameDecoder(ProtocolConstants.MAX_FRAME_LENGTH, 0, 4, 0, 4),
                new LengthFieldPrepender(4),
                new ClientEnvelopeCodec(),
                new ClientEnvelopePayloadDecoder(),
                new ClientMessageEncoder(),
                new InboundDispatchHandler(router),
                new ChannelInboundHandlerAdapter() {
                  @Override
                  public void channelInactive(ChannelHandlerContext ctx) {
                    if (localQuic.isActive()) {
                      localQuic.close();
                    }
                    ctx.fireChannelInactive();
                  }
                }
              );
            }
          }).addListener((Future<QuicStreamChannel> streamFuture) -> {
            if (streamFuture.isSuccess()) {
              this.streamChannel = streamFuture.getNow();
              log.info("[CLIENT] Bidirectional stream ready.");
              connectPromise.trySuccess(null);
            } else {
              cleanupFailedConnect(localQuic, localDatagram, localGroup);
              connectPromise.tryFailure(streamFuture.cause());
            }
          });
        });

      return connectPromise;
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  private void cleanupFailedConnect(QuicChannel quic, Channel datagram, EventLoopGroup eventGroup) {
    this.streamChannel = null;
    this.quicChannel = null;
    this.datagramChannel = null;
    this.group = null;
    if (quic != null && quic.isOpen()) {
      quic.close();
    }
    if (datagram != null && datagram.isOpen()) {
      datagram.close();
    }
    if (eventGroup != null && !eventGroup.isShutdown()) {
      eventGroup.shutdownGracefully(0, 50, TimeUnit.MILLISECONDS);
    }
  }

  public ChannelFuture send(PacketMessage message) {
    QuicStreamChannel stream = this.streamChannel;
    if (stream == null || !stream.isActive()) {
      throw new IllegalStateException("Client is not connected. Call connect() first.");
    }
    return stream.writeAndFlush(message).addListener((ChannelFutureListener) future -> {
      if (!future.isSuccess()) {
        log.error("[CLIENT] Failed to send message (svc: {}, msg: {}): {}",
          message.serviceId(), message.messageId(), future.cause().getMessage(), future.cause());
      }
    });
  }

  public ChannelFuture sendRaw(ByteBuf byteBuf) {
    QuicStreamChannel stream = this.streamChannel;
    if (stream == null || !stream.isActive()) {
      byteBuf.release();
      throw new IllegalStateException("Client is not connected. Call connect() first.");
    }
    return stream.writeAndFlush(byteBuf).addListener((ChannelFutureListener) future -> {
      if (!future.isSuccess()) {
        log.error("[CLIENT] Failed to send raw frame: {}", future.cause().getMessage(), future.cause());
      }
    });
  }

  public boolean isConnected() {
    QuicStreamChannel stream = this.streamChannel;
    QuicChannel quic = this.quicChannel;
    return stream != null && stream.isActive()
      && quic != null && quic.isActive();
  }

  public ChannelFuture reconnect(String authToken) {
    return send(new ReconnectRequest(authToken));
  }

  @Override
  @PreDestroy
  public void close() {
    log.info("[CLIENT] Shutting down client...");
    QuicStreamChannel stream = this.streamChannel;
    QuicChannel quic = this.quicChannel;
    Channel datagram = this.datagramChannel;
    EventLoopGroup eventGroup = this.group;

    this.streamChannel = null;
    this.quicChannel = null;
    this.datagramChannel = null;
    this.group = null;

    closeChannel(stream);
    closeChannel(quic);
    closeChannel(datagram);

    if (eventGroup != null && !eventGroup.isShutdown()) {
      boolean inEventLoop = false;
      for (EventExecutor exec : eventGroup) {
        if (exec.inEventLoop()) {
          inEventLoop = true;
          break;
        }
      }
      Future<?> shutdownFuture = eventGroup.shutdownGracefully(0, 50, TimeUnit.MILLISECONDS);
      if (!inEventLoop) {
        shutdownFuture.syncUninterruptibly();
      }
    }
  }

  private static void closeChannel(Channel ch) {
    if (ch != null && ch.isOpen()) {
      ChannelFuture future = ch.close();
      if (!ch.eventLoop().inEventLoop()) {
        future.syncUninterruptibly();
      }
    }
  }
}