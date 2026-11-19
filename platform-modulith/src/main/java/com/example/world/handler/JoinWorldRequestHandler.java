package com.example.world.handler;

import com.example.common.handler.PacketHandler;
import com.example.config.WorldServerProperties;
import com.example.server.SessionContext;
import com.example.server.auth.TokenClaims;
import com.example.server.auth.TokenVerifier;
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
import io.netty.util.concurrent.EventExecutor;
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
  private final TokenVerifier tokenVerifier;

  @Override
  public void handle(ChannelHandlerContext ctx, JoinWorldRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    Long characterId = ctx.channel().attr(SessionContext.CHARACTER_ID).get();
    if (sessionId == null || sessionId <= 0L || characterId == null || characterId <= 0L) {
      log.error("[WORLD-SERVER] Security violation: Received JoinWorldRequest with missing or invalid SessionContext (sessionId={}, characterId={}). Terminating connection.",
        sessionId, characterId);
      ctx.close();
      return;
    }

    dispatcher.dispatch(ctx, () -> {
      log.info("[WORLD-SERVER] JoinWorld attempt: Session #{}, characterId={}", sessionId, characterId);

      Optional<TokenClaims> claimsOpt = tokenVerifier.verify(packet.authToken());
      if (claimsOpt.isEmpty()) {
        log.warn("[WORLD-SERVER] Unauthorized JoinWorld attempt for Session #{} (Char #{}): invalid or expired JWT",
          sessionId, characterId);
        runOnChannelEventLoop(ctx, () ->
          ctx.writeAndFlush(new JoinWorldResponse(false, 0, "", 0, "Unauthorized"))
        );
        return;
      }

      Optional<CharacterRecord> characterOpt = characterRepository.findById(characterId);
      if (characterOpt.isEmpty()) {
        log.error("[WORLD-SERVER] Character #{} not found in repository", characterId);
        runOnChannelEventLoop(ctx, () ->
          ctx.writeAndFlush(new JoinWorldResponse(false, 0, "", 0, "Character does not exist"))
        );
        return;
      }

      CharacterRecord character = characterOpt.get();
      TokenClaims claims = claimsOpt.get();
      if (character.accountId() != claims.accountId()) {
        log.warn("[WORLD-SERVER] Character ownership mismatch for Session #{}: charAccount={}, tokenAccount={}",
          sessionId, character.accountId(), claims.accountId());
        runOnChannelEventLoop(ctx, () ->
          ctx.writeAndFlush(new JoinWorldResponse(false, 0, "", 0, "Character access denied"))
        );
        return;
      }

      int realmId = properties.getRealmId();
      if (character.worldId() != realmId) {
        log.warn("[WORLD-SERVER] Realm mismatch for Char #{}: character worldId={}, server realmId={}",
          characterId, character.worldId(), realmId);
        runOnChannelEventLoop(ctx, () ->
          ctx.writeAndFlush(new JoinWorldResponse(false, 0, "", 0, "Character belongs to a different world"))
        );
        return;
      }

      int targetZoneId = character.zoneId();
      EndpointTarget zoneEndpoint = zoneRegistry.getZoneEndpoint(realmId, targetZoneId);

      if (zoneEndpoint == null) {
        log.error("[WORLD-SERVER] Target Zone #{} not found in active zone registry for Realm #{}", targetZoneId, realmId);
        runOnChannelEventLoop(ctx, () ->
          ctx.writeAndFlush(new JoinWorldResponse(false, 0, "", 0, "Target zone unavailable"))
        );
        return;
      }

      sessionRegistry.registerSession(sessionId, characterId, targetZoneId, zoneEndpoint, ctx);

      log.info("[WORLD-SERVER] Dispatching CTRL_SET_ZONE -> Gateway: Session #{} -> Zone #{} ({}:{})",
        sessionId, targetZoneId, zoneEndpoint.host(), zoneEndpoint.port());

      runOnChannelEventLoop(ctx, () -> {
        ctx.writeAndFlush(new SetSessionZoneControlMessage(targetZoneId, zoneEndpoint.host(), zoneEndpoint.port()));
        ctx.writeAndFlush(new JoinWorldResponse(
          true,
          targetZoneId,
          zoneEndpoint.host(),
          zoneEndpoint.port(),
          ""
        ));
      });
    });
  }

  private void runOnChannelEventLoop(ChannelHandlerContext ctx, Runnable task) {
    if (ctx == null) {
      return;
    }
    EventExecutor executor = ctx.executor();
    if (executor == null || executor.inEventLoop()) {
      task.run();
    } else {
      executor.execute(task);
    }
  }
}
