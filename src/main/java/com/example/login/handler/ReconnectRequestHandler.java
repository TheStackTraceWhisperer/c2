package com.example.login.handler;

import com.example.common.backend.protocol.SetSessionAuthStateControlMessage;
import com.example.common.backend.protocol.SetSessionCharacterControlMessage;
import com.example.common.handler.PacketHandler;
import com.example.login.dto.ReconnectRequest;
import com.example.login.dto.ReconnectResponse;
import com.example.login.state.AuthSessionRegistry;
import com.example.server.SessionContext;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ReconnectRequestHandler implements PacketHandler<ReconnectRequest> {

  private final AuthSessionRegistry sessionRegistry;

  @Override
  public void handle(ChannelHandlerContext ctx, ReconnectRequest packet) {
    Long newSessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    log.info("[LOGIN-SERVER] Reconnection attempt from Session #{}: accountId={}", newSessionId, packet.accountId());

    Optional<AuthSessionRegistry.UserSession> userSessionOpt = sessionRegistry.validateAndGet(
      packet.accountId(),
      packet.authToken()
    );

    if (userSessionOpt.isEmpty()) {
      log.warn("[LOGIN-SERVER] Reconnection rejected for Session #{}: invalid account or token", newSessionId);
      ctx.writeAndFlush(new ReconnectResponse(false, packet.accountId(), 0L, "Invalid or expired session token"));
      return;
    }

    AuthSessionRegistry.UserSession userSession = userSessionOpt.get();
    log.info("[LOGIN-SERVER] Reconnection approved for Session #{}: accountId={}, characterId={}",
      newSessionId, userSession.accountId(), userSession.characterId());

    // 1. Send Gateway Control Packet to grant Authenticated status
    ctx.write(new SetSessionAuthStateControlMessage(true, userSession.accountId()));

    // 2. If character was already selected prior to disconnect, grant World access
    if (userSession.characterId() > 0L) {
      ctx.write(new SetSessionCharacterControlMessage(userSession.characterId()));
    }

    // 3. Send public ReconnectResponse to Client
    ctx.writeAndFlush(new ReconnectResponse(true, userSession.accountId(), userSession.characterId(), ""));
  }
}