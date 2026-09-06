package com.example.client;

import com.example.client.handler.*;
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

  private EventLoopGroup group;
  private Channel datagramChannel;
  private QuicChannel quicChannel;
  private QuicStreamChannel streamChannel;

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
    group = new NioEventLoopGroup(1);

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

    datagramChannel = new Bootstrap()
      .group(group)
      .channel(NioDatagramChannel.class)
      .handler(codec)
      .bind(0)
      .sync()
      .channel();

    ClientConfiguration.GatewayEndpoint gatewayProperties = properties.getGateway();
    int port = gatewayProperties.getPort();
    String host = gatewayProperties.getHostname();
    log.info("[CLIENT] Connecting to QUIC gateway at {}:{}...", host, port);

    Promise<Void> connectPromise = group.next().newPromise();

    QuicChannel.newBootstrap(datagramChannel)
      .handler(new ChannelInboundHandlerAdapter() {
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
          log.info("[CLIENT] QuicChannel inactive.");
          if (streamChannel != null && streamChannel.isActive()) {
            streamChannel.close();
          }
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
          connectPromise.setFailure(future.cause());
          return;
        }

        quicChannel = future.getNow();
        log.info("[CLIENT] QUIC Handshake successful. Opening bidirectional stream...");

        quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
          @Override
          protected void initChannel(QuicStreamChannel ch) {
            ch.pipeline().addLast(
              new LengthFieldBasedFrameDecoder(ProtocolConstants.MAX_FRAME_LENGTH, 0, 4, 0, 4),
              new LengthFieldPrepender(4),
              new ClientEnvelopeCodec(),
              new ClientEnvelopePayloadDecoder(),
              new ClientMessageEncoder(),
              new InboundDispatchHandler(router)
            );
          }
        }).addListener((Future<QuicStreamChannel> streamFuture) -> {
          if (streamFuture.isSuccess()) {
            streamChannel = streamFuture.getNow();
            log.info("[CLIENT] Bidirectional stream ready.");
            connectPromise.setSuccess(null);
          } else {
            connectPromise.setFailure(streamFuture.cause());
          }
        });
      });

    return connectPromise;
  }

  public ChannelFuture send(PacketMessage message) {
    if (streamChannel == null || !streamChannel.isActive()) {
      throw new IllegalStateException("Client is not connected. Call connect() first.");
    }
    return streamChannel.writeAndFlush(message).addListener((ChannelFutureListener) future -> {
      if (!future.isSuccess()) {
        log.error("[CLIENT] Failed to send message (svc: {}, msg: {}): {}",
          message.serviceId(), message.messageId(), future.cause().getMessage(), future.cause());
      }
    });
  }

  public ChannelFuture sendRaw(ByteBuf byteBuf) {
    if (streamChannel == null || !streamChannel.isActive()) {
      byteBuf.release();
      throw new IllegalStateException("Client is not connected. Call connect() first.");
    }
    return streamChannel.writeAndFlush(byteBuf).addListener((ChannelFutureListener) future -> {
      if (!future.isSuccess()) {
        log.error("[CLIENT] Failed to send raw frame: {}", future.cause().getMessage(), future.cause());
      }
    });
  }

  public boolean isConnected() {
    return streamChannel != null && streamChannel.isActive()
      && quicChannel != null && quicChannel.isActive();
  }

  public ChannelFuture reconnect(String authToken) {
    return send(new ReconnectRequest(authToken));
  }

  @Override
  @PreDestroy
  public void close() {
    log.info("[CLIENT] Shutting down client...");
    if (streamChannel != null && streamChannel.isActive()) {
      streamChannel.close().syncUninterruptibly();
    }
    if (quicChannel != null && quicChannel.isActive()) {
      quicChannel.close().syncUninterruptibly();
    }
    if (datagramChannel != null && datagramChannel.isActive()) {
      datagramChannel.close().syncUninterruptibly();
    }
    if (group != null && !group.isShutdown()) {
      group.shutdownGracefully(0, 50, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }
  }
}