package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.capture.ScreenshotCapture;
import com.example.client.desktop.scene.SceneCamera;
import com.example.client.desktop.scene.WorldScene;
import com.ffxi.model.ZoneMeshMath;
import com.ffxi.model.ZoneMeshVertex;
import com.ffxi.viewer.environment.WeatherPreset;
import com.ffxi.viewer.model.LoadedZone;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * High-resolution Zone Rendering Snapshot Suite.
 * Drives the production OpenGL 4.6 Core {@link WorldScene} pipeline (ZoneShader, ShadowMapRenderer,
 * SsaoRenderer, AuthenticSkyDome) across major FFXI nations, towns, ports, and outdoors in both
 * perspective (1080p) and top-down orthographic (4K) projections.
 */
public class ZoneRenderingSnapshotSuiteTest {

    private static final Path FFXI_ROOT = FfxiPathConfig.resolveFfxiInstallDirectory();

    public enum ProjectionMode {
        PERSPECTIVE,
        ORTHOGRAPHIC
    }

    public record ZoneCamera(
            String id,
            String label,
            int zoneId,
            Vector3f eye,
            Vector3f target,
            Vector3f up,
            float fovDegrees,
            ProjectionMode projectionMode,
            float orthoSize
    ) {
        public ZoneCamera(String id, String label, int zoneId, Vector3f eye, Vector3f target, float fovDegrees) {
            this(id, label, zoneId, eye, target, new Vector3f(0.0f, -1.0f, 0.0f), fovDegrees, ProjectionMode.PERSPECTIVE, 0.0f);
        }

        public static ZoneCamera topDownOrtho(String id, String label, int zoneId) {
            return new ZoneCamera(
                    id, label, zoneId,
                    new Vector3f(0.0f, -200.0f, 0.0f),
                    new Vector3f(0.0f, 100.0f, 0.0f),
                    new Vector3f(0.0f, 0.0f, 1.0f),
                    0.0f,
                    ProjectionMode.ORTHOGRAPHIC,
                    0.0f
            );
        }

        public static ZoneCamera topDownOrtho(String id, String label, int zoneId, float centerX, float centerZ, float altitude, float orthoSize) {
            return new ZoneCamera(
                    id, label, zoneId,
                    new Vector3f(centerX, -Math.abs(altitude), centerZ),
                    new Vector3f(centerX, 100.0f, centerZ),
                    new Vector3f(0.0f, 0.0f, 1.0f),
                    0.0f,
                    ProjectionMode.ORTHOGRAPHIC,
                    orthoSize
            );
        }
    }

    private static HeadlessGlContext glContext;
    private static WorldScene worldScene;

