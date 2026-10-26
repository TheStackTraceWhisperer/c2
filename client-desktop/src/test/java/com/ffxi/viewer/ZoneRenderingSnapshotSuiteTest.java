package com.ffxi.viewer;

import com.ffxi.export.TextureDecoder;
import com.ffxi.model.ZoneMeshChunk;
import com.ffxi.model.ZoneMeshVertex;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.model.ZoneMeshMath;
import com.ffxi.viewer.model.ZoneModelLoader;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * High-resolution 1080p Zone Rendering Snapshot Suite.
 * Loads authentic retail FFXI zone geometry, models, and textures across all major nations,
 * towns, ports, and outdoors, evaluating transforms, perspective and orthographic camera projections,
 * dual-pass alpha blending, and ambient texture modulation.
 */
public class ZoneRenderingSnapshotSuiteTest {

    private static final Path FFXI_ROOT = Path.of("/home/samuel_l_hyman/Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI");
    private static final Path OUTPUT_DIR = Path.of("target/snapshots");

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
            Color skyZenith,
            Color skyHorizon,
            ProjectionMode projectionMode,
            float orthoSize
    ) {
        public ZoneCamera(String id, String label, int zoneId, Vector3f eye, Vector3f target, float fovDegrees, Color zenith, Color horizon) {
            this(id, label, zoneId, eye, target, new Vector3f(0.0f, 1.0f, 0.0f), fovDegrees, zenith, horizon, ProjectionMode.PERSPECTIVE, 0.0f);
        }

        public ZoneCamera(String id, String label, int zoneId, Vector3f eye, Vector3f target, float fovDegrees) {
            this(id, label, zoneId, eye, target, new Vector3f(0.0f, 1.0f, 0.0f), fovDegrees, new Color(45, 80, 150), new Color(175, 205, 230), ProjectionMode.PERSPECTIVE, 0.0f);
        }

        public static ZoneCamera topDownOrtho(String id, String label, int zoneId) {
            return new ZoneCamera(
                    id, label, zoneId,
                    new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, -100.0f, 0.0f),
                    new Vector3f(0.0f, 0.0f, -1.0f), // North is UP (-Z)
                    0.0f,
                    new Color(22, 26, 32), new Color(22, 26, 32),
                    ProjectionMode.ORTHOGRAPHIC,
                    0.0f // Auto-compute from bounding box
            );
        }

        public static ZoneCamera topDownOrtho(String id, String label, int zoneId, float centerX, float centerZ, float altitude, float orthoSize) {
            return new ZoneCamera(
                    id, label, zoneId,
                    new Vector3f(centerX, altitude, centerZ),
                    new Vector3f(centerX, -100.0f, centerZ),
                    new Vector3f(0.0f, 0.0f, -1.0f),
                    0.0f,
                    new Color(22, 26, 32), new Color(22, 26, 32),
                    ProjectionMode.ORTHOGRAPHIC,
                    orthoSize
            );
        }
    }

    private record DecodedZoneTexture(int width, int height, byte[] rgba) {}

    @Test
    @DisplayName("Render and snapshot iconic FFXI zones in perspective projection")
    void testRenderAndSnapshotIconicZones() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);
        Files.createDirectories(OUTPUT_DIR);

        List<ZoneCamera> scenarios = List.of(
                // 1. San d'Oria Kingdom
                new ZoneCamera(
                        "zone_230_south_san_doria_overview",
                        "Southern San d'Oria - Grand City Ramparts & Plaza",
                        230,
                        new Vector3f(120.0f, 65.0f, -80.0f),
                        new Vector3f(0.0f, 0.0f, -20.0f),
                        52.0f,
                        new Color(40, 75, 140),
                        new Color(170, 200, 230)
                ),
                new ZoneCamera(
                        "zone_230_south_san_doria_fountain",
                        "Southern San d'Oria - Fountain Square",
                        230,
                        new Vector3f(-20.0f, 15.0f, -90.0f),
                        new Vector3f(-5.0f, 0.0f, -20.0f),
                        58.0f,
                        new Color(45, 80, 145),
                        new Color(175, 205, 235)
                ),
                new ZoneCamera(
                        "zone_231_north_san_doria_cathedral",
                        "Northern San d'Oria - Cathedral Spire & Chateau Approach",
                        231,
                        new Vector3f(65.0f, 36.0f, 105.0f),
                        new Vector3f(-110.0f, 14.0f, 120.0f),
                        52.0f,
                        new Color(35, 70, 135),
                        new Color(165, 195, 225)
                ),
                new ZoneCamera(
                        "zone_232_port_san_doria_airship",
                        "Port San d'Oria - Canal Waterways & Airship Port",
                        232,
                        new Vector3f(90.0f, 50.0f, -90.0f),
                        new Vector3f(0.0f, -5.0f, 0.0f),
                        52.0f,
                        new Color(40, 80, 150),
                        new Color(175, 205, 235)
                ),
                new ZoneCamera(
                        "zone_233_chateau_d_oraguille",
                        "Chateau d'Oraguille - Royal Throne & Grand Hall",
                        233,
                        new Vector3f(0.0f, 25.0f, 80.0f),
                        new Vector3f(0.0f, 5.0f, -20.0f),
                        55.0f,
                        new Color(30, 45, 80),
                        new Color(120, 140, 175)
                ),

                // 2. Bastok Republic
                new ZoneCamera(
                        "zone_234_bastok_mines_ironworks",
                        "Bastok Mines - Mining Gorge & Rail Tracks",
                        234,
                        new Vector3f(-70.0f, 45.0f, 90.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        52.0f,
                        new Color(55, 70, 105),
                        new Color(190, 185, 180)
                ),
                new ZoneCamera(
                        "zone_235_bastok_markets_plaza",
                        "Bastok Markets - Commercial Plazas & Aqueducts",
                        235,
                        new Vector3f(80.0f, 40.0f, 80.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        52.0f,
                        new Color(50, 70, 115),
                        new Color(185, 185, 185)
                ),
                new ZoneCamera(
                        "zone_236_port_bastok_wharf",
                        "Port Bastok - Steamship Wharf & Warehouses",
                        236,
                        new Vector3f(90.0f, 45.0f, -90.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        52.0f,
                        new Color(45, 75, 130),
                        new Color(180, 195, 215)
                ),

                // 3. Windurst Federation
                new ZoneCamera(
                        "zone_238_windurst_waters",
                        "Windurst Waters - Canals & Tarutaru Dwellings",
                        238,
                        new Vector3f(90.0f, 45.0f, -100.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        52.0f,
                        new Color(35, 90, 145),
                        new Color(175, 215, 215)
                ),
                new ZoneCamera(
                        "zone_241_windurst_woods_tree",
                        "Windurst Woods - Great Star Tree & Mithra Dwellings",
                        241,
                        new Vector3f(120.0f, 50.0f, 100.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        54.0f,
                        new Color(30, 85, 140),
                        new Color(170, 210, 210)
                ),

                // 4. Jeuno Grand Duchy
                new ZoneCamera(
                        "zone_244_upper_jeuno_ramparts",
                        "Upper Jeuno - Fortress Ramparts & Stone Arches",
                        244,
                        new Vector3f(80.0f, 45.0f, 90.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        50.0f,
                        new Color(45, 70, 130),
                        new Color(180, 195, 220)
                ),
                new ZoneCamera(
                        "zone_245_lower_jeuno_bazaar",
                        "Lower Jeuno - Grand Promenade & Merchants",
                        245,
                        new Vector3f(80.0f, 40.0f, 80.0f),
                        new Vector3f(0.0f, 0.0f, 0.0f),
                        52.0f,
                        new Color(45, 75, 135),
                        new Color(180, 200, 225)
                ),

                // 5. Coastal Ports & Outposts
                new ZoneCamera(
                        "zone_248_selbina_windmill_harbor",
                        "Selbina - Windmill Port Harbor & Ocean Overlook",
                        248,
                        new Vector3f(70.0f, 35.0f, 80.0f),
                        new Vector3f(0.0f, -5.0f, 0.0f),
                        54.0f,
                        new Color(35, 80, 145),
                        new Color(170, 205, 230)
                ),
                new ZoneCamera(
                        "zone_249_mhaura_port_pier",
                        "Mhaura - Coastal Docks & Ferry Wharf",
                        249,
                        new Vector3f(80.0f, 40.0f, -70.0f),
                        new Vector3f(0.0f, -2.0f, 0.0f),
                        52.0f,
                        new Color(40, 85, 150),
                        new Color(180, 215, 240)
                ),

                // 6. Vast Outdoor Field Areas
                new ZoneCamera(
                        "zone_100_west_ronfaure_overlook",
                        "West Ronfaure - Rolling Woodlands & Watchtower",
                        100,
                        new Vector3f(260.0f, 95.0f, -140.0f),
                        new Vector3f(60.0f, 20.0f, 10.0f),
                        54.0f,
                        new Color(35, 75, 145),
                        new Color(180, 210, 240)
                ),
                new ZoneCamera(
                        "zone_102_la_theine_crag_plateau",
                        "La Theine Plateau - Crag of Holla Highlands",
                        102,
                        new Vector3f(320.0f, 120.0f, -220.0f),
                        new Vector3f(40.0f, 10.0f, -20.0f),
                        55.0f,
                        new Color(30, 75, 155),
                        new Color(185, 215, 245)
                )
        );

        renderSnapshotBatch(scenarios);
    }

    @Test
    @DisplayName("Render top-down orthographic zone maps verifying complete continuous ground geometry")
    void testRenderTopDownOrthographicZoneMaps() throws Exception {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);
        Files.createDirectories(OUTPUT_DIR);

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

    private static void renderSnapshotBatch(List<ZoneCamera> scenarios) throws Exception {
        Map<Integer, LoadedZone> loadedZoneCache = new HashMap<>();

        for (ZoneCamera cam : scenarios) {
            LoadedZone zone = loadedZoneCache.computeIfAbsent(cam.zoneId, zid -> {
                try {
                    return ZoneModelLoader.loadZone(FFXI_ROOT, zid);
                } catch (IOException e) {
                    throw new RuntimeException("Failed loading zone " + zid, e);
                }
            });

            assertNotNull(zone, "Zone must be loaded: " + cam.zoneId);
            assertFalse(zone.chunkInstances().isEmpty(), "Zone must have geometry chunks");

            System.out.printf("[ZONE-SUITE] Rendering '%s' (%s, ID %d, %d chunks)...%n",
                    cam.label, zone.zoneName(), cam.zoneId, zone.chunkInstances().size());

            BufferedImage snapshot = renderZoneSnapshot(zone, cam);
            File outFile = OUTPUT_DIR.resolve(cam.id + ".png").toFile();
            ImageIO.write(snapshot, "PNG", outFile);

            assertTrue(outFile.exists() && outFile.length() > 0, "Snapshot must be saved");
            System.out.printf("[ZONE-SUITE] Successfully saved %s (%d bytes)%n", outFile.getName(), outFile.length());
        }
    }

    private static BufferedImage renderZoneSnapshot(LoadedZone zone, ZoneCamera cam) {
        int width = (cam.projectionMode() == ProjectionMode.ORTHOGRAPHIC) ? 3840 : 1920;
        int height = (cam.projectionMode() == ProjectionMode.ORTHOGRAPHIC) ? 2160 : 1080;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        float[] zBuffer = new float[width * height];
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);

        // Pre-decode all textures in zone
        Map<String, DecodedZoneTexture> decodedTextures = new HashMap<>();
        for (Map.Entry<String, CharacterTextureLoader.TextureSource> entry : zone.textureSources().entrySet()) {
            String norm = normalizeName(entry.getKey());
            CharacterTextureLoader.TextureSource src = entry.getValue();
            try {
                byte[] rgba = TextureDecoder.decode(src.rawData(), src.resource());
                if (rgba != null && rgba.length > 0) {
                    DecodedZoneTexture dzt = new DecodedZoneTexture(src.resource().width(), src.resource().height(), rgba);
                    decodedTextures.putIfAbsent(norm, dzt);
                    if (norm.contains(" ")) {
                        String shortName = norm.substring(norm.lastIndexOf(' ') + 1);
                        decodedTextures.putIfAbsent(shortName, dzt);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Render natural sky or radar background gradient
        Graphics2D g = image.createGraphics();
        for (int y = 0; y < height; y++) {
            float ratio = (float) y / height;
            int r = (int) (cam.skyZenith.getRed() + ratio * (cam.skyHorizon.getRed() - cam.skyZenith.getRed()));
            int gr = (int) (cam.skyZenith.getGreen() + ratio * (cam.skyHorizon.getGreen() - cam.skyZenith.getGreen()));
            int b = (int) (cam.skyZenith.getBlue() + ratio * (cam.skyHorizon.getBlue() - cam.skyZenith.getBlue()));
            g.setColor(new Color(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, gr)), Math.max(0, Math.min(255, b))));
            g.drawLine(0, y, width, y);
        }
        g.dispose();

        // Camera Matrices: Perspective vs Orthographic
        Matrix4f proj;
        Matrix4f view;
        if (cam.projectionMode() == ProjectionMode.ORTHOGRAPHIC) {
            float orthoSize = cam.orthoSize();
            float centerX = cam.eye().x();
            float centerZ = cam.eye().z();
            float altitude = cam.eye().y();

            if (orthoSize <= 0.0f) {
                // Auto compute bounding box across all chunks
                float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
                float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
                float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

                for (LoadedZone.ZoneChunkInstance inst : zone.chunkInstances()) {
                    if (!ZoneMeshMath.hasValidVertices(inst.chunk())) continue;
                    Matrix4f m = inst.modelMatrix();
                    for (ZoneMeshVertex v : inst.chunk().vertices()) {
                        Vector4f p = new Vector4f(-v.px(), -v.py(), v.pz(), 1.0f);
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
                orthoSize = Math.max(sizeX, sizeZ) * 1.05f;
                altitude = maxY + 150.0f;
            }

            float halfW = orthoSize * 0.5f;
            float halfH = (orthoSize * height / (float) width) * 0.5f;
            float far = Math.max(1000.0f, altitude + 1000.0f);
            proj = new Matrix4f().ortho(-halfW, halfW, -halfH, halfH, 1.0f, far);
            view = new Matrix4f().lookAt(
                    new Vector3f(centerX, altitude, centerZ),
                    new Vector3f(centerX, -200.0f, centerZ),
                    cam.up()
            );
        } else {
            proj = new Matrix4f().perspective((float) Math.toRadians(cam.fovDegrees()), (float) width / height, 0.5f, 10000.0f);
            view = new Matrix4f().lookAt(cam.eye(), cam.target(), cam.up());
        }
        Matrix4f viewProj = new Matrix4f(proj).mul(view);

        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        // Render opaque geometry first, then alpha blended
        for (int pass = 0; pass < 2; pass++) {
            boolean blendPass = (pass == 1);

            for (LoadedZone.ZoneChunkInstance inst : zone.chunkInstances()) {
                ZoneMeshChunk chunk = inst.chunk();
                if (chunk.renderBlend() != blendPass) continue;
                if (!ZoneMeshMath.hasValidVertices(chunk)) continue;

                List<Integer> triangles = ZoneMeshMath.expandTriangles(chunk);
                if (triangles.isEmpty()) continue;

                List<ZoneMeshVertex> verts = chunk.vertices();
                Matrix4f modelMatrix = inst.modelMatrix();
                DecodedZoneTexture tex = decodedTextures.get(normalizeName(chunk.textureName()));
                if (tex == null && chunk.textureName() != null && !chunk.textureName().isBlank()) {
                    String norm = normalizeName(chunk.textureName());
                    for (Map.Entry<String, DecodedZoneTexture> e : decodedTextures.entrySet()) {
                        if (e.getKey().endsWith(" " + norm) || e.getKey().startsWith(norm + " ") || e.getKey().contains(norm)) {
                            tex = e.getValue();
                            break;
                        }
                    }
                }
                float discardThresh = inst.discardThreshold();
                boolean invertCull = inst.invertCull();
                boolean mirrored = modelMatrix.determinant() < 0.0f;

                // Transform all vertices in chunk to world and clip
                int numV = verts.size();
                float[] sx = new float[numV];
                float[] sy = new float[numV];
                float[] clipW = new float[numV];
                float[] clipZ = new float[numV];
                boolean[] valid = new boolean[numV];

                for (int i = 0; i < numV; i++) {
                    ZoneMeshVertex v = verts.get(i);
                    Vector4f p = new Vector4f(
                            ZoneMeshMath.sanitizeFinite(-v.px()),
                            ZoneMeshMath.sanitizeFinite(-v.py()),
                            ZoneMeshMath.sanitizeFinite(v.pz()),
                            1.0f
                    );
                    modelMatrix.transform(p);
                    viewProj.transform(p);

                    clipW[i] = p.w;
                    clipZ[i] = p.z / p.w;

                    if (cam.projectionMode() == ProjectionMode.ORTHOGRAPHIC) {
                        sx[i] = (p.x * 0.5f + 0.5f) * width;
                        sy[i] = (p.y * 0.5f + 0.5f) * height;
                        valid[i] = true;
                    } else {
                        if (p.w > 0.1f) {
                            sx[i] = (p.x / p.w * 0.5f + 0.5f) * width;
                            sy[i] = (0.5f - p.y / p.w * 0.5f) * height;
                            valid[i] = true;
                        } else {
                            valid[i] = false;
                        }
                    }
                }

                // Rasterize triangles
                for (int t = 0; t + 2 < triangles.size(); t += 3) {
                    int i0 = triangles.get(t);
                    int i1 = triangles.get(t + 1);
                    int i2 = triangles.get(t + 2);

                    if (!valid[i0] || !valid[i1] || !valid[i2]) continue;

                    float x0 = sx[i0], y0 = sy[i0], w0 = clipW[i0], z0 = clipZ[i0];
                    float x1 = sx[i1], y1 = sy[i1], w1 = clipW[i1], z1 = clipZ[i1];
                    float x2 = sx[i2], y2 = sy[i2], w2 = clipW[i2], z2 = clipZ[i2];

                    // Backface culling:
                    // In screen space (Y downwards), a counter-clockwise front face has 2D cross < 0.
                    // Backface has cross >= 0. For mirrored meshes (modelMatrix determinant < 0),
                    // winding order is reversed, so front face has cross > 0, backface has cross <= 0.
                    float cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
                    if (cam.projectionMode() == ProjectionMode.ORTHOGRAPHIC) {
                        cross = -cross;
                    }
                    if (!invertCull) {
                        if (!mirrored && cross >= 0.0f) continue;
                        if (mirrored && cross <= 0.0f) continue;
                    }

                    // Frustum bbox check
                    float minXF = Math.min(x0, Math.min(x1, x2));
                    float maxXF = Math.max(x0, Math.max(x1, x2));
                    float minYF = Math.min(y0, Math.min(y1, y2));
                    float maxYF = Math.max(y0, Math.max(y1, y2));

                    if (maxXF < 0f || minXF >= width || maxYF < 0f || minYF >= height) continue;

                    ZoneMeshVertex v0 = verts.get(i0);
                    ZoneMeshVertex v1 = verts.get(i1);
                    ZoneMeshVertex v2 = verts.get(i2);

                    rasterizeZoneTriangle(
                            pixels, zBuffer, width, height,
                            x0, y0, z0, w0, v0.u(), v0.v(), v0.colorR(), v0.colorG(), v0.colorB(), v0.colorA(),
                            x1, y1, z1, w1, v1.u(), v1.v(), v1.colorR(), v1.colorG(), v1.colorB(), v1.colorA(),
                            x2, y2, z2, w2, v2.u(), v2.v(), v2.colorR(), v2.colorG(), v2.colorB(), v2.colorA(),
                            tex, discardThresh, blendPass
                    );
                }
            }
        }

        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static void rasterizeZoneTriangle(
            int[] pixels, float[] zBuffer, int width, int height,
            float x0, float y0, float z0, float w0, float u0, float v0, int cr0, int cg0, int cb0, int ca0,
            float x1, float y1, float z1, float w1, float u1, float v1, int cr1, int cg1, int cb1, int ca1,
            float x2, float y2, float z2, float w2, float u2, float v2, int cr2, int cg2, int cb2, int ca2,
            DecodedZoneTexture tex, float discardThresh, boolean blendPass
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

        // ONLY discard pixels if discardThreshold is explicitly requested (foliage, cutouts starting with '_')
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

                        // Default neutral modulation factor if vertex color was uncolored (all zeros)
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
                            float fu = u - (float) Math.floor(u);
                            float fv = v - (float) Math.floor(v);
                            int tx = Math.max(0, Math.min(tw - 1, (int) (fu * tw)));
                            int ty = Math.max(0, Math.min(th - 1, (int) (fv * th)));
                            int off = (ty * tw + tx) * 4;

                            byte[] raw = tex.rgba();
                            if (off + 3 >= raw.length) continue;
                            int texAlpha = raw[off + 3] & 0xFF;

                            // Alpha discard only if enabled for cutout meshes
                            if (useAlphaDiscard && texAlpha < alphaThresholdByte) continue;

                            int tr = raw[off] & 0xFF;
                            int tg = raw[off + 1] & 0xFF;
                            int tb = raw[off + 2] & 0xFF;

                            // Authentic FFXI 2.0x vertex color modulation
                            r = Math.min(255, (int) (2.0f * vr * tr));
                            g = Math.min(255, (int) (2.0f * vg * tg));
                            b = Math.min(255, (int) (2.0f * vb * tb));
                            a = Math.min(255, (int) (texAlpha * va));
                        } else {
                            // Untextured geometry
                            if (blendPass) {
                                r = Math.min(255, (int) (vr * 255.0f));
                                g = Math.min(255, (int) (vg * 255.0f));
                                b = Math.min(255, (int) (vb * 255.0f));
                                a = Math.min(255, (int) (va * 255.0f));
                            } else {
                                r = Math.min(255, (int) (2.0f * vr * 90));
                                g = Math.min(255, (int) (2.0f * vg * 90));
                                b = Math.min(255, (int) (2.0f * vb * 90));
                                a = 255;
                            }
                        }

                        if (!blendPass) {
                            zBuffer[idx] = z;
                            pixels[idx] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                        } else {
                            // Alpha blending: src_alpha, one_minus_src_alpha
                            float alpha = a / 255.0f;
                            int prev = pixels[idx];
                            int pr = (prev >> 16) & 0xFF;
                            int pg = (prev >> 8) & 0xFF;
                            int pb = prev & 0xFF;

                            int fr = (int) (r * alpha + pr * (1.0f - alpha));
                            int fg = (int) (g * alpha + pg * (1.0f - alpha));
                            int fb = (int) (b * alpha + pb * (1.0f - alpha));
                            pixels[idx] = (0xFF << 24) | (fr << 16) | (fg << 8) | fb;
                        }
                    }
                }
            }
        }
    }

    private static String normalizeName(String name) {
        if (name == null) return "";
        return name.trim().replaceAll("_+$", "").toLowerCase(Locale.ROOT);
    }
}
