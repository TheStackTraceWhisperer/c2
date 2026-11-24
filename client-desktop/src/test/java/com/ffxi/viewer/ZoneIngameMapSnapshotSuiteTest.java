package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.interaction.ZoneInteraction;
import com.example.asset.interaction.ZoneInteractionParser;
import com.example.client.desktop.capture.ScreenshotCapture;
import com.example.client.desktop.engine.services.imgui.ImGuiService;
import com.example.client.desktop.scene.WorldScene;
import com.example.client.desktop.ui.map.InGameMapPanel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * In-Game Cartography Map Snapshot Suite.
 * Drives the production {@link InGameMapPanel} + {@link ImGuiService} + {@link ZoneInteractionParser}
 * pipeline under OpenGL 4.6 Core across all 16 retail zone maps (zero test-side Java2D rendering).
 */
public class ZoneIngameMapSnapshotSuiteTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();
    private static final int WIDTH = 680;
    private static final int HEIGHT = 760;

    private record ZoneMapScenario(int zoneId, int subZoneId, String snapshotId) {
    }

    private static HeadlessGlContext glContext;
    private static ImGuiService imGuiService;
    private static InGameMapPanel mapPanel;
    private static WorldScene zoneByteLoader;

    @BeforeAll
    static void initSuite() {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required for OpenGL 4.6 Core snapshots (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        glContext = HeadlessGlContext.create(WIDTH, HEIGHT);
        imGuiService = new ImGuiService(null);
        imGuiService.start(glContext.getWindowHandle());

        mapPanel = new InGameMapPanel();
        mapPanel.setWindowPosition(20.0f, 20.0f);
        mapPanel.setVisible(true);

        zoneByteLoader = new WorldScene();
    }

    @AfterAll
    static void tearDownSuite() {
        if (mapPanel != null) {
            mapPanel.close();
        }
        if (imGuiService != null) {
            imGuiService.stop();
        }
        if (zoneByteLoader != null) {
            zoneByteLoader.close();
        }
        if (glContext != null) {
            glContext.close();
        }
    }

    @Test
    @DisplayName("Render all 16 authentic in-game retail maps through production InGameMapPanel + ImGuiService")
    void testRenderAllZoneIngameMaps() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        List<ZoneMapScenario> scenarios = List.of(
                // 1. San d'Oria Kingdom
                new ZoneMapScenario(230, 0, "map_zone_230_south_san_doria"),
                new ZoneMapScenario(231, 0, "map_zone_231_north_san_doria"),
                new ZoneMapScenario(232, 0, "map_zone_232_port_san_doria"),
                new ZoneMapScenario(233, 0, "map_zone_233_chateau_d_oraguille"),

                // 2. Republic of Bastok
                new ZoneMapScenario(234, 0, "map_zone_234_bastok_mines"),
                new ZoneMapScenario(235, 0, "map_zone_235_bastok_markets"),
                new ZoneMapScenario(236, 0, "map_zone_236_port_bastok"),

                // 3. Federation of Windurst
                new ZoneMapScenario(238, 1, "map_zone_238_windurst_waters_north"),
                new ZoneMapScenario(238, 2, "map_zone_238_windurst_waters_south"),
                new ZoneMapScenario(241, 0, "map_zone_241_windurst_woods"),

                // 4. Grand Duchy of Jeuno
                new ZoneMapScenario(244, 0, "map_zone_244_upper_jeuno"),
                new ZoneMapScenario(245, 0, "map_zone_245_lower_jeuno"),

                // 5. Outposts, Ports & Open Fields
                new ZoneMapScenario(248, 0, "map_zone_248_selbina"),
                new ZoneMapScenario(249, 0, "map_zone_249_mhaura"),
                new ZoneMapScenario(100, 0, "map_zone_100_west_ronfaure"),
                new ZoneMapScenario(102, 0, "map_zone_102_la_theine_plateau")
        );

        for (ZoneMapScenario scenario : scenarios) {
            byte[] zoneBytes = zoneByteLoader.loadZoneBytes(scenario.zoneId());
            List<ZoneInteraction> interactions = (zoneBytes != null && zoneBytes.length > 0)
                    ? ZoneInteractionParser.parseInteractions(zoneBytes)
                    : List.of();

            imGuiService.beginFrame(WIDTH, HEIGHT);
            mapPanel.render(
                    scenario.zoneId(),
                    scenario.subZoneId(),
                    0.0f, 0.0f, 0.0f, 0.0f,
                    interactions,
                    List.of()
            );

            assertTrue(mapPanel.hasLoadedTexture(scenario.zoneId(), scenario.subZoneId()),
                    "InGameMapPanel must load retail GL texture for zone " + scenario.zoneId()
                            + " subZone " + scenario.subZoneId());

            Path outPath = ScreenshotCapture.captureOffscreen(WIDTH, HEIGHT, scenario.snapshotId(), () -> {
                GL11.glClearColor(0.04f, 0.06f, 0.09f, 1.0f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                imGuiService.postUpdate();
            });

            assertTrue(Files.isRegularFile(outPath) && Files.size(outPath) > 10_000,
                    "Map snapshot must be saved: " + outPath.getFileName());
            System.out.printf("[MAP-SUITE] Rendered production InGameMapPanel zone %d (sub=%d, %d RID markers) -> %s (%,d bytes)%n",
                    scenario.zoneId(), scenario.subZoneId(), interactions.size(), outPath.getFileName(), Files.size(outPath));
        }
    }
}
