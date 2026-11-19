package com.example.gateway;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.dto.GatewayErrorResponse;
import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.*;
import com.example.config.GatewayProperties;
import com.example.config.LobbyServerProperties;
import com.example.config.LoginServerProperties;
import com.example.gateway.handler.*;
import com.example.gateway.session.ClientSession;
import com.example.server.codec.BackendForySerializationProvider;
import com.example.server.protocol.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamChannelConfig;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayStreamHandlersTest {

  @BeforeAll
  static void enableParanoid() {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
  }

  private static QuicGateway createGateway(BackendConnectionManager connMgr) {
    return new QuicGateway(
      new GatewayProperties(),
      connMgr,
      new SetSessionAuthStateHandler(),
      new SetSessionCharacterHandler(),
      new SetSessionWorldHandler(),
      new SetSessionZoneHandler()
    );
  }

  private static BackendConnectionManager createStubConnManager(AtomicReference<Promise<QuicChannel>> nextPromiseRef, boolean failImmediately) {
    LoginServerProperties loginProps = new LoginServerProperties();
    loginProps.setHost("127.0.0.1");
    loginProps.setPort(9991);
    LobbyServerProperties lobbyProps = new LobbyServerProperties();
    lobbyProps.setHost("127.0.0.1");
    lobbyProps.setPort(9992);

    return new BackendConnectionManager(loginProps, lobbyProps) {
      @Override
      public Future<QuicChannel> getConnection(short serviceId, String host, int port, EventLoop eventLoop) {
        Promise<QuicChannel> p = eventLoop.newPromise();
        if (nextPromiseRef != null) {
          nextPromiseRef.set(p);
        }
        if (failImmediately) {
          p.setFailure(new IllegalStateException("Simulated backend offline"));
        }
        return p;
      }
    };
  }

  private static QuicStreamChannel wrapEmbeddedAsQuicStream(EmbeddedChannel embedded) {
    QuicStreamChannelConfig configProxy = (QuicStreamChannelConfig) Proxy.newProxyInstance(
      QuicStreamChannelConfig.class.getClassLoader(),
      new Class<?>[]{QuicStreamChannelConfig.class},
      (proxy, method, args) -> {
        Object res = ChannelConfig.class.getMethod(method.getName(), method.getParameterTypes())
          .invoke(embedded.config(), args);
        return (res == embedded.config()) ? proxy : res;
      }
    );

    return (QuicStreamChannel) Proxy.newProxyInstance(
      QuicStreamChannel.class.getClassLoader(),
      new Class<?>[]{QuicStreamChannel.class},
      (proxy, method, args) -> {
        if ("config".equals(method.getName())) {
          return configProxy;
        }
        try {
          return EmbeddedChannel.class.getMethod(method.getName(), method.getParameterTypes()).invoke(embedded, args);
        } catch (NoSuchMethodException e) {
          if ("streamId".equals(method.getName())) return 1L;
          throw e;
        }
      }
    );
  }

  private static GatewayErrorResponse decodeOutboundError(EmbeddedChannel clientChannel) {
    ByteBuf frame = clientChannel.readOutbound();
    assertThat(frame).isNotNull();
    short svc = frame.readShort();
    short msgId = frame.readShort();
    assertThat(svc).isEqualTo(ServiceType.SYSTEM);
    assertThat(msgId).isEqualTo(MessageType.GATEWAY_ERROR_RESPONSE);
    byte[] bytes = new byte[frame.readableBytes()];
    frame.readBytes(bytes);
    frame.release();
    return (GatewayErrorResponse) ForySerializationProvider.get().deserialize(bytes);
  }

  @Test
  @DisplayName("GatewayInboundStreamHandler rejects spoofed GATEWAY/reserved/SESSION_DISCONNECTED frames without leaking")
  void testSpoofingRejectionZeroLeak() {
    short[][] spoofedPairs = {
      {ServiceType.GATEWAY, MessageType.CTRL_SET_AUTH_STATE},
      {ServiceType.AUTH, (short) 49},
      {ServiceType.AUTH, MessageType.SESSION_DISCONNECTED}
    };

    for (short[] pair : spoofedPairs) {
      QuicGateway gw = createGateway(createStubConnManager(null, false));
      EmbeddedChannel clientChannel = new EmbeddedChannel(new ClientEnvelopeCodec(), new GatewayInboundStreamHandler(gw));
      ClientSession session = new ClientSession(100L, null, 100, 100);
      session.setAuthenticated(true);
      session.setCharacterId(55L);
      clientChannel.attr(ClientSession.KEY).set(session);

      ByteBuf payload = clientChannel.alloc().buffer(4).writeInt(42);
      ClientEnvelope spoofed = new ClientEnvelope(pair[0], pair[1], payload);

      try {
        assertThat(clientChannel.writeInbound(spoofed)).isFalse();
        assertThat(payload.refCnt()).isZero();

        GatewayErrorResponse err = decodeOutboundError(clientChannel);
        assertThat(err.errorCode()).isEqualTo(GatewayErrorResponse.ERROR_MALFORMED_PAYLOAD);
        assertThat(clientChannel.isOpen()).isFalse();
      } finally {
        clientChannel.finishAndReleaseAll();
      }
    }
  }

  @Test
  @DisplayName("GatewayInboundStreamHandler enforces bounded pending queue (128) and releases all buffers on overflow & close")
  void testBoundedPendingQueueAndDrainZeroLeak() {
    AtomicReference<Promise<QuicChannel>> pendingPromise = new AtomicReference<>();
    QuicGateway gw = createGateway(createStubConnManager(pendingPromise, false));
    EmbeddedChannel clientChannel = new EmbeddedChannel(new ClientEnvelopeCodec(), new GatewayInboundStreamHandler(gw));
    ClientSession session = new ClientSession(101L, null, 500, 500);
    clientChannel.attr(ClientSession.KEY).set(session);

    List<ByteBuf> payloads = new ArrayList<>();
    try {
      for (int i = 0; i < GatewayInboundStreamHandler.MAX_PENDING_ENVELOPES; i++) {
        ByteBuf p = clientChannel.alloc().buffer(4).writeInt(i);
        payloads.add(p);
        assertThat(clientChannel.writeInbound(new ClientEnvelope(ServiceType.AUTH, MessageType.PING, p))).isFalse();
        assertThat(p.refCnt()).isEqualTo(1); // held in pending queue
      }

      // 129th envelope must overflow, release excess payload, and emit ERROR_RATE_LIMITED
      ByteBuf overflowPayload = clientChannel.alloc().buffer(4).writeInt(999);
      assertThat(clientChannel.writeInbound(new ClientEnvelope(ServiceType.AUTH, MessageType.PING, overflowPayload))).isFalse();
      assertThat(overflowPayload.refCnt()).isZero();

      GatewayErrorResponse err = decodeOutboundError(clientChannel);
      assertThat(err.errorCode()).isEqualTo(GatewayErrorResponse.ERROR_RATE_LIMITED);

      // Closing channel drains & releases all 128 pending envelopes
      clientChannel.close().syncUninterruptibly();
      for (ByteBuf p : payloads) {
        assertThat(p.refCnt()).isZero();
      }
    } finally {
      clientChannel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("GatewayInboundStreamHandler sends ERROR_SERVICE_UNAVAILABLE and releases queued frames on connect failure")
  void testConnectFailureEmitsServiceUnavailableAndReleases() {
    QuicGateway gw = createGateway(createStubConnManager(null, true));
    EmbeddedChannel clientChannel = new EmbeddedChannel(new ClientEnvelopeCodec(), new GatewayInboundStreamHandler(gw));
    ClientSession session = new ClientSession(102L, null, 100, 100);
    clientChannel.attr(ClientSession.KEY).set(session);

    ByteBuf payload = clientChannel.alloc().buffer(4).writeInt(7);
    try {
      clientChannel.writeInbound(new ClientEnvelope(ServiceType.AUTH, MessageType.PING, payload));
      clientChannel.runPendingTasks();

      assertThat(payload.refCnt()).isZero();
      GatewayErrorResponse err = decodeOutboundError(clientChannel);
      assertThat(err.errorCode()).isEqualTo(GatewayErrorResponse.ERROR_SERVICE_UNAVAILABLE);
      assertThat(err.rejectedServiceId()).isEqualTo(ServiceType.AUTH);
      assertThat(err.rejectedMessageId()).isEqualTo(MessageType.PING);
    } finally {
      clientChannel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("GatewayBackendRelayHandler relays without leaks across success, inactive client, write failure, and control routing")
  void testGatewayBackendRelayHandlerZeroLeak() {
    ClientSession session = new ClientSession(200L, null, 100, 100);
    IndexedPacketRouter controlRouter = new IndexedPacketRouter();
    AtomicBoolean controlHandled = new AtomicBoolean(false);
    controlRouter.register(MessageType.CTRL_SET_AUTH_STATE, (ctx, msg) -> controlHandled.set(true));

    EmbeddedChannel clientChannel = new EmbeddedChannel(new ClientEnvelopeCodec());
    EmbeddedChannel backendChannel = new EmbeddedChannel(new GatewayBackendRelayHandler(clientChannel, session, controlRouter));

    try {
      // 1. Control frame consumed internally without leaking
      byte[] ctrlBytes = BackendForySerializationProvider.get().serialize(new SetSessionAuthStateControlMessage(true, 55L));
      ByteBuf ctrlBuf = backendChannel.alloc().buffer(ctrlBytes.length).writeBytes(ctrlBytes);
      assertThat(backendChannel.writeInbound(
        new BackendEnvelope(200L, 0L, ServiceType.GATEWAY, MessageType.CTRL_SET_AUTH_STATE, ctrlBuf)
      )).isFalse();
      assertThat(controlHandled.get()).isTrue();
      assertThat(ctrlBuf.refCnt()).isZero();

      // 2. Normal pass-through relay
      ByteBuf relayBuf = backendChannel.alloc().buffer(5).writeBytes("hello".getBytes(StandardCharsets.UTF_8));
      assertThat(backendChannel.writeInbound(
        new BackendEnvelope(200L, 1001L, ServiceType.ZONE, MessageType.ZONE_STATE_DELTA_BROADCAST, relayBuf)
      )).isFalse();
      assertThat(relayBuf.refCnt()).isZero();

      ByteBuf relayedOut = clientChannel.readOutbound();
      assertThat(relayedOut).isNotNull();
      assertThat(relayedOut.readShort()).isEqualTo(ServiceType.ZONE);
      assertThat(relayedOut.readShort()).isEqualTo(MessageType.ZONE_STATE_DELTA_BROADCAST);
      assertThat(relayedOut.toString(StandardCharsets.UTF_8)).isEqualTo("hello");
      relayedOut.release();

      // 3. Downstream write failure before ClientEnvelopeCodec (no double release)
      EmbeddedChannel failingClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
          io.netty.util.ReferenceCountUtil.release(msg);
          promise.setFailure(new RuntimeException("Simulated write failure"));
        }
      });
      EmbeddedChannel backendForFailing = new EmbeddedChannel(new GatewayBackendRelayHandler(failingClient, session, controlRouter));
      ByteBuf failBuf = backendForFailing.alloc().buffer(4).writeInt(123);
      assertThat(backendForFailing.writeInbound(
        new BackendEnvelope(200L, 1001L, ServiceType.AUTH, MessageType.PONG, failBuf)
      )).isFalse();
      assertThat(failBuf.refCnt()).isZero();
      failingClient.finishAndReleaseAll();
      backendForFailing.finishAndReleaseAll();

      // 4. Inactive client channel drops cleanly without leak
      clientChannel.close().syncUninterruptibly();
      ByteBuf droppedBuf = backendChannel.alloc().buffer(4).writeInt(456);
      assertThat(backendChannel.writeInbound(
        new BackendEnvelope(200L, 1001L, ServiceType.ZONE, MessageType.PLAYER_MOVEMENT_RESPONSE, droppedBuf)
      )).isFalse();
      assertThat(droppedBuf.refCnt()).isZero();
    } finally {
      backendChannel.finishAndReleaseAll();
      clientChannel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("channelWritabilityChanged recovers clientStream autoRead only when all active backend streams are writable")
  void testChannelWritabilityRecoveryNoDeadlock() {
    QuicGateway gw = createGateway(createStubConnManager(null, false));
    ClientSession session = new ClientSession(300L, null, 100, 100);

    EmbeddedChannel clientChannel = new EmbeddedChannel(new ClientEnvelopeCodec(), new GatewayInboundStreamHandler(gw));
    clientChannel.attr(ClientSession.KEY).set(session);

    EmbeddedChannel backendEmb1 = new EmbeddedChannel(new GatewayBackendRelayHandler(clientChannel, session, gw.getControlRouter()));
    EmbeddedChannel backendEmb2 = new EmbeddedChannel(new GatewayBackendRelayHandler(clientChannel, session, gw.getControlRouter()));
    backendEmb1.config().setWriteBufferWaterMark(new WriteBufferWaterMark(8, 16));
    backendEmb2.config().setWriteBufferWaterMark(new WriteBufferWaterMark(8, 16));
    clientChannel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(8, 16));

    session.getBackendStreams().put(new BackendConnectionManager.EndpointKey(ServiceType.AUTH, "127.0.0.1", 9991),
      wrapEmbeddedAsQuicStream(backendEmb1));
    session.getBackendStreams().put(new BackendConnectionManager.EndpointKey(ServiceType.ZONE, "127.0.0.1", 9995),
      wrapEmbeddedAsQuicStream(backendEmb2));

    try {
      // Simulate both backend1 unwritable and clientChannel unwritable (the deadlock scenario)
      clientChannel.config().setAutoRead(false);
      ByteBuf big1 = Unpooled.wrappedBuffer(new byte[32]);
      backendEmb1.write(big1); // exceeds high watermark 16 -> backend1 is unwritable
      assertThat(backendEmb1.isWritable()).isFalse();

      // Now backend2 triggers writability event while backend1 is STILL unwritable -> client autoRead MUST stay false
      backendEmb2.pipeline().fireChannelWritabilityChanged();
      assertThat(clientChannel.config().isAutoRead()).isFalse();

      // Drain backend1 so all active backend streams are writable again -> client autoRead MUST recover to true
      backendEmb1.flush();
      ByteBuf drained = backendEmb1.readOutbound();
      if (drained != null) drained.release();
      assertThat(backendEmb1.isWritable()).isTrue();
      assertThat(clientChannel.config().isAutoRead()).isTrue();

      // Also test GatewayInboundStreamHandler.channelWritabilityChanged recovery path
      clientChannel.config().setAutoRead(false);
      clientChannel.pipeline().fireChannelWritabilityChanged();
      assertThat(clientChannel.config().isAutoRead()).isTrue();
    } finally {
      backendEmb1.finishAndReleaseAll();
      backendEmb2.finishAndReleaseAll();
      clientChannel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("SetSessionZoneHandler sends zoneTransfer=true disconnect without leaking payload")
  void testSetSessionZoneHandlerZeroLeak() {
    ClientSession session = new ClientSession(400L, null, 100, 100);
    session.setCharacterId(1001L);
    session.setCurrentZoneId(230);
    session.setZoneEndpoint(new EndpointTarget("127.0.0.1", 9995));

    EmbeddedChannel oldZoneEmbedded = new EmbeddedChannel(new BackendEnvelopeCodec());
    session.getBackendStreams().put(
      new BackendConnectionManager.EndpointKey(ServiceType.ZONE, "127.0.0.1", 9995),
      wrapEmbeddedAsQuicStream(oldZoneEmbedded)
    );

    EmbeddedChannel ctxCh = new EmbeddedChannel();
    ctxCh.attr(ClientSession.KEY).set(session);
    ctxCh.pipeline().addLast(new ChannelInboundHandlerAdapter());
    ChannelHandlerContext ctx = ctxCh.pipeline().firstContext();

    try {
      SetSessionZoneHandler handler = new SetSessionZoneHandler();
      handler.handle(ctx, new SetSessionZoneControlMessage(231, "127.0.0.1", 9996));

      assertThat(session.getCurrentZoneId()).isEqualTo(231);
      assertThat(session.getZoneEndpoint()).isEqualTo(new EndpointTarget("127.0.0.1", 9996));

      ByteBuf frame = oldZoneEmbedded.readOutbound();
      assertThat(frame).isNotNull();
      long sid = frame.readLong();
      long cid = frame.readLong();
      short svc = frame.readShort();
      short msgId = frame.readShort();
      assertThat(sid).isEqualTo(400L);
      assertThat(cid).isEqualTo(1001L);
      assertThat(svc).isEqualTo(ServiceType.ZONE);
      assertThat(msgId).isEqualTo(MessageType.SESSION_DISCONNECTED);

      byte[] bytes = new byte[frame.readableBytes()];
      frame.readBytes(bytes);
      frame.release();

      SessionDisconnectedEvent event = (SessionDisconnectedEvent) BackendForySerializationProvider.get().deserialize(bytes);
      assertThat(event.sessionId()).isEqualTo(400L);
      assertThat(event.zoneTransfer()).isTrue();
    } finally {
      oldZoneEmbedded.finishAndReleaseAll();
      ctxCh.finishAndReleaseAll();
    }
  }
}
