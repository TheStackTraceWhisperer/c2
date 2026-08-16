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
    long sid = (sessionId != null) ? sessionId : 0L;
    long characterId = 1001L; // Derived from session registry / context

    long currentTick = simulationEngine.getCurrentTick().get();

    log.debug("[ZONE-SERVER] Movement from Session #{}, Char #{}: pos=({}, {}, {}), tick={}",
      sid, characterId, packet.x(), packet.y(), packet.z(), currentTick);

    // Queue movement for authoritative simulation processing
    simulationEngine.enqueueMovement(ctx, sid, characterId, packet);

    // Direct synchronous ACK response to client stream
    ctx.writeAndFlush(new PlayerMovementResponse(characterId, packet.x(), packet.y(), packet.z(), currentTick));
  }
}