package com.example.lobby.handler;

import com.example.common.handler.PacketHandler;
import com.example.lobby.NationStartingLocation;
import com.example.lobby.dto.CreateCharacterRequest;
import com.example.lobby.dto.CreateCharacterResponse;
import com.example.server.SessionContext;
import com.example.server.auth.TokenClaims;
import com.example.server.auth.TokenVerifier;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.repository.CharacterRepository;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class CreateCharacterRequestHandler implements PacketHandler<CreateCharacterRequest> {

  private final TokenVerifier tokenVerifier;
  private final CharacterRepository characterRepository;
  private final VirtualThreadTaskDispatcher dispatcher;

  @Override
  public void handle(ChannelHandlerContext ctx, CreateCharacterRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    if (sessionId == null || sessionId <= 0L) {
      log.error("[LOBBY-SERVER] Security violation: Missing or invalid SessionContext. Terminating connection.");
      ctx.close();
      return;
    }

    dispatcher.dispatch(ctx, () -> {
      log.info("[LOBBY-SERVER] CreateCharacter attempt for Session #{}: name='{}', nation={}, job={}",
        sessionId, packet.name(), packet.nationId(), packet.mainJobId());

      Optional<TokenClaims> claimsOpt = tokenVerifier.verify(packet.authToken());
      if (claimsOpt.isEmpty()) {
        log.warn("[LOBBY-SERVER] Unauthorized create character attempt from Session #{}: invalid token", sessionId);
        ctx.writeAndFlush(new CreateCharacterResponse(false, 0L, "Unauthorized"));
        return;
      }

      TokenClaims claims = claimsOpt.get();
      long accountId = claims.accountId();

      NationStartingLocation spawn = NationStartingLocation.fromNationId(packet.nationId());

      CharacterRecord newRecord = new CharacterRecord(
        accountId,
        0L,
        packet.worldId(),
        packet.name(),
        packet.raceId(),
        packet.gender(),
        packet.faceId(),
        packet.hairId(),
        packet.sizeId(),
        packet.nationId(),
        packet.mainJobId(),
        (short) 1,
        (short) 0,
        (short) 0,
        spawn.zoneId,
        spawn.posX,
        spawn.posY,
        spawn.posZ,
        spawn.rotYaw,
        spawn.rotPitch,
        spawn.zoneId,
        spawn.posX,
        spawn.posY,
        spawn.posZ,
        Instant.now()
      );

      try {
        CharacterRecord saved = characterRepository.save(newRecord);
        log.info("[LOBBY-SERVER] Character created successfully: id={}, name='{}' in Zone #{} ({}, {}, {})",
          saved.characterId(), saved.name(), spawn.zoneId, spawn.posX, spawn.posY, spawn.posZ);
        ctx.writeAndFlush(new CreateCharacterResponse(true, saved.characterId(), ""));
      } catch (Exception e) {
        log.error("[LOBBY-SERVER] Failed to create character '{}': {}", packet.name(), e.getMessage(), e);
        ctx.writeAndFlush(new CreateCharacterResponse(false, 0L, "Character name already taken or invalid"));
      }
    });
  }
}