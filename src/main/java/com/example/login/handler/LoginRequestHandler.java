package com.example.login.handler;

import com.example.common.backend.protocol.SetSessionAuthStateControlMessage;
import com.example.common.handler.PacketHandler;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.server.SessionContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
@Singleton
public class LoginRequestHandler implements PacketHandler<LoginRequest> {

  @Override
  public void handle(ChannelHandlerContext ctx, LoginRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    log.info("[LOGIN-SERVER] Auth attempt from Session #{}: user='{}'", sessionId, packet.username());

    boolean authSuccess = "admin".equalsIgnoreCase(packet.username()) || "player1".equalsIgnoreCase(packet.username());
    long accountId = authSuccess ? Math.abs(packet.username().hashCode()) : 0L;
    String token = authSuccess ? UUID.randomUUID().toString() : "";
    String err = authSuccess ? "" : "Invalid credentials";

    // 1. Send control packet to Gateway to mark authenticated
    if (authSuccess) {
      ctx.write(new SetSessionAuthStateControlMessage(true, accountId));
    }

    // 2. Send public response for client
    log.info("[LOGIN-SERVER] Auth result for Session #{}: success={}, accountId={}", sessionId, authSuccess, accountId);
    ctx.writeAndFlush(new LoginResponse(authSuccess, accountId, token, err))
      .addListener((ChannelFutureListener) future -> {
        if (future.isSuccess()) {
          log.info("[LOGIN-SERVER] LoginResponse sent to Session #{}", sessionId);
        } else {
          log.error("[LOGIN-SERVER] Failed to send LoginResponse to Session #{}: {}",
            sessionId, future.cause().getMessage(), future.cause());
        }
      });
  }
}