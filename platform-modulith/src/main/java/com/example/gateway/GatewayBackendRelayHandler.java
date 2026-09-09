package com.example.gateway;

import com.example.common.handler.IndexedPacketRouter;
import com.example.common.protocol.ClientEnvelope;
import com.example.common.protocol.PacketMessage;
import com.example.common.protocol.ServiceType;
import com.example.gateway.session.ClientSession;
import com.example.server.codec.BackendForySerializationProvider;
import com.example.server.protocol.BackendEnvelope;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
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

      try {
        if (msg.payload() != null && msg.payload().isReadable()) {
          byte[] bytes = new byte[msg.payload().readableBytes()];
          msg.payload().readBytes(bytes);
          Object controlMsg = BackendForySerializationProvider.get().deserialize(bytes);

          if (controlMsg instanceof PacketMessage packetMessage) {
            ctx.channel().attr(ClientSession.KEY).set(session);
            controlRouter.route(ctx, packetMessage);
          }
        }
      } finally {
        if (msg.payload() != null) {
          msg.payload().release();
        }
      }
      return;
    }

    log.info("[GATEWAY-RELAY] (Backend -> Client) Blind pass-through svc: {}, msg: {} (Session #{})",
      msg.serviceId(), msg.messageId(), msg.sessionId());

    if (!clientStreamChannel.isActive()) {
      log.warn("[GATEWAY-RELAY] Client channel inactive, dropping relay packet");
      if (msg.payload() != null && msg.payload().refCnt() > 0) {
        msg.payload().release();
      }
      return;
    }

    // Pass the payload directly without double-retaining; ClientEnvelopeCodec.encode releases it
    ClientEnvelope clientEnvelope = new ClientEnvelope(
      msg.serviceId(),
      msg.messageId(),
      msg.payload().retain()
    );

    clientStreamChannel.writeAndFlush(clientEnvelope).addListener((ChannelFutureListener) future -> {
      if (!future.isSuccess()) {
        log.error("[GATEWAY-RELAY] Failed to relay to client stream: {}", future.cause().getMessage());
        if (clientEnvelope.payload() != null) {
          clientEnvelope.payload().release();
        }
      }
    });

    if (!clientStreamChannel.isWritable()) {
      ctx.channel().config().setAutoRead(false);
    }
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (ctx.channel().isWritable() && clientStreamChannel.isActive()) {
      clientStreamChannel.config().setAutoRead(true);
    }
    super.channelWritabilityChanged(ctx);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    log.info("[GATEWAY-RELAY] Backend stream inactive.");
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    if (clientStreamChannel != null && clientStreamChannel.isActive()) {
      clientStreamChannel.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter() {
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext clientCtx) throws Exception {
          if (ctx.channel().isActive()) {
            ctx.channel().config().setAutoRead(clientCtx.channel().isWritable());
          }
          super.channelWritabilityChanged(clientCtx);
        }
      });
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    log.error("[GATEWAY-RELAY] Error: {}", cause.getMessage(), cause);
    ctx.close();
  }
}