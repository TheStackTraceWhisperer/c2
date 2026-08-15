package com.example.lobby.handler;

import com.example.server.auth.TokenClaims;
import com.example.server.auth.TokenVerifier;
import com.example.server.protocol.SetSessionCharacterControlMessage;
import com.example.common.handler.PacketHandler;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.lobby.dto.SelectCharacterResponse;
import com.example.server.SessionContext;
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

  @Override
  public void handle(ChannelHandlerContext ctx, SelectCharacterRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    log.info("[LOBBY-SERVER] Character selection for Session #{}: characterId={}", sessionId, packet.characterId());

    // 1. Verify stateless JWT
    Optional<TokenClaims> claimsOpt = tokenVerifier.verify(packet.authToken());
    if (claimsOpt.isEmpty()) {
      log.warn("[LOBBY-SERVER] Unauthorized character selection attempt for Session #{}: invalid token", sessionId);
      ctx.writeAndFlush(new SelectCharacterResponse(false, packet.characterId(), "Unauthorized"));
      return;
    }

    TokenClaims claims = claimsOpt.get();
    log.info("[LOBBY-SERVER] Verified accountId={} for character selection", claims.accountId());

    // 2. Validate character logic (e.g. check character belongs to account)
    boolean valid = packet.characterId() == 1001L || packet.characterId() == 1002L;
    String err = valid ? "" : "Character not found";

    if (valid) {
      // Command the Gateway edge to bind characterId to this QuicSession
      ctx.write(new SetSessionCharacterControlMessage(packet.characterId()));
    }

    ctx.writeAndFlush(new SelectCharacterResponse(valid, packet.characterId(), err));
  }
}