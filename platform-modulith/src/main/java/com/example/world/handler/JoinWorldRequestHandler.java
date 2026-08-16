package com.example.world.handler;

import com.example.common.handler.PacketHandler;
import com.example.config.DemoProperties;
import com.example.config.DemoProperties.EndpointProperties;
import com.example.server.SessionContext;
import com.example.server.protocol.SetSessionZoneControlMessage;
import com.example.world.dto.JoinWorldRequest;
import com.example.world.dto.JoinWorldResponse;
import com.example.world.registry.WorldSessionRegistry;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class JoinWorldRequestHandler implements PacketHandler<JoinWorldRequest> {

  private final DemoProperties properties;
  private final WorldSessionRegistry sessionRegistry;

  @Override
  public void handle(ChannelHandlerContext ctx, JoinWorldRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    long sid = (sessionId != null) ? sessionId : 0L;

    log.info("[WORLD-SERVER] JoinWorld attempt: Session #{}, characterId={}", sid, packet.characterId());

    int initialZoneId = 1;
    EndpointProperties zoneEndpoint = properties.getGateway().getWorld(initialZoneId);

    if (zoneEndpoint == null) {
      log.error("[WORLD-SERVER] No zone route configured for initial Zone #{}", initialZoneId);
      ctx.writeAndFlush(new JoinWorldResponse(false, 0, "", 0, "Initial zone unavailable"));
      return;
    }

    sessionRegistry.registerSession(sid, packet.characterId(), initialZoneId, zoneEndpoint);

    log.info("[WORLD-SERVER] Dispatching CTRL_SET_ZONE -> Gateway: Session #{} -> Zone #{} ({}:{})",
      sid, initialZoneId, zoneEndpoint.getHostname(), zoneEndpoint.getPort());

    ctx.write(new SetSessionZoneControlMessage(initialZoneId, zoneEndpoint.getHostname(), zoneEndpoint.getPort()));

    ctx.writeAndFlush(new JoinWorldResponse(
      true,
      initialZoneId,
      zoneEndpoint.getHostname(),
      zoneEndpoint.getPort(),
      ""
    ));
  }
}