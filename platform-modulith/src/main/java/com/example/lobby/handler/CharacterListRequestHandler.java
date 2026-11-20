package com.example.lobby.handler;

import com.example.common.handler.PacketHandler;
import com.example.lobby.dto.CharacterListRequest;
import com.example.lobby.dto.CharacterListResponse;
import com.example.lobby.dto.CharacterSummary;
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

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Singleton
public class CharacterListRequestHandler implements PacketHandler<CharacterListRequest> {

  private final TokenVerifier tokenVerifier;
  private final CharacterRepository characterRepository;
  private final ActiveSessionJdbcRepository activeSessionRepository;
  private final VirtualThreadTaskDispatcher dispatcher;

  public CharacterListRequestHandler(
    TokenVerifier tokenVerifier,
    CharacterRepository characterRepository,
    VirtualThreadTaskDispatcher dispatcher) {
    this(tokenVerifier, characterRepository, null, dispatcher);
  }

  @Inject
  public CharacterListRequestHandler(
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
  public void handle(ChannelHandlerContext ctx, CharacterListRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    if (sessionId == null || sessionId <= 0L) {
      log.error("[LOBBY-SERVER] Security violation: Missing or invalid SessionContext. Terminating connection.");
      ctx.close();
      return;
    }

    dispatcher.dispatch(ctx, () -> {
      log.info("[LOBBY-SERVER] Fetching character list for Session #{}", sessionId);

      Optional<TokenClaims> claimsOpt = tokenVerifier.verify(packet.authToken());
      if (claimsOpt.isEmpty()) {
        log.warn("[LOBBY-SERVER] Unauthorized character list query from Session #{}: invalid or expired token", sessionId);
        ctx.writeAndFlush(new CharacterListResponse(0L, Collections.emptyList()));
        return;
      }

      TokenClaims claims = claimsOpt.get();
      if (!LobbySessionValidator.isSessionAuthorized(activeSessionRepository, sessionId, claims.accountId())) {
        log.warn("[LOBBY-SERVER] Session mismatch for character list query: Session #{} vs accountId={}",
          sessionId, claims.accountId());
        ctx.writeAndFlush(new CharacterListResponse(0L, Collections.emptyList()));
        return;
      }

      log.info("[LOBBY-SERVER] Verified accountId={} for character list query", claims.accountId());

      List<CharacterRecord> records = characterRepository.findByAccountId(claims.accountId());
      List<CharacterSummary> summaries = records.stream()
        .map(r -> new CharacterSummary(
          r.characterId(),
          r.name(),
          r.worldId(),
          r.raceId(),
          r.gender(),
          r.zoneId(),
          (int) r.mainJobLevel(),
          r.nationId(),
          r.mainJobId(),
          r.subJobId(),
          r.faceId(),
          r.hairId(),
          r.sizeId()
        ))
        .toList();

      ctx.writeAndFlush(new CharacterListResponse(claims.accountId(), summaries));
    });
  }
}