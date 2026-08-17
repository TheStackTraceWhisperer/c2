package com.example.zone.handler;

import com.example.common.handler.PacketHandler;
import com.example.server.SessionContext;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
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
    if (sessionId == null || sessionId <= 0L) {
      log.error("[ZONE-SERVER] Security violation: Missing or invalid SessionContext. Terminating connection.");
      ctx.close();
      return;
    }

    long characterId = simulationEngine.resolveCharacterId(sessionId);
    long currentTick = simulationEngine.getCurrentTick().get();

    log.debug("[ZONE-SERVER] Movement from Session #{}, Char #{}: pos=({}, {}, {}), tick={}",
      sessionId, characterId, packet.x(), packet.y(), packet.z(), currentTick);

    simulationEngine.enqueueMovement(ctx, sessionId, characterId, packet);
    ctx.writeAndFlush(new PlayerMovementResponse(characterId, packet.x(), packet.y(), packet.z(), currentTick));
  }
}