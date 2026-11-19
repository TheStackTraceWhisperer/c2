package com.example.zone.engine;

import com.example.common.codec.ForySerializationProvider;
import com.example.common.protocol.MovementFlags;
import com.example.common.protocol.ProtocolConstants;
import com.example.config.ZoneServerProperties;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.CharacterStateSnapshot;
import com.example.server.entity.NpcRecord;
import com.example.server.entity.ZoneInstanceRecord;
import com.example.server.entity.ZoneLineRecord;
import com.example.server.protocol.SetSessionZoneControlMessage;
import com.example.server.repository.CharacterRepository;
import com.example.server.repository.NpcJdbcRepository;
import com.example.server.repository.ZoneInstanceJdbcRepository;
import com.example.server.repository.ZoneLineJdbcRepository;
import com.example.zone.collision.ZoneCollisionService;
import com.example.zone.dto.EntitySpawnRecord;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.PlayerMovementResponse;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    @DisplayName("Town NPCs (including patrolling NPCs) are spawned and excluded from database persistence, and cutscene_only NPCs are skipped")
    void testNpcExcludedFromPersistence() {
        NpcJdbcRepository mockNpcRepo = (NpcJdbcRepository) Proxy.newProxyInstance(
                NpcJdbcRepository.class.getClassLoader(),
                new Class<?>[]{NpcJdbcRepository.class},
                (proxy, method, args) -> {
                    if ("findByZoneId".equals(method.getName())) {
                        return List.of(
                                new NpcRecord(
                                        17719297L, "Ceraule",
                                        -86.077f, 1.999f, -34.793f, (short) 115,
                                        230, "normal", 27, (short) 1, (short) 32, (short) 14,
                                        "equipped", (short) 3, (short) 2,
                                        4108, 8204, 12300, 16396, 20492, 24576, 28672, 0, 0, true
                                ),
                                new NpcRecord(
                                        17719999L, "CutscenePhantom",
                                        10.0f, 1.0f, 10.0f, (short) 0,
                                        230, "cutscene_only", 0, (short) 0, (short) 0, (short) 0,
                                        null, (short) 0, (short) 0,
                                        0, 0, 0, 0, 0, 0, 0, 0, 0, false
                                )
                        );
                    }
                    return null;
                }
        );

        NpcPatrolSimulation patrolSim = new NpcPatrolSimulation();
        ZoneSimulationEngine engineWithNpcs = new ZoneSimulationEngine(
                props,
                spatialGrid,
                charRepo,
                zoneLineRepo,
                dispatcher,
                collisionService,
                patrolSim,
                mockNpcRepo
        );

        // Register a patrolling NPC and advance a tick before start() or directly
        ZoneEntityState sentry = ZoneEntityState.createNpc(88001L, 230, "PatrolSentry", 0f, 0f, 0f, 0f);
        engineWithNpcs.registerNpc(sentry);
        patrolSim.registerPatrol(88001L, new NpcPatrolRoute(
                "sentry-route",
                List.of(new NpcPatrolWaypoint(10f, 0f, 0f)),
                true
        ), 4.0f);

        engineWithNpcs.tick();
        assertThat(sentry.getX()).isGreaterThan(0f);
        assertThat(sentry.isDirty()).isFalse();

        engineWithNpcs.start();
        try {
            assertThat(engineWithNpcs.getEntity(17719297L)).isNotNull();
            assertThat(engineWithNpcs.getEntity(17719999L)).isNull();

            engineWithNpcs.flushDirtyEntitiesAsync();
        } finally {
            engineWithNpcs.stop();
        }

        // Verify no batches flushed for town or patrolling NPCs (flush or stop)
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

    @Test
    @DisplayName("LSB Spawn Filter: loadAuthenticNpcs registers valid actors and skips doors, ships, status_4, blanks, zero-model standard, and cutscene_only")
    void testLoadAuthenticNpcsAppliesNpcSpawnFilter() {
        NpcJdbcRepository mockNpcRepo = (NpcJdbcRepository) Proxy.newProxyInstance(
                NpcJdbcRepository.class.getClassLoader(),
                new Class<?>[]{NpcJdbcRepository.class},
                (proxy, method, args) -> {
                    if ("findByZoneId".equals(method.getName())) {
                        return List.of(
                                // Valid: equipped normal NPC
                                new NpcRecord(
                                        17719297L, "Ceraule",
                                        -86.077f, 1.999f, -34.793f, (short) 115,
                                        230, "normal", 27, (short) 1, (short) 32, (short) 14,
                                        "equipped", (short) 3, (short) 2,
                                        4108, 8204, 12300, 16396, 20492, 24576, 28672, 0, 0, true
                                ),
                                // Valid: standard normal NPC with modelId > 0
                                new NpcRecord(
                                        17719481L, "Well",
                                        -25.189f, -2.000f, 39.299f, (short) 64,
                                        230, "normal", 27, (short) 1, (short) 32, (short) 1,
                                        "standard", (short) 0, (short) 0,
                                        0, 0, 0, 0, 0, 0, 0, 0, 996, true
                                ),
                                // Valid: chocobo normal NPC
                                new NpcRecord(
                                        17719401L, "Chocobo",
                                        9.956f, -3.000f, -110.892f, (short) 0,
                                        230, "normal", 27, (short) 1, (short) 32, (short) 1,
                                        "chocobo", (short) 0, (short) 0,
                                        0, 0, 0, 0, 0, 0, 0, 0, 0, false
                                ),
                                // Skipped: door look_type
                                new NpcRecord(
                                        17719501L, "_6r0",
                                        12.0f, 0.0f, 15.0f, (short) 0,
                                        230, "normal", 4099, (short) 0, (short) 0, (short) 0,
                                        "door", (short) 0, (short) 0,
                                        0, 0, 0, 0, 0, 0, 0, 0, 0, false
                                ),
                                // Skipped: ship look_type
                                new NpcRecord(
                                        17719502L, "Ship_Trigger",
                                        0.0f, 0.0f, 0.0f, (short) 0,
                                        230, "normal", 0, (short) 0, (short) 0, (short) 0,
                                        "ship", (short) 0, (short) 0,
                                        0, 0, 0, 0, 0, 0, 0, 0, 0, false
                                ),
                                // Skipped: status_4
                                new NpcRecord(
                                        17719503L, "HiddenEventNpc",
                                        5.0f, 0.0f, 5.0f, (short) 0,
                                        230, "status_4", 27, (short) 0, (short) 32, (short) 0,
                                        "equipped", (short) 1, (short) 1,
                                        0, 0, 0, 0, 0, 0, 0, 0, 0, false
                                ),
                                // Skipped: blank trigger name
                                new NpcRecord(
                                        17719504L, "blank",
                                        0.0f, 0.0f, 0.0f, (short) 0,
                                        230, "normal", 2327, (short) 0, (short) 32, (short) 0,
                                        "standard", (short) 0, (short) 0,
                                        0, 0, 0, 0, 0, 0, 0, 0, 963, false
                                ),
                                // Skipped: cutscene_only
                                new NpcRecord(
                                        17719505L, "CutsceneActor",
                                        1.0f, 0.0f, 1.0f, (short) 0,
                                        230, "cutscene_only", 27, (short) 0, (short) 32, (short) 0,
                                        "equipped", (short) 1, (short) 1,
                                        0, 0, 0, 0, 0, 0, 0, 0, 0, false
                                ),
                                // Skipped: standard with modelId=0
                                new NpcRecord(
                                        17719506L, "UnmodeledTrigger",
                                        2.0f, 0.0f, 2.0f, (short) 0,
                                        230, "normal", 27, (short) 0, (short) 32, (short) 0,
                                        "standard", (short) 0, (short) 0,
                                        0, 0, 0, 0, 0, 0, 0, 0, 0, false
                                )
                        );
                    }
                    return null;
                }
        );

        ZoneSimulationEngine filteredEngine = new ZoneSimulationEngine(
                props,
                spatialGrid,
                charRepo,
                zoneLineRepo,
                dispatcher,
                collisionService,
                new NpcPatrolSimulation(),
                mockNpcRepo
        );
        filteredEngine.start();

        try {
            assertThat(filteredEngine.getEntityCount()).isEqualTo(3);
            assertThat(filteredEngine.getEntity(17719297L)).isNotNull();
            assertThat(filteredEngine.getEntity(17719481L)).isNotNull();
            assertThat(filteredEngine.getEntity(17719401L)).isNotNull();

            assertThat(filteredEngine.getEntity(17719501L)).isNull();
            assertThat(filteredEngine.getEntity(17719502L)).isNull();
            assertThat(filteredEngine.getEntity(17719503L)).isNull();
            assertThat(filteredEngine.getEntity(17719504L)).isNull();
            assertThat(filteredEngine.getEntity(17719505L)).isNull();
            assertThat(filteredEngine.getEntity(17719506L)).isNull();
        } finally {
            filteredEngine.stop();
        }
    }

    @Test
    @DisplayName("Non-blocking first-movement registration: deduplicates async DB loads via commandQueue without stalling tick")
    void testNonBlockingFirstMovementRegistration() throws Exception {
        CountDownLatch dbStarted = new CountDownLatch(1);
        CountDownLatch dbRelease = new CountDownLatch(1);
        AtomicInteger findByIdCalls = new AtomicInteger(0);

        TestCharacterRepository slowRepo = new TestCharacterRepository() {
            @Override
            public Optional<CharacterRecord> findById(long id) {
                if (id == 4001L) {
                    findByIdCalls.incrementAndGet();
                    dbStarted.countDown();
                    try {
                        dbRelease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.findById(id);
            }
        };

        slowRepo.records.put(4001L, characterRecord(4001L, 1, "AsyncWalker", 230, 10.0f, 0.0f, 10.0f));
        slowRepo.records.put(4002L, characterRecord(4002L, 1, "AlreadyLoaded", 230, 20.0f, 0.0f, 20.0f));

        VirtualThreadTaskDispatcher asyncDispatcher = new VirtualThreadTaskDispatcher();
        ZoneSimulationEngine asyncEngine = new ZoneSimulationEngine(
                props, spatialGrid, slowRepo, zoneLineRepo, asyncDispatcher, collisionService, new NpcPatrolSimulation()
        );

        EmbeddedChannel channel1 = new EmbeddedChannel();
        List<Object> out1 = new CopyOnWriteArrayList<>();
        ChannelHandlerContext ctx1 = createProxyContext(channel1, out1);

        EmbeddedChannel channel2 = new EmbeddedChannel();
        List<Object> out2 = new CopyOnWriteArrayList<>();
        ChannelHandlerContext ctx2 = createProxyContext(channel2, out2);

        try {
            // Pre-register player 4002, leave player 4001 unregistered
            asyncEngine.registerSessionEntity(2002L, 4002L, ctx2);
            asyncEngine.tick();

            // Send two rapid movements for unregistered 4001 AND one for registered 4002
            asyncEngine.enqueueMovement(ctx1, 2001L, 4001L, new PlayerMovementRequest(10.5f, 0f, 10f, 0f, 0f, 0f, 0f, 0f, MovementFlags.FORWARD, 1L));
            asyncEngine.enqueueMovement(ctx1, 2001L, 4001L, new PlayerMovementRequest(11.0f, 0f, 10f, 0f, 0f, 0f, 0f, 0f, MovementFlags.FORWARD, 2L));
            asyncEngine.enqueueMovement(ctx2, 2002L, 4002L, new PlayerMovementRequest(20.5f, 0f, 20f, 0f, 0f, 0f, 0f, 0f, MovementFlags.FORWARD, 1L));

            // tick() must return without blocking on slowRepo.findById(4001L) (which stays latched for 5s)
            long startNs = System.nanoTime();
            asyncEngine.tick();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            assertThat(elapsedMs).isLessThan(2000L);
            assertThat(dbRelease.getCount()).isEqualTo(1L);

            // Registered player 4002 was still processed immediately
            assertThat(out2).anySatisfy(p -> {
                assertThat(p).isInstanceOf(PlayerMovementResponse.class);
                assertThat(((PlayerMovementResponse) p).x()).isEqualTo(20.5f);
            });

            // Async DB load triggered exactly once (deduplicated via pendingLoads)
            assertThat(dbStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(findByIdCalls.get()).isEqualTo(1);
            assertThat(asyncEngine.getEntity(4001L)).isNull();

            // Release DB load and await commandQueue completion on tick
            dbRelease.countDown();
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                asyncEngine.tick();
                assertThat(asyncEngine.getEntity(4001L)).isNotNull();
                assertThat(out1).anySatisfy(p -> assertThat(p).isInstanceOf(PlayerMovementResponse.class));
            });

            assertThat(asyncEngine.getEntity(4001L).getX()).isEqualTo(11.0f);
            assertThat(findByIdCalls.get()).isEqualTo(1);
        } finally {
            asyncEngine.stop();
            channel1.close();
            channel2.close();
        }
    }

    @Test
    @DisplayName("Stationary quietude: networkDirty clears after broadcast pass while persistence dirty remains intact")
    void testStationaryQuietudeClearsNetworkDirty() {
        charRepo.records.put(5001L, characterRecord(5001L, 1, "Observer", 230, 0f, 0f, 0f));
        charRepo.records.put(5002L, characterRecord(5002L, 1, "Neighbor", 230, 5f, 0f, 0f));

        EmbeddedChannel ch1 = new EmbeddedChannel();
        List<Object> out1 = new CopyOnWriteArrayList<>();
        ChannelHandlerContext ctx1 = createProxyContext(ch1, out1);

        EmbeddedChannel ch2 = new EmbeddedChannel();
        List<Object> out2 = new CopyOnWriteArrayList<>();
        ChannelHandlerContext ctx2 = createProxyContext(ch2, out2);

        try {
            engine.registerNpc(ZoneEntityState.createNpc(77001L, 230, "StaticGuard", 3f, 0f, 0f, 0f));
            engine.registerSessionEntity(501L, 5001L, ctx1);
            engine.registerSessionEntity(502L, 5002L, ctx2);

            // Tick 1: initial AoI spawn broadcast for Observer
            engine.tick();
            long initialDeltas = out1.stream().filter(ZoneStateDeltaMessage.class::isInstance).count();
            assertThat(initialDeltas).isEqualTo(1);

            // Subsequent 20 stationary ticks must emit zero redundant AoI deltas
            out1.clear();
            for (int i = 0; i < 20; i++) {
                engine.tick();
            }
            assertThat(out1.stream().filter(ZoneStateDeltaMessage.class::isInstance)).isEmpty();

            // Neighbor moves once -> single update broadcast, then quiet again while persistence dirty remains true
            engine.enqueueMovement(ctx2, 502L, 5002L, new PlayerMovementRequest(5.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, MovementFlags.FORWARD, 1L));
            engine.tick();

            List<ZoneStateDeltaMessage> moveDeltas = out1.stream()
                    .filter(ZoneStateDeltaMessage.class::isInstance)
                    .map(ZoneStateDeltaMessage.class::cast)
                    .toList();
            assertThat(moveDeltas).hasSize(1);
            assertThat(moveDeltas.get(0).updates()).hasSize(1);
            assertThat(moveDeltas.get(0).updates().get(0).characterId()).isEqualTo(5002L);

            assertThat(engine.getEntity(5002L).isNetworkDirty()).isFalse();
            assertThat(engine.getEntity(5002L).isDirty()).isTrue();

            out1.clear();
            for (int i = 0; i < 10; i++) {
                engine.tick();
            }
            assertThat(out1.stream().filter(ZoneStateDeltaMessage.class::isInstance)).isEmpty();
        } finally {
            ch1.close();
            ch2.close();
        }
    }

    @Test
    @DisplayName("AoI chunking: 260 nearby entities chunked under MAX_PAYLOAD_SIZE (16 KB) with per-chunk knownEntities updates")
    void testAoIChunkingOver250EntitiesUnderMaxPayload() {
        charRepo.records.put(6000L, characterRecord(6000L, 1, "CrowdObserver", 230, 0f, 0f, 0f));

        EmbeddedChannel ch = new EmbeddedChannel();
        List<Object> out = new CopyOnWriteArrayList<>();
        ChannelHandlerContext ctx = createProxyContext(ch, out);

        try {
            engine.registerSessionEntity(600L, 6000L, ctx);
            for (int i = 1; i <= 260; i++) {
                ZoneEntityState npc = ZoneEntityState.createNpc(
                        90000L + i, 230, "Citizen_" + i + "_WithLongName",
                        (i % 20) * 0.5f, 0f, (i / 20) * 0.5f, 0f
                );
                engine.registerNpc(npc);
            }

            engine.tick();

            List<ZoneStateDeltaMessage> spawnDeltas = out.stream()
                    .filter(ZoneStateDeltaMessage.class::isInstance)
                    .map(ZoneStateDeltaMessage.class::cast)
                    .toList();

            assertThat(spawnDeltas.size()).isGreaterThanOrEqualTo(9); // ceil(260 / 30) = 9
            Set<Long> spawnedIds = new HashSet<>();
            for (ZoneStateDeltaMessage delta : spawnDeltas) {
                assertThat(delta.spawns().size()).isLessThanOrEqualTo(ZoneSimulationEngine.MAX_SPAWNS_PER_DELTA);
                byte[] serialized = ForySerializationProvider.get().serialize(delta);
                assertThat(serialized.length).isLessThan(ProtocolConstants.MAX_PAYLOAD_SIZE);
                delta.spawns().forEach(s -> spawnedIds.add(s.characterId()));
            }
            assertThat(spawnedIds).hasSize(260);
            assertThat(engine.getEntity(6000L).getKnownEntities()).hasSize(260);

            // Trigger 260 updates in one tick and verify update chunking & payload size
            out.clear();
            for (int i = 1; i <= 260; i++) {
                ZoneEntityState npc = engine.getEntity(90000L + i);
                npc.setX(npc.getX() + 0.1f);
                npc.setNetworkDirty(true);
            }
            engine.tick();

            List<ZoneStateDeltaMessage> updateDeltas = out.stream()
                    .filter(ZoneStateDeltaMessage.class::isInstance)
                    .map(ZoneStateDeltaMessage.class::cast)
                    .toList();
            assertThat(updateDeltas.size()).isGreaterThanOrEqualTo(5); // ceil(260 / 60) = 5
            int totalUpdates = 0;
            for (ZoneStateDeltaMessage delta : updateDeltas) {
                assertThat(delta.updates().size()).isLessThanOrEqualTo(ZoneSimulationEngine.MAX_UPDATES_PER_DELTA);
                byte[] serialized = ForySerializationProvider.get().serialize(delta);
                assertThat(serialized.length).isLessThan(ProtocolConstants.MAX_PAYLOAD_SIZE);
                totalUpdates += delta.updates().size();
            }
            assertThat(totalUpdates).isEqualTo(260);
        } finally {
            ch.close();
        }
    }

    @Test
    @DisplayName("Anti-cheat: rejects NaN/Inf inputs, clamps velocity magnitude, enforces per-tick displacement budget, and preserves velocity when reconciled")
    void testNanAndSameTickSpeedHackRejection() {
        charRepo.records.put(7001L, characterRecord(7001L, 1, "AntiCheatTarget", 230, 10.0f, 0.0f, 10.0f));
        EmbeddedChannel ch = new EmbeddedChannel();
        List<Object> out = new CopyOnWriteArrayList<>();
        ChannelHandlerContext ctx = createProxyContext(ch, out);

        try {
            engine.registerSessionEntity(701L, 7001L, ctx);
            engine.tick();

            // 1. Valid baseline movement to establish known authoritative velocity
            engine.enqueueMovement(ctx, 701L, 7001L, new PlayerMovementRequest(
                    10.5f, 0f, 10f, 2.0f, 0f, 0f, 90f, 0f, MovementFlags.FORWARD, 1L));
            engine.tick();

            ZoneEntityState state = engine.getEntity(7001L);
            float authX = state.getX();
            float authVx = state.getVx();
            assertThat(authX).isCloseTo(10.6f, org.assertj.core.data.Offset.offset(0.01f));

            // 2. Non-finite coordinates/velocity rejected immediately with authoritative pos & reconciled=true
            out.clear();
            engine.enqueueMovement(ctx, 701L, 7001L, new PlayerMovementRequest(
                    Float.NaN, 0f, 10f, 5f, 0f, 0f, 0f, 0f, MovementFlags.FORWARD, 2L));
            engine.enqueueMovement(ctx, 701L, 7001L, new PlayerMovementRequest(
                    11f, Float.POSITIVE_INFINITY, 10f, 0f, 0f, 0f, 0f, 0f, MovementFlags.FORWARD, 3L));
            engine.tick();

            List<PlayerMovementResponse> nanResponses = out.stream()
                    .filter(PlayerMovementResponse.class::isInstance)
                    .map(PlayerMovementResponse.class::cast)
                    .toList();
            assertThat(nanResponses).hasSize(2);
            assertThat(nanResponses).allSatisfy(r -> {
                assertThat(r.reconciled()).isTrue();
                assertThat(r.x()).isEqualTo(authX);
                assertThat(r.vx()).isEqualTo(authVx);
            });
            assertThat(state.getX()).isEqualTo(authX);
            assertThat(state.getVx()).isEqualTo(authVx);

            // 3. Velocity vector > MAX_SPEED_UNITS_PER_SEC clamped before prediction
            out.clear();
            engine.enqueueMovement(ctx, 701L, 7001L, new PlayerMovementRequest(
                    authX, 0f, 10f, 5000.0f, 0f, 0f, 90f, 0f, MovementFlags.FORWARD, 4L));
            engine.tick();

            PlayerMovementResponse clampedResp = out.stream()
                    .filter(PlayerMovementResponse.class::isInstance)
                    .map(PlayerMovementResponse.class::cast)
                    .findFirst().orElseThrow();
            assertThat(clampedResp.reconciled()).isFalse();
            assertThat(clampedResp.vx()).isLessThanOrEqualTo(ZoneSimulationEngine.MAX_SPEED_UNITS_PER_SEC);
            assertThat(clampedResp.x()).isCloseTo(authX + ZoneSimulationEngine.MAX_SPEED_UNITS_PER_SEC * 0.05f,
                    org.assertj.core.data.Offset.offset(0.01f));

            // 4. Multiple packets within the SAME 50ms tick cannot bypass MAX_DISTANCE_PER_TICK (2.5m)
            out.clear();
            float beforeBurstX = state.getX();
            float beforeBurstVx = state.getVx();
            engine.enqueueMovement(ctx, 701L, 7001L, new PlayerMovementRequest(
                    beforeBurstX + 1.8f, 0f, 10f, 1.0f, 0f, 0f, 90f, 0f, MovementFlags.FORWARD, 5L));
            engine.enqueueMovement(ctx, 701L, 7001L, new PlayerMovementRequest(
                    beforeBurstX + 3.6f, 0f, 10f, 99.0f, 0f, 0f, 90f, 0f, MovementFlags.FORWARD, 6L));
            engine.tick();

            List<PlayerMovementResponse> burstResponses = out.stream()
                    .filter(PlayerMovementResponse.class::isInstance)
                    .map(PlayerMovementResponse.class::cast)
                    .toList();
            assertThat(burstResponses).hasSize(2);
            assertThat(burstResponses.get(0).reconciled()).isFalse();
            assertThat(burstResponses.get(1).reconciled()).isTrue();
            // Position stays at first move and vx is NOT overwritten by the reconciled second packet
            assertThat(state.getX()).isEqualTo(burstResponses.get(0).x());
            assertThat(state.getVx()).isEqualTo(1.0f);
            assertThat(burstResponses.get(1).vx()).isEqualTo(1.0f);
            assertThat(beforeBurstVx).isGreaterThan(0f);
        } finally {
            ch.close();
        }
    }

    @Test
    @DisplayName("Cross-zone transfer: processes remaining players in same tick, sequences DB before SetSessionZoneControlMessage + ZoneTransferNotification, and aborts on DB error")
    void testCrossZoneTransferContinuesOtherPlayersAndSequencesPersistence() {
        ZoneLineRecord syntheticLine = new ZoneLineRecord(
                99, 230, 231, "SanDoriaGate", "DISCRETE",
                50.0f, 0.0f, 50.0f, 2.0f, 5.0f, 2.0f, 0.0f,
                80.0f, -2.0f, -140.0f, 0.0f, 0.0f,
                0f, 0f, 0f, 1000, 0.5f
        );
        ZoneLineJdbcRepository lineRepo = (ZoneLineJdbcRepository) Proxy.newProxyInstance(
                ZoneLineJdbcRepository.class.getClassLoader(),
                new Class<?>[]{ZoneLineJdbcRepository.class},
                (proxy, method, args) -> "findBySourceZoneId".equals(method.getName()) ? List.of(syntheticLine) : null
        );
        ZoneInstanceJdbcRepository instanceRepo = (ZoneInstanceJdbcRepository) Proxy.newProxyInstance(
                ZoneInstanceJdbcRepository.class.getClassLoader(),
                new Class<?>[]{ZoneInstanceJdbcRepository.class},
                (proxy, method, args) -> {
                    if ("findByWorldIdAndZoneIdAndIsStaticTrue".equals(method.getName())
                            && Integer.valueOf(1).equals(args[0])
                            && Integer.valueOf(231).equals(args[1])) {
                        return Optional.of(new ZoneInstanceRecord(
                                2, 1, 231, "DEFAULT", "10.20.30.40", 9231, "ONLINE", true, Instant.now()
                        ));
                    }
                    return Optional.empty();
                }
        );

        List<String> eventOrder = new CopyOnWriteArrayList<>();
        AtomicBoolean failUpdate = new AtomicBoolean(false);
        AtomicInteger failedUpdateAttempts = new AtomicInteger(0);
        TestCharacterRepository orderedRepo = new TestCharacterRepository() {
            @Override
            public void updatePosition(long characterId, int zoneId, float posX, float posY, float posZ, float rotYaw, float rotPitch) {
                if (failUpdate.get()) {
                    failedUpdateAttempts.incrementAndGet();
                    throw new IllegalStateException("Simulated DB failure");
                }
                eventOrder.add("DB_UPDATE");
                super.updatePosition(characterId, zoneId, posX, posY, posZ, rotYaw, rotPitch);
            }
        };

        orderedRepo.records.put(8001L, characterRecord(8001L, 1, "TransferringPlayer", 230, 49.5f, 0f, 50f));
        orderedRepo.records.put(8002L, characterRecord(8002L, 1, "RemainingPlayer", 230, 10.0f, 0f, 10f));

        ZoneSimulationEngine transferEngine = new ZoneSimulationEngine(
                props, spatialGrid, orderedRepo, lineRepo, dispatcher, collisionService,
                new NpcPatrolSimulation(), null, instanceRepo
        );

        EmbeddedChannel chA = new EmbeddedChannel();
        List<Object> outA = new CopyOnWriteArrayList<>();
        ChannelHandlerContext ctxA = (ChannelHandlerContext) Proxy.newProxyInstance(
                ChannelHandlerContext.class.getClassLoader(),
                new Class<?>[]{ChannelHandlerContext.class},
                (proxy, method, args) -> {
                    if ("writeAndFlush".equals(method.getName())) {
                        eventOrder.add(args[0].getClass().getSimpleName());
                        outA.add(args[0]);
                        return null;
                    }
                    if ("channel".equals(method.getName())) {
                        return chA;
                    }
                    return null;
                }
        );

        EmbeddedChannel chB = new EmbeddedChannel();
        List<Object> outB = new CopyOnWriteArrayList<>();
        ChannelHandlerContext ctxB = createProxyContext(chB, outB);

        try {
            transferEngine.start();
            transferEngine.registerSessionEntity(801L, 8001L, ctxA);
            transferEngine.registerSessionEntity(802L, 8002L, ctxB);

            // Enqueue BOTH Player A (triggers zoneline) AND Player B (normal movement) before single tick
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(transferEngine.getEntity(8001L)).isNotNull();
                assertThat(transferEngine.getEntity(8002L)).isNotNull();
            });

            transferEngine.enqueueMovement(ctxA, 801L, 8001L, new PlayerMovementRequest(
                    50.5f, 0f, 50f, 1f, 0f, 0f, 0f, 0f, MovementFlags.FORWARD, 1L));
            transferEngine.enqueueMovement(ctxB, 802L, 8002L, new PlayerMovementRequest(
                    10.5f, 0f, 10f, 1f, 0f, 0f, 0f, 0f, MovementFlags.FORWARD, 1L));

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(outA).anySatisfy(p -> assertThat(p).isInstanceOf(ZoneTransferNotification.class));
                assertThat(outB).anySatisfy(p -> assertThat(p).isInstanceOf(PlayerMovementResponse.class));
            });

            // 1. Both SetSessionZoneControlMessage (first) and ZoneTransferNotification (second) emitted AFTER DB_UPDATE
            assertThat(eventOrder).containsSubsequence("DB_UPDATE", "SetSessionZoneControlMessage", "ZoneTransferNotification");

            SetSessionZoneControlMessage ctrl = outA.stream()
                    .filter(SetSessionZoneControlMessage.class::isInstance)
                    .map(SetSessionZoneControlMessage.class::cast)
                    .findFirst().orElseThrow();
            assertThat(ctrl.zoneId()).isEqualTo(231);
            assertThat(ctrl.host()).isEqualTo("10.20.30.40");
            assertThat(ctrl.port()).isEqualTo(9231);

            ZoneTransferNotification notif = outA.stream()
                    .filter(ZoneTransferNotification.class::isInstance)
                    .map(ZoneTransferNotification.class::cast)
                    .findFirst().orElseThrow();
            assertThat(notif.targetZoneId()).isEqualTo(231);
            assertThat(notif.zoneHost()).isEqualTo("10.20.30.40");
            assertThat(notif.zonePort()).isEqualTo(9231);
            assertThat(notif.spawnX()).isEqualTo(80.0f);
            assertThat(notif.spawnY()).isEqualTo(-2.0f);
            assertThat(notif.spawnZ()).isEqualTo(-140.0f);

            // 2. Player B was still processed in the same tick (continue, not return!)
            PlayerMovementResponse respB = outB.stream()
                    .filter(PlayerMovementResponse.class::isInstance)
                    .map(PlayerMovementResponse.class::cast)
                    .findFirst().orElseThrow();
            assertThat(respB.characterId()).isEqualTo(8002L);

            // 3. DB failure during transfer must NOT ghost-transfer the player
            outA.clear();
            orderedRepo.records.put(8003L, characterRecord(8003L, 1, "FailHero", 230, 49.5f, 0f, 50f));
            failUpdate.set(true);
            transferEngine.registerSessionEntity(803L, 8003L, ctxA);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(transferEngine.getEntity(8003L)).isNotNull()
            );

            transferEngine.enqueueMovement(ctxA, 803L, 8003L, new PlayerMovementRequest(
                    50.5f, 0f, 50f, 1f, 0f, 0f, 0f, 0f, MovementFlags.FORWARD, 2L));
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(failedUpdateAttempts.get()).isGreaterThanOrEqualTo(1);
                assertThat(transferEngine.getEntity(8003L)).isNotNull();
            });
            assertThat(transferEngine.getEntity(8003L).getX()).isEqualTo(49.5f);
            assertThat(outA).noneMatch(p -> p instanceof SetSessionZoneControlMessage || p instanceof ZoneTransferNotification);
        } finally {
            transferEngine.stop();
            chA.close();
            chB.close();
        }
    }

    @Test
    @DisplayName("Actor model safety: removeEntityBySession routes via commandQueue and movementQueue enforces 4096 cap")
    void testCommandQueueDisconnectAndMovementQueueCap() {
        charRepo.records.put(9101L, characterRecord(9101L, 1, "DisconnectHero", 230, 1.0f, 0f, 1.0f));
        EmbeddedChannel ch = new EmbeddedChannel();
        List<Object> out = new CopyOnWriteArrayList<>();
        ChannelHandlerContext ctx = createProxyContext(ch, out);

        try {
            engine.registerSessionEntity(911L, 9101L, ctx);
            engine.tick();
            assertThat(engine.getEntity(9101L)).isNotNull();

            // Calling removeEntityBySession must NOT mutate entities until tick() drains commandQueue
            engine.removeEntityBySession(911L);
            assertThat(engine.getEntity(9101L)).isNotNull();

            engine.tick();
            assertThat(engine.getEntity(9101L)).isNull();
            assertThat(charRepo.flushedBatches).hasSize(1);

            // Movement queue cap: enqueue 4200 movements and verify excess is dropped cleanly
            charRepo.records.put(9102L, characterRecord(9102L, 1, "FloodHero", 230, 0f, 0f, 0f));
            engine.registerSessionEntity(912L, 9102L, ctx);
            engine.tick();

            PlayerMovementRequest req = new PlayerMovementRequest(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, MovementFlags.IDLE, 1L);
            for (int i = 0; i < ZoneSimulationEngine.MAX_MOVEMENT_QUEUE_SIZE + 100; i++) {
                engine.enqueueMovement(ctx, 912L, 9102L, req);
            }
            out.clear();
            engine.tick();
            long responseCount = out.stream().filter(PlayerMovementResponse.class::isInstance).count();
            assertThat(responseCount).isEqualTo(ZoneSimulationEngine.MAX_MOVEMENT_QUEUE_SIZE);
        } finally {
            ch.close();
        }
    }

    private static CharacterRecord characterRecord(long charId, int worldId, String name, int zoneId, float x, float y, float z) {
        return new CharacterRecord(
                1L, charId, worldId, name, (short) 1, (short) 0, (short) 1,
                (short) 1, (short) 1, (short) 1, (short) 1, (short) 1, (short) 0, (short) 0,
                zoneId, x, y, z, 0.0f, 0.0f,
                zoneId, x, y, z, Instant.now()
        );
    }

    private static ChannelHandlerContext createProxyContext(EmbeddedChannel channel, List<Object> outboundPackets) {
        return (ChannelHandlerContext) Proxy.newProxyInstance(
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
    }
}
