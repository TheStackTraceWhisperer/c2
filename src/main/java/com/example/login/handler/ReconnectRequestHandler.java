package com.example.login.handler;

import com.example.common.auth.TokenClaims;
import com.example.common.auth.TokenVerifier;
import com.example.common.backend.protocol.SetSessionAuthStateControlMessage;
import com.example.common.handler.PacketHandler;
import com.example.login.dto.ReconnectRequest;
import com.example.login.dto.ReconnectResponse;
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

  private final TokenVerifier tokenVerifier;

  @Override
  public void handle(ChannelHandlerContext ctx, ReconnectRequest packet) {
    Long newSessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    log.info("[LOGIN-SERVER] Reconnection attempt from Session #{}", newSessionId);

    Optional<TokenClaims> claimsOpt = tokenVerifier.verify(packet.authToken());
    if (claimsOpt.isEmpty()) {
      log.warn("[LOGIN-SERVER] Reconnection rejected for Session #{}: invalid or expired JWT", newSessionId);
      ctx.writeAndFlush(new ReconnectResponse(false, 0L, 0L, "Invalid or expired session token"));
      return;
    }

    TokenClaims claims = claimsOpt.get();
    log.info("[LOGIN-SERVER] Reconnection approved for Session #{}: accountId={}", newSessionId, claims.accountId());

    // Send Gateway Control Packet to grant Authenticated status
    ctx.write(new SetSessionAuthStateControlMessage(true, claims.accountId()));

    // Respond to client
    ctx.writeAndFlush(new ReconnectResponse(true, claims.accountId(), 0L, ""));
  }
}