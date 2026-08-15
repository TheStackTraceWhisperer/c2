package com.example.lobby.handler;

import com.example.common.backend.protocol.SetSessionCharacterControlMessage;
import com.example.common.handler.PacketHandler;
import com.example.lobby.dto.SelectCharacterRequest;
import com.example.lobby.dto.SelectCharacterResponse;
import com.example.login.state.AuthSessionRegistry;
import com.example.server.SessionContext;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class SelectCharacterRequestHandler implements PacketHandler<SelectCharacterRequest> {

  private final AuthSessionRegistry sessionRegistry;

  @Override
  public void handle(ChannelHandlerContext ctx, SelectCharacterRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    log.info("[LOBBY-SERVER] Character selection for Session #{}: accountId={}, characterId={}",
      sessionId, packet.accountId(), packet.characterId());

    // 1. Verify bearer token
    boolean authorized = sessionRegistry.validateAndGet(packet.accountId(), packet.authToken()).isPresent();
    if (!authorized) {
      log.warn("[LOBBY-SERVER] Unauthorized character selection attempt for Session #{}", sessionId);
      ctx.writeAndFlush(new SelectCharacterResponse(false, packet.characterId(), "Unauthorized"));
      return;
    }

    // 2. Character validation logic (e.g. check character belongs to account)
    boolean valid = packet.characterId() == 1001L || packet.characterId() == 1002L;
    String err = valid ? "" : "Character not found";

    if (valid) {
      sessionRegistry.updateCharacter(packet.accountId(), packet.characterId());
      // Command the Gateway to bind characterId to this QuicSession
      ctx.write(new SetSessionCharacterControlMessage(packet.characterId()));
    }

    ctx.writeAndFlush(new SelectCharacterResponse(valid, packet.characterId(), err));
  }
}