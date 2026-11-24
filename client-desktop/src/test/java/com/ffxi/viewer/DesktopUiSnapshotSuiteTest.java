package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.geometry.Vector3f;
import com.example.asset.interaction.ZoneInteraction;
import com.example.client.desktop.capture.ScreenshotCapture;
import com.example.client.desktop.engine.services.imgui.ImGuiService;
import com.example.client.desktop.scene.CameraMode;
import com.example.client.desktop.scene.SceneActor;
import com.example.client.desktop.scene.SceneCamera;
import com.example.client.desktop.scene.WorldScene;
import com.example.client.desktop.state.InGameHudOverlay;
import com.example.client.desktop.ui.CharacterPanel;
import com.example.client.desktop.ui.DebugLogPanel;
import com.example.client.desktop.ui.InGamePanel;
import com.example.client.desktop.ui.LoginPanel;
import com.example.client.desktop.ui.radar.RadarViewportConfig;
import com.example.client.desktop.ui.radar.WorldRadarPanel;
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.protocol.JobId;
import com.example.common.protocol.NationId;
import com.example.common.protocol.RaceId;
import com.example.common.time.VanadielTime;
import com.example.lobby.dto.CharacterSummary;
import com.example.zone.dto.ActorAppearance;
import com.example.zone.dto.EntitySpawnRecord;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.environment.WeatherPreset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Desktop UI & GPU HUD Snapshot Suite.
 * Exercises the actual production UI panels ({@link LoginPanel}, {@link CharacterPanel},
 * {@link InGamePanel}, {@link WorldRadarPanel}, {@link DebugLogPanel}) and in-game HUD
 * ({@link InGameHudOverlay} + {@link com.ffxi.viewer.renderer.FontMojiGlRenderer}) through
 * {@link ImGuiService} and {@link WorldScene} under OpenGL 4.6 Core.
 */
public class DesktopUiSnapshotSuiteTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private static HeadlessGlContext glContext;
    private static ImGuiService imGuiService;
    private static WorldScene worldScene;

    @BeforeAll
    static void initSuite() {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required for OpenGL 4.6 Core snapshots (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        glContext = HeadlessGlContext.create(WIDTH, HEIGHT);
        imGuiService = new ImGuiService(null);
        imGuiService.start(glContext.getWindowHandle());

        worldScene = new WorldScene();
        worldScene.init();
        assertTrue(worldScene.isInitialized(), "WorldScene must initialize in OpenGL 4.6 Core context");
        worldScene.setShowSkyDome(true);
        worldScene.setEnableShadows(false);
        worldScene.setEnableSsao(false);
        worldScene.setEnableActorShadows(true);
        worldScene.setManualTimeOverride(true);
        worldScene.setManualTimeHour(12.0f);
        worldScene.setWeatherPreset(WeatherPreset.FINE);
    }

    @AfterAll
    static void tearDownSuite() {
        if (worldScene != null) {
            worldScene.close();
        }
        if (imGuiService != null) {
            imGuiService.stop();
        }
        if (glContext != null) {
            glContext.close();
        }
    }

    @Test
    @DisplayName("Render production ImGui desktop panels and GPU FontMoji HUD under OpenGL 4.6 Core")
    void testRenderProductionDesktopUiPanelsAndFontMojiHud() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        DebugLogPanel debugLog = new DebugLogPanel();
        debugLog.log("Initialized OpenGL 4.6 Core desktop UI renderer");
        debugLog.log("FontMojiGlRenderer atlas ready from ROM/0/1.DAT");

        // 1. LoginPanel + DebugLogPanel
        ClientState loginState = new ClientState();
        loginState.setConnectionState(ClientConnectionState.DISCONNECTED);
        LoginPanel loginPanel = new LoginPanel();
        imGuiService.beginFrame(WIDTH, HEIGHT);
        loginPanel.render(loginState);
        debugLog.render();
        Path loginOut = ScreenshotCapture.captureOffscreen(WIDTH, HEIGHT, "ui_login_panel", () -> {
            GL11.glClearColor(0.06f, 0.08f, 0.12f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            imGuiService.postUpdate();
        });
        assertTrue(Files.isRegularFile(loginOut) && Files.size(loginOut) > 10_000);

        // 2. CharacterPanel + DebugLogPanel
        ClientState charSelectState = new ClientState();
        charSelectState.setConnectionState(ClientConnectionState.AUTHENTICATED);
        charSelectState.updateCharacters(List.of(
                new CharacterSummary(101L, "Adventurer", 1, RaceId.HUME_MALE, (short) 0, 230, 75, NationId.SAN_D_ORIA, JobId.WARRIOR, JobId.MONK),
                new CharacterSummary(102L, "VanaMage", 1, RaceId.TARUTARU_FEMALE, (short) 1, 238, 60, NationId.WINDURST, JobId.BLACK_MAGE, JobId.WHITE_MAGE)
        ));
        charSelectState.setSelectedCharacterId(101L);
        CharacterPanel characterPanel = new CharacterPanel();
        imGuiService.beginFrame(WIDTH, HEIGHT);
        characterPanel.render(charSelectState);
        debugLog.render();
        Path charOut = ScreenshotCapture.captureOffscreen(WIDTH, HEIGHT, "ui_character_select_panel", () -> {
            GL11.glClearColor(0.06f, 0.08f, 0.12f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            imGuiService.postUpdate();
        });
        assertTrue(Files.isRegularFile(charOut) && Files.size(charOut) > 10_000);

        // Prepare live Zone 230 scene for in-game HUD + FontMoji tests
        worldScene.loadZone(FFXI_ROOT, 230);
        assertNotNull(worldScene.getLoadedZone(), "Zone 230 must load for UI HUD snapshots");

        float playerX = 5.0f;
        float playerZ = -15.0f;
        float floorY = worldScene.getLoadedZone().collisionSystem() != null
                ? worldScene.getLoadedZone().collisionSystem().findFloorY(playerX, playerZ, -20.0f)
                : 0.0f;
        if (!Float.isFinite(floorY)) {
            floorY = 0.0f;
        }

        SceneActor localActor = new SceneActor(1L, "Adventurer", SceneActor.ActorType.PLAYER);
        localActor.setVisualModel(worldScene.createCharacterInstance(FFXI_ROOT, RaceGenderConfig.HumeM));
        localActor.setPosition(playerX, floorY, playerZ);
        localActor.setRotation(180.0f);
        worldScene.setLocalPlayer(localActor);

        SceneCamera camera = worldScene.getCamera();
        camera.setMode(CameraMode.FREE_ORBIT);
        camera.setFov(48.0f);
        camera.setNearPlane(0.2f);
        camera.setFarPlane(2000.0f);
        camera.setTarget(playerX, floorY, playerZ);
        camera.setTargetOffsetY(1.0f);
        camera.setYaw(0.0f);
        camera.setPitch(16.0f);
        camera.setDistance(6.0f);

        ClientState inGameState = new ClientState();
        inGameState.setConnectionState(ClientConnectionState.IN_ZONE);
        inGameState.setCurrentWorldId(1);
        inGameState.setCurrentZoneId(230);
        inGameState.updatePosition(playerX, floorY, playerZ, 180.0f, 0.0f, 1L);
        inGameState.addOrUpdateRemoteEntity(new EntitySpawnRecord(
                2001L, "Glenne", JobId.NPC, JobId.NONE, 75,
                playerX + 6.0f, floorY, playerZ + 5.0f,
                0.0f, 0.0f, 0.0f,
                90.0f, 0.0f,
                ActorAppearance.forPlayer(RaceId.ELVAAN_FEMALE, 1, 1)
        ));

        ZoneInteraction sampleZoneline = new ZoneInteraction(
                new Vector3f(-113.4f, 0.0f, -57.4f),
                new Vector3f(0.0f, 0.0f, 0.0f),
                new Vector3f(4.0f, 4.0f, 4.0f),
                "z6e0", "z2t3", 100, 0
        );

        VanadielTime noon = VanadielTime.ofHour(12.0f);
        worldScene.update(0.0f, noon);

        float aspect = (float) WIDTH / (float) HEIGHT;
        float[] proj = new float[16];
        float[] view = new float[16];
        camera.getProjectionMatrix(aspect).get(proj);
        camera.getViewMatrix().get(view);

        InGameHudOverlay hudOverlay = new InGameHudOverlay();
        hudOverlay.setManualTimeOverride(true);
        hudOverlay.setManualTimeHour(12.0f);

        // 3. Production In-Game ImGui Panels (InGamePanel + WorldRadarPanel + InGameHudOverlay clock + DebugLogPanel) over 3D WorldScene
        InGamePanel inGamePanel = new InGamePanel();
        inGamePanel.setContext("Adventurer", "session-230");
        inGamePanel.updateKeepAlive("connected", 14L);
        WorldRadarPanel radarPanel = new WorldRadarPanel(new RadarViewportConfig(), inGameState, camera);
        imGuiService.beginFrame(WIDTH, HEIGHT);
        inGamePanel.render();
        radarPanel.render();
        hudOverlay.renderClockBanner(noon, sampleZoneline);
        debugLog.render();
        BufferedImage hudFrame = worldScene.captureFrame(WIDTH, HEIGHT, () -> imGuiService.postUpdate());
        Path hudOut = ScreenshotCapture.saveBufferedImage(hudFrame, "ui_ingame_hud_and_radar");
        assertTrue(Files.isRegularFile(hudOut) && Files.size(hudOut) > 50_000);

        // 4. Production GPU FontMoji Nameplate & Zoneline Banner Overlay over 3D WorldScene
        BufferedImage fontMojiFrame = worldScene.captureFrame(WIDTH, HEIGHT, () ->
                hudOverlay.renderNameplates(
                        inGameState,
                        "Adventurer",
                        proj,
                        view,
                        WIDTH,
                        HEIGHT,
                        System.currentTimeMillis(),
                        worldScene,
                        worldScene.getFontMojiGlRenderer(),
                        sampleZoneline
                )
        );
        Path fontOut = ScreenshotCapture.saveBufferedImage(fontMojiFrame, "ui_font_moji_gpu_overlay");
        assertTrue(Files.isRegularFile(fontOut) && Files.size(fontOut) > 50_000);
    }
}
