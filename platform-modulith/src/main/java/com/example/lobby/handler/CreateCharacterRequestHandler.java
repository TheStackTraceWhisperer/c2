package com.example.lobby.handler;

import com.example.common.handler.PacketHandler;
import com.example.common.protocol.JobId;
import com.example.common.protocol.NationId;
import com.example.common.protocol.RaceId;
import com.example.lobby.NationStartingLocation;
import com.example.lobby.dto.CreateCharacterRequest;
import com.example.lobby.dto.CreateCharacterResponse;
import com.example.server.SessionContext;
import com.example.server.auth.TokenClaims;
import com.example.server.auth.TokenVerifier;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.repository.ActiveSessionJdbcRepository;
import com.example.server.repository.CharacterRepository;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Singleton
public class CreateCharacterRequestHandler implements PacketHandler<CreateCharacterRequest> {

  private final TokenVerifier tokenVerifier;
  private final CharacterRepository characterRepository;
  private final ActiveSessionJdbcRepository activeSessionRepository;
  private final VirtualThreadTaskDispatcher dispatcher;

  public CreateCharacterRequestHandler(
    TokenVerifier tokenVerifier,
    CharacterRepository characterRepository,
    VirtualThreadTaskDispatcher dispatcher) {
    this(tokenVerifier, characterRepository, null, dispatcher);
  }

  @Inject
  public CreateCharacterRequestHandler(
    TokenVerifier tokenVerifier,
    CharacterRepository characterRepository,
    ActiveSessionJdbcRepository activeSessionRepository,
    VirtualThreadTaskDispatcher dispatcher) {
    this.tokenVerifier = tokenVerifier;
    this.characterRepository = characterRepository;
    this.activeSessionRepository = activeSessionRepository;
    this.dispatcher = dispatcher;
  }

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

      if (!LobbySessionValidator.isSessionAuthorized(activeSessionRepository, sessionId, accountId)) {
        log.warn("[LOBBY-SERVER] Session mismatch for create character attempt: Session #{} vs accountId={}",
          sessionId, accountId);
        ctx.writeAndFlush(new CreateCharacterResponse(false, 0L, "Session mismatch"));
        return;
      }

      String validationError = validateRequest(packet);
      if (validationError != null) {
        log.warn("[LOBBY-SERVER] Invalid CreateCharacterRequest from Session #{}: {}", sessionId, validationError);
        ctx.writeAndFlush(new CreateCharacterResponse(false, 0L, validationError));
        return;
      }

      String trimmedName = packet.name().trim();
      NationStartingLocation spawn = NationStartingLocation.fromNationId(packet.nationId());

      CharacterRecord newRecord = new CharacterRecord(
        accountId,
        0L,
        packet.worldId(),
        trimmedName,
        packet.raceId(),
        packet.gender(),
        packet.faceId(),
        packet.hairId(),
        packet.sizeId(),
        spawn.nationId,
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
        log.error("[LOBBY-SERVER] Failed to create character '{}': {}", trimmedName, e.getMessage(), e);
        ctx.writeAndFlush(new CreateCharacterResponse(false, 0L, "Character name already taken or invalid"));
      }
    });
  }

  private static String validateRequest(CreateCharacterRequest packet) {
    if (packet.name() == null || packet.name().isBlank()) {
      return "Character name must not be blank";
    }
    String trimmedName = packet.name().trim();
    if (trimmedName.length() < 2 || trimmedName.length() > 32) {
      return "Character name must be between 2 and 32 characters";
    }
    if (packet.worldId() <= 0) {
      return "Invalid worldId";
    }
    if (packet.nationId() < NationId.SAN_D_ORIA || packet.nationId() > NationId.WINDURST) {
      return "Invalid nationId";
    }
    if (packet.raceId() < RaceId.HUME_MALE || packet.raceId() > RaceId.GALKA) {
      return "Invalid raceId";
    }
    if (packet.mainJobId() < JobId.WARRIOR || packet.mainJobId() > JobId.RUNE_FENCER) {
      return "Invalid mainJobId";
    }
    return null;
  }
}