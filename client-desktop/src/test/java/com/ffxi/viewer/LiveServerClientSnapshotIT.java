package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.camera.PolarCamera;
import com.example.client.state.ClientState;
import com.example.common.protocol.MovementFlags;
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
import com.ffxi.export.TextureDecoder;
import com.ffxi.model.*;
import com.ffxi.viewer.model.*;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import com.ffxi.viewer.renderer.FontMojiRenderer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.List;
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
 * 4. ClientState tracks the player ("Adventurer") and nearby authentic town NPCs with their real models.
 * 5. Headless software rasterizer captures live, composite 1080p in-engine screenshots across
 *    Southern San d'Oria (Zone 230) and Lower Jeuno (Zone 245).
 */
public class LiveServerClientSnapshotIT {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();
    private static final Path OUTPUT_DIR = Path.of("target/snapshots");

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

    private static FontMojiRenderer fontMoji;
    private static ActorRenderCache actorRenderCache;
    private static CachedActorRender playerRender;
    private static Matrix4f[] playerWalkPose;

    private static LoadedZone loadedZone230;
    private static Map<String, DecodedTextureBuffer> decodedZone230Textures;
    private static ZoneSimulationEngine serverEngine230;
    private static TestCharacterRepo testCharRepo230;

    private record DecodedTextureBuffer(int width, int height, byte[] rgba) {}

    private record CachedActorRender(
            LoadedCharacter character,
            Map<String, DecodedTextureBuffer> textures,
            Matrix4f[] idlePose,
            float nameplateHeight
    ) {}

    private record SceneActor(
            LoadedCharacter character,
            Map<String, DecodedTextureBuffer> textures,
            Matrix4f[] jointMatrices,
            float nameplateHeight,
            float x, float y, float z, float yaw,
            String name, Color nameColor
    ) {}

    private static final class ActorRenderCache {
        private final Map<CharacterAssetCache.AppearanceCacheKey, CachedActorRender> cache = new HashMap<>();

        CachedActorRender getOrLoad(ActorAppearance appearance) throws IOException {
            CharacterAssetCache.AppearanceCacheKey key = CharacterAssetCache.AppearanceCacheKey.from(appearance);
            CachedActorRender existing = cache.get(key);
            if (existing != null) {
                return existing;
            }
            LoadedCharacter loaded = CharacterModelLoader.loadActor(FFXI_ROOT, appearance);
            Map<String, DecodedTextureBuffer> textures = decodeCharacterTextures(loaded);

            AuthenticLocomotionController idleCtrl = new AuthenticLocomotionController(
                    loaded.skeleton(), loaded.slot0Animations(), loaded.slot1Animations()
            );
            idleCtrl.update(0.1f, MovementFlags.IDLE, 0.0f);
            Matrix4f[] idlePose = idleCtrl.computePoseMatrices();

            float nameplateHeight = computeNameplateHeight(loaded, idlePose);
            CachedActorRender rendered = new CachedActorRender(loaded, textures, idlePose, nameplateHeight);
            cache.put(key, rendered);
            return rendered;
        }
    }

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
        Files.createDirectories(OUTPUT_DIR);
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        // 1. Initialize Font typography
        try {
            Path rom01 = FFXI_ROOT.resolve("ROM/0/1.DAT");
            if (Files.exists(rom01)) {
                fontMoji = FontMojiRenderer.load(FFXI_ROOT);
            }
        } catch (Exception ignored) {}

        // 2. Initialize multi-model ActorRenderCache and load local player ("Adventurer", HumeM face 2)
        actorRenderCache = new ActorRenderCache();
        playerRender = actorRenderCache.getOrLoad(PLAYER_APPEARANCE);
        assertNotNull(playerRender.character(), "Adventurer player model must be loaded");

        AuthenticLocomotionController walkCtrl = new AuthenticLocomotionController(
                playerRender.character().skeleton(),
                playerRender.character().slot0Animations(),
                playerRender.character().slot1Animations()
        );
        walkCtrl.update(0.20f, MovementFlags.FORWARD, 2.5f);
        playerWalkPose = walkCtrl.computePoseMatrices();

        // 3. Load authentic Southern San d'Oria (Zone 230) geometry
        loadedZone230 = ZoneModelLoader.loadZone(FFXI_ROOT, 230);
        assertNotNull(loadedZone230, "Zone 230 must be loaded");
        decodedZone230Textures = decodeAllZoneTextures(loadedZone230);

        // 4. Initialize Live Authoritative Zone 230 Server with V2 + V4 NPC dataset
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

            PolarCamera camera = new PolarCamera();
            camera.setTarget(0.0f, -1.999f, 41.0f);
            camera.setTargetOffsetY(1.0f);
            camera.setYaw(0.0f);
            camera.setPitch(16.0f);
            camera.setDistance(6.0f);

            SceneActor localPlayer = new SceneActor(
                    playerRender.character(), playerRender.textures(), playerRender.idlePose(),
                    playerRender.nameplateHeight(),
                    0.0f, -1.999f, 41.0f, 0.0f,
                    PLAYER_NAME, new Color(140, 220, 255)
            );
            List<SceneActor> actors = buildStreamedSceneActors(
                    localPlayer, clientState, camera.getTargetX(), camera.getTargetZ(), 35.0f,
                    Set.of(17719298L, 17719299L)
            );

            BufferedImage snapshot = renderLiveE2EFrame(
                    230, sessionId, loadedZone230, decodedZone230Textures, camera, actors,
                    "World Spawn at Northern San d'Oria Archway | Authoritative Spatial Sync",
                    "Client connected to authoritative 20 Hz ZoneSimulationEngine #230. Streamed Adventurer spawn and equipped gate sentries Aubejart & Ephauge.",
                    clientState.getRemoteEntities().size()
            );

            File outFile = OUTPUT_DIR.resolve("e2e_live_01_south_sandoria_world_spawn.png").toFile();
            ImageIO.write(snapshot, "PNG", outFile);
            assertTrue(outFile.exists() && outFile.length() > 50000);
            System.out.printf("[E2E-LIVE] Saved snapshot -> %s (%,d bytes, %d rendered actors)%n",
                    outFile.getName(), outFile.length(), actors.size());
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
            assertThat(actorRenderCache.getOrLoad(raminel.appearance()).character().modelScale())
                    .isCloseTo(0.95f, within(0.01f));

