package com.example.gateway;

import com.example.common.backend.protocol.BackendEnvelope;
import com.example.common.backend.protocol.BackendEnvelopeCodec;
import com.example.common.protocol.ClientEnvelope;
import com.example.common.protocol.ServiceType;
import com.example.gateway.session.ClientSession;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
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

    // Security Gate: Reject unauthenticated access to Lobby/World
    if (msg.serviceId() == ServiceType.LOBBY && (session == null || !session.isAuthenticated())) {
      log.warn("[GATEWAY-SECURITY] Blocked unauthenticated access to LOBBY from Session #{}", sessionId);
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
        }
      });
    } else {
      enqueueAndConnect(ctx, session, serviceId, backendEnvelope);
    }
  }

  private void enqueueAndConnect(ChannelHandlerContext ctx, ClientSession session, short serviceId, BackendEnvelope envelope) {
    pendingQueues.computeIfAbsent(serviceId, k -> new ArrayDeque<>()).add(envelope);

    if (Boolean.TRUE.equals(connectingFlags.putIfAbsent(serviceId, Boolean.TRUE))) {
      // Stream acquisition already in progress; envelope safely buffered in pending queue
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
            ch.pipeline().addLast(
              new LengthFieldBasedFrameDecoder(65535, 0, 4, 0, 4),
              new LengthFieldPrepender(4),
              new BackendEnvelopeCodec(),
              new GatewayBackendRelayHandler(ctx.channel(), session)
            );
          }
        }).addListener((Future<QuicStreamChannel> streamFuture) -> {
          connectingFlags.remove(serviceId);

          // If client disconnected while backend stream was establishing, close immediately
          if (!ctx.channel().isActive()) {
            if (streamFuture.isSuccess()) streamFuture.getNow().close();
            drainAndRelease(serviceId);
            return;
          }

          if (streamFuture.isSuccess()) {
            QuicStreamChannel ch = streamFuture.getNow();

            if (session != null) {
              session.getBackendStreams().put(serviceId, ch);

              // Auto-eviction: remove from session map as soon as this specific stream channel closes
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
                ch.writeAndFlush(queue.poll());
              }
            }
          } else {
            log.error("[GATEWAY] Failed to open stream for svc {}: {}", serviceId, streamFuture.cause().getMessage());
            drainAndRelease(serviceId);
          }
        });
      });
  }

  private void drainAndRelease(short serviceId) {
    Queue<BackendEnvelope> queue = pendingQueues.remove(serviceId);
    if (queue != null) {
      while (!queue.isEmpty()) {
        BackendEnvelope env = queue.poll();
        if (env.payload() != null) env.payload().release();
      }
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    // Drain and release any pending frames if the client stream closes abruptly
    pendingQueues.keySet().forEach(this::drainAndRelease);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    log.error("[GATEWAY] Inbound stream error: {}", cause.getMessage(), cause);
    ctx.close();
  }
}