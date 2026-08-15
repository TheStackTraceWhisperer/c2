package com.example.lobby.handler;

import com.example.server.auth.TokenClaims;
import com.example.server.auth.TokenVerifier;
import com.example.common.handler.PacketHandler;
import com.example.lobby.dto.CharacterListRequest;
import com.example.lobby.dto.CharacterListResponse;
import com.example.lobby.dto.CharacterSummary;
import com.example.server.SessionContext;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class CharacterListRequestHandler implements PacketHandler<CharacterListRequest> {

  private final TokenVerifier tokenVerifier;

  @Override
  public void handle(ChannelHandlerContext ctx, CharacterListRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    log.info("[LOBBY-SERVER] Fetching character list for Session #{}", sessionId);

    // 1. Verify stateless JWT
    Optional<TokenClaims> claimsOpt = tokenVerifier.verify(packet.authToken());
    if (claimsOpt.isEmpty()) {
      log.warn("[LOBBY-SERVER] Unauthorized character list query from Session #{}: invalid or expired token", sessionId);
      ctx.writeAndFlush(new CharacterListResponse(0L, Collections.emptyList()));
      return;
    }

    TokenClaims claims = claimsOpt.get();
    log.info("[LOBBY-SERVER] Verified accountId={} for character list query", claims.accountId());

    // 2. Fetch characters for the verified account
    List<CharacterSummary> sampleCharacters = List.of(
      new CharacterSummary(1001L, "Warrior", 45, "WARRIOR"),
      new CharacterSummary(1002L, "Mage", 50, "WIZARD")
    );

    ctx.writeAndFlush(new CharacterListResponse(claims.accountId(), sampleCharacters));
  }
}