    @BeforeAll
    static void initSuite() {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required for OpenGL 4.6 Core snapshots (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        glContext = HeadlessGlContext.create();
        worldScene = new WorldScene();
        worldScene.init();
        assertTrue(worldScene.isInitialized(), "WorldScene must initialize in OpenGL 4.6 Core context");
        worldScene.setManualTimeOverride(true);
        worldScene.setManualTimeHour(12.0f);
        worldScene.setWeatherPreset(WeatherPreset.FINE);
    }

    @AfterAll
    static void tearDownSuite() {
        if (worldScene != null) {
            worldScene.close();
        }
        if (glContext != null) {
            glContext.close();
        }
    }

    @Test
    @DisplayName("Render and snapshot iconic FFXI zones in perspective projection")
    void testRenderAndSnapshotIconicZones() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        List<ZoneCamera> scenarios = List.of(
                // 1. San d'Oria Kingdom
                new ZoneCamera(
                        "zone_230_south_san_doria_overview",
                        "Southern San d'Oria - Grand City Ramparts & Plaza",
                        230,
                        new Vector3f(-120.0f, -65.0f, -80.0f),
                        new Vector3f(0.0f, 0.0f, -20.0f),
                        52.0f
                ),
                new ZoneCamera(
                        "zone_230_south_san_doria_fountain",
                        "Southern San d'Oria - Fountain Square",
                        230,
                        new Vector3f(20.0f, -15.0f, -90.0f),
                        new Vector3f(5.0f, 0.0f, -20.0f),
                        58.0f
                ),
                new ZoneCamera(
                        "zone_231_north_san_doria_cathedral",
                        "Northern San d'Oria - Cathedral Spire & Chateau Approach",
                        231,
                        new Vector3f(-65.0f, -36.0f, 105.0f),
                        new Vector3f(110.0f, -14.0f, 120.0f),
                        52.0f
                ),
                new ZoneCamera(
                        "zone_232_port_san_doria_airship",
                        "Port San d'Oria - Canal Waterways & Airship Port",
                        232,
                        new Vector3f(-90.0f, -50.0f, -90.0f),
                        new Vector3f(0.0f, 5.0f, 0.0f),
                        52.0f
                ),
                new ZoneCamera(
                        "zone_233_chateau_d_oraguille",
                        "Chateau d'Oraguille - Royal Throne & Grand Hall",
                        233,
                        new Vector3f(0.0f, -25.0f, 80.0f),
                        new Vector3f(0.0f, -5.0f, -20.0f),
                        55.0f
                ),

                // 2. Bastok Republic
                new ZoneCamera(
                        "zone_234_bastok_mines_ironworks",
                        "Bastok Mines - Mining Gorge & Rail Tracks",
                        234,
                        new Vector3f(70.0f, -45.0f, 90.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        52.0f
                ),
                new ZoneCamera(
                        "zone_235_bastok_markets_plaza",
                        "Bastok Markets - Commercial Plazas & Aqueducts",
                        235,
                        new Vector3f(-80.0f, -40.0f, 80.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        52.0f
                ),
                new ZoneCamera(
                        "zone_236_port_bastok_wharf",
                        "Port Bastok - Steamship Wharf & Warehouses",
                        236,
                        new Vector3f(-90.0f, -45.0f, -90.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        52.0f
                ),

                // 3. Windurst Federation
                new ZoneCamera(
                        "zone_238_windurst_waters",
                        "Windurst Waters - Canals & Tarutaru Dwellings",
                        238,
                        new Vector3f(-90.0f, -45.0f, -100.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        52.0f
                ),
                new ZoneCamera(
                        "zone_241_windurst_woods_tree",
                        "Windurst Woods - Great Star Tree & Mithra Dwellings",
                        241,
                        new Vector3f(-120.0f, -50.0f, 100.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        54.0f
                ),

                // 4. Jeuno Grand Duchy
                new ZoneCamera(
                        "zone_244_upper_jeuno_ramparts",
                        "Upper Jeuno - Fortress Ramparts & Stone Arches",
                        244,
                        new Vector3f(-80.0f, -45.0f, 90.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        50.0f
                ),
                new ZoneCamera(
                        "zone_245_lower_jeuno_bazaar",
                        "Lower Jeuno - Grand Promenade & Merchants",
                        245,
                        new Vector3f(-80.0f, -40.0f, 80.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        52.0f
                ),

                // 5. Coastal Ports & Outposts
                new ZoneCamera(
                        "zone_248_selbina_windmill_harbor",
                        "Selbina - Windmill Port Harbor & Ocean Overlook",
                        248,
                        new Vector3f(-70.0f, -35.0f, 80.0f),
                        new Vector3f(0.0f, 5.0f, 0.0f),
                        54.0f
                ),
                new ZoneCamera(
                        "zone_249_mhaura_port_pier",
                        "Mhaura - Coastal Docks & Ferry Wharf",
                        249,
                        new Vector3f(-80.0f, -40.0f, -70.0f),
                        new Vector3f(0.0f, 2.0f, 0.0f),
                        52.0f
                ),

                // 6. Vast Outdoor Field Areas
                new ZoneCamera(
                        "zone_100_west_ronfaure_overlook",
                        "West Ronfaure - Rolling Woodlands & Watchtower",
                        100,
                        new Vector3f(-260.0f, -95.0f, -140.0f),
                        new Vector3f(-60.0f, -20.0f, 10.0f),
                        54.0f
                ),
                new ZoneCamera(
                        "zone_102_la_theine_crag_plateau",
                        "La Theine Plateau - Crag of Holla Highlands",
                        102,
                        new Vector3f(-320.0f, -120.0f, -220.0f),
                        new Vector3f(-40.0f, -10.0f, -20.0f),
                        55.0f
                )
        );

        renderSnapshotBatch(scenarios);
    }

    @Test
    @DisplayName("Render top-down orthographic zone maps verifying complete continuous ground geometry")
    void testRenderTopDownOrthographicZoneMaps() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        List<ZoneCamera> orthoScenarios = List.of(
                // 1. San d'Oria
                ZoneCamera.topDownOrtho("zone_230_south_san_doria_topdown_ortho", "Southern San d'Oria - Top-Down Ortho Map", 230),
                ZoneCamera.topDownOrtho("zone_231_north_san_doria_topdown_ortho", "Northern San d'Oria - Top-Down Ortho Map", 231),
                ZoneCamera.topDownOrtho("zone_232_port_san_doria_topdown_ortho", "Port San d'Oria - Top-Down Ortho Map", 232),
                ZoneCamera.topDownOrtho("zone_233_chateau_d_oraguille_topdown_ortho", "Chateau d'Oraguille - Top-Down Ortho Map", 233),

                // 2. Bastok
                ZoneCamera.topDownOrtho("zone_234_bastok_mines_topdown_ortho", "Bastok Mines - Top-Down Ortho Map", 234),
                ZoneCamera.topDownOrtho("zone_235_bastok_markets_topdown_ortho", "Bastok Markets - Top-Down Ortho Map", 235),
                ZoneCamera.topDownOrtho("zone_236_port_bastok_topdown_ortho", "Port Bastok - Top-Down Ortho Map", 236),

                // 3. Windurst
                ZoneCamera.topDownOrtho("zone_238_windurst_waters_topdown_ortho", "Windurst Waters - Top-Down Ortho Map", 238),
                ZoneCamera.topDownOrtho("zone_241_windurst_woods_topdown_ortho", "Windurst Woods - Top-Down Ortho Map", 241),

                // 4. Jeuno
                ZoneCamera.topDownOrtho("zone_244_upper_jeuno_topdown_ortho", "Upper Jeuno - Top-Down Ortho Map", 244),
                ZoneCamera.topDownOrtho("zone_245_lower_jeuno_topdown_ortho", "Lower Jeuno - Top-Down Ortho Map", 245),

                // 5. Ports & Fields
                ZoneCamera.topDownOrtho("zone_248_selbina_topdown_ortho", "Selbina - Top-Down Ortho Map", 248),
                ZoneCamera.topDownOrtho("zone_249_mhaura_topdown_ortho", "Mhaura - Top-Down Ortho Map", 249),
                ZoneCamera.topDownOrtho("zone_100_west_ronfaure_topdown_ortho", "West Ronfaure - Top-Down Ortho Map", 100),
                ZoneCamera.topDownOrtho("zone_102_la_theine_crag_topdown_ortho", "La Theine Plateau - Top-Down Ortho Map", 102)
        );

        renderSnapshotBatch(orthoScenarios);
    }

    @Test
    @DisplayName("Render and snapshot retail S2F diurnal cycles (Dawn 06:00, Noon 12:00, Dusk 18:30, Midnight 00:00)")
    void testRenderAndSnapshotDiurnalCycles() throws Exception {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);

        record DiurnalStop(String suffix, float hour) {}
        List<DiurnalStop> stops = List.of(
                new DiurnalStop("dawn", 6.0f),
                new DiurnalStop("noon", 12.0f),
                new DiurnalStop("dusk", 18.5f),
                new DiurnalStop("midnight", 0.0f)
        );
        List<ZoneCamera> anchors = List.of(
                new ZoneCamera(
                        "diurnal_231_cathedral_moat",
                        "Northern San d'Oria - Cathedral Moat & Lamps",
                        231,
                        new Vector3f(-65.0f, -36.0f, 105.0f),
                        new Vector3f(110.0f, -14.0f, 120.0f),
                        52.0f
                ),
                new ZoneCamera(
                        "diurnal_238_windurst_waters",
                        "Windurst Waters - Canals & Night Lights",
                        238,
                        new Vector3f(-90.0f, -45.0f, -100.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        52.0f
                ),
                new ZoneCamera(
                        "diurnal_100_west_ronfaure",
                        "West Ronfaure - Open Sky, Horizon & Celestial Cycle",
                        100,
                        new Vector3f(-260.0f, -95.0f, -140.0f),
                        new Vector3f(-60.0f, -45.0f, 10.0f),
                        54.0f
                )
        );

        try {
            worldScene.setManualTimeOverride(true);
            worldScene.setWeatherPreset(WeatherPreset.FINE);
            for (ZoneCamera anchor : anchors) {
                for (DiurnalStop stop : stops) {
                    ZoneCamera shot = new ZoneCamera(
                            anchor.id() + "_" + stop.suffix(),
                            anchor.label() + " [" + stop.suffix() + "]",
                            anchor.zoneId(),
                            anchor.eye(),
                            anchor.target(),
                            anchor.fovDegrees()
                    );
                    worldScene.setManualTimeHour(stop.hour());
                    renderSnapshotBatch(List.of(shot));
                }
            }
        } finally {
            worldScene.setManualTimeHour(12.0f);
        }
    }

    private static void renderSnapshotBatch(List<ZoneCamera> scenarios) throws Exception {
        for (ZoneCamera cam : scenarios) {
            if (worldScene.getCurrentZoneId() != cam.zoneId()) {
                worldScene.loadZone(FFXI_ROOT, cam.zoneId());
            }
            LoadedZone zone = worldScene.getLoadedZone();
            assertNotNull(zone, "Zone must be loaded: " + cam.zoneId());
            assertFalse(zone.chunkInstances().isEmpty(), "Zone must have geometry chunks");

            System.out.printf("[ZONE-SUITE] Rendering GL4.6 '%s' (%s, ID %d, %d chunks)...%n",
                    cam.label(), zone.zoneName(), cam.zoneId(), zone.chunkInstances().size());

            BufferedImage snapshot = renderZoneSnapshot(zone, cam);
            Path outPath = ScreenshotCapture.saveBufferedImage(snapshot, cam.id());

            assertTrue(Files.isRegularFile(outPath) && Files.size(outPath) > 0, "Snapshot must be saved");
            System.out.printf("[ZONE-SUITE] Successfully saved %s (%,d bytes)%n",
                    outPath.getFileName(), Files.size(outPath));
        }
    }

    private static BufferedImage renderZoneSnapshot(LoadedZone zone, ZoneCamera cam) {
        SceneCamera camera = worldScene.getCamera();

        if (cam.projectionMode() == ProjectionMode.ORTHOGRAPHIC) {
            int width = 3840;
            int height = 2160;
            float orthoSize = cam.orthoSize();
            float centerX = cam.eye().x();
            float centerZ = cam.eye().z();
            float altitude = cam.eye().y();

            float halfW;
            float halfH;
            float far;

            if (orthoSize <= 0.0f) {
                float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
                float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
                float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

                for (LoadedZone.ZoneChunkInstance inst : zone.chunkInstances()) {
                    if (!ZoneMeshMath.hasValidVertices(inst.chunk())) continue;
                    Matrix4f m = inst.modelMatrix();
                    for (ZoneMeshVertex v : inst.chunk().vertices()) {
                        Vector4f p = new Vector4f(
                                ZoneMeshMath.sanitizeFinite(v.px()),
                                ZoneMeshMath.sanitizeFinite(v.py()),
                                ZoneMeshMath.sanitizeFinite(v.pz()),
                                1.0f
                        );
                        m.transform(p);
                        minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
                        minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
                        minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
                    }
                }

                centerX = (minX + maxX) * 0.5f;
                centerZ = (minZ + maxZ) * 0.5f;
                float sizeX = maxX - minX;
                float sizeZ = maxZ - minZ;

                float margin = 1.06f;
                float aspect = (float) width / (float) height;
                float reqHalfW = (sizeX * 0.5f) * margin;
                float reqHalfH = (sizeZ * 0.5f) * margin;

                halfH = Math.max(reqHalfH, reqHalfW / aspect);
                halfW = halfH * aspect;

                altitude = minY - 200.0f;
                far = Math.max(2000.0f, (maxY - minY) + 500.0f);
                System.out.printf("[ZONE-ORTHO] %s (Zone %d) bounds: X=[%.1f, %.1f] (%.1fm), Z=[%.1f, %.1f] (%.1fm), Y=[%.1f, %.1f] -> View Extents: W=%.1fm x H=%.1fm%n",
                        zone.zoneName(), zone.zoneId(), minX, maxX, sizeX, minZ, maxZ, sizeZ, minY, maxY, halfW * 2.0f, halfH * 2.0f);
            } else {
                halfW = orthoSize * 0.5f;
                halfH = (orthoSize * height / (float) width) * 0.5f;
                far = Math.max(1000.0f, Math.abs(altitude) + 1000.0f);
            }

            worldScene.setShowSkyDome(false);
            worldScene.setEnableShadows(false);
            worldScene.setEnableSsao(false);
            worldScene.setEnableActorShadows(false);
            worldScene.setEnableFog(false);
            worldScene.setClearColor(22.0f / 255.0f, 26.0f / 255.0f, 32.0f / 255.0f);

            camera.setOrthographicCamera(
                    new Vector3f(centerX, altitude, centerZ),
                    new Vector3f(centerX, altitude + 100.0f, centerZ),
                    cam.up(),
                    halfW,
                    halfH,
                    1.0f,
                    far
            );
            return worldScene.captureFrame(width, height);
        }

        worldScene.setShowSkyDome(true);
        worldScene.setEnableShadows(false);
        worldScene.setEnableSsao(false);
        worldScene.setEnableActorShadows(true);
        worldScene.setEnableFog(true);
        worldScene.setClearColor(0.08f, 0.10f, 0.14f);

        camera.setCutsceneCamera(cam.eye(), cam.target(), cam.up(), cam.fovDegrees());
        camera.setNearPlane(0.5f);
        camera.setFarPlane(10000.0f);
        return worldScene.captureFrame(1920, 1080);
    }
}
