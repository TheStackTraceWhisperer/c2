package com.example.zone.engine;

import com.example.config.ZoneServerProperties;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.CharacterStateSnapshot;
import com.example.server.repository.CharacterRepository;
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
        props.setAssetPath("game-asset-pipeline/src/test/resources/dats");
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
    @DisplayName("ZoneSimulationEngine loads authentic town NPCs from NpcTable into spatial grid on startup")
    void testStartupLoadsTownNpcs() {
        engine.start();

        assertThat(engine.getEntityCount()).isGreaterThan(50);

        // Verify known sample NPC from Zone 230
        ZoneEntityState sampleNpc = engine.getEntities().values().stream()
                .filter(ZoneEntityState::isNpc)
                .findFirst()
                .orElse(null);
        assertThat(sampleNpc).isNotNull();
        assertThat(sampleNpc.isNpc()).isTrue();
        assertThat(sampleNpc.getMainJobId()).isEqualTo(JobId.NPC);
        assertThat(sampleNpc.getZoneId()).isEqualTo(230);
        assertThat(sampleNpc.getChannelContext()).isNull();
    }

    @Test
    @DisplayName("Observer receives EntitySpawnRecord for nearby town NPCs on entering AoI, and despawns on leaving")
    void testAoISpawnAndDespawnMessages() {
        engine.start();

        // Find an existing loaded NPC to test proximity against
        ZoneEntityState targetNpc = engine.getEntities().values().stream()
                .filter(ZoneEntityState::isNpc)
                .findFirst()
                .orElse(null);
        assertThat(targetNpc).isNotNull();

        float npcX = targetNpc.getX();
        float npcZ = targetNpc.getZ();
        long npcId = targetNpc.getCharacterId();

        // Setup channel and proxy ChannelHandlerContext for observer player
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

        // Setup player character at NPC's location
        long playerId = 1001L;
        charRepo.records.put(playerId, new CharacterRecord(
                1L, playerId, 1, "TestHero", (short) 1, (short) 0, (short) 1,
                (short) 1, (short) 1, (short) 1, (short) 1, (short) 1, (short) 0, (short) 0,
                230, npcX, 2.0f, npcZ, 0.0f, 0.0f,
                230, npcX, 2.0f, npcZ, Instant.now()
        ));

        // 1. Register player right next to the NPC (< 50m AoI)
        engine.registerSessionEntity(12345L, playerId, ctx);

        // Await AoI broadcast delta packet containing spawn
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            boolean npcSpawned = outboundPackets.stream()
                    .filter(msg -> msg instanceof ZoneStateDeltaMessage)
                    .map(msg -> (ZoneStateDeltaMessage) msg)
                    .filter(d -> d.spawns() != null)
                    .flatMap(d -> d.spawns().stream())
                    .anyMatch(s -> s.characterId() == npcId && s.mainJobId() == JobId.NPC);
            assertThat(npcSpawned).isTrue();
        });

        // 2. Clear captured packets and move player far away (> 200m) outside the AoI
        outboundPackets.clear();
        ZoneEntityState playerEntity = engine.getEntity(playerId);
        assertThat(playerEntity).isNotNull();
        playerEntity.setX(npcX + 300.0f);
        playerEntity.setZ(npcZ + 300.0f);
        spatialGrid.updatePosition(playerId, npcX + 300.0f, npcZ + 300.0f);

        // Await AoI broadcast delta packet containing despawn
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            boolean npcDespawned = outboundPackets.stream()
                    .filter(msg -> msg instanceof ZoneStateDeltaMessage)
                    .map(msg -> (ZoneStateDeltaMessage) msg)
                    .filter(d -> d.despawns() != null)
                    .flatMap(d -> d.despawns().stream())
                    .anyMatch(id -> id == npcId);
            assertThat(npcDespawned).isTrue();
        });

        channel.close();
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
    @DisplayName("NpcPatrolSimulation autonomously navigates waypoints and streams 20 Hz movement updates to observers")
    void testNpcPatrolSimulationAdvancesAndBroadcastsDeltas() {
        engine.start();

        ZoneEntityState targetNpc = engine.getEntities().values().stream()
                .filter(ZoneEntityState::isNpc)
                .findFirst()
                .orElse(null);
        assertThat(targetNpc).isNotNull();

        long npcId = targetNpc.getCharacterId();
        float startX = targetNpc.getX();
        float startY = targetNpc.getY();
        float startZ = targetNpc.getZ();

        // Register patrol route with 2 waypoints
        NpcPatrolRoute route = new NpcPatrolRoute(
                "route-guard-1",
                List.of(
                        new NpcPatrolWaypoint(startX + 10.0f, startY, startZ, 0.5f),
                        new NpcPatrolWaypoint(startX, startY, startZ, 0.5f)
                ),
                true
        );
        engine.getPatrolSimulation().registerPatrol(npcId, route, 4.0f); // 4 m/s walking speed

        // Setup observer player stationed at start position (< 50m AoI)
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

        long playerId = 2001L;
        charRepo.records.put(playerId, new CharacterRecord(
                1L, playerId, 1, "ObserverHero", (short) 1, (short) 0, (short) 1,
                (short) 1, (short) 1, (short) 1, (short) 1, (short) 1, (short) 0, (short) 0,
                230, startX, startY, startZ, 0.0f, 0.0f,
                230, startX, startY, startZ, Instant.now()
        ));
        engine.registerSessionEntity(54321L, playerId, ctx);

        // Await patrol movement update packet
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            boolean hasUpdate = outboundPackets.stream()
                    .filter(msg -> msg instanceof ZoneStateDeltaMessage)
                    .map(msg -> (ZoneStateDeltaMessage) msg)
                    .filter(d -> d.updates() != null)
                    .flatMap(d -> d.updates().stream())
                    .anyMatch(u -> u.characterId() == npcId && (u.vx() != 0f || u.x() != startX));
            assertThat(hasUpdate).isTrue();
        });

        // Verify NPC position has advanced along +X towards waypoint
        assertThat(targetNpc.getX()).isGreaterThan(startX);
        channel.close();
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
}
