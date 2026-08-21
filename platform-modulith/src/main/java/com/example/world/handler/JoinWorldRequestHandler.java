package com.example.world.handler;

import com.example.common.handler.PacketHandler;
import com.example.config.WorldServerProperties;
import com.example.server.SessionContext;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.protocol.EndpointTarget;
import com.example.server.protocol.SetSessionZoneControlMessage;
import com.example.server.repository.CharacterRepository;
import com.example.world.dto.JoinWorldRequest;
import com.example.world.dto.JoinWorldResponse;
import com.example.world.registry.WorldSessionRegistry;
import com.example.world.registry.ZoneRegistry;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class JoinWorldRequestHandler implements PacketHandler<JoinWorldRequest> {

  private final CharacterRepository characterRepository;
  private final ZoneRegistry zoneRegistry;
  private final WorldSessionRegistry sessionRegistry;
  private final VirtualThreadTaskDispatcher dispatcher;
  private final WorldServerProperties properties;

  @Override
  public void handle(ChannelHandlerContext ctx, JoinWorldRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    if (sessionId == null || sessionId <= 0L) {
      log.error("[WORLD-SERVER] Security violation: Received JoinWorldRequest with missing or invalid SessionContext. Terminating connection.");
      ctx.close();
      return;
    }

    dispatcher.dispatch(ctx, () -> {
      log.info("[WORLD-SERVER] JoinWorld attempt: Session #{}, characterId={}", sessionId, packet.characterId());

      Optional<CharacterRecord> characterOpt = characterRepository.findById(packet.characterId());
      if (characterOpt.isEmpty()) {
        log.error("[WORLD-SERVER] Character #{} not found in repository", packet.characterId());
        ctx.writeAndFlush(new JoinWorldResponse(false, 0, "", 0, "Character does not exist"));
        return;
      }

      CharacterRecord character = characterOpt.get();
      int targetZoneId = character.zoneId();
      int realmId = properties.getRealmId();
      EndpointTarget zoneEndpoint = zoneRegistry.getZoneEndpoint(realmId, targetZoneId);

      if (zoneEndpoint == null) {
        log.error("[WORLD-SERVER] Target Zone #{} not found in active zone registry for Realm #{}", targetZoneId, realmId);
        ctx.writeAndFlush(new JoinWorldResponse(false, 0, "", 0, "Target zone unavailable"));
        return;
      }

      sessionRegistry.registerSession(sessionId, packet.characterId(), targetZoneId, zoneEndpoint, ctx);

      log.info("[WORLD-SERVER] Dispatching CTRL_SET_ZONE -> Gateway: Session #{} -> Zone #{} ({}:{})",
        sessionId, targetZoneId, zoneEndpoint.host(), zoneEndpoint.port());

      ctx.write(new SetSessionZoneControlMessage(targetZoneId, zoneEndpoint.host(), zoneEndpoint.port()));

      ctx.writeAndFlush(new JoinWorldResponse(
        true,
        targetZoneId,
        zoneEndpoint.host(),
        zoneEndpoint.port(),
        ""
      ));
    });
  }
}