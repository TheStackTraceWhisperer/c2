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
import io.micronaut.context.annotation.Value;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Semaphore;

@Slf4j
@Singleton
public class LoginRequestHandler implements PacketHandler<LoginRequest> {

  private static final int MAX_CONCURRENT_ARGON2 = 4;

  private final AccountRepository accountRepository;
  private final ActiveSessionJdbcRepository activeSessionRepository;
  private final Argon2PasswordEncoder passwordEncoder;
  private final JwtTokenIssuer tokenIssuer;
  private final VirtualThreadTaskDispatcher dispatcher;
  private final boolean autoRegister;
  private final Semaphore argon2Semaphore = new Semaphore(MAX_CONCURRENT_ARGON2);

  public LoginRequestHandler(
    AccountRepository accountRepository,
    ActiveSessionJdbcRepository activeSessionRepository,
    Argon2PasswordEncoder passwordEncoder,
    JwtTokenIssuer tokenIssuer,
    VirtualThreadTaskDispatcher dispatcher) {
    this(accountRepository, activeSessionRepository, passwordEncoder, tokenIssuer, dispatcher, true);
  }

  @Inject
  public LoginRequestHandler(
    AccountRepository accountRepository,
    ActiveSessionJdbcRepository activeSessionRepository,
    Argon2PasswordEncoder passwordEncoder,
    JwtTokenIssuer tokenIssuer,
    VirtualThreadTaskDispatcher dispatcher,
    @Value("${app.login.auto-register:true}") boolean autoRegister) {
    this.accountRepository = accountRepository;
    this.activeSessionRepository = activeSessionRepository;
    this.passwordEncoder = passwordEncoder;
    this.tokenIssuer = tokenIssuer;
    this.dispatcher = dispatcher;
    this.autoRegister = autoRegister;
  }

  @Override
  public void handle(ChannelHandlerContext ctx, LoginRequest packet) {
    Long sessionId = ctx.channel().attr(SessionContext.SESSION_ID).get();
    if (sessionId == null || sessionId <= 0L) {
      log.error("[LOGIN-SERVER] Security violation: Missing or invalid SessionContext. Terminating connection.");
      ctx.close();
      return;
    }

    if (packet == null
      || packet.username() == null || packet.username().isBlank() || packet.username().length() > 64
      || packet.password() == null || packet.password().isEmpty() || packet.password().length() > 128) {
      log.warn("[LOGIN-SERVER] Rejected malformed login request from Session #{}", sessionId);
      ctx.writeAndFlush(new LoginResponse(false, 0L, "", "Invalid username or password"));
      return;
    }

    String username = packet.username();
    String password = packet.password();

    dispatcher.dispatch(ctx, () -> {
      log.info("[LOGIN-SERVER] Auth attempt from Session #{}: user='{}'", sessionId, username);

      Optional<AccountRecord> accountOpt = accountRepository.findByUsername(username);
      AccountRecord account;

      if (accountOpt.isEmpty()) {
        if (!autoRegister) {
          log.warn("[LOGIN-SERVER] Auth failed: unknown user='{}' (auto-register disabled)", username);
          ctx.writeAndFlush(new LoginResponse(false, 0L, "", "Invalid credentials"));
          return;
        }
        log.info("[LOGIN-SERVER] Account '{}' does not exist. Auto-registering new account...", username);
        String hash = hashWithLimit(password);
        try {
          account = accountRepository.save(AccountRecord.of(
            username, username + "@ffxi.internal", hash
          ));
        } catch (Exception e) {
          log.warn("[LOGIN-SERVER] Auto-registration failed for user='{}': {}", username, e.getMessage());
          ctx.writeAndFlush(new LoginResponse(false, 0L, "", "Registration failed"));
          return;
        }
      } else {
        account = accountOpt.get();
        if (!"ACTIVE".equalsIgnoreCase(account.status())) {
          log.warn("[LOGIN-SERVER] Auth rejected: account '{}' has status '{}'", username, account.status());
          ctx.writeAndFlush(new LoginResponse(false, 0L, "", "Account is not active"));
          return;
        }
        if (!verifyWithLimit(password, account.passwordHash())) {
          log.warn("[LOGIN-SERVER] Auth failed: password mismatch for user='{}'", username);
          ctx.writeAndFlush(new LoginResponse(false, 0L, "", "Invalid credentials"));
          return;
        }
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

      log.info("[LOGIN-SERVER] Auth success for user='{}' (Account #{}, Session #{})", username, accountId, sessionId);
      ctx.write(new SetSessionAuthStateControlMessage(true, accountId));
      ctx.writeAndFlush(new LoginResponse(true, accountId, token, ""));
    });
  }

  private String hashWithLimit(String rawPassword) {
    acquireArgon2();
    try {
      return passwordEncoder.encode(rawPassword);
    } finally {
      argon2Semaphore.release();
    }
  }

  private boolean verifyWithLimit(String rawPassword, String encodedHash) {
    acquireArgon2();
    try {
      return passwordEncoder.matches(rawPassword, encodedHash);
    } finally {
      argon2Semaphore.release();
    }
  }

  private void acquireArgon2() {
    try {
      argon2Semaphore.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for Argon2 permit", e);
    }
  }
}