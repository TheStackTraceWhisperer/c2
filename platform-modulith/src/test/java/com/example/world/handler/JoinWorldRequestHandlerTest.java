package com.example.world.handler;

import com.example.config.WorldServerProperties;
import com.example.server.SessionContext;
import com.example.server.auth.TokenClaims;
import com.example.server.auth.TokenVerifier;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.ZoneInstanceRecord;
import com.example.server.protocol.SetSessionZoneControlMessage;
import com.example.server.repository.CharacterRepository;
import com.example.server.repository.ZoneInstanceJdbcRepository;
import com.example.world.dto.JoinWorldRequest;
import com.example.world.dto.JoinWorldResponse;
import com.example.world.registry.WorldSessionRegistry;
import com.example.world.registry.ZoneRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class JoinWorldRequestHandlerTest {

  private WorldServerProperties properties;
  private WorldSessionRegistry sessionRegistry;
  private ZoneRegistry zoneRegistry;
  private VirtualThreadTaskDispatcher syncDispatcher;

  @BeforeEach
  void setUp() {
    properties = new WorldServerProperties();
    properties.setHost("127.0.0.1");
    properties.setPort(9001);
    properties.setRealmId(1);

    sessionRegistry = new WorldSessionRegistry();

    ZoneInstanceJdbcRepository instanceRepo = (ZoneInstanceJdbcRepository) Proxy.newProxyInstance(
      ZoneInstanceJdbcRepository.class.getClassLoader(),
      new Class<?>[]{ZoneInstanceJdbcRepository.class},
      (proxy, method, args) -> {
        if ("findByWorldIdAndZoneIdAndIsStaticTrue".equals(method.getName())
            && Integer.valueOf(1).equals(args[0])
            && Integer.valueOf(230).equals(args[1])) {
          return Optional.of(new ZoneInstanceRecord(1, 1, 230, "DEFAULT", "10.0.0.230", 9230, "ONLINE", true, Instant.now()));
        }
        return Optional.empty();
      }
    );
    zoneRegistry = new ZoneRegistry(instanceRepo);

    syncDispatcher = new VirtualThreadTaskDispatcher() {
      @Override
      public void dispatch(ChannelHandlerContext ctx, Runnable task) {
        task.run();
      }
    };
  }

  @Test
  @DisplayName("Rejects invalid or expired JWT authToken")
  void testRejectsInvalidToken() {
    CharacterRepository charRepo = charRepoReturning(character(1001L, 42L, 1, 230));
    TokenVerifier rejectAll = token -> Optional.empty();

    JoinWorldRequestHandler handler = new JoinWorldRequestHandler(
      charRepo, zoneRegistry, sessionRegistry, syncDispatcher, properties, rejectAll
    );

    EmbeddedChannel ch = new EmbeddedChannel();
    ch.attr(SessionContext.SESSION_ID).set(10L);
    ch.attr(SessionContext.CHARACTER_ID).set(1001L);
    List<Object> out = new CopyOnWriteArrayList<>();
    ChannelHandlerContext ctx = proxyCtx(ch, out);

    try {
      handler.handle(ctx, new JoinWorldRequest("bad-jwt"));

      assertThat(out).hasSize(1);
      JoinWorldResponse resp = (JoinWorldResponse) out.get(0);
      assertThat(resp.success()).isFalse();
      assertThat(resp.errorMessage()).isEqualTo("Unauthorized");
      assertThat(sessionRegistry.getSession(10L)).isNull();
    } finally {
      ch.close();
    }
  }

  @Test
  @DisplayName("Rejects character belonging to a different world/realm than WorldServerProperties.realmId")
  void testRejectsRealmMismatch() {
    CharacterRepository charRepo = charRepoReturning(character(1003L, 42L, 2, 234));
    TokenVerifier validVerifier = token -> Optional.of(new TokenClaims(42L, "admin", Instant.now().getEpochSecond() + 300));

    JoinWorldRequestHandler handler = new JoinWorldRequestHandler(
      charRepo, zoneRegistry, sessionRegistry, syncDispatcher, properties, validVerifier
    );

    EmbeddedChannel ch = new EmbeddedChannel();
    ch.attr(SessionContext.SESSION_ID).set(11L);
    ch.attr(SessionContext.CHARACTER_ID).set(1003L);
    List<Object> out = new CopyOnWriteArrayList<>();
    ChannelHandlerContext ctx = proxyCtx(ch, out);

    try {
      handler.handle(ctx, new JoinWorldRequest("valid-jwt"));

      assertThat(out).hasSize(1);
      JoinWorldResponse resp = (JoinWorldResponse) out.get(0);
      assertThat(resp.success()).isFalse();
      assertThat(resp.errorMessage()).contains("different world");
      assertThat(sessionRegistry.getSession(11L)).isNull();
    } finally {
      ch.close();
    }
  }

  @Test
  @DisplayName("Rejects character owned by a different account than JWT subject")
  void testRejectsAccountMismatch() {
    CharacterRepository charRepo = charRepoReturning(character(1001L, 99L, 1, 230));
    TokenVerifier validVerifier = token -> Optional.of(new TokenClaims(42L, "admin", Instant.now().getEpochSecond() + 300));

    JoinWorldRequestHandler handler = new JoinWorldRequestHandler(
      charRepo, zoneRegistry, sessionRegistry, syncDispatcher, properties, validVerifier
    );

    EmbeddedChannel ch = new EmbeddedChannel();
    ch.attr(SessionContext.SESSION_ID).set(12L);
    ch.attr(SessionContext.CHARACTER_ID).set(1001L);
    List<Object> out = new CopyOnWriteArrayList<>();
    ChannelHandlerContext ctx = proxyCtx(ch, out);

    try {
      handler.handle(ctx, new JoinWorldRequest("valid-jwt"));

      assertThat(out).hasSize(1);
      JoinWorldResponse resp = (JoinWorldResponse) out.get(0);
      assertThat(resp.success()).isFalse();
      assertThat(resp.errorMessage()).contains("access denied");
      assertThat(sessionRegistry.getSession(12L)).isNull();
    } finally {
      ch.close();
    }
  }

  @Test
  @DisplayName("Valid JWT + matching realm + matching account registers session and emits SetSessionZoneControlMessage + JoinWorldResponse")
  void testValidJoinWorldSucceeds() {
    CharacterRepository charRepo = charRepoReturning(character(1001L, 42L, 1, 230));
    TokenVerifier validVerifier = token -> Optional.of(new TokenClaims(42L, "admin", Instant.now().getEpochSecond() + 300));

    JoinWorldRequestHandler handler = new JoinWorldRequestHandler(
      charRepo, zoneRegistry, sessionRegistry, syncDispatcher, properties, validVerifier
    );

    EmbeddedChannel ch = new EmbeddedChannel();
    ch.attr(SessionContext.SESSION_ID).set(13L);
    ch.attr(SessionContext.CHARACTER_ID).set(1001L);
    List<Object> out = new CopyOnWriteArrayList<>();
    ChannelHandlerContext ctx = proxyCtx(ch, out);

    try {
      handler.handle(ctx, new JoinWorldRequest("valid-jwt"));

      assertThat(out).hasSize(2);
      assertThat(out.get(0)).isInstanceOf(SetSessionZoneControlMessage.class);
      SetSessionZoneControlMessage ctrl = (SetSessionZoneControlMessage) out.get(0);
      assertThat(ctrl.zoneId()).isEqualTo(230);
      assertThat(ctrl.host()).isEqualTo("10.0.0.230");
      assertThat(ctrl.port()).isEqualTo(9230);

      assertThat(out.get(1)).isInstanceOf(JoinWorldResponse.class);
      JoinWorldResponse resp = (JoinWorldResponse) out.get(1);
      assertThat(resp.success()).isTrue();
      assertThat(resp.initialZoneId()).isEqualTo(230);
      assertThat(resp.zoneHost()).isEqualTo("10.0.0.230");
      assertThat(resp.zonePort()).isEqualTo(9230);

      assertThat(sessionRegistry.getSession(13L)).isNotNull();
      assertThat(sessionRegistry.getSession(13L).getCurrentZoneId()).isEqualTo(230);
    } finally {
      ch.close();
    }
  }

  private static CharacterRepository charRepoReturning(CharacterRecord record) {
    return new CharacterRepository(null) {
      @Override
      public Optional<CharacterRecord> findById(long characterId) {
        return record != null && record.characterId() == characterId ? Optional.of(record) : Optional.empty();
      }
    };
  }

  private static CharacterRecord character(long charId, long accountId, int worldId, int zoneId) {
    return new CharacterRecord(
      accountId, charId, worldId, "Hero", (short) 1, (short) 0, (short) 1,
      (short) 1, (short) 1, (short) 1, (short) 1, (short) 1, (short) 0, (short) 0,
      zoneId, 10f, 0f, 10f, 0f, 0f,
      zoneId, 10f, 0f, 10f, Instant.now()
    );
  }

  private static ChannelHandlerContext proxyCtx(EmbeddedChannel channel, List<Object> outbound) {
    return (ChannelHandlerContext) Proxy.newProxyInstance(
      ChannelHandlerContext.class.getClassLoader(),
      new Class<?>[]{ChannelHandlerContext.class},
      (proxy, method, args) -> {
        if ("writeAndFlush".equals(method.getName()) || "write".equals(method.getName())) {
          outbound.add(args[0]);
          return null;
        }
        if ("channel".equals(method.getName())) {
          return channel;
        }
        return null;
      }
    );
  }
}
