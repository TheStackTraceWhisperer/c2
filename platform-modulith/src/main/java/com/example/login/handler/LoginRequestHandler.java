package com.example.login.handler;

import com.example.common.handler.PacketHandler;
import com.example.login.auth.JwtTokenIssuer;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.server.SessionContext;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.AccountRecord;
import com.example.server.protocol.SetSessionAuthStateControlMessage;
import com.example.server.repository.AccountRepository;
import com.example.server.security.Argon2PasswordEncoder;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class LoginRequestHandler implements PacketHandler<LoginRequest> {

  private final AccountRepository accountRepository;
  private final Argon2PasswordEncoder passwordEncoder;
  private final JwtTokenIssuer tokenIssuer;
  private final VirtualThreadTaskDispatcher dispatcher;

  @Override
  public void handle(ChannelHandlerContext ctx, LoginRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    if (sessionId == null || sessionId <= 0L) {
      log.error("[LOGIN-SERVER] Security violation: Missing or invalid SessionContext. Terminating connection.");
      ctx.close();
      return;
    }

    // Instantly offload DB lookup & Argon2 hashing from the Netty EventLoop
    dispatcher.dispatch(ctx, () -> {
      log.info("[LOGIN-SERVER] Auth attempt from Session #{}: user='{}'", sessionId, packet.username());

      Optional<AccountRecord> accountOpt = accountRepository.findByUsername(packet.username());

      boolean success = false;
      long accountId = 0L;
      String token = "";
      String error = "Invalid credentials";

      if (accountOpt.isPresent()) {
        AccountRecord account = accountOpt.get();
        if (passwordEncoder.matches(packet.password(), account.passwordHash())) {
          success = true;
          accountId = account.id();
          token = tokenIssuer.issueToken(accountId, account.username(), 15);
          error = "";
        }
      }

      if (success) {
        log.info("[LOGIN-SERVER] Auth success for user='{}' (Account #{}, Session #{})", packet.username(), accountId, sessionId);
        ctx.write(new SetSessionAuthStateControlMessage(true, accountId));
      } else {
        log.warn("[LOGIN-SERVER] Auth failed for user='{}' (Session #{})", packet.username(), sessionId);
      }

      ctx.writeAndFlush(new LoginResponse(success, accountId, token, error));
      log.info("[LOGIN-SERVER] LoginResponse sent to Session #{}", sessionId);
    });
  }
}