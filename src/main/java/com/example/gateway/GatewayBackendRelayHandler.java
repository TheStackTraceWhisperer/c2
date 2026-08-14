package com.example.gateway;


import com.example.common.backend.protocol.BackendEnvelope;
import com.example.common.protocol.ClientEnvelope;
import com.example.common.protocol.MessageType;
import com.example.common.protocol.ServiceType;
import com.example.gateway.session.ClientSession;
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

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, BackendEnvelope msg) {
    log.info("[GATEWAY-RELAY] (Backend -> Client) Relaying svc: {}, msg: {} (Session #{})",
      msg.serviceId(), msg.messageId(), msg.sessionId());

    // Update Session State on Login success
    if (msg.serviceId() == ServiceType.AUTH && msg.messageId() == MessageType.LOGIN_RESPONSE) {
      if (session != null) {
        session.setAuthenticated(true);
        log.info("[GATEWAY-SECURITY] Session #{} marked as AUTHENTICATED", session.getSessionId());
      }
    }

    ClientEnvelope clientEnvelope = new ClientEnvelope(
      msg.serviceId(),
      msg.messageId(),
      msg.payload().retain()
    );

    clientStreamChannel.writeAndFlush(clientEnvelope).addListener((ChannelFutureListener) future -> {
      if (!future.isSuccess()) {
        log.error("[GATEWAY-RELAY] Failed to relay to client stream: {}", future.cause().getMessage());
      }
    });
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    log.info("[GATEWAY-RELAY] Backend stream inactive.");
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    log.error("[GATEWAY-RELAY] Error: {}", cause.getMessage(), cause);
    ctx.close();
  }
}