            var playerState = serverEngine230.getEntity(charId);
            float px = playerState.getX();
            float py = playerState.getY();
            float pz = playerState.getZ();

            // Frame Market Square from the open south cobblestone plaza looking NNE so Raminel (child) and Adventurer
            // stand at comparable depth with Machielle (left-back) and Corua (right) cleanly visible
            PolarCamera camera = new PolarCamera();
            camera.setTarget(-69.8f, 1.999f, -7.2f);
            camera.setTargetOffsetY(1.0f);
            camera.setYaw(10.0f);
            camera.setPitch(11.0f);
            camera.setDistance(9.5f);

            SceneActor localPlayer = new SceneActor(
                    playerRender.character(), playerRender.textures(), playerWalkPose,
                    playerRender.nameplateHeight(),
                    px, py, pz, 200.0f,
                    PLAYER_NAME, new Color(140, 220, 255)
            );
            List<SceneActor> actors = buildStreamedSceneActors(
                    localPlayer, clientState, camera.getTargetX(), camera.getTargetZ(), 35.0f,
                    Set.of(17719304L, 17719303L, 17719326L)
            );

            BufferedImage snapshot = renderLiveE2EFrame(
                    230, sessionId, loadedZone230, decodedZone230Textures, camera, actors,
                    "Southern San d'Oria Market Square | Multi-Model NPC Streaming",
                    "Grounded child Raminel (#2474, 0.95x), Adventurer, equipped ElvaanF Machielle, and Corua (#142).",
                    clientState.getRemoteEntities().size()
            );

            File outFile = OUTPUT_DIR.resolve("e2e_live_02_south_sandoria_npc_plaza.png").toFile();
            ImageIO.write(snapshot, "PNG", outFile);
            assertTrue(outFile.exists() && outFile.length() > 50000);
            System.out.printf("[E2E-LIVE] Saved snapshot -> %s (%,d bytes, %d rendered actors)%n",
                    outFile.getName(), outFile.length(), actors.size());
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
            PolarCamera camera = new PolarCamera();
            camera.setTarget(-86.2f, 1.999f, -34.2f);
            camera.setTargetOffsetY(1.0f);
            camera.setYaw(185.0f);
            camera.setPitch(15.0f);
            camera.setDistance(5.8f);

            SceneActor localPlayer = new SceneActor(
                    playerRender.character(), playerRender.textures(), playerRender.idlePose(),
                    playerRender.nameplateHeight(),
                    -84.5f, 1.999f, -32.8f, 222.0f,
                    PLAYER_NAME, new Color(140, 220, 255)
            );
            List<SceneActor> actors = buildStreamedSceneActors(
                    localPlayer, clientState, camera.getTargetX(), camera.getTargetZ(), 35.0f,
                    Set.of(17719297L, 17719638L, 17720019L)
            );

            BufferedImage snapshot = renderLiveE2EFrame(
                    230, sessionId, loadedZone230, decodedZone230Textures, camera, actors,
                    "Ceraule Rendezvous | Canonical Equipped & Standard NPCs",
                    "Adventurer meeting Ceraule (#17719297, ElvaanM armor 12) with Rolandienne & Repairer Moogle (#82) via Zone #230 AoI.",
                    clientState.getRemoteEntities().size()
            );

            File outFile = OUTPUT_DIR.resolve("e2e_live_03_south_sandoria_ceraule_rendezvous.png").toFile();
            ImageIO.write(snapshot, "PNG", outFile);
            assertTrue(outFile.exists() && outFile.length() > 50000);
            System.out.printf("[E2E-LIVE] Saved snapshot -> %s (%,d bytes, %d rendered actors)%n",
                    outFile.getName(), outFile.length(), actors.size());
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

        LoadedZone loadedZone245 = ZoneModelLoader.loadZone(FFXI_ROOT, 245);
        assertNotNull(loadedZone245, "Zone 245 (Lower Jeuno) must be loaded");
        Map<String, DecodedTextureBuffer> decodedZone245Textures = decodeAllZoneTextures(loadedZone245);

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
            PolarCamera camera = new PolarCamera();
            camera.setTarget(20.3f, 3.899f, -60.6f);
            camera.setTargetOffsetY(1.0f);
            camera.setYaw(125.0f);
            camera.setPitch(15.0f);
            camera.setDistance(3.9f);

            SceneActor localPlayer = new SceneActor(
                    playerRender.character(), playerRender.textures(), playerRender.idlePose(),
                    playerRender.nameplateHeight(),
                    19.5f, 3.899f, -59.5f, 142.0f,
                    PLAYER_NAME, new Color(140, 220, 255)
            );
            List<SceneActor> actors = buildStreamedSceneActors(
                    localPlayer, clientState245, camera.getTargetX(), camera.getTargetZ(), 35.0f,
                    Set.of(17780743L)
            );

            BufferedImage snapshot = renderLiveE2EFrame(
                    245, sessionId, loadedZone245, decodedZone245Textures, camera, actors,
                    "Lower Jeuno Rendezvous | Canonical Standard NPC Aldo (#78)",
                    "ZoneSimulationEngine #245 loaded 123 authentic Lower Jeuno NPCs. Adventurer facing Tenshodo leader Aldo (model #78).",
                    clientState245.getRemoteEntities().size()
            );

