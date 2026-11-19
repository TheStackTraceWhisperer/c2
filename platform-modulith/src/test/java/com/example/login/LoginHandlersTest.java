package com.example.login;

import com.example.common.dto.PingMessage;
import com.example.common.dto.PongMessage;
import com.example.login.auth.JwtTokenIssuer;
import com.example.login.dto.LoginRequest;
import com.example.login.dto.LoginResponse;
import com.example.login.dto.ReconnectRequest;
import com.example.login.dto.ReconnectResponse;
import com.example.login.handler.LoginRequestHandler;
import com.example.login.handler.PingPacketHandler;
import com.example.login.handler.ReconnectRequestHandler;
import com.example.server.SessionContext;
import com.example.server.auth.JwtTokenVerifier;
import com.example.server.auth.RsaKeyProvider;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.AccountRecord;
import com.example.server.entity.ActiveSessionRecord;
import com.example.server.protocol.SetSessionAuthStateControlMessage;
import com.example.server.protocol.SetSessionCharacterControlMessage;
import com.example.server.repository.AccountRepository;
import com.example.server.repository.ActiveSessionJdbcRepository;
import com.example.server.security.Argon2PasswordEncoder;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LoginHandlersTest {

  static class SyncDispatcher extends VirtualThreadTaskDispatcher {
    @Override
    public void dispatch(io.netty.channel.ChannelHandlerContext ctx, Runnable task) {
      task.run();
    }
  }

  static class InMemoryAccountRepo extends AccountRepository {
    final Map<String, AccountRecord> byName = new HashMap<>();
    final AtomicLong idSeq = new AtomicLong(1);

    InMemoryAccountRepo() {
      super(null);
    }

    @Override
    public Optional<AccountRecord> findByUsername(String username) {
      return Optional.ofNullable(byName.get(username));
    }

    @Override
    public AccountRecord save(AccountRecord record) {
      long id = record.id() != null ? record.id() : idSeq.getAndIncrement();
      AccountRecord saved = new AccountRecord(
        id, record.username(), record.email(), record.passwordHash(), record.status(), record.createdAt(), record.updatedAt()
      );
      byName.put(saved.username(), saved);
      return saved;
    }
  }

  private static ActiveSessionJdbcRepository inMemorySessions(Map<Long, ActiveSessionRecord> store) {
    return (ActiveSessionJdbcRepository) Proxy.newProxyInstance(
      ActiveSessionJdbcRepository.class.getClassLoader(),
      new Class<?>[]{ActiveSessionJdbcRepository.class},
      (proxy, method, args) -> {
        switch (method.getName()) {
          case "findByAccountId" -> {
            return Optional.ofNullable(store.get((Long) args[0]));
          }
          case "findBySessionId" -> {
            long sid = (Long) args[0];
            return store.values().stream().filter(r -> r.sessionId() == sid).findFirst();
          }
          case "upsert" -> {
            long accountId = (Long) args[0];
            store.put(accountId, new ActiveSessionRecord(
              accountId, (Long) args[1], (Integer) args[2], (Long) args[3], (Instant) args[4], (Instant) args[5]
            ));
            return null;
          }
          default -> {
            return null;
          }
        }
      }
    );
  }

  @Test
  @DisplayName("LoginRequestHandler validates inputs, checks ACTIVE status, gates auto-register, and skips redundant verify")
  void testLoginRequestHandlerHardening() {
    InMemoryAccountRepo accounts = new InMemoryAccountRepo();
    Map<Long, ActiveSessionRecord> sessions = new HashMap<>();
    ActiveSessionJdbcRepository sessionRepo = inMemorySessions(sessions);
    AtomicInteger matchCalls = new AtomicInteger(0);
    Argon2PasswordEncoder encoder = new Argon2PasswordEncoder() {
      @Override
      public boolean matches(String raw, String enc) {
        matchCalls.incrementAndGet();
        return super.matches(raw, enc);
      }
    };
    RsaKeyProvider keys = new RsaKeyProvider(null, null, null, null);
    JwtTokenIssuer issuer = new JwtTokenIssuer(keys);

    LoginRequestHandler autoRegDisabled = new LoginRequestHandler(
      accounts, sessionRepo, encoder, issuer, new SyncDispatcher(), false
    );
    LoginRequestHandler autoRegEnabled = new LoginRequestHandler(
      accounts, sessionRepo, encoder, issuer, new SyncDispatcher(), true
    );

    EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    ch.attr(SessionContext.SESSION_ID).set(500L);
    try {
      // Blank username rejected
      autoRegEnabled.handle(ch.pipeline().firstContext(), new LoginRequest("  ", "pw"));
      LoginResponse blankResp = ch.readOutbound();
      assertThat(blankResp.success()).isFalse();

      // Oversized password (>128) rejected
      autoRegEnabled.handle(ch.pipeline().firstContext(), new LoginRequest("alice", "x".repeat(129)));
      LoginResponse longPwResp = ch.readOutbound();
      assertThat(longPwResp.success()).isFalse();

      // Unknown user rejected when auto-register=false
      autoRegDisabled.handle(ch.pipeline().firstContext(), new LoginRequest("newuser", "pw123"));
      LoginResponse disabledRegResp = ch.readOutbound();
      assertThat(disabledRegResp.success()).isFalse();

      // Auto-register creates account and skips redundant matches()
      autoRegEnabled.handle(ch.pipeline().firstContext(), new LoginRequest("newuser", "pw123"));
      assertThat((Object) ch.readOutbound()).isInstanceOf(SetSessionAuthStateControlMessage.class);
      LoginResponse regResp = ch.readOutbound();
      assertThat(regResp.success()).isTrue();
      assertThat(regResp.token()).isNotBlank();
      assertThat(matchCalls.get()).isZero();

      // Suspended account rejected even with matching password
      accounts.byName.put("banned", new AccountRecord(
        99L, "banned", "b@x", encoder.encode("pw123"), "SUSPENDED", Instant.now(), Instant.now()
      ));
      autoRegEnabled.handle(ch.pipeline().firstContext(), new LoginRequest("banned", "pw123"));
      LoginResponse bannedResp = ch.readOutbound();
      assertThat(bannedResp.success()).isFalse();
      assertThat(bannedResp.errorMessage()).contains("not active");
    } finally {
      ch.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("ReconnectRequestHandler rejects different live session and restores characterId on same session")
  void testReconnectRequestHandlerSessionAndCharacterRestoration() {
    RsaKeyProvider keys = new RsaKeyProvider(null, null, null, null);
    JwtTokenIssuer issuer = new JwtTokenIssuer(keys);
    JwtTokenVerifier verifier = new JwtTokenVerifier(keys);
    Map<Long, ActiveSessionRecord> sessions = new HashMap<>();
    ActiveSessionJdbcRepository sessionRepo = inMemorySessions(sessions);

    ReconnectRequestHandler handler = new ReconnectRequestHandler(verifier, sessionRepo, new SyncDispatcher());
    String token = issuer.issueToken(10L, "hero", 15);

    // Existing live session on Session #100 with selected character 1001
    sessions.put(10L, new ActiveSessionRecord(10L, 100L, 1, 1001L, Instant.now(), Instant.now()));

    EmbeddedChannel otherCh = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    otherCh.attr(SessionContext.SESSION_ID).set(200L);
    try {
      handler.handle(otherCh.pipeline().firstContext(), new ReconnectRequest(token));
      ReconnectResponse rejected = otherCh.readOutbound();
      assertThat(rejected.success()).isFalse();
    } finally {
      otherCh.finishAndReleaseAll();
    }

    // Reconnecting on same Session #100 preserves characterId=1001 and emits SetSessionCharacterControlMessage
    EmbeddedChannel sameCh = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    sameCh.attr(SessionContext.SESSION_ID).set(100L);
    try {
      handler.handle(sameCh.pipeline().firstContext(), new ReconnectRequest(token));
      assertThat((Object) sameCh.readOutbound()).isInstanceOf(SetSessionAuthStateControlMessage.class);
      SetSessionCharacterControlMessage charCtrl = sameCh.readOutbound();
      assertThat(charCtrl.characterId()).isEqualTo(1001L);
      ReconnectResponse ok = sameCh.readOutbound();
      assertThat(ok.success()).isTrue();
      assertThat(ok.characterId()).isEqualTo(1001L);
    } finally {
      sameCh.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("PingPacketHandler responds immediately when active and skips when inactive")
  void testPingPacketHandlerImmediateAndActiveCheck() {
    PingPacketHandler handler = new PingPacketHandler();
    EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    ch.attr(SessionContext.SESSION_ID).set(1L);
    io.netty.channel.ChannelHandlerContext ctx = ch.pipeline().firstContext();
    try {
      handler.handle(ctx, new PingMessage(42L, "ping"));
      PongMessage pong = ch.readOutbound();
      assertThat(pong).isNotNull();
      assertThat(pong.timestamp()).isEqualTo(42L);
      assertThat(pong.message()).isEqualTo("PONG");

      ch.close().syncUninterruptibly();
      handler.handle(ctx, new PingMessage(43L, "ping"));
      assertThat((Object) ch.readOutbound()).isNull();
    } finally {
      ch.finishAndReleaseAll();
    }
  }
}
