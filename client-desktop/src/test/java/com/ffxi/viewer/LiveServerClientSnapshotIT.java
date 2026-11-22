package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.capture.ScreenshotCapture;
import com.example.client.desktop.scene.CameraMode;
import com.example.client.desktop.scene.SceneCamera;
import com.example.client.desktop.scene.WorldScene;
import com.example.client.desktop.state.InGameHudOverlay;
import com.example.client.desktop.state.RemoteActorSynchronizer;
import com.example.client.state.ClientState;
import com.example.common.protocol.MovementFlags;
import com.example.common.time.VanadielTime;
import com.example.config.ZoneServerProperties;
import com.example.server.concurrent.VirtualThreadTaskDispatcher;
import com.example.server.entity.CharacterRecord;
import com.example.server.entity.CharacterStateSnapshot;
import com.example.server.entity.NpcRecord;
import com.example.server.repository.CharacterRepository;
import com.example.server.repository.NpcJdbcRepository;
import com.example.server.repository.ZoneLineJdbcRepository;
import com.example.zone.collision.ZoneCollisionService;
import com.example.zone.dto.ActorAppearance;
import com.example.zone.dto.ActorLookType;
import com.example.zone.dto.PlayerMovementRequest;
import com.example.zone.dto.ZoneStateDeltaMessage;
import com.example.zone.engine.NpcSpawnFilter;
import com.example.zone.engine.ZoneSimulationEngine;
import com.example.zone.patrol.NpcPatrolSimulation;
import com.example.zone.spatial.SpatialGrid;
import com.ffxi.viewer.environment.WeatherPreset;
import com.ffxi.viewer.renderer.FontMojiRenderer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live Server-Client E2E Integration Snapshot Suite.
 *
 * Verifies and visually demonstrates the complete, end-to-end integration of the engine:
 * 1. Authoritative 20 Hz ZoneSimulationEngine initializes with authentic DAT collision and zonelines.
 * 2. Full authentic LandSandBoat NPC dataset is loaded from V2__npc_list.sql + V4__fix_npc_disappear_status_and_grounding.sql and filtered via NpcSpawnFilter.
 * 3. Client session connects, receives authoritative spawn and AoI spatial delta streams with full ActorAppearance.
 * 4. ClientState and RemoteActorSynchronizer synchronize the player ("Adventurer") and nearby authentic town NPCs into WorldScene.
 * 5. Real headless OpenGL 4.6 Core pipeline (WorldScene + ZoneShader + CharacterShader + ShadowMap + SSAO + SkyDome + InGameHudOverlay + FontMojiGlRenderer)
 *    captures live composite 1080p in-engine screenshots across Southern San d'Oria (Zone 230) and Lower Jeuno (Zone 245).
 */
public class LiveServerClientSnapshotIT {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    private static final String PLAYER_NAME = "Adventurer";
    private static final ActorAppearance PLAYER_APPEARANCE = ActorAppearance.forPlayer(1, 2, 1);

    private static final Pattern NPC_SQL_ROW_PATTERN = Pattern.compile(
            "\\((\\d+),\\s*'((?:''|[^'])*)',\\s*([-\\d.]+),\\s*([-\\d.]+),\\s*([-\\d.]+),\\s*(\\d+),\\s*(\\d+),\\s*'([^']*)',\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(?:'([^']*)'|NULL),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(true|false)\\)"
    );

