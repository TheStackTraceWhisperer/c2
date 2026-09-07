package com.example.login.handler;

import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.common.handler.PacketHandler;
import com.example.server.SessionContext;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class PingPacketHandler implements PacketHandler<PingMessage> {

  @Override
  public void handle(ChannelHandlerContext ctx, PingMessage packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    log.info("[LOGIN-SERVER] Received from Session #{}: {} (ts: {})", sessionId, packet.message(), packet.timestamp());

    ctx.executor().schedule(() -> {
      log.info("[LOGIN-SERVER] Replying PONG to Session #{}", sessionId);
      ctx.writeAndFlush(new PongMessage(packet.timestamp(), "PONG"));
    }, 100, TimeUnit.MILLISECONDS);
  }
}