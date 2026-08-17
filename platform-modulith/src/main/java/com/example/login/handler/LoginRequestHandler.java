package com.example.login.handler;

import com.example.common.handler.PacketHandler;
import com.example.login.auth.JwtTokenIssuer;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.server.SessionContext;
import com.example.server.protocol.SetSessionAuthStateControlMessage;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class LoginRequestHandler implements PacketHandler<LoginRequest> {

  private final JwtTokenIssuer tokenIssuer;

  @Override
  public void handle(ChannelHandlerContext ctx, LoginRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    if (sessionId == null || sessionId <= 0L) {
      log.error("[LOGIN-SERVER] Security violation: Missing or invalid SessionContext. Terminating connection.");
      ctx.close();
      return;
    }

    log.info("[LOGIN-SERVER] Auth attempt from Session #{}: user='{}'", sessionId, packet.username());

    boolean success = "admin".equals(packet.username()) && "secret123".equals(packet.password());
    long accountId = success ? 92668751L : 0L;
    String token = success ? tokenIssuer.issueToken(accountId, packet.username(), 15) : "";
    String error = success ? "" : "Invalid credentials";

    if (success) {
      ctx.write(new SetSessionAuthStateControlMessage(true, accountId));
    }

    ctx.writeAndFlush(new LoginResponse(success, accountId, token, error));
    log.info("[LOGIN-SERVER] LoginResponse sent to Session #{}", sessionId);
  }
}