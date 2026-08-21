package com.example.lobby.handler;

import com.example.common.handler.PacketHandler;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.lobby.dto.SelectCharacterResponse;
import com.example.server.SessionContext;
import com.example.server.auth.TokenClaims;
import com.example.server.auth.TokenVerifier;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.RealmRecord;
import com.example.server.protocol.SetSessionCharacterControlMessage;
import com.example.server.protocol.SetSessionWorldControlMessage;
import com.example.server.repository.CharacterRepository;
import com.example.server.repository.RealmJdbcRepository;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class SelectCharacterRequestHandler implements PacketHandler<SelectCharacterRequest> {

  private final TokenVerifier tokenVerifier;
  private final CharacterRepository characterRepository;
  private final RealmJdbcRepository realmRepository;
  private final VirtualThreadTaskDispatcher dispatcher;

  @Override
  public void handle(ChannelHandlerContext ctx, SelectCharacterRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    if (sessionId == null || sessionId <= 0L) {
      log.error("[LOBBY-SERVER] Security violation: Missing or invalid SessionContext. Terminating connection.");
      ctx.close();
      return;
    }

    dispatcher.dispatch(ctx, () -> {
      log.info("[LOBBY-SERVER] Character selection for Session #{}: characterId={}", sessionId, packet.characterId());

      Optional<TokenClaims> claimsOpt = tokenVerifier.verify(packet.authToken());
      if (claimsOpt.isEmpty()) {
        log.warn("[LOBBY-SERVER] Unauthorized character selection attempt for Session #{}: invalid token", sessionId);
        ctx.writeAndFlush(new SelectCharacterResponse(false, packet.characterId(), "Unauthorized"));
        return;
      }

      TokenClaims claims = claimsOpt.get();
      Optional<CharacterRecord> characterOpt = characterRepository.findByAccountAndCharacterId(claims.accountId(), packet.characterId());
      if (characterOpt.isEmpty()) {
        log.warn("[LOBBY-SERVER] Character #{} not found for accountId={}", packet.characterId(), claims.accountId());
        ctx.writeAndFlush(new SelectCharacterResponse(false, packet.characterId(), "Character not found"));
        return;
      }

      CharacterRecord character = characterOpt.get();
      int realmId = character.worldId();
      Optional<RealmRecord> realmOpt = realmRepository.findById(realmId);

      if (realmOpt.isEmpty() || realmOpt.get().host() == null || realmOpt.get().host().isBlank()) {
        log.error("[LOBBY-SERVER] Target Realm #{} for character #{} is not yet online or registered in DB", realmId, character.characterId());
        ctx.writeAndFlush(new SelectCharacterResponse(false, packet.characterId(), "Target realm is unavailable"));
        return;
      }

      RealmRecord realm = realmOpt.get();
      ctx.write(new SetSessionCharacterControlMessage(character.characterId()));

      log.info("[LOBBY-SERVER] Binding Session #{} to Realm #{} ({}:{}) from topology database",
        sessionId, realm.id(), realm.host(), realm.port());

      ctx.write(new SetSessionWorldControlMessage(realm.id(), realm.host(), realm.port()));
      ctx.writeAndFlush(new SelectCharacterResponse(true, character.characterId(), ""));
    });
  }
}