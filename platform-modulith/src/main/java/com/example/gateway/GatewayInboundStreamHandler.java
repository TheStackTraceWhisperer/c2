package com.example.gateway;

import com.example.common.protocol.ClientEnvelope;
import com.example.common.protocol.ProtocolConstants;
import com.example.common.protocol.ServiceType;
import com.example.config.DemoProperties.EndpointProperties;
import com.example.gateway.session.ClientSession;
import com.example.server.protocol.BackendEnvelope;
import com.example.server.protocol.BackendEnvelopeCodec;
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
        log.warn("[GATEWAY-RATELIMIT] Dropped packet svc: {}, msg: {} from Session #{} (Rate limit exceeded)",
          msg.serviceId(), msg.messageId(), sessionId);
        return;
      }

      if ((msg.serviceId() == ServiceType.LOBBY || msg.serviceId() == ServiceType.WORLD || msg.serviceId() == ServiceType.ZONE)
        && (session == null || !session.isAuthenticated())) {
        log.warn("[GATEWAY-SECURITY] Blocked unauthenticated access to svc {} from Session #{}", msg.serviceId(), sessionId);
        return;
      }

      if ((msg.serviceId() == ServiceType.WORLD || msg.serviceId() == ServiceType.ZONE)
        && (session == null || session.getCharacterId() == 0L)) {
        log.warn("[GATEWAY-SECURITY] Blocked unselected character access to svc {} from Session #{}", msg.serviceId(), sessionId);
        return;
      }

      EndpointProperties endpoint = gateway.getConnectionManager().resolveEndpoint(msg.serviceId(), session);
      if (endpoint == null) {
        log.error("[GATEWAY] No destination endpoint resolved for svc: {} on Session #{}", msg.serviceId(), sessionId);
        return;
      }

      long endpointKey = BackendConnectionManager.makeKey(msg.serviceId(), endpoint.getHostname(), endpoint.getPort());
      long characterId = (session != null) ? session.getCharacterId() : 0L;

      log.debug("[GATEWAY] ClientEnvelope (svc: {}, msg: {}). Stamping Session #{} -> Target Key 0x{}",
        msg.serviceId(), msg.messageId(), sessionId, Long.toHexString(endpointKey));

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
        enqueueAndConnect(ctx, session, msg.serviceId(), endpoint.getHostname(), endpoint.getPort(), endpointKey, backendEnvelope);
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
                log.debug("[GATEWAY] Backend stream for key 0x{} closed. Evicting from Session #{}",
                  Long.toHexString(endpointKey), session.getSessionId());
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

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    log.error("[GATEWAY] Inbound stream error: {}", cause.getMessage(), cause);
    ctx.close();

    if (cause instanceof TooLongFrameException || cause instanceof CorruptedFrameException) {
      QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
      if (streamChannel.parent() != null && streamChannel.parent().isActive()) {
        log.warn("[GATEWAY-SECURITY] Terminating QuicChannel due to protocol violation from stream {}", streamChannel);
        streamChannel.parent().close();
      }
    }
  }
}