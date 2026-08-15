package com.example.world.handler;

import com.example.common.handler.PacketHandler;
import com.example.server.SessionContext;
import com.example.world.dto.PlayerMovementRequest;
import com.example.world.dto.PlayerMovementResponse;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Singleton
public class PlayerMovementRequestHandler implements PacketHandler<PlayerMovementRequest> {

  private final AtomicLong serverTick = new AtomicLong(0L);

  @Override
  public void handle(ChannelHandlerContext ctx, PlayerMovementRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    long currentTick = serverTick.incrementAndGet();

    log.debug("[WORLD-SERVER] Movement from Session #{}: pos=({}, {}, {}), tick={}",
      sessionId, packet.x(), packet.y(), packet.z(), currentTick);

    // Echo back acknowledged position with the server tick
    ctx.writeAndFlush(new PlayerMovementResponse(1001L, packet.x(), packet.y(), packet.z(), currentTick));
  }
}