    private static final Pattern V4_DISAPPEAR_BLOCK_PATTERN = Pattern.compile(
            "UPDATE\\s+game\\.npc_list\\s+SET\\s+status\\s*=\\s*'disappear'\\s+WHERE\\s+id\\s+IN\\s*\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern V4_POS_Y_UPDATE_PATTERN = Pattern.compile(
            "UPDATE\\s+game\\.npc_list\\s+SET\\s+pos_y\\s*=\\s*([-0-9.]+)\\s+WHERE\\s+id\\s*=\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static HeadlessGlContext glContext;
    private static WorldScene worldScene;
    private static InGameHudOverlay hudOverlay;

    private static ZoneSimulationEngine serverEngine230;
    private static TestCharacterRepo testCharRepo230;

    static class TestCharacterRepo extends CharacterRepository {
        final Map<Long, CharacterRecord> records = new HashMap<>();

        public TestCharacterRepo() {
            super(null);
        }

        @Override
        public Optional<CharacterRecord> findById(long id) {
            return Optional.ofNullable(records.get(id));
        }

        @Override
        public void flushStateBatch(Collection<CharacterStateSnapshot> snapshots) {}

        @Override
        public void updatePosition(long characterId, int zoneId, float posX, float posY, float posZ, float rotYaw, float rotPitch) {
            CharacterRecord existing = records.get(characterId);
            if (existing != null) {
                records.put(characterId, new CharacterRecord(
                        existing.accountId(), characterId, existing.worldId(), existing.name(),
                        existing.raceId(), existing.gender(), existing.faceId(), existing.hairId(),
                        existing.sizeId(), existing.nationId(), existing.mainJobId(), existing.mainJobLevel(),
                        existing.subJobId(), existing.subJobLevel(), zoneId, posX, posY, posZ,
                        rotYaw, rotPitch, existing.hpZoneId(), existing.hpPosX(), existing.hpPosY(),
                        existing.hpPosZ(), Instant.now()
                ));
            }
        }
    }

    private record ClientSessionHarness(
            ClientState clientState,
            List<Object> outboundPackets,
            ChannelHandlerContext ctx
    ) {
        static ClientSessionHarness create() {
            ClientState state = new ClientState();
            List<Object> packets = new CopyOnWriteArrayList<>();
            EmbeddedChannel channel = new EmbeddedChannel();
            ChannelHandlerContext ctx = (ChannelHandlerContext) Proxy.newProxyInstance(
                    ChannelHandlerContext.class.getClassLoader(),
                    new Class<?>[]{ChannelHandlerContext.class},
                    (proxy, method, args) -> {
                        if ("writeAndFlush".equals(method.getName())) {
                            packets.add(args[0]);
                            return null;
                        }
                        if ("channel".equals(method.getName())) {
                            return channel;
                        }
                        return null;
                    }
            );
            return new ClientSessionHarness(state, packets, ctx);
        }

        void drainDeltas() {
            while (!outboundPackets.isEmpty()) {
                Object packet = outboundPackets.remove(0);
                if (packet instanceof ZoneStateDeltaMessage delta) {
                    clientState.applyZoneStateDelta(delta);
                }
            }
        }
    }

    @BeforeAll
    static void initE2EEnvironment() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required for OpenGL 4.6 Core E2E snapshots (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        glContext = HeadlessGlContext.create();
        worldScene = new WorldScene();
        worldScene.init();
        assertTrue(worldScene.isInitialized(), "WorldScene must initialize in OpenGL 4.6 Core context");
        assertNotNull(worldScene.getFontMojiGlRenderer(), "FontMojiGlRenderer must initialize from retail ROM/0/1.DAT");

        worldScene.setManualTimeOverride(true);
        worldScene.setManualTimeHour(12.0f);
        worldScene.setWeatherPreset(WeatherPreset.FINE);
        worldScene.loadZone(FFXI_ROOT, 230);
        assertNotNull(worldScene.getLoadedZone(), "Zone 230 must be loaded in WorldScene");

        hudOverlay = new InGameHudOverlay();

        // Initialize Live Authoritative Zone 230 Server with V2 + V4 NPC dataset
        List<NpcRecord> zone230Npcs = loadNpcRecordsFromMigrationSql(230);
        long spawnable230 = zone230Npcs.stream().filter(NpcSpawnFilter::shouldSpawn).count();
        assertThat(spawnable230).as("Zone 230 filtered spawnable NPC count").isEqualTo(166L);

        testCharRepo230 = new TestCharacterRepo();
        serverEngine230 = createAndStartZoneEngine(230, 9000, zone230Npcs, testCharRepo230);
    }

    @AfterAll
    static void tearDownE2E() {
        if (serverEngine230 != null) {
            serverEngine230.stop();
        }
        if (worldScene != null) {
            worldScene.close();
        }
        if (glContext != null) {
            glContext.close();
        }
    }

    @Test
    @DisplayName("E2E Integration Scenario 1: Spawn at Northern San d'Oria Archway, receive server delta, render live scene")
    void testE2ESpawnAndRender() throws Exception {
        long charId = 1001L;
        long sessionId = 99001L;
        ClientSessionHarness harness = ClientSessionHarness.create();

        try {
            // Adventurer spawns at (0.0, -1.999, 41.0) facing North (0 deg) toward Aubejart & Ephauge
            testCharRepo230.records.put(charId, createAdventurerRecord(charId, 230, 0.0f, -1.999f, 41.0f, 0.0f));
            serverEngine230.registerSessionEntity(sessionId, charId, harness.ctx());

            await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
                harness.drainDeltas();
                assertThat(harness.clientState().getRemoteEntities()).containsKeys(17719298L, 17719299L);
            });

            ClientState clientState = harness.clientState();
            var aubejart = clientState.getRemoteEntities().get(17719298L);
            var ephauge = clientState.getRemoteEntities().get(17719299L);

            assertThat(aubejart.name()).isEqualTo("Aubejart");
            assertThat(aubejart.appearance().lookType()).isEqualTo(ActorLookType.EQUIPPED);
            assertThat(aubejart.appearance().raceId()).isEqualTo(3);
            assertThat(aubejart.appearance().faceId()).isEqualTo(8);
            assertThat(aubejart.appearance().head()).isEqualTo(28);
            assertThat(aubejart.appearance().body()).isEqualTo(28);

            assertThat(ephauge.name()).isEqualTo("Ephauge");
            assertThat(ephauge.appearance().lookType()).isEqualTo(ActorLookType.EQUIPPED);
            assertThat(ephauge.appearance().raceId()).isEqualTo(3);
            assertThat(ephauge.appearance().faceId()).isEqualTo(0);
            assertThat(ephauge.appearance().head()).isEqualTo(28);
            assertThat(ephauge.appearance().body()).isEqualTo(28);

            assertThat(clientState.getRemoteEntities().values())
                    .extracting(ClientState.RemoteEntityState::name)
                    .doesNotContain("Aldo");

            Path outPath = renderAndSaveLiveE2EFrame(
                    230, sessionId, charId, clientState,
                    0.0f, -1.999f, 41.0f, 0.0f,
                    MovementFlags.IDLE, 0.0f, 0.10f,
                    0.0f, -1.999f, 41.0f,
                    0.0f, 16.0f, 6.0f,
                    "World Spawn at Northern San d'Oria Archway | Authoritative Spatial Sync",
                    "Client connected to authoritative 20 Hz ZoneSimulationEngine #230. Streamed Adventurer spawn and equipped gate sentries Aubejart & Ephauge.",
                    Set.of(17719298L, 17719299L),
                    "e2e_live_01_south_sandoria_world_spawn.png"
            );

            assertTrue(Files.isRegularFile(outPath) && Files.size(outPath) > 50000);
            System.out.printf("[E2E-LIVE] Saved GL4.6 snapshot -> %s (%,d bytes, %d world actors)%n",
                    outPath.getFileName(), Files.size(outPath), worldScene.getActors().size());
        } finally {
            serverEngine230.removeEntityBySession(sessionId);
        }
    }

    @Test
    @DisplayName("E2E Integration Scenario 2: Walk in Southern San d'Oria Market Square near Raminel, Corua, and Machielle")
    void testE2EMovementAndRender() throws Exception {
        long charId = 1002L;
        long sessionId = 99002L;
        ClientSessionHarness harness = ClientSessionHarness.create();

        try {
            // Adventurer spawns in Southern San d'Oria Market Square and walks beside grounded child Raminel toward Machielle and Corua
            testCharRepo230.records.put(charId, createAdventurerRecord(charId, 230, -71.0f, 1.999f, -7.2f, 200.0f));
            serverEngine230.registerSessionEntity(sessionId, charId, harness.ctx());

            await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(serverEngine230.getEntity(charId)).isNotNull();
            });

            PlayerMovementRequest moveReq = new PlayerMovementRequest(
                    -71.4f, 1.999f, -8.4f,
                    -0.4f, 0.0f, -1.2f,
                    200.0f, 0.0f,
                    MovementFlags.FORWARD,
                    100L
            );
            serverEngine230.enqueueMovement(harness.ctx(), sessionId, charId, moveReq);

            await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
                var ent = serverEngine230.getEntity(charId);
                assertThat(ent).isNotNull();
                assertThat(ent.getX()).isCloseTo(-71.4f, within(0.5f));
                assertThat(ent.getZ()).isCloseTo(-8.4f, within(0.5f));
                harness.drainDeltas();
                assertThat(harness.clientState().getRemoteEntities()).containsKeys(17719304L, 17719303L, 17719326L);
            });

            ClientState clientState = harness.clientState();
            var corua = clientState.getRemoteEntities().get(17719304L);
            var machielle = clientState.getRemoteEntities().get(17719303L);
            var raminel = clientState.getRemoteEntities().get(17719326L);

            assertThat(corua.name()).isEqualTo("Corua");
            assertThat(corua.appearance().lookType()).isEqualTo(ActorLookType.STANDARD);
            assertThat(corua.appearance().modelId()).isEqualTo(142);

            assertThat(machielle.name()).isEqualTo("Machielle");
            assertThat(machielle.appearance().lookType()).isEqualTo(ActorLookType.EQUIPPED);
            assertThat(machielle.appearance().raceId()).isEqualTo(4);
            assertThat(machielle.appearance().faceId()).isEqualTo(4);
            assertThat(machielle.appearance().body()).isEqualTo(19);

            assertThat(raminel.name()).isEqualTo("Raminel");
            assertThat(raminel.appearance().lookType()).isEqualTo(ActorLookType.STANDARD);
            assertThat(raminel.appearance().modelId()).isEqualTo(2474);
            assertThat(raminel.y()).isCloseTo(2.000f, within(0.05f));
            assertThat(worldScene.getCharacterAssetCache().getOrLoadActor(FFXI_ROOT, raminel.appearance()).modelScale())
                    .isCloseTo(0.95f, within(0.01f));

            var playerState = serverEngine230.getEntity(charId);
            float px = playerState.getX();
            float py = playerState.getY();
            float pz = playerState.getZ();

            // Frame Market Square from the open south cobblestone plaza looking NNE so Raminel (child) and Adventurer
            // stand at comparable depth with Machielle (left-back) and Corua (right) cleanly visible
            Path outPath = renderAndSaveLiveE2EFrame(
                    230, sessionId, charId, clientState,
                    px, py, pz, 200.0f,
                    MovementFlags.FORWARD, 2.5f, 0.25f,
                    -69.8f, 1.999f, -7.2f,
                    10.0f, 11.0f, 9.5f,
                    "Southern San d'Oria Market Square | Multi-Model NPC Streaming",
                    "Grounded child Raminel (#2474, 0.95x), Adventurer, equipped ElvaanF Machielle, and Corua (#142).",
                    Set.of(17719304L, 17719303L, 17719326L),
                    "e2e_live_02_south_sandoria_npc_plaza.png"
            );

            assertThat(worldScene.getActor(17719326L).getVisualModel().getCharacter().modelScale())
                    .isCloseTo(0.95f, within(0.01f));
            assertTrue(Files.isRegularFile(outPath) && Files.size(outPath) > 50000);
            System.out.printf("[E2E-LIVE] Saved GL4.6 snapshot -> %s (%,d bytes, %d world actors)%n",
                    outPath.getFileName(), Files.size(outPath), worldScene.getActors().size());
        } finally {
            serverEngine230.removeEntityBySession(sessionId);
        }
    }

    @Test
    @DisplayName("E2E Integration Scenario 3: Rendezvous with canonical NPC Ceraule (ID 17719297), Rolandienne, and Repairer Moogle")
    void testE2ECerauleRendezvousAndRender() throws Exception {
        long charId = 1003L;
        long sessionId = 99003L;
        ClientSessionHarness harness = ClientSessionHarness.create();

        try {
            // Adventurer stands at (-84.5, 1.999, -32.8) facing SW (222 deg) toward Ceraule at (-86.077, 1.999, -34.793)
            testCharRepo230.records.put(charId, createAdventurerRecord(charId, 230, -84.5f, 1.999f, -32.8f, 222.0f));
            serverEngine230.registerSessionEntity(sessionId, charId, harness.ctx());

            await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
                harness.drainDeltas();
                assertThat(harness.clientState().getRemoteEntities()).containsKeys(17719297L, 17719638L, 17720019L);
            });

            ClientState clientState = harness.clientState();
            assertThat(clientState.getRemoteEntities()).doesNotContainKey(17719370L);
            assertThat(clientState.getRemoteEntities().values())
                    .extracting(ClientState.RemoteEntityState::name)
                    .doesNotContain("Claidie");

            var ceraule = clientState.getRemoteEntities().get(17719297L);
            assertThat(ceraule.name()).isEqualTo("Ceraule");
            assertThat(ceraule.appearance().lookType()).isEqualTo(ActorLookType.EQUIPPED);
            assertThat(ceraule.appearance().raceId()).isEqualTo(3);
            assertThat(ceraule.appearance().faceId()).isEqualTo(2);
            assertThat(ceraule.appearance().head()).isEqualTo(12);
            assertThat(ceraule.appearance().body()).isEqualTo(12);

            var rolandienne = clientState.getRemoteEntities().get(17719638L);
            assertThat(rolandienne.name()).isEqualTo("Rolandienne");
            assertThat(rolandienne.appearance().lookType()).isEqualTo(ActorLookType.EQUIPPED);

            var repairerMoogle = clientState.getRemoteEntities().get(17720019L);
            assertThat(repairerMoogle.name()).isEqualTo("Repairer Moogle");
            assertThat(repairerMoogle.appearance().lookType()).isEqualTo(ActorLookType.STANDARD);
            assertThat(repairerMoogle.appearance().modelId()).isEqualTo(82);

            // Frame Adventurer (left), Ceraule (center, standing idle), and Watch/Moogle plaza NPCs in the background
            Path outPath = renderAndSaveLiveE2EFrame(
                    230, sessionId, charId, clientState,
                    -84.5f, 1.999f, -32.8f, 222.0f,
                    MovementFlags.IDLE, 0.0f, 0.10f,
                    -86.2f, 1.999f, -34.2f,
                    185.0f, 15.0f, 5.8f,
                    "Ceraule Rendezvous | Canonical Equipped & Standard NPCs",
                    "Adventurer meeting Ceraule (#17719297, ElvaanM armor 12) with Rolandienne & Repairer Moogle (#82) via Zone #230 AoI.",
                    Set.of(17719297L, 17719638L, 17720019L),
                    "e2e_live_03_south_sandoria_ceraule_rendezvous.png"
            );

            assertTrue(Files.isRegularFile(outPath) && Files.size(outPath) > 50000);
            System.out.printf("[E2E-LIVE] Saved GL4.6 snapshot -> %s (%,d bytes, %d world actors)%n",
                    outPath.getFileName(), Files.size(outPath), worldScene.getActors().size());
        } finally {
            serverEngine230.removeEntityBySession(sessionId);
        }
    }

    @Test
    @DisplayName("E2E Integration Scenario 4: Lower Jeuno (Zone 245) rendezvous with canonical standard NPC Aldo (model 78)")
    void testE2ELowerJeunoAldoRendezvous() throws Exception {
        List<NpcRecord> zone245Npcs = loadNpcRecordsFromMigrationSql(245);
        long spawnable245 = zone245Npcs.stream().filter(NpcSpawnFilter::shouldSpawn).count();
        assertThat(spawnable245).as("Zone 245 filtered spawnable NPC count").isEqualTo(123L);

        TestCharacterRepo charRepo245 = new TestCharacterRepo();
        ZoneSimulationEngine engine245 = createAndStartZoneEngine(245, 9001, zone245Npcs, charRepo245);
        long charId = 1004L;
        long sessionId = 99004L;
        ClientSessionHarness harness = ClientSessionHarness.create();

        try {
            // Place Adventurer ~2.4m NW of Aldo (21.049, 3.899, -61.530) inside Aldo's Room facing SE (142 deg) toward Aldo
            charRepo245.records.put(charId, createAdventurerRecord(charId, 245, 19.5f, 3.899f, -59.5f, 142.0f));
            engine245.registerSessionEntity(sessionId, charId, harness.ctx());

            await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
                harness.drainDeltas();
                assertThat(harness.clientState().getRemoteEntities()).containsKey(17780743L);
            });

            ClientState clientState245 = harness.clientState();
            var aldo = clientState245.getRemoteEntities().get(17780743L);
            assertThat(aldo.name()).isEqualTo("Aldo");
            assertThat(aldo.appearance().lookType()).isEqualTo(ActorLookType.STANDARD);
            assertThat(aldo.appearance().modelId()).isEqualTo(78);

            // Frame Adventurer and Aldo from inside Aldo's Room (NW interior looking SE across the wooden floor)
            Path outPath = renderAndSaveLiveE2EFrame(
                    245, sessionId, charId, clientState245,
                    19.5f, 3.899f, -59.5f, 142.0f,
                    MovementFlags.IDLE, 0.0f, 0.10f,
                    20.3f, 3.899f, -60.6f,
                    125.0f, 15.0f, 3.9f,
                    "Lower Jeuno Rendezvous | Canonical Standard NPC Aldo (#78)",
                    "ZoneSimulationEngine #245 loaded 123 authentic Lower Jeuno NPCs. Adventurer facing Tenshodo leader Aldo (model #78).",
                    Set.of(17780743L),
                    "e2e_live_04_lower_jeuno_aldo.png"
            );

            assertTrue(Files.isRegularFile(outPath) && Files.size(outPath) > 50000);
            System.out.printf("[E2E-LIVE] Saved GL4.6 snapshot -> %s (%,d bytes, %d world actors)%n",
                    outPath.getFileName(), Files.size(outPath), worldScene.getActors().size());
        } finally {
            engine245.removeEntityBySession(sessionId);
            engine245.stop();
        }
    }

    private static Path renderAndSaveLiveE2EFrame(
            int zoneId,
            long sessionId,
            long charId,
            ClientState clientState,
            float playerX,
            float playerY,
            float playerZ,
            float playerYaw,
            int moveFlags,
            float moveSpeed,
            float stepDt,
            float targetX,
            float targetY,
            float targetZ,
            float camYaw,
            float camPitch,
            float camDist,
            String title,
            String subtitle,
            Set<Long> requiredEntityIds,
            String outputFilename
    ) throws IOException {
        if (worldScene.getCurrentZoneId() != zoneId) {
            worldScene.loadZone(FFXI_ROOT, zoneId);
        }

        var localPlayer = new com.example.client.desktop.scene.SceneActor(
                charId, PLAYER_NAME, com.example.client.desktop.scene.SceneActor.ActorType.PLAYER
        );
        localPlayer.setVisualModel(worldScene.createCharacterInstance(FFXI_ROOT, PLAYER_APPEARANCE));
        worldScene.setLocalPlayer(localPlayer);

        long now = System.currentTimeMillis();
        clientState.updatePosition(playerX, playerY, playerZ, playerYaw, 0.0f, 0L);
        RemoteActorSynchronizer.syncActors(worldScene, clientState, FFXI_ROOT, moveSpeed, moveFlags, now);

        for (Long reqId : requiredEntityIds) {
            var actor = worldScene.getActor(reqId);
            assertNotNull(actor, "Required streamed actor missing from WorldScene: " + reqId);
            assertNotNull(actor.getVisualModel(), "Required streamed actor missing visual model: " + reqId);
        }

        SceneCamera camera = worldScene.getCamera();
        camera.setMode(CameraMode.FREE_ORBIT);
        camera.setFov(48.0f);
        camera.setNearPlane(0.2f);
        camera.setFarPlane(2000.0f);
        camera.setTarget(targetX, targetY, targetZ);
        camera.setTargetOffsetY(1.0f);
        camera.setYaw(camYaw);
        camera.setPitch(camPitch);
        camera.setDistance(camDist);

        worldScene.update(stepDt, VanadielTime.ofHour(12.0f));

        float aspect = (float) WIDTH / (float) HEIGHT;
        float[] proj = new float[16];
        float[] view = new float[16];
        camera.getProjectionMatrix(aspect).get(proj);
        camera.getViewMatrix().get(view);

        BufferedImage snapshot = worldScene.captureFrame(WIDTH, HEIGHT, () ->
                hudOverlay.renderNameplates(
                        clientState,
                        PLAYER_NAME,
                        proj,
                        view,
                        WIDTH,
                        HEIGHT,
                        now,
                        worldScene,
                        worldScene.getFontMojiGlRenderer(),
                        null
                )
        );

        List<String> nearbyNames = new ArrayList<>();
        clientState.getRemoteEntities().values().stream()
                .sorted(Comparator.comparingDouble(e -> Math.hypot(e.x() - targetX, e.z() - targetZ)))
                .limit(6)
                .forEach(e -> nearbyNames.add(e.name()));
        String npcSummary = nearbyNames.isEmpty() ? "none nearby" : String.join(", ", nearbyNames);

        Graphics2D g2 = snapshot.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        renderLiveIntegrationHud(
                snapshot, g2, worldScene.getFontMojiGlRenderer().getFont(), camera,
                zoneId, sessionId, PLAYER_NAME,
                playerX, playerY, playerZ, playerYaw,
                title, subtitle, clientState.getRemoteEntities().size(), npcSummary
        );
        g2.dispose();

        return ScreenshotCapture.saveBufferedImage(snapshot, outputFilename);
    }

    private static CharacterRecord createAdventurerRecord(
            long charId, int zoneId, float x, float y, float z, float yaw
    ) {
        return new CharacterRecord(
                1L, charId, 1, PLAYER_NAME, (short) 1, (short) 0, (short) 2, (short) 1,
                (short) 1, (short) 1, (short) 1, (short) 1, (short) 0, (short) 0,
                zoneId, x, y, z, yaw, 0.0f,
                zoneId, x, y, z, Instant.now()
        );
    }

    private static ZoneSimulationEngine createAndStartZoneEngine(
            int zoneId,
            int port,
            List<NpcRecord> zoneNpcs,
            TestCharacterRepo charRepo
    ) {
        ZoneServerProperties props = new ZoneServerProperties();
        props.setHost("127.0.0.1");
        props.setPort(port);
        props.setZoneId(zoneId);
        props.setCollisionEnabled(true);
        props.setMaxStepHeight(1.2f);
        props.setMaxJumpHeight(3.5f);

        SpatialGrid spatialGrid = new SpatialGrid();
        ZoneLineJdbcRepository zoneLineRepo = (ZoneLineJdbcRepository) Proxy.newProxyInstance(
                ZoneLineJdbcRepository.class.getClassLoader(),
                new Class<?>[]{ZoneLineJdbcRepository.class},
                (proxy, method, args) -> List.of()
        );

        VirtualThreadTaskDispatcher dispatcher = new VirtualThreadTaskDispatcher();
        ZoneCollisionService collisionService = new ZoneCollisionService(props);
        collisionService.init();

        NpcPatrolSimulation patrolSim = new NpcPatrolSimulation();
        NpcJdbcRepository npcRepo = (NpcJdbcRepository) Proxy.newProxyInstance(
                NpcJdbcRepository.class.getClassLoader(),
                new Class<?>[]{NpcJdbcRepository.class},
                (proxy, method, args) -> {
                    if ("findByZoneId".equals(method.getName()) && Integer.valueOf(zoneId).equals(args[0])) {
                        return zoneNpcs;
                    }
                    return List.of();
                }
        );

        ZoneSimulationEngine engine = new ZoneSimulationEngine(
                props, spatialGrid, charRepo, zoneLineRepo, dispatcher, collisionService, patrolSim, npcRepo
        );
        engine.start();
        return engine;
    }

    private static List<NpcRecord> loadNpcRecordsFromMigrationSql(int zoneId) throws IOException {
        String v2Sql = readMigrationSqlResource("V2__npc_list.sql");
        String v4Sql = readMigrationSqlResource("V4__fix_npc_disappear_status_and_grounding.sql");

        Set<Long> disappearIds = new HashSet<>();
        Matcher disappearMatcher = V4_DISAPPEAR_BLOCK_PATTERN.matcher(v4Sql);
        while (disappearMatcher.find()) {
            for (String token : disappearMatcher.group(1).split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    disappearIds.add(Long.parseLong(trimmed));
                }
            }
        }

        Map<Long, Float> updatedPosY = new HashMap<>();
        Matcher posYMatcher = V4_POS_Y_UPDATE_PATTERN.matcher(v4Sql);
        while (posYMatcher.find()) {
            float newY = Float.parseFloat(posYMatcher.group(1));
            long id = Long.parseLong(posYMatcher.group(2));
            updatedPosY.put(id, newY);
        }

        List<NpcRecord> result = new ArrayList<>();
        Matcher m = NPC_SQL_ROW_PATTERN.matcher(v2Sql);
        while (m.find()) {
            int rowZoneId = Integer.parseInt(m.group(7));
            if (rowZoneId != zoneId) {
                continue;
            }
            long id = Long.parseLong(m.group(1));
            String name = m.group(2).replace("''", "'");
            float posX = Float.parseFloat(m.group(3));
            float posY = updatedPosY.getOrDefault(id, Float.parseFloat(m.group(4)));
            float posZ = Float.parseFloat(m.group(5));
            short posRot = (short) Integer.parseInt(m.group(6));
            String status = disappearIds.contains(id) ? "disappear" : m.group(8);
            int entityFlags = Integer.parseInt(m.group(9));
            short animationSub = (short) Integer.parseInt(m.group(10));
            short namePrefix = (short) Integer.parseInt(m.group(11));
            short moving = (short) Integer.parseInt(m.group(12));
            String lookType = m.group(13);
            short raceId = (short) Integer.parseInt(m.group(14));
            short faceId = (short) Integer.parseInt(m.group(15));
            int head = Integer.parseInt(m.group(16));
            int body = Integer.parseInt(m.group(17));
            int hands = Integer.parseInt(m.group(18));
            int legs = Integer.parseInt(m.group(19));
            int feet = Integer.parseInt(m.group(20));
            int mainWeapon = Integer.parseInt(m.group(21));
            int subShield = Integer.parseInt(m.group(22));
            int ranged = Integer.parseInt(m.group(23));
            int modelId = Integer.parseInt(m.group(24));
            boolean widescan = Boolean.parseBoolean(m.group(25));

            result.add(new NpcRecord(
                    id, name, posX, posY, posZ, posRot, rowZoneId, status,
                    entityFlags, animationSub, namePrefix, moving, lookType,
                    raceId, faceId, head, body, hands, legs, feet,
                    mainWeapon, subShield, ranged, modelId, widescan
            ));
        }
        assertThat(result).as("Loaded SQL NPC records for zone " + zoneId).isNotEmpty();
        return result;
    }

    private static String readMigrationSqlResource(String filename) throws IOException {
        String resourcePath = "db/migration/" + filename;
        try (InputStream in = LiveServerClientSnapshotIT.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        List<Path> candidates = List.of(
                Path.of("../platform-modulith/src/main/resources", resourcePath),
                Path.of("platform-modulith/src/main/resources", resourcePath)
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("Unable to locate " + resourcePath + " on classpath or relative paths");
    }

    private static void renderLiveIntegrationHud(
            BufferedImage dest, Graphics2D g, FontMojiRenderer fontMoji, SceneCamera cam,
            int zoneId, long sessionId, String playerName,
            float charX, float charY, float charZ, float charYaw,
            String title, String subtitle, int activeNpcCount, String nearbyNpcSummary
    ) {
        g.setColor(new Color(10, 16, 26, 230));
        g.fillRoundRect(24, 20, WIDTH - 48, 120, 12, 12);
        g.setColor(new Color(40, 75, 120));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(24, 20, WIDTH - 48, 120, 12, 12);

        drawText(dest, fontMoji, title, 40, 32, new Color(255, 215, 80), 2);
        drawText(dest, fontMoji, subtitle, 40, 64, new Color(200, 220, 240), 1);

        String tele1 = String.format(
                "SERVER: Authoritative 20 Hz ZoneSimulationEngine #%d | QUIC State: IN_ZONE | Session #%d | OpenGL 4.6 Core",
                zoneId, sessionId
        );
        String tele2 = String.format(
                "SPATIAL AoI: %d Authentic LSB NPCs Streamed to ClientState (Nearby: %s)",
                activeNpcCount, nearbyNpcSummary
        );
        String tele3 = String.format(
                "AVATAR: %s @ (%5.1f, %5.2f, %5.1f) | Facing=%5.1f deg [%s] | Cam: Yaw=%5.1f deg Pitch=%5.1f deg Dist=%4.1fm",
                playerName, charX, charY, charZ, charYaw, resolveCardinal(charYaw), cam.getYaw(), cam.getPitch(), cam.getDistance()
        );

        drawText(dest, fontMoji, tele1, 40, 84, new Color(130, 210, 255), 1);
        drawText(dest, fontMoji, tele2, 40, 102, new Color(120, 240, 160), 1);
        drawText(dest, fontMoji, tele3, 40, 120, new Color(225, 235, 250), 1);

        drawCompassRose(dest, g, fontMoji, WIDTH - 80, 80, cam.getYaw());
    }

    private static void drawCompassRose(BufferedImage dest, Graphics2D g, FontMojiRenderer fontMoji, int cx, int cy, float camYaw) {
        int r = 36;
        g.setColor(new Color(15, 22, 32, 220));
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
        g.setColor(new Color(70, 110, 160));
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(cx - r, cy - r, r * 2, r * 2);

        double needleAngle = Math.toRadians(-camYaw);
        int nx = cx + (int) (r * 0.75 * Math.sin(needleAngle));
        int ny = cy - (int) (r * 0.75 * Math.cos(needleAngle));

        g.setColor(new Color(255, 60, 60));
        g.setStroke(new BasicStroke(2.5f));
        g.drawLine(cx, cy, nx, ny);

        drawText(dest, fontMoji, "N", nx - 4, ny - 6, Color.YELLOW, 1);
        g.setColor(Color.WHITE);
        g.fillOval(cx - 3, cy - 3, 6, 6);
    }

    private static void drawText(BufferedImage dest, FontMojiRenderer fontMoji, String text, int startX, int startY, Color color, int scale) {
        if (text == null || text.isEmpty()) return;
        if (fontMoji != null) {
            fontMoji.drawString(dest, text, startX, startY, color, scale);
        } else {
            Graphics2D g = dest.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12 * scale));
            g.setColor(Color.BLACK);
            g.drawString(text, startX + 1, startY + 12 * scale + 1);
            g.setColor(color);
            g.drawString(text, startX, startY + 12 * scale);
            g.dispose();
        }
    }

    private static String resolveCardinal(float yaw) {
        float y = (yaw % 360.0f + 360.0f) % 360.0f;
        if (y >= 315.0f || y < 45.0f) return "NORTH";
        if (y >= 45.0f && y < 135.0f) return "EAST";
        if (y >= 135.0f && y < 225.0f) return "SOUTH";
        return "WEST";
    }
}
