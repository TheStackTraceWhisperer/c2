package com.example.zone.handler;

import com.example.common.handler.PacketHandler;
import com.example.server.SessionContext;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.engine.ZoneSimulationEngine;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class PlayerMovementRequestHandler implements PacketHandler<PlayerMovementRequest> {

  private final ZoneSimulationEngine simulationEngine;

  @Override
  public void handle(ChannelHandlerContext ctx, PlayerMovementRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    Long characterId = ctx.channel().attr(SessionContext.CHARACTER_ID).get();

    if (sessionId == null || sessionId <= 0L || characterId == null || characterId <= 0L) {
      log.error("[ZONE-SERVER] Security violation: Missing SessionContext (session={}, character={}).", sessionId, characterId);
      ctx.close();
      return;
    }

    simulationEngine.enqueueMovement(ctx, sessionId, characterId, packet);
  }
}