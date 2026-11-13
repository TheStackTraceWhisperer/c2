package com.example.zone.engine;

import com.example.config.ZoneServerProperties;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.CharacterStateSnapshot;
import com.example.server.entity.NpcRecord;
import com.example.server.repository.CharacterRepository;
import com.example.server.repository.NpcJdbcRepository;
import com.example.server.repository.ZoneLineJdbcRepository;
import com.example.zone.collision.ZoneCollisionService;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.ZoneStateDeltaMessage;
import com.example.zone.spatial.SpatialGrid;
import com.example.zone.state.ZoneEntityState;
import com.example.common.protocol.JobId;
import com.example.world.dto.ZoneTransferNotification;
import com.example.zone.patrol.NpcPatrolRoute;
import com.example.zone.patrol.NpcPatrolSimulation;
import com.example.zone.patrol.NpcPatrolWaypoint;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ZoneSimulationEngineTest {

    private ZoneServerProperties props;
    private SpatialGrid spatialGrid;
    private TestCharacterRepository charRepo;
    private ZoneLineJdbcRepository zoneLineRepo;
    private TestTaskDispatcher dispatcher;
    private ZoneCollisionService collisionService;
    private ZoneSimulationEngine engine;

    static class TestCharacterRepository extends CharacterRepository {
        Map<Long, CharacterRecord> records = new HashMap<>();
        List<List<CharacterStateSnapshot>> flushedBatches = new CopyOnWriteArrayList<>();

        public TestCharacterRepository() {
            super(null);
        }

        @Override
        public Optional<CharacterRecord> findById(long id) {
            return Optional.ofNullable(records.get(id));
        }

        @Override
        public void flushStateBatch(Collection<CharacterStateSnapshot> snapshots) {
            if (snapshots != null && !snapshots.isEmpty()) {
                flushedBatches.add(new ArrayList<>(snapshots));
            }
        }

        @Override
        public void updatePosition(long characterId, int zoneId, float posX, float posY, float posZ, float rotYaw, float rotPitch) {
            CharacterRecord existing = records.get(characterId);
            if (existing != null) {
                records.put(characterId, new CharacterRecord(
                    existing.accountId(),
                    characterId,
                    existing.worldId(),
                    existing.name(),
                    existing.raceId(),
                    existing.gender(),
                    existing.faceId(),
                    existing.hairId(),
                    existing.sizeId(),
                    existing.nationId(),
                    existing.mainJobId(),
                    existing.mainJobLevel(),
                    existing.subJobId(),
                    existing.subJobLevel(),
                    zoneId,
                    posX,
                    posY,
                    posZ,
                    rotYaw,
                    rotPitch,
                    existing.hpZoneId(),
                    existing.hpPosX(),
                    existing.hpPosY(),
                    existing.hpPosZ(),
                    existing.lastSavedAt()
                ));
            }
        }
    }

    static class TestTaskDispatcher extends VirtualThreadTaskDispatcher {
        AtomicInteger taskCount = new AtomicInteger(0);

        @Override
        public void dispatch(Runnable task) {
            taskCount.incrementAndGet();
            task.run();
        }
    }

    @BeforeEach
    void setUp() {
        props = new ZoneServerProperties();
        props.setHost("127.0.0.1");
        props.setPort(9000);
        props.setZoneId(230); // South San d'Oria
        props.setCollisionEnabled(false);

        spatialGrid = new SpatialGrid();
        charRepo = new TestCharacterRepository();

        zoneLineRepo = (ZoneLineJdbcRepository) Proxy.newProxyInstance(
                ZoneLineJdbcRepository.class.getClassLoader(),
                new Class<?>[]{ZoneLineJdbcRepository.class},
                (proxy, method, args) -> {
                    if ("findBySourceZoneId".equals(method.getName())) {
                        return List.of();
                    }
                    return null;
                }
        );

        dispatcher = new TestTaskDispatcher();
        collisionService = new ZoneCollisionService(props);
        NpcPatrolSimulation patrolSim = new NpcPatrolSimulation();

        engine = new ZoneSimulationEngine(
                props,
                spatialGrid,
                charRepo,
                zoneLineRepo,
                dispatcher,
                collisionService,
                patrolSim
        );
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
    }



    @Test
    @DisplayName("Town NPCs are excluded from database character persistence")
    void testNpcExcludedFromPersistence() {
        engine.start();

        engine.flushDirtyEntitiesAsync();

        // Verify no batches flushed for town NPCs
        assertThat(charRepo.flushedBatches).isEmpty();
        assertThat(dispatcher.taskCount.get()).isEqualTo(0);
    }



    @Test
    @DisplayName("Swept-OBB zoneline collision triggers ZoneTransferNotification, updates DB position, and deregisters entity")
    void testZonelineSweptObbTransition() {
        engine.start();

        assertThat(engine.getCachedZoneLines()).isNotEmpty();
        var zoneline = engine.getCachedZoneLines().get(0);

        EmbeddedChannel channel = new EmbeddedChannel();
        List<Object> outboundPackets = new CopyOnWriteArrayList<>();
        ChannelHandlerContext ctx = (ChannelHandlerContext) Proxy.newProxyInstance(
                ChannelHandlerContext.class.getClassLoader(),
                new Class<?>[]{ChannelHandlerContext.class},
                (proxy, method, args) -> {
                    if ("writeAndFlush".equals(method.getName())) {
                        outboundPackets.add(args[0]);
                        return null;
                    }
                    if ("channel".equals(method.getName())) {
                        return channel;
                    }
                    return null;
                }
        );

        long playerId = 3001L;
        // Position player just 0.5m inside/across the boundary
        float startX = zoneline.centerX() - 0.5f;
        float startY = zoneline.centerY();
        float startZ = zoneline.centerZ();

        charRepo.records.put(playerId, new CharacterRecord(
                1L, playerId, 1, "ZoningHero", (short) 1, (short) 0, (short) 1,
                (short) 1, (short) 1, (short) 1, (short) 1, (short) 1, (short) 0, (short) 0,
                230, startX, startY, startZ, 0.0f, 0.0f,
                230, startX, startY, startZ, Instant.now()
        ));

        engine.registerSessionEntity(99999L, playerId, ctx);

        // Swept movement across the zoneline center (1.0m delta, well within 2.5m speed limit)
        float targetX = zoneline.centerX() + 0.5f;
        PlayerMovementRequest request = new PlayerMovementRequest(
                targetX, startY, startZ,
                2.0f, 0.0f, 0.0f,
                0.0f, 0.0f,
                (byte) 0,
                1L
        );

        engine.enqueueMovement(ctx, 99999L, playerId, request);

        // Await ZoneTransferNotification packet
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            boolean hasTransfer = outboundPackets.stream()
                    .anyMatch(msg -> msg instanceof ZoneTransferNotification);
            assertThat(hasTransfer).isTrue();
        });

        ZoneTransferNotification transfer = outboundPackets.stream()
                .filter(msg -> msg instanceof ZoneTransferNotification)
                .map(msg -> (ZoneTransferNotification) msg)
                .findFirst()
                .orElseThrow();

        assertThat(transfer.targetZoneId()).isEqualTo(zoneline.targetZoneId());
        assertThat(transfer.spawnX()).isEqualTo(zoneline.destX());
        assertThat(transfer.spawnY()).isEqualTo(zoneline.destY());
        assertThat(transfer.spawnZ()).isEqualTo(zoneline.destZ());

        // Verify entity was deregistered from zone simulation engine
        assertThat(engine.getEntity(playerId)).isNull();

        // Verify character record was persisted with destination coordinates
        CharacterRecord updated = charRepo.records.get(playerId);
        assertThat(updated.zoneId()).isEqualTo(zoneline.targetZoneId());
        assertThat(updated.posX()).isEqualTo(zoneline.destX());
        assertThat(updated.posY()).isEqualTo(zoneline.destY());
        assertThat(updated.posZ()).isEqualTo(zoneline.destZ());

        channel.close();
    }

    @Test
    @DisplayName("LSB Validation: Authentic LandSandBoat NPCs loaded from database repository into spatial grid")
    void testAuthenticLandSandBoatNpcsLoadedAndSpawned() {
        NpcJdbcRepository mockNpcRepo = (NpcJdbcRepository) Proxy.newProxyInstance(
                NpcJdbcRepository.class.getClassLoader(),
                new Class<?>[]{NpcJdbcRepository.class},
                (proxy, method, args) -> {
                    if ("findByZoneId".equals(method.getName())) {
                        int zoneId = (int) args[0];
                        if (zoneId == 230) {
                            return List.of(
                                    new NpcRecord(
                                            17719297L, "Ceraule",
                                            -86.077f, 1.999f, -34.793f, (short) 115,
                                            230, "normal", 27, (short) 1, (short) 32, (short) 14,
                                            "equipped", (short) 3, (short) 2,
                                            4108, 8204, 12300, 16396, 20492, 24576, 28672, 0, 0, true
                                    ),
                                    new NpcRecord(
                                            17719298L, "Aubejart",
                                            2.900f, -1.999f, 46.644f, (short) 64,
                                            230, "normal", 27, (short) 1, (short) 32, (short) 8,
                                            "equipped", (short) 3, (short) 8,
                                            4124, 8220, 12316, 16412, 20508, 24576, 28672, 0, 0, true
                                    )
                            );
                        }
                    }
                    return null;
                }
        );

        ZoneSimulationEngine engineWithNpcs = new ZoneSimulationEngine(
                props,
                spatialGrid,
                charRepo,
                zoneLineRepo,
                dispatcher,
                collisionService,
                new NpcPatrolSimulation(),
                mockNpcRepo
        );
        engineWithNpcs.start();

        try {
            assertThat(engineWithNpcs.getEntity(17719297L)).isNotNull();
            ZoneEntityState ceraule = engineWithNpcs.getEntity(17719297L);
            assertThat(ceraule.getName()).isEqualTo("Ceraule");
            assertThat(ceraule.getX()).isEqualTo(-86.077f);
            assertThat(ceraule.getY()).isEqualTo(1.999f);
            assertThat(ceraule.getZ()).isEqualTo(-34.793f);
            assertThat(ceraule.getYaw()).isEqualTo(115 * 360.0f / 256.0f);
            assertThat(ceraule.getAnimationSub()).isEqualTo(1); // Sitting / resting pose
            assertThat(ceraule.isNpc()).isTrue();

            assertThat(engineWithNpcs.getEntity(17719298L)).isNotNull();
            ZoneEntityState aubejart = engineWithNpcs.getEntity(17719298L);
            assertThat(aubejart.getName()).isEqualTo("Aubejart");
            assertThat(aubejart.getYaw()).isEqualTo(64 * 360.0f / 256.0f);
            assertThat(aubejart.getAnimationSub()).isEqualTo(1);

            // Verify spatial grid indexing
            Set<Long> nearby = spatialGrid.getNearbyEntities(-86.0f, -34.0f);
            assertThat(nearby).contains(17719297L);
        } finally {
            engineWithNpcs.stop();
        }
    }
}
