package com.example.world.handler;

import com.example.common.handler.PacketHandler;
import com.example.config.DemoProperties.EndpointProperties;
import com.example.server.SessionContext;
import com.example.server.entity.CharacterRecord;
import com.example.server.protocol.SetSessionZoneControlMessage;
import com.example.server.repository.CharacterRepository;
import com.example.world.dto.ZoneTransferRequest;
import com.example.world.dto.ZoneTransferResponse;
import com.example.world.registry.WorldSessionRegistry;
import com.example.world.registry.ZoneRegistry;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ZoneTransferRequestHandler implements PacketHandler<ZoneTransferRequest> {

  private final CharacterRepository characterRepository;
  private final ZoneRegistry zoneRegistry;
  private final WorldSessionRegistry sessionRegistry;

  @Override
  public void handle(ChannelHandlerContext ctx, ZoneTransferRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    if (sessionId == null || sessionId <= 0L) {
      log.error("[WORLD-SERVER] Security violation: Received ZoneTransferRequest with missing or invalid SessionContext. Terminating connection.");
      ctx.close();
      return;
    }

    log.info("[WORLD-SERVER] ZoneTransfer request: Session #{}, charId={}, targetZone={}",
      sessionId, packet.characterId(), packet.targetZoneId());

    EndpointProperties targetZoneEndpoint = zoneRegistry.getZoneEndpoint(packet.targetZoneId());
    if (targetZoneEndpoint == null) {
      log.error("[WORLD-SERVER] Target Zone #{} not found in active zone registry", packet.targetZoneId());
      ctx.writeAndFlush(new ZoneTransferResponse(false, packet.targetZoneId(), "", 0, "Target zone unavailable"));
      return;
    }

    sessionRegistry.updateZone(sessionId, packet.targetZoneId(), targetZoneEndpoint);
    characterRepository.updatePosition(packet.characterId(), packet.targetZoneId(), 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

    log.info("[WORLD-SERVER] Switching Gateway affinity for Session #{} -> Zone #{} ({}:{})",
      sessionId, packet.targetZoneId(), targetZoneEndpoint.getHostname(), targetZoneEndpoint.getPort());

    ctx.write(new SetSessionZoneControlMessage(
      packet.targetZoneId(),
      targetZoneEndpoint.getHostname(),
      targetZoneEndpoint.getPort()
    ));

    ctx.writeAndFlush(new ZoneTransferResponse(
      true,
      packet.targetZoneId(),
      targetZoneEndpoint.getHostname(),
      targetZoneEndpoint.getPort(),
      ""
    ));
  }
}