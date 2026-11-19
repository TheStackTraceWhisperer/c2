package com.example.gateway;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.dto.GatewayErrorResponse;
import com.example.common.protocol.ClientEnvelope;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.ProtocolConstants;
import com.example.common.protocol.ServiceType;
import com.example.gateway.BackendConnectionManager.EndpointKey;
import com.example.gateway.session.ClientSession;
import com.example.server.protocol.BackendEnvelope;
import com.example.server.protocol.BackendEnvelopeCodec;
import com.example.server.protocol.EndpointTarget;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@RequiredArgsConstructor
public class GatewayInboundStreamHandler extends SimpleChannelInboundHandler<ClientEnvelope> {

  static final int MAX_PENDING_ENVELOPES = 128;

  private final QuicGateway gateway;
  private final Map<EndpointKey, Queue<BackendEnvelope>> pendingQueues = new ConcurrentHashMap<>();
  private final Map<EndpointKey, Boolean> connectingFlags = new ConcurrentHashMap<>();

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, ClientEnvelope msg) {
    Channel clientStream = ctx.channel();
    ClientSession session = resolveSession(clientStream);
    long sessionId = (session != null) ? session.getSessionId() : 0L;

    if (session != null && !session.getRateLimiter().tryConsume()) {
      log.warn("[GATEWAY-RATELIMIT] Dropped packet svc: {}, msg: {} from Session #{} (Rate limit exceeded). Terminating connection.",
        msg.serviceId(), msg.messageId(), sessionId);
      sendErrorAndSever(ctx, clientStream, new GatewayErrorResponse(
        GatewayErrorResponse.ERROR_RATE_LIMITED,
        msg.serviceId(),
        msg.messageId(),
        "Rate limit exceeded"
      ));
      return;
    }

    if (msg.serviceId() == ServiceType.GATEWAY
      || msg.messageId() < 50
      || msg.messageId() == MessageType.SESSION_DISCONNECTED) {
      log.warn("[GATEWAY-SECURITY] Blocked spoofed/internal packet svc: {}, msg: {} from Session #{}. Terminating connection.",
        msg.serviceId(), msg.messageId(), sessionId);
      sendErrorAndSever(ctx, clientStream, new GatewayErrorResponse(
        GatewayErrorResponse.ERROR_MALFORMED_PAYLOAD,
        msg.serviceId(),
        msg.messageId(),
        "Forbidden internal or reserved message type"
      ));
      return;
    }

    if ((msg.serviceId() == ServiceType.LOBBY || msg.serviceId() == ServiceType.WORLD || msg.serviceId() == ServiceType.ZONE)
      && (session == null || !session.isAuthenticated())) {
      log.warn("[GATEWAY-SECURITY] Blocked unauthenticated access to svc {} from Session #{}. Terminating connection.",
        msg.serviceId(), sessionId);
      sendErrorAndSever(ctx, clientStream, new GatewayErrorResponse(
        GatewayErrorResponse.ERROR_UNAUTHENTICATED,
        msg.serviceId(),
        msg.messageId(),
        "Unauthenticated request"
      ));
      return;
    }

    if ((msg.serviceId() == ServiceType.WORLD || msg.serviceId() == ServiceType.ZONE)
      && (session == null || session.getCharacterId() == 0L)) {
      log.warn("[GATEWAY-SECURITY] Blocked out-of-state packet to svc {} from Session #{}. Terminating connection.",
        msg.serviceId(), sessionId);
      sendErrorAndSever(ctx, clientStream, new GatewayErrorResponse(
        GatewayErrorResponse.ERROR_INVALID_SESSION_STATE,
        msg.serviceId(),
        msg.messageId(),
        "Character not selected"
      ));
      return;
    }

    EndpointTarget endpoint = gateway.getConnectionManager().resolveEndpoint(msg.serviceId(), session);
    if (endpoint == null) {
      log.error("[GATEWAY] No destination endpoint resolved for svc: {} on Session #{}", msg.serviceId(), sessionId);
      sendError(ctx, new GatewayErrorResponse(
        GatewayErrorResponse.ERROR_SERVICE_UNAVAILABLE,
        msg.serviceId(),
        msg.messageId(),
        "Service unavailable"
      ));
      return;
    }

    EndpointKey endpointKey = new EndpointKey(msg.serviceId(), endpoint.host(), endpoint.port());
    long characterId = (session != null) ? session.getCharacterId() : 0L;

    ByteBuf retainedPayload = (msg.payload() != null) ? msg.payload().retain() : null;
    BackendEnvelope backendEnvelope = new BackendEnvelope(
      sessionId,
      characterId,
      msg.serviceId(),
      msg.messageId(),
      retainedPayload
    );

    QuicStreamChannel backendStream = (session != null) ? session.getBackendStreams().get(endpointKey) : null;

    if (backendStream != null && backendStream.isActive()) {
      backendStream.writeAndFlush(backendEnvelope).addListener((ChannelFutureListener) future -> {
        if (!future.isSuccess()) {
          log.error("[GATEWAY] Failed to forward to backend stream for {}: {}",
            endpointKey, future.cause() != null ? future.cause().getMessage() : "unknown");
        }
      });

      if (!backendStream.isWritable()) {
        clientStream.config().setAutoRead(false);
      }
    } else {
      enqueueAndConnect(ctx, session, endpointKey, backendEnvelope);
    }
  }

  private void enqueueAndConnect(
    ChannelHandlerContext ctx,
    ClientSession session,
    EndpointKey endpointKey,
    BackendEnvelope envelope) {

    Queue<BackendEnvelope> queue = pendingQueues.computeIfAbsent(endpointKey, k -> new ConcurrentLinkedQueue<>());
    if (queue.size() >= MAX_PENDING_ENVELOPES) {
      log.warn("[GATEWAY] Pending queue full ({} envelopes) for {} on Session #{}",
        MAX_PENDING_ENVELOPES, endpointKey, session != null ? session.getSessionId() : 0L);
      ReferenceCountUtil.release(envelope);
      sendError(ctx, new GatewayErrorResponse(
        GatewayErrorResponse.ERROR_RATE_LIMITED,
        endpointKey.serviceId(),
        envelope.messageId(),
        "Pending queue limit exceeded"
      ));
      return;
    }
    queue.add(envelope);

    if (Boolean.TRUE.equals(connectingFlags.putIfAbsent(endpointKey, Boolean.TRUE))) {
      return;
    }

    gateway.getConnectionManager().getConnection(endpointKey.serviceId(), endpointKey.host(), endpointKey.port(), ctx.channel().eventLoop())
      .addListener((Future<QuicChannel> connFuture) -> {
        if (!connFuture.isSuccess()) {
          runOnClientEventLoop(ctx, () -> handleConnectFailure(ctx, endpointKey, connFuture.cause()));
          return;
        }

        QuicChannel backendQuicChannel = connFuture.getNow();
        backendQuicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
          @Override
          protected void initChannel(QuicStreamChannel ch) {
            ch.config().setWriteBufferWaterMark(new WriteBufferWaterMark(32 * 1024, 64 * 1024));
            ch.pipeline().addLast(
              new LengthFieldBasedFrameDecoder(ProtocolConstants.MAX_FRAME_LENGTH, 0, 4, 0, 4),
              new LengthFieldPrepender(4),
              new BackendEnvelopeCodec(),
              new GatewayBackendRelayHandler(ctx.channel(), session, gateway.getControlRouter())
            );
          }
        }).addListener((Future<QuicStreamChannel> streamFuture) -> {
          if (streamFuture.isSuccess()) {
            QuicStreamChannel ch = streamFuture.getNow();
            runOnClientEventLoop(ctx, () -> handleStreamSuccess(ctx, session, endpointKey, ch));
          } else {
            runOnClientEventLoop(ctx, () -> handleStreamFailure(ctx, endpointKey, streamFuture.cause()));
          }
        });
      });
  }

  private void runOnClientEventLoop(ChannelHandlerContext ctx, Runnable task) {
    EventLoop eventLoop = ctx.channel().eventLoop();
    if (eventLoop == null || eventLoop.inEventLoop()) {
      task.run();
    } else if (!eventLoop.isShuttingDown()) {
      eventLoop.execute(task);
    } else {
      task.run();
    }
  }

  private void handleConnectFailure(ChannelHandlerContext ctx, EndpointKey endpointKey, Throwable cause) {
    log.error("[GATEWAY] Failed to get connection for {}: {}",
      endpointKey, cause != null ? cause.getMessage() : "unknown");
    connectingFlags.remove(endpointKey);
    short rejectedMsgId = drainAndRelease(endpointKey);
    if (ctx.channel().isActive()) {
      sendError(ctx, new GatewayErrorResponse(
        GatewayErrorResponse.ERROR_SERVICE_UNAVAILABLE,
        endpointKey.serviceId(),
        rejectedMsgId,
        "Backend service unavailable"
      ));
    }
  }

  private void handleStreamSuccess(
    ChannelHandlerContext ctx,
    ClientSession session,
    EndpointKey endpointKey,
    QuicStreamChannel ch) {

    if (!ctx.channel().isActive()) {
      connectingFlags.remove(endpointKey);
      ch.close();
      drainAndRelease(endpointKey);
      return;
    }

    if (session != null) {
      session.getBackendStreams().put(endpointKey, ch);
      ch.closeFuture().addListener((ChannelFutureListener) f -> {
        session.getBackendStreams().remove(endpointKey, ch);
        if (ctx.channel().isActive() && GatewayBackendRelayHandler.allBackendStreamsWritable(session)) {
          runOnClientEventLoop(ctx, () -> {
            if (ctx.channel().isActive()) {
              ctx.channel().config().setAutoRead(true);
            }
          });
        }
      });
    }

    // Clear connecting flag only after backendStreams has been updated
    connectingFlags.remove(endpointKey);

    log.info("[GATEWAY] Backend stream established for {}. Flushing pending frames...", endpointKey);

    Queue<BackendEnvelope> queue = pendingQueues.remove(endpointKey);
    if (queue != null) {
      BackendEnvelope queuedEnvelope;
      while ((queuedEnvelope = queue.poll()) != null) {
        ch.writeAndFlush(queuedEnvelope).addListener((ChannelFutureListener) wf -> {
          if (!wf.isSuccess()) {
            log.error("[GATEWAY] Failed to flush queued frame to backend {}: {}",
              endpointKey, wf.cause() != null ? wf.cause().getMessage() : "unknown");
          }
        });
      }
    }

    if (!ch.isWritable()) {
      ctx.channel().config().setAutoRead(false);
    }
  }

  private void handleStreamFailure(ChannelHandlerContext ctx, EndpointKey endpointKey, Throwable cause) {
    log.error("[GATEWAY] Failed to open stream for {}: {}",
      endpointKey, cause != null ? cause.getMessage() : "unknown");
    connectingFlags.remove(endpointKey);
    short rejectedMsgId = drainAndRelease(endpointKey);
    if (ctx.channel().isActive()) {
      sendError(ctx, new GatewayErrorResponse(
        GatewayErrorResponse.ERROR_SERVICE_UNAVAILABLE,
        endpointKey.serviceId(),
        rejectedMsgId,
        "Backend stream unavailable"
      ));
    }
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    Channel clientStream = ctx.channel();
    ClientSession session = resolveSession(clientStream);

    if (session != null) {
      boolean writable = clientStream.isWritable();
      boolean allBackendsWritable = true;
      for (QuicStreamChannel stream : session.getBackendStreams().values()) {
        if (stream.isActive()) {
          if (stream.eventLoop() != null && !stream.eventLoop().inEventLoop()) {
            stream.eventLoop().execute(() -> {
              if (stream.isActive()) {
                stream.config().setAutoRead(writable);
              }
            });
          } else {
            stream.config().setAutoRead(writable);
          }
          if (!stream.isWritable()) {
            allBackendsWritable = false;
          }
        }
      }
      if (writable && allBackendsWritable) {
        clientStream.config().setAutoRead(true);
      }
    }
    super.channelWritabilityChanged(ctx);
  }

  private short drainAndRelease(EndpointKey endpointKey) {
    short firstMessageId = 0;
    Queue<BackendEnvelope> queue = pendingQueues.remove(endpointKey);
    if (queue != null) {
      BackendEnvelope env;
      while ((env = queue.poll()) != null) {
        if (firstMessageId == 0) {
          firstMessageId = env.messageId();
        }
        ReferenceCountUtil.release(env);
      }
    }
    return firstMessageId;
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    pendingQueues.keySet().forEach(this::drainAndRelease);
    connectingFlags.clear();
    super.channelInactive(ctx);
  }

  private void sendError(ChannelHandlerContext ctx, GatewayErrorResponse errorResponse) {
    byte[] bytes = ForySerializationProvider.get().serialize(errorResponse);
    ByteBuf payload = ctx.alloc().buffer(bytes.length).writeBytes(bytes);
    ClientEnvelope envelope = new ClientEnvelope(ServiceType.SYSTEM, MessageType.GATEWAY_ERROR_RESPONSE, payload);
    ctx.writeAndFlush(envelope).addListener((ChannelFutureListener) f -> {
      if (!f.isSuccess()) {
        log.error("[GATEWAY] Failed to send error frame to client: {}",
          f.cause() != null ? f.cause().getMessage() : "unknown");
      }
    });
  }

  private void sendErrorAndSever(ChannelHandlerContext ctx, Channel clientStream, GatewayErrorResponse errorResponse) {
    byte[] bytes = ForySerializationProvider.get().serialize(errorResponse);
    ByteBuf payload = ctx.alloc().buffer(bytes.length).writeBytes(bytes);
    ClientEnvelope envelope = new ClientEnvelope(ServiceType.SYSTEM, MessageType.GATEWAY_ERROR_RESPONSE, payload);
    ctx.writeAndFlush(envelope).addListener((ChannelFutureListener) f -> {
      if (!f.isSuccess()) {
        log.error("[GATEWAY] Failed to send error frame before severing: {}",
          f.cause() != null ? f.cause().getMessage() : "unknown");
      }
      clientStream.close();
      if (clientStream.parent() != null && clientStream.parent().isActive()) {
        log.warn("[GATEWAY-SECURITY] Defensively closing QuicChannel for session after security violation");
        clientStream.parent().close();
      }
    });
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    log.error("[GATEWAY] Inbound stream error: {}", cause.getMessage(), cause);

    if (cause instanceof TooLongFrameException || cause instanceof CorruptedFrameException ||
        cause instanceof IndexOutOfBoundsException || cause instanceof IllegalArgumentException) {
      Channel streamChannel = ctx.channel();
      if (streamChannel.isActive()) {
        sendErrorAndSever(ctx, streamChannel, new GatewayErrorResponse(
          GatewayErrorResponse.ERROR_MALFORMED_PAYLOAD,
          ServiceType.GATEWAY,
          (short) 0,
          "Malformed or oversized payload: " + cause.getMessage()
        ));
        return;
      }
      if (streamChannel.parent() != null && streamChannel.parent().isActive()) {
        log.warn("[GATEWAY-SECURITY] Terminating QuicChannel due to protocol violation from stream {}", streamChannel);
        streamChannel.parent().close();
      }
    }
    ctx.close();
  }

  private static ClientSession resolveSession(Channel ch) {
    ClientSession session = (ch.parent() != null) ? ch.parent().attr(ClientSession.KEY).get() : null;
    return (session != null) ? session : ch.attr(ClientSession.KEY).get();
  }
}