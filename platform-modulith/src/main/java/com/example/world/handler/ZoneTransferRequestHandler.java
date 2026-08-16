package com.example.world.handler;

import com.example.common.handler.PacketHandler;
import com.example.config.DemoProperties;
import com.example.config.DemoProperties.EndpointProperties;
import com.example.server.SessionContext;
import com.example.server.protocol.SetSessionZoneControlMessage;
import com.example.world.dto.ZoneTransferRequest;
import com.example.world.dto.ZoneTransferResponse;
import com.example.world.registry.WorldSessionRegistry;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ZoneTransferRequestHandler implements PacketHandler<ZoneTransferRequest> {

  private final DemoProperties properties;
  private final WorldSessionRegistry sessionRegistry;

  @Override
  public void handle(ChannelHandlerContext ctx, ZoneTransferRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    long sid = (sessionId != null) ? sessionId : 0L;

    log.info("[WORLD-SERVER] ZoneTransfer request: Session #{}, charId={}, targetZone={}",
      sid, packet.characterId(), packet.targetZoneId());

    EndpointProperties targetZoneEndpoint = properties.getGateway().getWorld(packet.targetZoneId());
    if (targetZoneEndpoint == null) {
      log.error("[WORLD-SERVER] Target Zone #{} not found in routing map", packet.targetZoneId());
      ctx.writeAndFlush(new ZoneTransferResponse(false, packet.targetZoneId(), "", 0, "Target zone unavailable"));
      return;
    }

    sessionRegistry.updateZone(sid, packet.targetZoneId(), targetZoneEndpoint);

    log.info("[WORLD-SERVER] Switching Gateway affinity for Session #{} -> Zone #{} ({}:{})",
      sid, packet.targetZoneId(), targetZoneEndpoint.getHostname(), targetZoneEndpoint.getPort());

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