            File outFile = OUTPUT_DIR.resolve("e2e_live_04_lower_jeuno_aldo.png").toFile();
            ImageIO.write(snapshot, "PNG", outFile);
            assertTrue(outFile.exists() && outFile.length() > 50000);
            System.out.printf("[E2E-LIVE] Saved snapshot -> %s (%,d bytes, %d rendered actors)%n",
                    outFile.getName(), outFile.length(), actors.size());
        } finally {
            engine245.removeEntityBySession(sessionId);
            engine245.stop();
        }
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

    private static List<SceneActor> buildStreamedSceneActors(
            SceneActor localPlayer,
            ClientState clientState,
            float targetX,
            float targetZ,
            float maxDistMeters,
            Set<Long> requiredEntityIds
    ) throws IOException {
        List<SceneActor> actors = new ArrayList<>();
        actors.add(localPlayer);

        List<ClientState.RemoteEntityState> nearby = new ArrayList<>();
        for (ClientState.RemoteEntityState entity : clientState.getRemoteEntities().values()) {
            double dist = Math.hypot(entity.x() - targetX, entity.z() - targetZ);
            if (dist <= maxDistMeters) {
                nearby.add(entity);
            }
        }
        nearby.sort(Comparator.comparingDouble(e -> Math.hypot(e.x() - targetX, e.z() - targetZ)));

        for (ClientState.RemoteEntityState entity : nearby) {
            ActorAppearance appearance = entity.appearance();
            if (appearance == null || appearance.lookType() == ActorLookType.NONE) {
                continue;
            }
            try {
                CachedActorRender cached = actorRenderCache.getOrLoad(appearance);
                actors.add(new SceneActor(
                        cached.character(),
                        cached.textures(),
                        cached.idlePose(),
                        cached.nameplateHeight(),
                        entity.x(),
                        entity.y(),
                        entity.z(),
                        entity.yaw(),
                        entity.name(),
                        new Color(100, 240, 140)
                ));
            } catch (Exception e) {
                if (requiredEntityIds.contains(entity.characterId())) {
                    throw new IOException("Required featured NPC " + entity.name() + " (" + entity.characterId()
                            + ", appearance=" + appearance + ") failed to load", e);
                }
                System.err.printf("[E2E-LIVE] Skipping ambient NPC %s (%d): %s%n",
                        entity.name(), entity.characterId(), e.getMessage());
            }
        }
        return actors;
    }

    private static Map<String, DecodedTextureBuffer> decodeCharacterTextures(LoadedCharacter character) {
        Map<String, DecodedTextureBuffer> map = new HashMap<>();
        for (Map.Entry<String, CharacterTextureLoader.TextureSource> entry : character.textures().entrySet()) {
            String norm = CharacterTextureLoader.normalizeName(entry.getKey());
            CharacterTextureLoader.TextureSource src = entry.getValue();
            try {
                byte[] rgba = TextureDecoder.decode(src.rawData(), src.resource());
                if (rgba != null && rgba.length > 0) {
                    DecodedTextureBuffer dtb = new DecodedTextureBuffer(src.resource().width(), src.resource().height(), rgba);
                    map.putIfAbsent(norm, dtb);
                    if (norm.contains(" ")) {
                        String shortName = norm.substring(norm.lastIndexOf(' ') + 1);
                        map.putIfAbsent(shortName, dtb);
                    }
                }
            } catch (Exception ignored) {}
        }
        return map;
    }

    private static float computeNameplateHeight(LoadedCharacter character, Matrix4f[] jointMatrices) {
        float minY = Float.POSITIVE_INFINITY;
        Vector4f accum = new Vector4f();
        Vector4f scratch = new Vector4f();
        for (SkeletonMeshMesh mesh : character.meshes()) {
            for (SkeletonMeshVertex vertex : mesh.resolvedVertices()) {
                Vector3f pos = computeSkinnedPosition(vertex, jointMatrices, accum, scratch);
                if (Float.isFinite(pos.y()) && pos.y() < minY) {
                    minY = pos.y();
                }
            }
        }
        float extentAboveFeet = (Float.isFinite(minY)
                ? (character.feetLocalY() - minY)
                : (character.feetOffsetY() * 2.0f)) * character.effectiveScale();
        return Math.max(1.1f, Math.min(4.2f, extentAboveFeet + 0.18f));
    }

    private static BufferedImage renderLiveE2EFrame(
            int zoneId,
            long sessionId,
            LoadedZone zone,
            Map<String, DecodedTextureBuffer> decodedZoneTextures,
            PolarCamera camera,
            List<SceneActor> actors,
            String title,
            String subtitle,
            int activeNpcCount
    ) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        float[] zBuffer = new float[WIDTH * HEIGHT];
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);

        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Sky gradient
        Color skyZenith = new Color(30, 60, 115);
        Color skyHorizon = new Color(175, 205, 235);
        for (int y = 0; y < HEIGHT; y++) {
            float ratio = (float) y / HEIGHT;
            int r = (int) (skyZenith.getRed() + ratio * (skyHorizon.getRed() - skyZenith.getRed()));
            int gr = (int) (skyZenith.getGreen() + ratio * (skyHorizon.getGreen() - skyZenith.getGreen()));
            int b = (int) (skyZenith.getBlue() + ratio * (skyHorizon.getBlue() - skyZenith.getBlue()));
            g2.setColor(new Color(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, gr)), Math.max(0, Math.min(255, b))));
            g2.drawLine(0, y, WIDTH, y);
        }

        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(48.0f), (float) WIDTH / HEIGHT, 0.2f, 2000.0f);
        Matrix4f view = new Matrix4f().lookAt(
                new Vector3f(camera.getEyeX(), camera.getEyeY(), camera.getEyeZ()),
                new Vector3f(camera.getTargetX(), camera.getTargetY() - camera.getTargetOffsetY(), camera.getTargetZ()),
                new Vector3f(0.0f, -1.0f, 0.0f)
        );
        Matrix4f viewProj = new Matrix4f(proj).mul(view);

        int[] pixels = new int[WIDTH * HEIGHT];
        image.getRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);

        // 1. Zone geometry (Opaque pass)
        rasterizeZoneGeometry(zone, decodedZoneTextures, viewProj, pixels, zBuffer, false);

        // 2. Dynamic Actors (Player and streamed NPCs)
        for (SceneActor actor : actors) {
            rasterizeDynamicActor(actor.character(), actor.textures(), actor.jointMatrices(),
                    actor.x(), actor.y(), actor.z(), actor.yaw(), viewProj, pixels, zBuffer);
        }

        // 3. Zone geometry (Blended alpha pass)
        rasterizeZoneGeometry(zone, decodedZoneTextures, viewProj, pixels, zBuffer, true);

        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);

        // 4. Overhead Nameplates (depth-aware occlusion check + dynamic model height)
        Vector4f headClip = new Vector4f();
        for (SceneActor actor : actors) {
            float headY = actor.y() - actor.nameplateHeight();
            headClip.set(actor.x(), headY, actor.z(), 1.0f);
            viewProj.transform(headClip);
            if (headClip.w > 0.1f && headClip.w < 40.0f) {
                float sx = (headClip.x / headClip.w * 0.5f + 0.5f) * WIDTH;
                float sy = (0.5f - headClip.y / headClip.w * 0.5f) * HEIGHT;
                float ndcZ = headClip.z / headClip.w;
                if (sx >= 20 && sx < WIDTH - 20 && sy >= 150 && sy < HEIGHT - 20) {
                    int pxIdx = Math.max(0, Math.min(HEIGHT - 1, (int) sy)) * WIDTH + Math.max(0, Math.min(WIDTH - 1, (int) sx));
                    if (ndcZ <= zBuffer[pxIdx] + 0.0002f) {
                        drawActorNameplate(image, g2, sx, sy, actor.name(), actor.nameColor());
                    }
                }
            }
        }

        // 5. Stamped HUD Overlays
        SceneActor primaryActor = actors.isEmpty() ? null : actors.get(0);
        String playerName = primaryActor != null ? primaryActor.name() : PLAYER_NAME;
        float px = primaryActor != null ? primaryActor.x() : 0.0f;
        float py = primaryActor != null ? primaryActor.y() : 0.0f;
        float pz = primaryActor != null ? primaryActor.z() : 0.0f;
        float pyaw = primaryActor != null ? primaryActor.yaw() : 0.0f;

        List<String> renderedNpcNames = new ArrayList<>();
        for (int i = 1; i < actors.size() && renderedNpcNames.size() < 6; i++) {
            renderedNpcNames.add(actors.get(i).name());
        }
        String npcSummary = renderedNpcNames.isEmpty() ? "none nearby" : String.join(", ", renderedNpcNames);

        renderLiveIntegrationHud(
                image, g2, camera, zoneId, sessionId, playerName,
                px, py, pz, pyaw, title, subtitle, activeNpcCount, npcSummary
        );
        g2.dispose();

        return image;
    }

    private static void drawActorNameplate(BufferedImage dest, Graphics2D g, float sx, float sy, String name, Color nameColor) {
        if (name == null || name.isEmpty()) return;
        int textWidth = name.length() * 9;
        int padX = 8;
        int boxW = textWidth + padX * 2;
        int boxH = 20;
        int boxX = (int) sx - boxW / 2;
        int boxY = (int) sy - boxH;

        g.setColor(new Color(10, 16, 26, 210));
        g.fillRoundRect(boxX, boxY, boxW, boxH, 8, 8);
        g.setColor(new Color(60, 95, 140, 220));
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(boxX, boxY, boxW, boxH, 8, 8);

        drawText(dest, name, boxX + padX, boxY + 3, nameColor, 1);
    }

    private static void renderLiveIntegrationHud(
            BufferedImage dest, Graphics2D g, PolarCamera cam,
            int zoneId, long sessionId, String playerName,
            float charX, float charY, float charZ, float charYaw,
            String title, String subtitle, int activeNpcCount, String nearbyNpcSummary
    ) {
        // Top banner background
        g.setColor(new Color(10, 16, 26, 230));
        g.fillRoundRect(24, 20, WIDTH - 48, 120, 12, 12);
        g.setColor(new Color(40, 75, 120));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(24, 20, WIDTH - 48, 120, 12, 12);

        // Title and description
        drawText(dest, title, 40, 32, new Color(255, 215, 80), 2);
        drawText(dest, subtitle, 40, 64, new Color(200, 220, 240), 1);

        String tele1 = String.format(
                "SERVER: Authoritative 20 Hz ZoneSimulationEngine #%d | QUIC State: IN_ZONE | Session #%d | Fory Protocol",
                zoneId, sessionId
        );
        String tele2 = String.format(
                "SPATIAL AoI: %d Authentic LSB NPCs Streamed to ClientState (Nearby: %s)",
                activeNpcCount, nearbyNpcSummary
        );
        String tele3 = String.format(
                "AVATAR: %s @ (%5.1f, %5.2f, %5.1f) | Facing=%5.1f° [%s] | Cam: Yaw=%5.1f° Pitch=%5.1f° Dist=%4.1fm",
                playerName, charX, charY, charZ, charYaw, resolveCardinal(charYaw), cam.getYaw(), cam.getPitch(), cam.getDistance()
        );

        drawText(dest, tele1, 40, 84, new Color(130, 210, 255), 1);
        drawText(dest, tele2, 40, 102, new Color(120, 240, 160), 1);
        drawText(dest, tele3, 40, 120, new Color(225, 235, 250), 1);

        // Compass Rose
        drawCompassRose(dest, g, WIDTH - 80, 80, cam.getYaw());
    }

    private static void drawCompassRose(BufferedImage dest, Graphics2D g, int cx, int cy, float camYaw) {
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

        drawText(dest, "N", nx - 4, ny - 6, Color.YELLOW, 1);
        g.setColor(Color.WHITE);
        g.fillOval(cx - 3, cy - 3, 6, 6);
    }

    private static void drawText(BufferedImage dest, String text, int startX, int startY, Color color, int scale) {
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

    private static Map<String, DecodedTextureBuffer> decodeAllZoneTextures(LoadedZone zone) {
        Map<String, DecodedTextureBuffer> map = new HashMap<>();
        for (Map.Entry<String, CharacterTextureLoader.TextureSource> entry : zone.textureSources().entrySet()) {
            String norm = normalizeName(entry.getKey());
            CharacterTextureLoader.TextureSource src = entry.getValue();
            try {
                byte[] rgba = TextureDecoder.decode(src.rawData(), src.resource());
                if (rgba != null && rgba.length > 0) {
                    DecodedTextureBuffer dzt = new DecodedTextureBuffer(src.resource().width(), src.resource().height(), rgba);
                    map.putIfAbsent(norm, dzt);
                    if (norm.contains(" ")) {
                        String shortName = norm.substring(norm.lastIndexOf(' ') + 1);
                        map.putIfAbsent(shortName, dzt);
                    }
                }
            } catch (Exception ignored) {}
        }
        return map;
    }

    private static void rasterizeZoneGeometry(
            LoadedZone zone, Map<String, DecodedTextureBuffer> decodedTextures, Matrix4f viewProj,
            int[] pixels, float[] zBuffer, boolean blendPass
    ) {
        for (LoadedZone.ZoneChunkInstance inst : zone.chunkInstances()) {
            ZoneMeshChunk chunk = inst.chunk();
            if (chunk.renderBlend() != blendPass) continue;
            if (!ZoneMeshMath.hasValidVertices(chunk)) continue;

            List<Integer> triangles = ZoneMeshMath.expandTriangles(chunk);
            if (triangles.isEmpty()) continue;

            List<ZoneMeshVertex> verts = chunk.vertices();
            Matrix4f modelMatrix = inst.modelMatrix();
            DecodedTextureBuffer tex = decodedTextures.get(normalizeName(chunk.textureName()));
            if (tex == null && chunk.textureName() != null && !chunk.textureName().isBlank()) {
                String norm = normalizeName(chunk.textureName());
                for (Map.Entry<String, DecodedTextureBuffer> e : decodedTextures.entrySet()) {
                    if (e.getKey().endsWith(" " + norm) || e.getKey().startsWith(norm + " ") || e.getKey().contains(norm)) {
                        tex = e.getValue();
                        break;
                    }
                }
            }

            float discardThresh = inst.discardThreshold();
            boolean invertCull = inst.invertCull();
            boolean mirrored = modelMatrix.determinant() < 0.0f;

            int numV = verts.size();
            float[] clipX = new float[numV];
            float[] clipY = new float[numV];
            float[] clipZRaw = new float[numV];
            float[] clipW = new float[numV];
            float[] sx = new float[numV];
            float[] sy = new float[numV];
            float[] clipZ = new float[numV];
            boolean[] valid = new boolean[numV];

            final float nearW = 0.2f;
            for (int i = 0; i < numV; i++) {
                ZoneMeshVertex v = verts.get(i);
                Vector4f p = new Vector4f(
                        ZoneMeshMath.sanitizeFinite(v.px()),
                        ZoneMeshMath.sanitizeFinite(v.py()),
                        ZoneMeshMath.sanitizeFinite(v.pz()),
                        1.0f
                );
                modelMatrix.transform(p);
                viewProj.transform(p);

                clipX[i] = p.x;
                clipY[i] = p.y;
                clipZRaw[i] = p.z;
                clipW[i] = p.w;

                if (p.w > nearW) {
                    sx[i] = (p.x / p.w * 0.5f + 0.5f) * WIDTH;
                    sy[i] = (0.5f - p.y / p.w * 0.5f) * HEIGHT;
                    clipZ[i] = p.z / p.w;
                    valid[i] = true;
                } else {
                    valid[i] = false;
                }
            }

            for (int t = 0; t + 2 < triangles.size(); t += 3) {
                int i0 = triangles.get(t);
                int i1 = triangles.get(t + 1);
                int i2 = triangles.get(t + 2);

                if (valid[i0] && valid[i1] && valid[i2]) {
                    float x0 = sx[i0], y0 = sy[i0], w0 = clipW[i0], z0 = clipZ[i0];
                    float x1 = sx[i1], y1 = sy[i1], w1 = clipW[i1], z1 = clipZ[i1];
                    float x2 = sx[i2], y2 = sy[i2], w2 = clipW[i2], z2 = clipZ[i2];

                    float cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
                    if (!invertCull) {
                        if (!mirrored && cross >= 0.0f) continue;
                        if (mirrored && cross <= 0.0f) continue;
                    }

                    float minXF = Math.min(x0, Math.min(x1, x2));
                    float maxXF = Math.max(x0, Math.max(x1, x2));
                    float minYF = Math.min(y0, Math.min(y1, y2));
                    float maxYF = Math.max(y0, Math.max(y1, y2));

                    if (maxXF < 0f || minXF >= WIDTH || maxYF < 0f || minYF >= HEIGHT) continue;

                    ZoneMeshVertex v0 = verts.get(i0);
                    ZoneMeshVertex v1 = verts.get(i1);
                    ZoneMeshVertex v2 = verts.get(i2);

                    rasterizeZoneTriangle(
                            pixels, zBuffer, WIDTH, HEIGHT,
                            x0, y0, z0, w0, v0.u(), v0.v(), v0.colorR(), v0.colorG(), v0.colorB(), v0.colorA(),
                            x1, y1, z1, w1, v1.u(), v1.v(), v1.colorR(), v1.colorG(), v1.colorB(), v1.colorA(),
                            x2, y2, z2, w2, v2.u(), v2.v(), v2.colorR(), v2.colorG(), v2.colorB(), v2.colorA(),
                            tex, discardThresh, blendPass
                    );
                } else if (valid[i0] || valid[i1] || valid[i2]) {
                    rasterizeNearClippedZoneTriangle(
                            verts.get(i0), clipX[i0], clipY[i0], clipZRaw[i0], clipW[i0],
                            verts.get(i1), clipX[i1], clipY[i1], clipZRaw[i1], clipW[i1],
                            verts.get(i2), clipX[i2], clipY[i2], clipZRaw[i2], clipW[i2],
                            nearW, invertCull, mirrored,
                            pixels, zBuffer, tex, discardThresh, blendPass
                    );
                }
            }
        }
    }

    private static void rasterizeNearClippedZoneTriangle(
            ZoneMeshVertex v0, float x0, float y0, float z0, float w0,
            ZoneMeshVertex v1, float x1, float y1, float z1, float w1,
            ZoneMeshVertex v2, float x2, float y2, float z2, float w2,
            float nearW, boolean invertCull, boolean mirrored,
            int[] pixels, float[] zBuffer, DecodedTextureBuffer tex, float discardThresh, boolean blendPass
    ) {
        float[][] in = {
                {x0, y0, z0, w0, v0.u(), v0.v(), v0.colorR(), v0.colorG(), v0.colorB(), v0.colorA()},
                {x1, y1, z1, w1, v1.u(), v1.v(), v1.colorR(), v1.colorG(), v1.colorB(), v1.colorA()},
                {x2, y2, z2, w2, v2.u(), v2.v(), v2.colorR(), v2.colorG(), v2.colorB(), v2.colorA()}
        };
        float[][] out = new float[4][10];
        int outCount = 0;

        for (int i = 0; i < 3; i++) {
            float[] curr = in[i];
            float[] next = in[(i + 1) % 3];
            boolean currIn = curr[3] >= nearW;
            boolean nextIn = next[3] >= nearW;

            if (currIn) {
                System.arraycopy(curr, 0, out[outCount++], 0, 10);
            }
            if (currIn != nextIn) {
                float t = (nearW - curr[3]) / (next[3] - curr[3]);
                for (int k = 0; k < 10; k++) {
                    out[outCount][k] = curr[k] + t * (next[k] - curr[k]);
                }
                outCount++;
            }
        }

        if (outCount < 3) return;

        for (int i = 1; i + 1 < outCount; i++) {
            float[] a = out[0];
            float[] b = out[i];
            float[] c = out[i + 1];

            float sax = (a[0] / a[3] * 0.5f + 0.5f) * WIDTH;
            float say = (0.5f - a[1] / a[3] * 0.5f) * HEIGHT;
            float sbx = (b[0] / b[3] * 0.5f + 0.5f) * WIDTH;
            float sby = (0.5f - b[1] / b[3] * 0.5f) * HEIGHT;
            float scx = (c[0] / c[3] * 0.5f + 0.5f) * WIDTH;
            float scy = (0.5f - c[1] / c[3] * 0.5f) * HEIGHT;

            float cross = (sbx - sax) * (scy - say) - (sby - say) * (scx - sax);
            if (!invertCull) {
                if (!mirrored && cross >= 0.0f) continue;
                if (mirrored && cross <= 0.0f) continue;
            }

            float minXF = Math.min(sax, Math.min(sbx, scx));
            float maxXF = Math.max(sax, Math.max(sbx, scx));
            float minYF = Math.min(say, Math.min(sby, scy));
            float maxYF = Math.max(say, Math.max(sby, scy));
            if (maxXF < 0f || minXF >= WIDTH || maxYF < 0f || minYF >= HEIGHT) continue;

            rasterizeZoneTriangle(
                    pixels, zBuffer, WIDTH, HEIGHT,
                    sax, say, a[2] / a[3], a[3], a[4], a[5], (int) a[6], (int) a[7], (int) a[8], (int) a[9],
                    sbx, sby, b[2] / b[3], b[3], b[4], b[5], (int) b[6], (int) b[7], (int) b[8], (int) b[9],
                    scx, scy, c[2] / c[3], c[3], c[4], c[5], (int) c[6], (int) c[7], (int) c[8], (int) c[9],
                    tex, discardThresh, blendPass
            );
        }
    }

    private static void rasterizeZoneTriangle(
            int[] pixels, float[] zBuffer, int width, int height,
            float x0, float y0, float z0, float w0, float u0, float v0, int cr0, int cg0, int cb0, int ca0,
            float x1, float y1, float z1, float w1, float u1, float v1, int cr1, int cg1, int cb1, int ca1,
            float x2, float y2, float z2, float w2, float u2, float v2, int cr2, int cg2, int cb2, int ca2,
            DecodedTextureBuffer tex, float discardThresh, boolean blendPass
    ) {
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));

        float denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(denom) < 1e-5f) return;

        float invW0 = 1.0f / w0;
        float invW1 = 1.0f / w1;
        float invW2 = 1.0f / w2;

        float u0W = u0 * invW0, v0W = v0 * invW0;
        float u1W = u1 * invW1, v1W = v1 * invW1;
        float u2W = u2 * invW2, v2W = v2 * invW2;

        float r0W = (cr0 / 255.0f) * invW0, g0W = (cg0 / 255.0f) * invW0, b0W = (cb0 / 255.0f) * invW0;
        float r1W = (cr1 / 255.0f) * invW1, g1W = (cg1 / 255.0f) * invW1, b1W = (cb1 / 255.0f) * invW1;
        float r2W = (cr2 / 255.0f) * invW2, g2W = (cg2 / 255.0f) * invW2, b2W = (cb2 / 255.0f) * invW2;

        float a0W = (ca0 / 255.0f) * invW0;
        float a1W = (ca1 / 255.0f) * invW1;
        float a2W = (ca2 / 255.0f) * invW2;

        boolean useAlphaDiscard = discardThresh > 0.0f;
        int alphaThresholdByte = Math.max(1, (int) (discardThresh * 255.0f));

        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;

                float b0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom;
                float b1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom;
                float b2 = 1.0f - b0 - b1;

                if (b0 >= 0f && b1 >= 0f && b2 >= 0f) {
                    float z = b0 * z0 + b1 * z1 + b2 * z2;
                    int idx = y * width + x;

                    if (z < zBuffer[idx]) {
                        float interpInvW = b0 * invW0 + b1 * invW1 + b2 * invW2;
                        float w = 1.0f / interpInvW;

                        float u = (b0 * u0W + b1 * u1W + b2 * u2W) * w;
                        float v = (b0 * v0W + b1 * v1W + b2 * v2W) * w;

                        float vr = (b0 * r0W + b1 * r1W + b2 * r2W) * w;
                        float vg = (b0 * g0W + b1 * g1W + b2 * g2W) * w;
                        float vb = (b0 * b0W + b1 * b1W + b2 * b2W) * w;

                        if (vr < 0.01f && vg < 0.01f && vb < 0.01f) {
                            vr = 0.5f; vg = 0.5f; vb = 0.5f;
                        }

                        float va = (b0 * a0W + b1 * a1W + b2 * a2W) * w;
                        if (va <= 0.001f && ca0 == 0 && ca1 == 0 && ca2 == 0) {
                            va = 1.0f;
                        }

                        int r, g, b, a;
                        if (tex != null && tex.rgba() != null && tex.rgba().length > 0) {
                            int tw = tex.width();
                            int th = tex.height();
                            int tx = Math.floorMod((int) (u * tw), tw);
                            int ty = Math.floorMod((int) (v * th), th);
                            int texIdx = (ty * tw + tx) * 4;

                            int tr = tex.rgba()[texIdx] & 0xFF;
                            int tg = tex.rgba()[texIdx + 1] & 0xFF;
                            int tb = tex.rgba()[texIdx + 2] & 0xFF;
                            int ta = tex.rgba()[texIdx + 3] & 0xFF;

                            if (useAlphaDiscard && ta < alphaThresholdByte) {
                                continue;
                            }

                            r = Math.min(255, (int) (tr * vr * 2.0f));
                            g = Math.min(255, (int) (tg * vg * 2.0f));
                            b = Math.min(255, (int) (tb * vb * 2.0f));
                            a = (int) (ta * va);
                        } else {
                            r = Math.min(255, (int) (vr * 255.0f * 1.5f));
                            g = Math.min(255, (int) (vg * 255.0f * 1.5f));
                            b = Math.min(255, (int) (vb * 255.0f * 1.5f));
                            a = (int) (va * 255.0f);
                        }

                        if (!blendPass) {
                            pixels[idx] = (255 << 24) | (r << 16) | (g << 8) | b;
                            zBuffer[idx] = z;
                        } else {
                            if (a > 5) {
                                float alpha = a / 255.0f;
                                int bg = pixels[idx];
                                int bgr = (bg >> 16) & 0xFF;
                                int bgg = (bg >> 8) & 0xFF;
                                int bgb = bg & 0xFF;

                                int outR = (int) (r * alpha + bgr * (1.0f - alpha));
                                int outG = (int) (g * alpha + bgg * (1.0f - alpha));
                                int outB = (int) (b * alpha + bgb * (1.0f - alpha));

                                pixels[idx] = (255 << 24) | (outR << 16) | (outG << 8) | outB;
                            }
                        }
                    }
                }
            }
        }
    }

    private static void rasterizeDynamicActor(
            LoadedCharacter character, Map<String, DecodedTextureBuffer> textures,
            Matrix4f[] jointMatrices,
            float charX, float charY, float charZ, float charYaw,
            Matrix4f viewProj, int[] pixels, float[] zBuffer
    ) {
        if (character == null || character.meshes().isEmpty()) return;

        float scale = character.effectiveScale();
        Matrix4f charModelMat = new Matrix4f()
                .translate(charX, charY - character.feetLocalY() * scale, charZ)
                .rotateY((float) Math.toRadians(charYaw - 90.0f))
                .scale(scale);

        Vector4f scratch = new Vector4f();
        Vector4f accum = new Vector4f();
        Vector4f worldPos = new Vector4f();
        Vector4f clipPos = new Vector4f();

        for (SkeletonMeshMesh mesh : character.meshes()) {
            DecodedTextureBuffer tex = textures.get(CharacterTextureLoader.normalizeName(mesh.textureName()));
            if (tex == null && mesh.textureName() != null && !mesh.textureName().isBlank()) {
                String norm = CharacterTextureLoader.normalizeName(mesh.textureName());
                for (Map.Entry<String, DecodedTextureBuffer> e : textures.entrySet()) {
                    if (e.getKey().endsWith(" " + norm) || e.getKey().startsWith(norm + " ") || e.getKey().contains(norm)) {
                        tex = e.getValue();
                        break;
                    }
                }
            }

            ExpandedMesh expanded = expandToTriangleList(mesh);
            int triVertCount = expanded.count();
            if (triVertCount < 3) continue;

            List<SkeletonMeshFaceVertex> srcFace = mesh.vertices();
            List<SkeletonMeshVertex> srcResolved = mesh.resolvedVertices();

            float[] sx = new float[triVertCount];
            float[] sy = new float[triVertCount];
            float[] sz = new float[triVertCount];
            float[] sw = new float[triVertCount];
            boolean[] valid = new boolean[triVertCount];

            for (int i = 0; i < triVertCount; i++) {
                SkeletonMeshVertex vertex = srcResolved.get(expanded.resolIdx()[i]);
                Vector3f modelPos = computeSkinnedPosition(vertex, jointMatrices, accum, scratch);

                worldPos.set(modelPos.x(), modelPos.y(), modelPos.z(), 1.0f);
                charModelMat.transform(worldPos);

                clipPos.set(worldPos);
                viewProj.transform(clipPos);

                if (clipPos.w > 0.1f) {
                    float invW = 1.0f / clipPos.w;
                    sx[i] = (clipPos.x * invW * 0.5f + 0.5f) * WIDTH;
                    sy[i] = (0.5f - clipPos.y * invW * 0.5f) * HEIGHT;
                    sz[i] = clipPos.z * invW;
                    sw[i] = clipPos.w;
                    valid[i] = true;
                } else {
                    valid[i] = false;
                }
            }

            for (int t = 0; t + 2 < triVertCount; t += 3) {
                if (!valid[t] || !valid[t + 1] || !valid[t + 2]) continue;

                float x0 = sx[t], y0 = sy[t], z0 = sz[t], w0 = sw[t];
                float x1 = sx[t + 1], y1 = sy[t + 1], z1 = sz[t + 1], w1 = sw[t + 1];
                float x2 = sx[t + 2], y2 = sy[t + 2], z2 = sz[t + 2], w2 = sw[t + 2];

                float cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
                if (!mesh.mirrored() && cross >= 0.0f) continue;
                if (mesh.mirrored() && cross <= 0.0f) continue;

                SkeletonMeshFaceVertex f0 = srcFace.get(expanded.faceIdx()[t]);
                SkeletonMeshFaceVertex f1 = srcFace.get(expanded.faceIdx()[t + 1]);
                SkeletonMeshFaceVertex f2 = srcFace.get(expanded.faceIdx()[t + 2]);

                rasterizeCharTriangle(
                        pixels, zBuffer, WIDTH, HEIGHT,
                        x0, y0, z0, w0, f0.u(), f0.v(),
                        x1, y1, z1, w1, f1.u(), f1.v(),
                        x2, y2, z2, w2, f2.u(), f2.v(),
                        tex
                );
            }
        }
    }

    private static void rasterizeCharTriangle(
            int[] pixels, float[] zBuffer, int width, int height,
            float x0, float y0, float z0, float w0, float u0, float v0,
            float x1, float y1, float z1, float w1, float u1, float v1,
            float x2, float y2, float z2, float w2, float u2, float v2,
            DecodedTextureBuffer texture
    ) {
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));

        float denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(denom) < 1e-5f) return;

        float invW0 = 1.0f / w0;
        float invW1 = 1.0f / w1;
        float invW2 = 1.0f / w2;

        float u0W = u0 * invW0, v0W = v0 * invW0;
        float u1W = u1 * invW1, v1W = v1 * invW1;
        float u2W = u2 * invW2, v2W = v2 * invW2;

        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;

                float b0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom;
                float b1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom;
                float b2 = 1.0f - b0 - b1;

                if (b0 >= 0f && b1 >= 0f && b2 >= 0f) {
                    float z = b0 * z0 + b1 * z1 + b2 * z2;
                    int idx = y * width + x;

                    if (z < zBuffer[idx]) {
                        float interpInvW = b0 * invW0 + b1 * invW1 + b2 * invW2;
                        float w = 1.0f / interpInvW;

                        float u = (b0 * u0W + b1 * u1W + b2 * u2W) * w;
                        float v = (b0 * v0W + b1 * v1W + b2 * v2W) * w;

                        int color = sampleCharTexture(texture, u, v);
                        if ((color >>> 24) > 64) {
                            pixels[idx] = color;
                            zBuffer[idx] = z;
                        }
                    }
                }
            }
        }
    }

    private static int sampleCharTexture(DecodedTextureBuffer texture, float u, float v) {
        if (texture == null) return 0xFFCCCCCC;

        int tw = texture.width();
        int th = texture.height();
        if (tw <= 0 || th <= 0) return 0xFFCCCCCC;

        float uFrac = u - (float) Math.floor(u);
        float vFrac = v - (float) Math.floor(v);

        int tx = Math.min(tw - 1, Math.max(0, (int) (uFrac * tw)));
        int ty = Math.min(th - 1, Math.max(0, (int) (vFrac * th)));

        int byteOffset = (ty * tw + tx) * 4;
        byte[] rgba = texture.rgba();
        if (byteOffset + 3 >= rgba.length) return 0xFFCCCCCC;

        int a = rgba[byteOffset + 3] & 0xFF;
        if (a < 64) return 0;

        int r = rgba[byteOffset] & 0xFF;
        int g = rgba[byteOffset + 1] & 0xFF;
        int b = rgba[byteOffset + 2] & 0xFF;

        return (255 << 24) | (r << 16) | (g << 8) | b;
    }

    private static Vector3f computeSkinnedPosition(
            SkeletonMeshVertex vertex,
            Matrix4f[] jointMatrices,
            Vector4f accum,
            Vector4f scratch
    ) {
        float joint0Weight = vertex.joint0Weight();
        float joint1Weight = vertex.joint1Weight();
        if (joint1Weight <= 0.0f) {
            joint0Weight = 1.0f;
            joint1Weight = 0.0f;
        } else {
            float total = joint0Weight + joint1Weight;
            if (total > 0.0f && Math.abs(total - 1.0f) > 1.0e-6f) {
                joint0Weight /= total;
                joint1Weight /= total;
            }
        }

        accum.set(0.0f, 0.0f, 0.0f, 0.0f);
        transformJoint(jointMatrices, vertex.jointIndex0(), vertex.p0(), joint0Weight, scratch);
        accum.add(scratch);
        transformJoint(jointMatrices, vertex.jointIndex1(), vertex.p1(), joint1Weight, scratch);
        accum.add(scratch);
        return new Vector3f(accum.x, accum.y, accum.z);
    }

    private static void transformJoint(
            Matrix4f[] jointMatrices,
            Integer jointIndex,
            Vec3f position,
            float weight,
            Vector4f dest
    ) {
        if (position == null || weight == 0.0f) {
            dest.set(0.0f, 0.0f, 0.0f, 0.0f);
            return;
        }
        int index = jointIndex == null ? 0 : Math.max(0, Math.min(jointMatrices.length - 1, jointIndex));
        jointMatrices[index].transform(position.x(), position.y(), position.z(), weight, dest);
    }

    private record ExpandedMesh(int[] faceIdx, int[] resolIdx) {
        int count() { return faceIdx.length; }
    }

    private static ExpandedMesh expandToTriangleList(SkeletonMeshMesh mesh) {
        List<SkeletonMeshFaceVertex> src = mesh.vertices();
        int n = src.size();

        if (n < 3) {
            return new ExpandedMesh(new int[0], new int[0]);
        }

        if (mesh.vertexCount() == mesh.triangleCount() * 3) {
            int triCount = (n / 3) * 3;
            int[] fi = new int[triCount];
            int[] ri = new int[triCount];
            for (int i = 0; i + 2 < triCount; i += 3) {
                fi[i]     = i;     ri[i]     = i;
                fi[i + 1] = i + 2; ri[i + 1] = i + 2;
                fi[i + 2] = i + 1; ri[i + 2] = i + 1;
            }
            return new ExpandedMesh(fi, ri);
        }

        int maxTris = n - 2;
        int[] fi = new int[maxTris * 3];
        int[] ri = new int[maxTris * 3];
        int out = 0;
        for (int i = 2; i < n; i++) {
            int a, b, c;
            if ((i & 1) == 0) {
                a = i - 2; b = i; c = i - 1;
            } else {
                a = i - 1; b = i; c = i - 2;
            }
            if (a == b || b == c || a == c) continue;
            fi[out] = a; ri[out] = a; out++;
            fi[out] = b; ri[out] = b; out++;
            fi[out] = c; ri[out] = c; out++;
        }
        int[] trimFi = new int[out];
        int[] trimRi = new int[out];
        System.arraycopy(fi, 0, trimFi, 0, out);
        System.arraycopy(ri, 0, trimRi, 0, out);
        return new ExpandedMesh(trimFi, trimRi);
    }

    private static String normalizeName(String s) {
        if (s == null) return "";
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s.trim().toLowerCase();
    }
}
