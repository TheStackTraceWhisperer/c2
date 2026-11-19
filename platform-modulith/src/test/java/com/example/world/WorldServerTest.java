package com.example.world;

import com.example.common.handler.IndexedPacketRouter;
import com.example.config.WorldServerProperties;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.ZoneInstanceRecord;
import com.example.server.entity.ZoneLineRecord;
import com.example.server.handler.SessionDisconnectedHandler;
import com.example.server.protocol.EndpointTarget;
import com.example.server.protocol.SessionDisconnectedEvent;
import com.example.server.protocol.SetSessionZoneControlMessage;
import com.example.server.repository.ActiveSessionJdbcRepository;
import com.example.server.repository.CharacterRepository;
import com.example.server.repository.ZoneInstanceJdbcRepository;
import com.example.server.repository.ZoneLineJdbcRepository;
import com.example.world.dto.ZoneTransferNotification;
import com.example.world.handler.JoinWorldRequestHandler;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class WorldServerTest {

  private WorldServerProperties properties;
  private ZoneRegistry zoneRegistry;
  private WorldSessionRegistry sessionRegistry;
  private VirtualThreadTaskDispatcher syncDispatcher;
  private SessionDisconnectedHandler disconnectHandler;

  @BeforeEach
  void setUp() {
    properties = new WorldServerProperties();
    properties.setHost("127.0.0.1");
    properties.setPort(9996);
    properties.setRealmId(1);

    ZoneInstanceJdbcRepository instanceRepo = (ZoneInstanceJdbcRepository) Proxy.newProxyInstance(
      ZoneInstanceJdbcRepository.class.getClassLoader(),
      new Class<?>[]{ZoneInstanceJdbcRepository.class},
      (proxy, method, args) -> {
        if ("findByWorldIdAndZoneIdAndIsStaticTrue".equals(method.getName())
            && Integer.valueOf(1).equals(args[0])
            && Integer.valueOf(231).equals(args[1])) {
          return Optional.of(new ZoneInstanceRecord(2, 1, 231, "DEFAULT", "10.0.0.231", 9231, "ONLINE", true, Instant.now()));
        }
        return Optional.empty();
      }
    );
    zoneRegistry = new ZoneRegistry(instanceRepo);
    sessionRegistry = new WorldSessionRegistry();
    syncDispatcher = new VirtualThreadTaskDispatcher() {
      @Override
      public void dispatch(Runnable task) {
        task.run();
      }
    };

    ActiveSessionJdbcRepository activeSessionRepo = (ActiveSessionJdbcRepository) Proxy.newProxyInstance(
      ActiveSessionJdbcRepository.class.getClassLoader(),
      new Class<?>[]{ActiveSessionJdbcRepository.class},
      (proxy, method, args) -> null
    );
    disconnectHandler = new SessionDisconnectedHandler(activeSessionRepo, syncDispatcher);
  }

  @Test
  @DisplayName("transferPlayer resolves ZoneLineJdbcRepository coordinates and persists DB before emitting transfer messages")
  void testTransferPlayerResolvesZoneLineAndSequencesDbFirst() {
    List<String> events = new CopyOnWriteArrayList<>();
    AtomicBoolean failDb = new AtomicBoolean(false);

    CharacterRepository charRepo = new CharacterRepository(null) {
      @Override
      public void updatePosition(long characterId, int zoneId, float posX, float posY, float posZ, float rotYaw, float rotPitch) {
        if (failDb.get()) {
          throw new IllegalStateException("DB error");
        }
        events.add("DB_UPDATE:" + zoneId + ":" + posX + "," + posY + "," + posZ);
      }
    };

    ZoneLineJdbcRepository zoneLineRepo = (ZoneLineJdbcRepository) Proxy.newProxyInstance(
      ZoneLineJdbcRepository.class.getClassLoader(),
      new Class<?>[]{ZoneLineJdbcRepository.class},
      (proxy, method, args) -> {
        if ("findBySourceZoneId".equals(method.getName()) && Integer.valueOf(230).equals(args[0])) {
          return List.of(new ZoneLineRecord(
            1, 230, 231, "SanDoriaGate", "DISCRETE",
            80f, -2f, 140f, 8f, 10f, 4f, 0f,
            82.5f, -3.5f, -142.0f, 180.0f, 0.0f,
            0f, 0f, 0f, 1000, 0.5f
          ));
        }
        return List.of();
      }
    );

    JoinWorldRequestHandler joinHandler = new JoinWorldRequestHandler(
      charRepo, zoneRegistry, sessionRegistry, syncDispatcher, properties, t -> Optional.empty()
    );
    WorldServer server = new WorldServer(
      properties, zoneRegistry, sessionRegistry, charRepo, syncDispatcher,
      new IndexedPacketRouter(), joinHandler, disconnectHandler, zoneLineRepo
    );
    server.registerHandlers();

    EmbeddedChannel ch = new EmbeddedChannel();
    List<Object> out = new CopyOnWriteArrayList<>();
    ChannelHandlerContext ctx = (ChannelHandlerContext) Proxy.newProxyInstance(
      ChannelHandlerContext.class.getClassLoader(),
      new Class<?>[]{ChannelHandlerContext.class},
      (proxy, method, args) -> {
        if ("writeAndFlush".equals(method.getName()) || "write".equals(method.getName())) {
          events.add(args[0].getClass().getSimpleName());
          out.add(args[0]);
          return null;
        }
        if ("channel".equals(method.getName())) {
          return ch;
        }
        return null;
      }
    );

    try {
      sessionRegistry.registerSession(55L, 1001L, 230, new EndpointTarget("10.0.0.230", 9230), ctx);
      server.transferPlayer(55L, 1001L, 231);

      assertThat(events).containsExactly(
        "DB_UPDATE:231:82.5,-3.5,-142.0",
        "SetSessionZoneControlMessage",
        "ZoneTransferNotification"
      );

      ZoneTransferNotification notif = (ZoneTransferNotification) out.get(1);
      assertThat(notif.spawnX()).isEqualTo(82.5f);
      assertThat(notif.spawnY()).isEqualTo(-3.5f);
      assertThat(notif.spawnZ()).isEqualTo(-142.0f);
      assertThat(sessionRegistry.getSession(55L).getCurrentZoneId()).isEqualTo(231);

      // DB failure must abort without emitting control/transfer packets or mutating session zone
      out.clear();
      events.clear();
      failDb.set(true);
      sessionRegistry.registerSession(56L, 1002L, 230, new EndpointTarget("10.0.0.230", 9230), ctx);
      server.transferPlayer(56L, 1002L, 231);
      assertThat(out).isEmpty();
      assertThat(sessionRegistry.getSession(56L).getCurrentZoneId()).isEqualTo(230);

      // Disconnect handler removes session from WorldSessionRegistry
      disconnectHandler.handle(ctx, new SessionDisconnectedEvent(56L, System.currentTimeMillis()));
      assertThat(sessionRegistry.getSession(56L)).isNull();
    } finally {
      ch.close();
    }
  }
}
