package com.example.gateway;

import com.example.common.dto.GatewayErrorResponse;
import com.example.common.protocol.ClientEnvelope;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.ProtocolConstants;
import com.example.common.protocol.ServiceType;
import com.example.gateway.session.ClientSession;
import com.example.server.codec.BackendForySerializationProvider;
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
import io.netty.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
public class GatewayInboundStreamHandler extends SimpleChannelInboundHandler<ClientEnvelope> {

  private final QuicGateway gateway;
  private final Map<Long, Queue<BackendEnvelope>> pendingQueues = new ConcurrentHashMap<>();
  private final Map<Long, Boolean> connectingFlags = new ConcurrentHashMap<>();

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, ClientEnvelope msg) {
    QuicStreamChannel clientStream = (QuicStreamChannel) ctx.channel();
    ClientSession session = clientStream.parent().attr(ClientSession.KEY).get();
    long sessionId = (session != null) ? session.getSessionId() : 0L;

    try {
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

      long endpointKey = BackendConnectionManager.makeKey(msg.serviceId(), endpoint.host(), endpoint.port());
      long characterId = (session != null) ? session.getCharacterId() : 0L;

      BackendEnvelope backendEnvelope = new BackendEnvelope(
        sessionId,
        characterId,
        msg.serviceId(),
        msg.messageId(),
        msg.payload().retain()
      );

      QuicStreamChannel backendStream = (session != null) ? session.getBackendStreams().get(endpointKey) : null;

      if (backendStream != null && backendStream.isActive()) {
        backendStream.writeAndFlush(backendEnvelope).addListener((ChannelFutureListener) future -> {
          if (!future.isSuccess()) {
            log.error("[GATEWAY] Failed to forward to backend stream for key 0x{}: {}",
              Long.toHexString(endpointKey), future.cause().getMessage());
            if (backendEnvelope.payload() != null && backendEnvelope.payload().refCnt() > 0) {
              backendEnvelope.payload().release();
            }
          }
        });

        if (!backendStream.isWritable()) {
          clientStream.config().setAutoRead(false);
        }
      } else {
        enqueueAndConnect(ctx, session, msg.serviceId(), endpoint.host(), endpoint.port(), endpointKey, backendEnvelope);
      }
    } finally {
      if (msg.payload() != null && msg.payload().refCnt() > 0) {
        msg.payload().release();
      }
    }
  }

  private void enqueueAndConnect(
    ChannelHandlerContext ctx,
    ClientSession session,
    short serviceId,
    String host,
    int port,
    long endpointKey,
    BackendEnvelope envelope) {

    pendingQueues.computeIfAbsent(endpointKey, k -> new ArrayDeque<>()).add(envelope);

    if (Boolean.TRUE.equals(connectingFlags.putIfAbsent(endpointKey, Boolean.TRUE))) {
      return;
    }

    gateway.getConnectionManager().getConnection(serviceId, host, port, ctx.channel().eventLoop())
      .addListener((Future<QuicChannel> connFuture) -> {
        if (!connFuture.isSuccess()) {
          log.error("[GATEWAY] Failed to get connection for key 0x{}: {}",
            Long.toHexString(endpointKey), connFuture.cause().getMessage());
          connectingFlags.remove(endpointKey);
          drainAndRelease(endpointKey);
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
          connectingFlags.remove(endpointKey);

          if (!ctx.channel().isActive()) {
            if (streamFuture.isSuccess()) streamFuture.getNow().close();
            drainAndRelease(endpointKey);
            return;
          }

          if (streamFuture.isSuccess()) {
            QuicStreamChannel ch = streamFuture.getNow();

            if (session != null) {
              session.getBackendStreams().put(endpointKey, ch);
              ch.closeFuture().addListener((ChannelFutureListener) f -> {
                session.getBackendStreams().remove(endpointKey, ch);
              });
            }

            log.info("[GATEWAY] Backend stream established for key 0x{}. Flushing pending frames...",
              Long.toHexString(endpointKey));

            Queue<BackendEnvelope> queue = pendingQueues.remove(endpointKey);
            if (queue != null) {
              while (!queue.isEmpty()) {
                BackendEnvelope queuedEnvelope = queue.poll();
                ch.writeAndFlush(queuedEnvelope).addListener((ChannelFutureListener) wf -> {
                  if (!wf.isSuccess() && queuedEnvelope.payload() != null && queuedEnvelope.payload().refCnt() > 0) {
                    queuedEnvelope.payload().release();
                  }
                });
              }
            }
          } else {
            log.error("[GATEWAY] Failed to open stream for key 0x{}: {}",
              Long.toHexString(endpointKey), streamFuture.cause().getMessage());
            drainAndRelease(endpointKey);
          }
        });
      });
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    QuicStreamChannel clientStream = (QuicStreamChannel) ctx.channel();
    ClientSession session = clientStream.parent().attr(ClientSession.KEY).get();

    if (session != null) {
      boolean writable = clientStream.isWritable();
      session.getBackendStreams().values().forEach(stream -> {
        if (stream.isActive()) {
          stream.config().setAutoRead(writable);
        }
      });
    }
    super.channelWritabilityChanged(ctx);
  }

  private void drainAndRelease(long endpointKey) {
    Queue<BackendEnvelope> queue = pendingQueues.remove(endpointKey);
    if (queue != null) {
      while (!queue.isEmpty()) {
        BackendEnvelope env = queue.poll();
        if (env.payload() != null && env.payload().refCnt() > 0) {
          env.payload().release();
        }
      }
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    pendingQueues.keySet().forEach(this::drainAndRelease);
  }

  private void sendError(ChannelHandlerContext ctx, GatewayErrorResponse errorResponse) {
    byte[] bytes = BackendForySerializationProvider.get().serialize(errorResponse);
    ByteBuf payload = ctx.alloc().buffer(bytes.length);
    payload.writeBytes(bytes);
    ClientEnvelope envelope = new ClientEnvelope(ServiceType.SYSTEM, MessageType.GATEWAY_ERROR_RESPONSE, payload);
    ctx.writeAndFlush(envelope).addListener((ChannelFutureListener) f -> {
      if (!f.isSuccess()) {
        log.error("[GATEWAY] Failed to send error frame to client: {}", f.cause().getMessage());
        if (payload.refCnt() > 0) {
          payload.release();
        }
      }
    });
  }

  private void sendErrorAndSever(ChannelHandlerContext ctx, QuicStreamChannel clientStream, GatewayErrorResponse errorResponse) {
    byte[] bytes = BackendForySerializationProvider.get().serialize(errorResponse);
    ByteBuf payload = ctx.alloc().buffer(bytes.length);
    payload.writeBytes(bytes);
    ClientEnvelope envelope = new ClientEnvelope(ServiceType.SYSTEM, MessageType.GATEWAY_ERROR_RESPONSE, payload);
    ctx.writeAndFlush(envelope).addListener((ChannelFutureListener) f -> {
      if (!f.isSuccess()) {
        log.error("[GATEWAY] Failed to send error frame before severing: {}", f.cause().getMessage());
        if (payload.refCnt() > 0) {
          payload.release();
        }
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

    if (cause instanceof TooLongFrameException || cause instanceof CorruptedFrameException) {
      QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
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
}