package com.example.gateway;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.ClientEnvelope;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;
import com.example.gateway.session.ClientSession;
import com.example.server.codec.BackendForySerializationProvider;
import com.example.server.protocol.BackendEnvelope;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class GatewayBackendRelayHandler extends SimpleChannelInboundHandler<BackendEnvelope> {

  private final Channel clientStreamChannel;
  private final ClientSession session;
  private final IndexedPacketRouter controlRouter;

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, BackendEnvelope msg) throws Exception {
    if (msg.serviceId() == ServiceType.GATEWAY) {
      log.info("[GATEWAY-RELAY] Consuming control message ID: {} for Session #{}", msg.messageId(), msg.sessionId());

      if (msg.payload() != null && msg.payload().isReadable()) {
        byte[] bytes = new byte[msg.payload().readableBytes()];
        msg.payload().readBytes(bytes);
        Object controlMsg = BackendForySerializationProvider.get().deserialize(bytes);

        if (controlMsg instanceof PacketMessage packetMessage) {
          ctx.channel().attr(ClientSession.KEY).set(session);
          controlRouter.route(ctx, packetMessage);
        }
      }
      return;
    }

    log.info("[GATEWAY-RELAY] (Backend -> Client) Blind pass-through svc: {}, msg: {} (Session #{})",
      msg.serviceId(), msg.messageId(), msg.sessionId());

    if (!clientStreamChannel.isActive()) {
      log.warn("[GATEWAY-RELAY] Client channel inactive, dropping relay packet");
      return;
    }

    // Retain once for ClientEnvelope pass-through; SimpleChannelInboundHandler auto-releases msg
    ByteBuf retainedPayload = (msg.payload() != null) ? msg.payload().retain() : null;
    ClientEnvelope clientEnvelope = new ClientEnvelope(
      msg.serviceId(),
      msg.messageId(),
      retainedPayload
    );

    clientStreamChannel.writeAndFlush(clientEnvelope).addListener((ChannelFutureListener) future -> {
      if (!future.isSuccess()) {
        log.error("[GATEWAY-RELAY] Failed to relay to client stream: {}",
          future.cause() != null ? future.cause().getMessage() : "unknown");
      }
    });

    if (!clientStreamChannel.isWritable()) {
      ctx.channel().config().setAutoRead(false);
    }
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (ctx.channel().isWritable()) {
      if (clientStreamChannel.isActive() && allBackendStreamsWritable(session)) {
        setClientAutoRead(true);
      }
    } else if (clientStreamChannel.isActive()) {
      setClientAutoRead(false);
    }
    super.channelWritabilityChanged(ctx);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    log.info("[GATEWAY-RELAY] Backend stream inactive.");
    if (clientStreamChannel.isActive() && allBackendStreamsWritable(session)) {
      setClientAutoRead(true);
    }
    super.channelInactive(ctx);
  }

  static boolean allBackendStreamsWritable(ClientSession session) {
    if (session == null) {
      return true;
    }
    for (QuicStreamChannel stream : session.getBackendStreams().values()) {
      if (stream.isActive() && !stream.isWritable()) {
        return false;
      }
    }
    return true;
  }

  private void setClientAutoRead(boolean autoRead) {
    if (!clientStreamChannel.isActive()) {
      return;
    }
    if (clientStreamChannel.eventLoop() != null && !clientStreamChannel.eventLoop().inEventLoop()) {
      clientStreamChannel.eventLoop().execute(() -> {
        if (clientStreamChannel.isActive()) {
          clientStreamChannel.config().setAutoRead(autoRead);
        }
      });
    } else {
      clientStreamChannel.config().setAutoRead(autoRead);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    log.error("[GATEWAY-RELAY] Error: {}", cause.getMessage(), cause);
    ctx.close();
  }
}