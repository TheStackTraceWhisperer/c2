package com.example.gateway;

import com.example.common.protocol.ClientEnvelope;
import com.example.common.protocol.ProtocolConstants;
import com.example.common.protocol.ServiceType;
import com.example.gateway.session.ClientSession;
import com.example.server.protocol.BackendEnvelope;
import com.example.server.protocol.BackendEnvelopeCodec;
import io.netty.channel.*;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
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
  private final Map<Short, Queue<BackendEnvelope>> pendingQueues = new ConcurrentHashMap<>();
  private final Map<Short, Boolean> connectingFlags = new ConcurrentHashMap<>();

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, ClientEnvelope msg) {
    QuicStreamChannel clientStream = (QuicStreamChannel) ctx.channel();
    ClientSession session = clientStream.parent().attr(ClientSession.KEY).get();
    long sessionId = (session != null) ? session.getSessionId() : 0L;

    // Defense 1: Rate Limiter (Token Bucket)
    if (session != null && !session.getRateLimiter().tryConsume()) {
      log.warn("[GATEWAY-RATELIMIT] Dropped packet svc: {}, msg: {} from Session #{} (Rate limit exceeded)",
        msg.serviceId(), msg.messageId(), sessionId);
      return;
    }

    // Defense 2: Security Gate (Auth)
    if ((msg.serviceId() == ServiceType.LOBBY || msg.serviceId() == ServiceType.WORLD)
      && (session == null || !session.isAuthenticated())) {
      log.warn("[GATEWAY-SECURITY] Blocked unauthenticated access to svc {} from Session #{}", msg.serviceId(), sessionId);
      return;
    }

    // Defense 3: Security Gate (Character Selection)
    if (msg.serviceId() == ServiceType.WORLD && (session == null || session.getCharacterId() == 0L)) {
      log.warn("[GATEWAY-SECURITY] Blocked unselected character access to WORLD from Session #{}", sessionId);
      return;
    }

    log.info("[GATEWAY] Inbound ClientEnvelope (svc: {}, msg: {}). Stamping Session #{}",
      msg.serviceId(), msg.messageId(), sessionId);

    BackendEnvelope backendEnvelope = new BackendEnvelope(
      sessionId,
      msg.serviceId(),
      msg.messageId(),
      msg.payload().retain()
    );

    short serviceId = msg.serviceId();
    QuicStreamChannel backendStream = (session != null) ? session.getBackendStreams().get(serviceId) : null;

    if (backendStream != null && backendStream.isActive()) {
      backendStream.writeAndFlush(backendEnvelope).addListener((ChannelFutureListener) future -> {
        if (!future.isSuccess()) {
          log.error("[GATEWAY] Failed to forward to backend stream for svc {}: {}", serviceId, future.cause().getMessage());
          if (backendEnvelope.payload() != null && backendEnvelope.payload().refCnt() > 0) {
            backendEnvelope.payload().release();
          }
        }
      });

      // Flow Control: If backend write buffer is congested, pause reading from client
      if (!backendStream.isWritable()) {
        clientStream.config().setAutoRead(false);
      }
    } else {
      enqueueAndConnect(ctx, session, serviceId, backendEnvelope);
    }
  }

  private void enqueueAndConnect(ChannelHandlerContext ctx, ClientSession session, short serviceId, BackendEnvelope envelope) {
    pendingQueues.computeIfAbsent(serviceId, k -> new ArrayDeque<>()).add(envelope);

    if (Boolean.TRUE.equals(connectingFlags.putIfAbsent(serviceId, Boolean.TRUE))) {
      return;
    }

    gateway.getConnectionManager().getConnection(serviceId, ctx.channel().eventLoop())
      .addListener((Future<QuicChannel> connFuture) -> {
        if (!connFuture.isSuccess()) {
          log.error("[GATEWAY] Failed to get connection for svc {}: {}", serviceId, connFuture.cause().getMessage());
          connectingFlags.remove(serviceId);
          drainAndRelease(serviceId);
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
          connectingFlags.remove(serviceId);

          if (!ctx.channel().isActive()) {
            if (streamFuture.isSuccess()) streamFuture.getNow().close();
            drainAndRelease(serviceId);
            return;
          }

          if (streamFuture.isSuccess()) {
            QuicStreamChannel ch = streamFuture.getNow();

            if (session != null) {
              session.getBackendStreams().put(serviceId, ch);

              ch.closeFuture().addListener((ChannelFutureListener) f -> {
                log.debug("[GATEWAY] Backend stream for svc {} closed. Evicting from Session #{}",
                  serviceId, session.getSessionId());
                session.getBackendStreams().remove(serviceId, ch);
              });
            }

            log.info("[GATEWAY] Backend stream established for svc {}. Flushing pending frames...", serviceId);

            Queue<BackendEnvelope> queue = pendingQueues.remove(serviceId);
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
            log.error("[GATEWAY] Failed to open stream for svc {}: {}", serviceId, streamFuture.cause().getMessage());
            drainAndRelease(serviceId);
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

  private void drainAndRelease(short serviceId) {
    Queue<BackendEnvelope> queue = pendingQueues.remove(serviceId);
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

    if (cause instanceof io.netty.handler.codec.TooLongFrameException || cause instanceof io.netty.handler.codec.CorruptedFrameException) {
      QuicStreamChannel streamChannel = (QuicStreamChannel) ctx.channel();
      if (streamChannel.parent() != null && streamChannel.parent().isActive()) {
        log.warn("[GATEWAY-SECURITY] Terminating QuicChannel due to protocol violation from stream {}", streamChannel);
        streamChannel.parent().close();
      }
    }
  }
}