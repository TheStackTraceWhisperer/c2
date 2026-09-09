package com.example.gateway;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.ClientEnvelopeCodec;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.ProtocolConstants;
import com.example.config.GatewayProperties;
import com.example.gateway.handler.SetSessionAuthStateHandler;
import com.example.gateway.handler.SetSessionCharacterHandler;
import com.example.gateway.handler.SetSessionWorldHandler;
import com.example.gateway.handler.SetSessionZoneHandler;
import com.example.gateway.session.ClientSession;
import com.example.gateway.session.SessionIdGenerator;
import com.example.server.codec.BackendForySerializationProvider;
import com.example.server.protocol.BackendEnvelope;
import com.example.server.protocol.SessionDisconnectedEvent;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
@Context
@Requires(env = "gateway")
@RequiredArgsConstructor
public class QuicGateway {

  private final GatewayProperties properties;
  @Getter private final BackendConnectionManager connectionManager;

  private final SetSessionAuthStateHandler authStateHandler;
  private final SetSessionCharacterHandler characterHandler;
  private final SetSessionWorldHandler worldHandler;
  private final SetSessionZoneHandler zoneHandler;

  @Getter private final IndexedPacketRouter controlRouter = new IndexedPacketRouter();

  private EventLoopGroup group;
  private Channel inboundDatagramChannel;
  private SessionIdGenerator sessionIdGenerator;

  @PostConstruct
  public void start() throws Exception {
    controlRouter.register(MessageType.CTRL_SET_AUTH_STATE, authStateHandler);
    controlRouter.register(MessageType.CTRL_SET_CHARACTER, characterHandler);
    controlRouter.register(MessageType.CTRL_SET_WORLD, worldHandler);
    controlRouter.register(MessageType.CTRL_SET_ZONE, zoneHandler);

    sessionIdGenerator = new SessionIdGenerator(properties.getNodeId());
    group = new NioEventLoopGroup(2);
    connectionManager.init(group);

    SelfSignedCertificate cert = new SelfSignedCertificate();
    QuicSslContext serverSslContext = QuicSslContextBuilder.forServer(cert.key(), null, cert.cert())
      .applicationProtocols(ProtocolConstants.ALPN_PROTOCOL)
      .build();

    ChannelHandler serverCodec = new QuicServerCodecBuilder()
      .sslContext(serverSslContext)
      .maxIdleTimeout(30, TimeUnit.SECONDS)
      .initialMaxData(10000000)
      .initialMaxStreamDataBidirectionalLocal(1000000)
      .initialMaxStreamDataBidirectionalRemote(1000000)
      .initialMaxStreamsBidirectional(100)
      .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
      .handler(new ChannelInitializer<QuicChannel>() {
        @Override
        protected void initChannel(QuicChannel quicChannel) {
          long sid = sessionIdGenerator.nextSessionId();
          ClientSession session = new ClientSession(sid, quicChannel, properties.getRateCapacity(), properties.getRateRefill());
          quicChannel.attr(ClientSession.KEY).set(session);
          log.info("[GATEWAY] Assigned ClientSession #{} to QuicChannel {}", sid, quicChannel);

          quicChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
              handleSessionDisconnect(session);
              ctx.fireChannelInactive();
            }
          });
        }
      })
      .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
        @Override
        protected void initChannel(QuicStreamChannel ch) {
          ch.config().setWriteBufferWaterMark(new WriteBufferWaterMark(32 * 1024, 64 * 1024));
          ch.pipeline().addLast(
            new LengthFieldBasedFrameDecoder(ProtocolConstants.MAX_FRAME_LENGTH, 0, 4, 0, 4),
            new LengthFieldPrepender(4),
            new ClientEnvelopeCodec(),
            new GatewayInboundStreamHandler(QuicGateway.this)
          );
        }
      })
      .build();

    inboundDatagramChannel = new Bootstrap()
      .group(group)
      .channel(NioDatagramChannel.class)
      .handler(serverCodec)
      .bind(new InetSocketAddress(properties.getHost(), properties.getPort()))
      .sync()
      .channel();

    log.info("[GATEWAY] Listening on UDP {}:{}", properties.getHost(), properties.getPort());
  }

  public boolean isRunning() {
    return inboundDatagramChannel != null && inboundDatagramChannel.isActive();
  }

  private void handleSessionDisconnect(ClientSession session) {
    long sessionId = session.getSessionId();
    long characterId = session.getCharacterId();
    int streamCount = session.getBackendStreams().size();

    if (streamCount == 0) return;

    log.info("[GATEWAY] Client disconnected: Session #{} (Char #{}). Broadcasting teardown...", sessionId, characterId);
    SessionDisconnectedEvent disconnectEvent = new SessionDisconnectedEvent(sessionId, System.currentTimeMillis());
    byte[] disconnectBytes = BackendForySerializationProvider.get().serialize(disconnectEvent);

    session.getBackendStreams().forEach((endpointKey, backendStream) -> {
      if (backendStream.isActive()) {
        short svcId = (short) (endpointKey >>> 48);
        ByteBuf payloadBuf = backendStream.alloc().buffer(disconnectBytes.length).writeBytes(disconnectBytes);
        BackendEnvelope envelope = new BackendEnvelope(sessionId, characterId, svcId, MessageType.SESSION_DISCONNECTED, payloadBuf.retain());
        backendStream.writeAndFlush(envelope).addListener((ChannelFutureListener) f -> {
          if (!f.isSuccess()) {
            payloadBuf.release();
          }
          backendStream.close();
        });
      }
    });

    session.getBackendStreams().clear();
  }

  @PreDestroy
  public void stop() {
    log.info("[GATEWAY] Shutting down gateway...");
    if (inboundDatagramChannel != null) inboundDatagramChannel.close();
    if (group != null) group.shutdownGracefully();
  }
}