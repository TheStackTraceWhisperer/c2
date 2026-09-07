package com.example.login.handler;

import com.example.common.handler.PacketHandler;
import com.example.login.auth.JwtTokenIssuer;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.server.SessionContext;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.AccountRecord;
import com.example.server.entity.ActiveSessionRecord;
import com.example.server.protocol.SetSessionAuthStateControlMessage;
import com.example.server.repository.AccountRepository;
import com.example.server.repository.ActiveSessionJdbcRepository;
import com.example.server.security.Argon2PasswordEncoder;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class LoginRequestHandler implements PacketHandler<LoginRequest> {

  private final AccountRepository accountRepository;
  private final ActiveSessionJdbcRepository activeSessionRepository;
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

    dispatcher.dispatch(ctx, () -> {
      log.info("[LOGIN-SERVER] Auth attempt from Session #{}: user='{}'", sessionId, packet.username());

      Optional<AccountRecord> accountOpt = accountRepository.findByUsername(packet.username());

      if (accountOpt.isEmpty()) {
        log.info("[LOGIN-SERVER] Account '{}' does not exist. Auto-registering new account...", packet.username());
        String hash = passwordEncoder.encode(packet.password());
        AccountRecord created = accountRepository.save(AccountRecord.of(
          packet.username(), packet.username() + "@ffxi.internal", hash
        ));
        accountOpt = Optional.of(created);
      }

      AccountRecord account = accountOpt.get();
      if (!passwordEncoder.matches(packet.password(), account.passwordHash())) {
        log.warn("[LOGIN-SERVER] Auth failed: password mismatch for user='{}'", packet.username());
        ctx.writeAndFlush(new LoginResponse(false, 0L, "", "Invalid credentials"));
        return;
      }

      long accountId = account.id();

      // Enforce Single-Session Concurrency per Account
      Optional<ActiveSessionRecord> existingSession = activeSessionRepository.findByAccountId(accountId);
      if (existingSession.isPresent() && existingSession.get().sessionId() != sessionId) {
        log.warn("[LOGIN-SERVER] Duplicate login rejected: Account #{} is already active on Session #{}",
          accountId, existingSession.get().sessionId());
        ctx.writeAndFlush(new LoginResponse(false, 0L, "", "Account is already logged in"));
        return;
      }

      // Register session concurrency lock
      activeSessionRepository.upsert(
        accountId,
        sessionId,
        1, // Gateway node ID
        null,
        Instant.now(),
        Instant.now()
      );

      String token = tokenIssuer.issueToken(accountId, account.username(), 15);

      log.info("[LOGIN-SERVER] Auth success for user='{}' (Account #{}, Session #{})", packet.username(), accountId, sessionId);
      ctx.write(new SetSessionAuthStateControlMessage(true, accountId));
      ctx.writeAndFlush(new LoginResponse(true, accountId, token, ""));
    });
  }
}