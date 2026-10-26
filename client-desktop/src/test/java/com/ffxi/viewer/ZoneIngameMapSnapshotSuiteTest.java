package com.ffxi.viewer;

import com.ffxi.crypto.DllLookupExtractor;
import com.ffxi.crypto.DllLookupOffsets;
import com.ffxi.export.TextureDecoder;
import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.TextureResource;
import com.ffxi.parser.DatParser;
import com.ffxi.tables.ZoneMap;
import com.ffxi.tables.ZoneMapTable;
import com.ffxi.tables.ZoneNameTable;
import com.ffxi.viewer.renderer.FontMojiRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ZoneIngameMapSnapshotSuiteTest {
    private static final Path FFXI_ROOT = Path.of("/home/samuel_l_hyman/Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI");
    private static final Path OUTPUT_DIR = Path.of("target/snapshots");

    public record Landmark(
            String name,
            String gridCoord,
            float worldX,
            float worldZ,
            String category,
            Color markerColor
    ) {
        public Landmark(String name, String gridCoord, float worldX, float worldZ, String category) {
            this(name, gridCoord, worldX, worldZ, category, colorForCategory(category));
        }

        private static Color colorForCategory(String category) {
            return switch (category.toUpperCase()) {
                case "GATE" -> new Color(100, 220, 255);       // Cyan
                case "LANDMARK" -> new Color(255, 215, 0);   // Gold
                case "GUILD" -> new Color(150, 255, 150);      // Soft Green
                case "PORT" -> new Color(120, 180, 255);       // Azure
                case "MOG_HOUSE" -> new Color(255, 140, 220);  // Pink
                case "TAVERN" -> new Color(255, 170, 90);     // Amber
                case "CRAG" -> new Color(220, 160, 255);       // Purple
                case "OUTPOST" -> new Color(255, 100, 100);    // Coral Red
                default -> new Color(240, 240, 240);
            };
        }
    }

    public record ZoneMapScenario(
            int zoneId,
            int subZoneId,
            String snapshotId,
            String zoneName,
            String subtitle,
            List<Landmark> landmarks
    ) {}

    private ZoneMapTable zoneMapTable;
    private FileTableManager fileTableManager;
    private ZoneNameTable zoneNameTable;
    private FontMojiRenderer fontMoji;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(Files.isDirectory(FFXI_ROOT), "FFXI retail installation directory required at: " + FFXI_ROOT);
        Files.createDirectories(OUTPUT_DIR);

        // Load DllLookupOffsets and ZoneMapTable from FFXiMain.dll
        Path dllPath = FFXI_ROOT.resolve("FFXiMain.dll");
        assumeTrue(Files.exists(dllPath), "FFXiMain.dll must exist: " + dllPath);
        byte[] dllBytes = Files.readAllBytes(dllPath);

        DllLookupOffsets offsets = DllLookupExtractor.loadFromDll(new ByteReader(dllBytes, "FFXiMain.dll"));
        assertTrue(offsets.zoneMapTableOffset() > 0, "ZoneMapTable offset must be discovered in FFXiMain.dll");

        this.zoneMapTable = ZoneMapTable.load(dllBytes, offsets.zoneMapTableOffset());
        assertFalse(this.zoneMapTable.getAll().isEmpty(), "ZoneMapTable must contain entries");

        // Load FileTableManager to resolve DAT file paths from fileId
        this.fileTableManager = new FileTableManager();
        this.fileTableManager.loadFromDirectory(FFXI_ROOT.toFile());

        // Load ZoneNameTable if available
        Path zoneNamePath = FFXI_ROOT.resolve(ZoneNameTable.RESOURCE_PATH);
        if (Files.exists(zoneNamePath)) {
            this.zoneNameTable = ZoneNameTable.load(Files.readAllBytes(zoneNamePath));
        }

        // Load authentic FontMojiRenderer from ROM/0/1.DAT
        this.fontMoji = FontMojiRenderer.load(FFXI_ROOT);
        assertNotNull(this.fontMoji, "FontMojiRenderer must load from retail installation");
    }

    @Test
    @DisplayName("Decode and render all authentic in-game retail maps with 8x8 cartography grids and POI landmarks")
    void testRenderAllZoneIngameMaps() throws Exception {
        List<ZoneMapScenario> scenarios = List.of(
                // 1. San d'Oria Kingdom
                new ZoneMapScenario(
                        230, 0, "map_zone_230_south_san_doria",
                        "Southern San d'Oria", "Kingdom of San d'Oria - Capital Metropolis",
                        List.of(
                                new Landmark("West Gate (West Ronfaure)", "D-8", -140.0f, 20.0f, "GATE"),
                                new Landmark("East Gate (King Ranperre)", "I-8", 140.0f, 20.0f, "GATE"),
                                new Landmark("North San d'Oria Archway", "E-5", 0.0f, 120.0f, "GATE"),
                                new Landmark("Lion Springs Tavern", "K-6", 90.0f, 80.0f, "TAVERN"),
                                new Landmark("Auction House Plaza", "F-7", -60.0f, 40.0f, "LANDMARK"),
                                new Landmark("Mog House Residential", "M-9", 160.0f, -40.0f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        231, 0, "map_zone_231_north_san_doria",
                        "Northern San d'Oria", "Kingdom of San d'Oria - Cathedral & Royal District",
                        List.of(
                                new Landmark("Grand San d'Oria Cathedral", "D-6", -115.0f, 95.0f, "LANDMARK"),
                                new Landmark("Victory Square Plaza", "E-7", -5.0f, 36.0f, "LANDMARK"),
                                new Landmark("South Gate (Rue du Bois)", "E-8", 0.0f, -26.0f, "GATE"),
                                new Landmark("Chateau d'Oraguille Gate", "H-4", 120.0f, 140.0f, "GATE"),
                                new Landmark("East Gate / Phoenix Fly", "G-7", 80.0f, 20.0f, "GATE"),
                                new Landmark("Mog House Residential", "H-7", 140.0f, 0.0f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        232, 0, "map_zone_232_port_san_doria",
                        "Port San d'Oria", "Kingdom of San d'Oria - Flyway Terminal & Harbor",
                        List.of(
                                new Landmark("Airship Dock Departure", "H-6", 20.0f, 80.0f, "PORT"),
                                new Landmark("Cargo Room & Warehouses", "G-7", -40.0f, 20.0f, "LANDMARK"),
                                new Landmark("North San d'Oria Gate", "F-5", -80.0f, 120.0f, "GATE"),
                                new Landmark("Mog House Residential", "J-10", 120.0f, -120.0f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        233, 0, "map_zone_233_chateau_d_oraguille",
                        "Chateau d'Oraguille", "Kingdom of San d'Oria - Royal Palace & Seat of Power",
                        List.of(
                                new Landmark("Royal Throne Room", "H-6", 0.0f, 100.0f, "LANDMARK"),
                                new Landmark("Royal Pavilion Gardens", "F-7", -60.0f, 40.0f, "LANDMARK"),
                                new Landmark("Great Hall of Knights", "H-8", 0.0f, 0.0f, "LANDMARK"),
                                new Landmark("Courtyard Archway Gate", "H-9", 0.0f, -80.0f, "GATE")
                        )
                ),

                // 2. Republic of Bastok
                new ZoneMapScenario(
                        234, 0, "map_zone_234_bastok_mines",
                        "Bastok Mines", "Republic of Bastok - Industrial District & Mining Guild",
                        List.of(
                                new Landmark("Ore Exchange & Miner Guild", "H-7", 0.0f, 40.0f, "GUILD"),
                                new Landmark("Zeruhn Mines Entrance", "I-9", 60.0f, -80.0f, "GATE"),
                                new Landmark("Markets Archway", "E-7", -120.0f, 20.0f, "GATE"),
                                new Landmark("Mog House Residential", "K-8", 140.0f, -20.0f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        235, 0, "map_zone_235_bastok_markets",
                        "Bastok Markets", "Republic of Bastok - Commercial Center & Steaming Sheep",
                        List.of(
                                new Landmark("Auction House / Fountain", "E-8", -60.0f, 0.0f, "LANDMARK"),
                                new Landmark("Steaming Sheep Restaurant", "E-8", -80.0f, -20.0f, "TAVERN"),
                                new Landmark("South Gate (N. Gustaberg)", "D-10", -120.0f, -140.0f, "GATE"),
                                new Landmark("Port Bastok Archway", "F-5", 0.0f, 120.0f, "GATE"),
                                new Landmark("Mog House Residential", "H-9", 40.0f, -80.0f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        236, 0, "map_zone_236_port_bastok",
                        "Port Bastok", "Republic of Bastok - Heavy Airship & Steamer Docks",
                        List.of(
                                new Landmark("Airship Agency Terminal", "H-6", 0.0f, 60.0f, "PORT"),
                                new Landmark("Cid's Workshop (Chief Eng.)", "D-7", -140.0f, 20.0f, "LANDMARK"),
                                new Landmark("Steamers Cargo Dock", "I-8", 60.0f, -40.0f, "PORT"),
                                new Landmark("Mog House Residential", "K-6", 140.0f, 60.0f, "MOG_HOUSE")
                        )
                ),

                // 3. Federation of Windurst
                new ZoneMapScenario(
                        238, 1, "map_zone_238_windurst_waters_north",
                        "Windurst Waters (North)", "Federation of Windurst - Canals & Academic Guilds",
                        List.of(
                                new Landmark("The Aurastery (Magic)", "E-8", -80.0f, -40.0f, "GUILD"),
                                new Landmark("The Rhinostery (Herbology)", "F-9", -20.0f, -80.0f, "GUILD"),
                                new Landmark("North Gate (W. Sarutabaruta)", "F-5", -20.0f, 140.0f, "GATE"),
                                new Landmark("Mog House Residential", "L-10", 160.0f, -120.0f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        238, 2, "map_zone_238_windurst_waters_south",
                        "Windurst Waters (South)", "Federation of Windurst - Southern Canals & Taverns",
                        List.of(
                                new Landmark("Timbre Timbers Tavern", "F-10", -40.0f, -120.0f, "TAVERN"),
                                new Landmark("Windurst Walls Archway", "H-9", 40.0f, -80.0f, "GATE"),
                                new Landmark("Canal Central Bridge", "G-8", 0.0f, -40.0f, "LANDMARK")
                        )
                ),
                new ZoneMapScenario(
                        241, 0, "map_zone_241_windurst_woods",
                        "Windurst Woods", "Federation of Windurst - Forest Promenade & Bazaar",
                        List.of(
                                new Landmark("Mithra Pride / Chocobos", "F-11", -40.0f, -140.0f, "LANDMARK"),
                                new Landmark("Manaclipper Ferry Dock", "I-11", 80.0f, -140.0f, "PORT"),
                                new Landmark("Auction House & Open Bazaar", "G-10", 0.0f, -100.0f, "LANDMARK"),
                                new Landmark("Heavens Tower Portal", "H-11", 40.0f, -140.0f, "LANDMARK"),
                                new Landmark("Mog House Residential", "K-12", 140.0f, -180.0f, "MOG_HOUSE")
                        )
                ),

                // 4. Grand Duchy of Jeuno
                new ZoneMapScenario(
                        244, 0, "map_zone_244_upper_jeuno",
                        "Upper Jeuno", "Grand Duchy of Jeuno - High Promenade & Embassy Bridge",
                        List.of(
                                new Landmark("Ru'Lude Palace Gate", "H-7", 20.0f, 20.0f, "GATE"),
                                new Landmark("Batallia Downs Gate", "F-5", -80.0f, 120.0f, "GATE"),
                                new Landmark("Temple of the Goddess", "G-8", -20.0f, -40.0f, "LANDMARK"),
                                new Landmark("Mog House Residential", "I-11", 60.0f, -160.0f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        245, 0, "map_zone_245_lower_jeuno",
                        "Lower Jeuno", "Grand Duchy of Jeuno - Central Bazaar & Neptune's Spire",
                        List.of(
                                new Landmark("Rolanberry Fields Gate", "J-11", 100.0f, -160.0f, "GATE"),
                                new Landmark("Neptune's Spire Tavern", "H-8", 20.0f, -20.0f, "TAVERN"),
                                new Landmark("Auction House & Bazaar", "H-9", 0.0f, -60.0f, "LANDMARK"),
                                new Landmark("Port Jeuno Stairs", "I-7", 40.0f, 40.0f, "GATE"),
                                new Landmark("Mog House Residential", "H-10", 0.0f, -120.0f, "MOG_HOUSE")
                        )
                ),

                // 5. Outposts, Ports & Open Fields
                new ZoneMapScenario(
                        248, 0, "map_zone_248_selbina",
                        "Selbina", "Zepwell / Kuzotz Sea Route - Coastal Free Port",
                        List.of(
                                new Landmark("Passenger Ferry Pier", "H-10", 20.0f, -120.0f, "PORT"),
                                new Landmark("Town Hall / Mayor's Office", "H-7", 0.0f, 20.0f, "LANDMARK"),
                                new Landmark("Weavers' Guild & Sheep", "G-8", -40.0f, -20.0f, "GUILD"),
                                new Landmark("Valkurm Dunes Town Gate", "F-6", -80.0f, 80.0f, "GATE")
                        )
                ),
                new ZoneMapScenario(
                        249, 0, "map_zone_249_mhaura",
                        "Mhaura", "Li'Telor / Mindartia Trade Harbor & Ferry Pier",
                        List.of(
                                new Landmark("Steamship Ferry Pier", "H-8", 20.0f, -20.0f, "PORT"),
                                new Landmark("Blacksmith Metalworks", "G-9", -40.0f, -60.0f, "GUILD"),
                                new Landmark("Town Center Plaza", "G-8", -20.0f, -20.0f, "LANDMARK"),
                                new Landmark("Buburimu Peninsula Gate", "F-6", -80.0f, 80.0f, "GATE")
                        )
                ),
                new ZoneMapScenario(
                        100, 0, "map_zone_100_west_ronfaure",
                        "West Ronfaure", "Kingdom of San d'Oria - Emerald Canopy Woodlands",
                        List.of(
                                new Landmark("Southern San d'Oria Gates", "I-6", 280.0f, 100.0f, "GATE"),
                                new Landmark("Knightwell Lake & Watchtower", "E-7", -450.0f, -150.0f, "LANDMARK"),
                                new Landmark("Regional Outpost", "G-9", -100.0f, -500.0f, "OUTPOST"),
                                new Landmark("Ghelsba Outpost Entrance", "E-4", -500.0f, 650.0f, "GATE")
                        )
                ),
                new ZoneMapScenario(
                        102, 0, "map_zone_102_la_theine_plateau",
                        "La Theine Plateau", "Gustaberg - Ronfaure Border - Rolling Highlands",
                        List.of(
                                new Landmark("Crag of Holla (Teleport)", "K-8", 480.0f, -100.0f, "CRAG"),
                                new Landmark("Ordelle's Caves Ravine", "H-7", 0.0f, 120.0f, "GATE"),
                                new Landmark("Regional Outpost", "G-9", -150.0f, -480.0f, "OUTPOST"),
                                new Landmark("West Ronfaure Canyon Pass", "D-4", -650.0f, 650.0f, "GATE")
                        )
                )
        );

        for (ZoneMapScenario scenario : scenarios) {
            renderMapSnapshot(scenario);
        }
    }

    private void renderMapSnapshot(ZoneMapScenario scenario) throws Exception {
        Optional<ZoneMap> mapOpt = zoneMapTable.get(scenario.zoneId, scenario.subZoneId);
        assertTrue(mapOpt.isPresent(), "ZoneMap entry must exist for zone " + scenario.zoneId + " subZone " + scenario.subZoneId);

        ZoneMap zm = mapOpt.get();
        Path datPath = fileTableManager.getFileNioPath(zm.fileId());
        assertNotNull(datPath, "DAT path must be resolved for fileId " + zm.fileId());
        assertTrue(Files.exists(datPath), "DAT file must exist on disk: " + datPath);
        String datRelPath = FFXI_ROOT.relativize(datPath).toString().replace('\\', '/');

        byte[] datBytes = Files.readAllBytes(datPath);
        DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(datBytes, datPath.getFileName().toString()));
        assertNotNull(graph, "DatResourceGraph must parse for " + datRelPath);

        List<TextureResource> textures = new ArrayList<>();
        collectTextures(graph.rootDirectory(), textures);
        assertFalse(textures.isEmpty(), "Map DAT must contain a texture resource: " + datRelPath);

        TextureResource mapTexture = textures.get(0);
        byte[] rgba = TextureDecoder.decode(datBytes, mapTexture);
        assertNotNull(rgba, "Texture must decode to RGBA: " + mapTexture.textureName());

        BufferedImage rawMapImg = toBufferedImage(rgba, mapTexture.width(), mapTexture.height());

        // Compose full high-resolution cartographic snapshot canvas
        int canvasW = 1280;
        int canvasH = 1280;
        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // 1. Sleek dark slate background
        g.setColor(new Color(14, 18, 28));
        g.fillRect(0, 0, canvasW, canvasH);

        // Subtle tech grid pattern across background
        g.setColor(new Color(24, 32, 48, 120));
        for (int x = 0; x < canvasW; x += 40) g.drawLine(x, 0, x, canvasH);
        for (int y = 0; y < canvasH; y += 40) g.drawLine(0, y, canvasW, y);

        // 2. Map viewport geometry: 1024x1024 centered square
        int mapX = 128;
        int mapY = 128;
        int mapW = 1024;
        int mapH = 1024;

        // Draw outer map border and shadow
        g.setColor(new Color(10, 14, 22, 220));
        g.fillRect(mapX - 4, mapY - 4, mapW + 8, mapH + 8);

        // Draw authentic in-game map texture
        g.drawImage(rawMapImg, mapX, mapY, mapW, mapH, null);

        // 3. Render Authentic FFXI 8x8 Cartography Grid Overlay
        drawCartographyGrid(canvas, g, mapX, mapY, mapW, mapH);

        // 4. Render Landmark Markers & Pinpoint Reticles
        drawLandmarks(canvas, g, scenario.landmarks, zm, mapX, mapY, mapW, mapH);

        // 5. Draw Cartography Telemetry Header & Compass Rose
        drawHeaderAndCompass(canvas, g, scenario, zm, datRelPath, mapTexture, canvasW);

        // 6. Draw Footer Telemetry Bar
        drawFooterTelemetry(canvas, g, zm, canvasW, canvasH);

        g.dispose();

        // 7. Save snapshot to target/snapshots/
        File outFile = OUTPUT_DIR.resolve(scenario.snapshotId + ".png").toFile();
        ImageIO.write(canvas, "PNG", outFile);

        assertTrue(outFile.exists() && outFile.length() > 50000, "Map snapshot must be written: " + outFile.getName());
        System.out.printf("[MAP-SUITE] Successfully rendered '%s' (%s, ID %d) -> %s (%,d bytes)%n",
                scenario.zoneName, scenario.subtitle, scenario.zoneId, outFile.getName(), outFile.length());
    }

    private void drawCartographyGrid(BufferedImage canvas, Graphics2D g, int mx, int my, int mw, int mh) {
        float cellW = mw / 8.0f;
        float cellH = mh / 8.0f;

        // Grid lines inside map area
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(new Color(100, 190, 240, 70));

        for (int col = 1; col < 8; col++) {
            int cx = Math.round(mx + col * cellW);
            g.drawLine(cx, my, cx, my + mh);
        }
        for (int row = 1; row < 8; row++) {
            int cy = Math.round(my + row * cellH);
            g.drawLine(mx, cy, mx + mw, cy);
        }

        // Crosshairs at internal grid intersections
        g.setColor(new Color(150, 225, 255, 140));
        g.setStroke(new BasicStroke(1.5f));
        int tick = 6;
        for (int col = 1; col < 8; col++) {
            int cx = Math.round(mx + col * cellW);
            for (int row = 1; row < 8; row++) {
                int cy = Math.round(my + row * cellH);
                g.drawLine(cx - tick, cy, cx + tick, cy);
                g.drawLine(cx, cy - tick, cx, cy + tick);
            }
        }

        // Coordinate Column Headers (A to H) using authentic FontMojiRenderer
        String[] cols = {"A", "B", "C", "D", "E", "F", "G", "H"};

        for (int i = 0; i < 8; i++) {
            int colCenter = Math.round(mx + (i + 0.5f) * cellW);
            int tw = measureTextWidth(cols[i], 1);

            // Top letter
            fontMoji.drawString(canvas, cols[i], colCenter - tw / 2, my - 24, FontMojiRenderer.COLOR_GOLD, 1);

            // Bottom letter
            fontMoji.drawString(canvas, cols[i], colCenter - tw / 2, my + mh + 8, FontMojiRenderer.COLOR_WHITE, 1);
        }

        // Coordinate Row Sidebars (1 to 8) using authentic FontMojiRenderer
        for (int i = 0; i < 8; i++) {
            int rowCenter = Math.round(my + (i + 0.5f) * cellH);
            String rowStr = String.valueOf(i + 1);
            int tw = measureTextWidth(rowStr, 1);

            // Left number
            fontMoji.drawString(canvas, rowStr, mx - tw - 12, rowCenter - 8, FontMojiRenderer.COLOR_GOLD, 1);

            // Right number
            fontMoji.drawString(canvas, rowStr, mx + mw + 8, rowCenter - 8, FontMojiRenderer.COLOR_WHITE, 1);
        }

        // Outer map border
        g.setStroke(new BasicStroke(2.0f));
        g.setColor(new Color(210, 180, 90));
        g.drawRect(mx, my, mw, mh);
    }

    private void drawLandmarks(BufferedImage canvas, Graphics2D g, List<Landmark> landmarks, ZoneMap zm, int mx, int my, int mw, int mh) {
        if (landmarks == null || landmarks.isEmpty()) return;

        float mapSpan = zm.mapSizePx() > 0 ? (float) zm.mapSizePx() : 640.0f;
        float xOff = zm.xOffset();
        float yOff = zm.yOffset();

        for (Landmark lm : landmarks) {
            // Map worldX, worldZ to pixel coordinates
            float normX = (lm.worldX - xOff) / mapSpan;
            float normY = (lm.worldZ - yOff) / mapSpan;

            // Clamp slightly inside map boundary
            normX = Math.max(0.01f, Math.min(0.99f, normX));
            normY = Math.max(0.01f, Math.min(0.99f, normY));

            int px = Math.round(mx + normX * mw);
            int py = Math.round(my + normY * mh);

            Color col = lm.markerColor;

            // Outer pulse glow
            g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 60));
            g.fillOval(px - 10, py - 10, 20, 20);

            // Inner circle
            g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 180));
            g.setStroke(new BasicStroke(1.8f));
            g.drawOval(px - 6, py - 6, 12, 12);

            // Center pin dot
            g.setColor(Color.WHITE);
            g.fillOval(px - 2, py - 2, 4, 4);

            // Crosshair tick marks
            g.setColor(col);
            g.drawLine(px - 8, py, px - 4, py);
            g.drawLine(px + 4, py, px + 8, py);
            g.drawLine(px, py - 8, px, py - 4);
            g.drawLine(px, py + 4, px, py + 8);

            // Draw clean badge callout
            drawLandmarkBadge(canvas, g, lm, px, py, mx, my, mw, mh, col);
        }
    }

    private void drawLandmarkBadge(BufferedImage canvas, Graphics2D g, Landmark lm, int px, int py, int mx, int my, int mw, int mh, Color col) {
        String line1 = "[" + lm.gridCoord + "] " + lm.name;
        String line2 = String.format("(X: %.1f, Z: %.1f)", lm.worldX, lm.worldZ);

        int textW = Math.max(measureTextWidth(line1, 1), measureTextWidth(line2, 1));
        int boxW = textW + 16;
        int boxH = 34;

        // Smart callout positioning to stay within map area
        int bx = px + 12;
        int by = py - 17;
        if (bx + boxW > mx + mw - 10) {
            bx = px - boxW - 12;
        }
        if (by < my + 10) {
            by = py + 12;
        } else if (by + boxH > my + mh - 10) {
            by = py - boxH - 12;
        }

        // Connecting pointer line
        g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 160));
        g.setStroke(new BasicStroke(1.0f));
        int targetX = (bx > px) ? bx : bx + boxW;
        int targetY = by + boxH / 2;
        g.drawLine(px, py, targetX, targetY);

        // Badge background
        g.setColor(new Color(16, 22, 34, 230));
        g.fillRoundRect(bx, by, boxW, boxH, 8, 8);

        // Badge border
        g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 180));
        g.drawRoundRect(bx, by, boxW, boxH, 8, 8);

        // Text lines using FontMojiRenderer
        fontMoji.drawString(canvas, line1, bx + 8, by + 4, FontMojiRenderer.COLOR_WHITE, 1);
        fontMoji.drawString(canvas, line2, bx + 8, by + 18, FontMojiRenderer.COLOR_INFO_BLUE, 1);
    }

    private void drawHeaderAndCompass(BufferedImage canvas, Graphics2D g, ZoneMapScenario scenario, ZoneMap zm, String datRel, TextureResource tr, int canvasW) {
        // Category Badge
        fontMoji.drawString(canvas, "FINAL FANTASY XI  |  AUTHENTIC CARTOGRAPHY & GRID SYSTEM", 40, 22, FontMojiRenderer.COLOR_GOLD, 1);

        // Title (scale 2 = 32px height)
        String title = scenario.zoneName.toUpperCase() + "  [ZONE " + scenario.zoneId + "]";
        fontMoji.drawString(canvas, title, 40, 44, FontMojiRenderer.COLOR_WHITE, 2);

        // Subtitle & DAT path
        String meta = String.format("%s  |  DAT: %s (FileID %d)  |  Texture: %s (%dx%d %s)",
                scenario.subtitle, datRel, zm.fileId(), tr.textureName().trim().toUpperCase(), tr.width(), tr.height(),
                tr.dxtType() != null && !tr.dxtType().isBlank() ? tr.dxtType() : "PALETTE-256");
        fontMoji.drawString(canvas, meta, 40, 84, FontMojiRenderer.COLOR_INFO_BLUE, 1);

        // Compass Rose in upper right corner
        int cx = canvasW - 80;
        int cy = 60;
        drawCompassRose(canvas, g, cx, cy);
    }

    private void drawCompassRose(BufferedImage canvas, Graphics2D g, int cx, int cy) {
        // Outer ring
        g.setColor(new Color(100, 160, 220, 100));
        g.setStroke(new BasicStroke(1.2f));
        g.drawOval(cx - 24, cy - 24, 48, 48);

        // North point (Gold)
        g.setColor(new Color(255, 215, 0));
        int[] nx = {cx, cx + 5, cx, cx - 5};
        int[] ny = {cy - 22, cy - 2, cy + 2, cy - 2};
        g.fillPolygon(nx, ny, 4);

        // South point (Cyan)
        g.setColor(new Color(80, 160, 220));
        int[] sx = {cx, cx + 5, cx, cx - 5};
        int[] sy = {cy + 22, cy + 2, cy - 2, cy + 2};
        g.fillPolygon(sx, sy, 4);

        // East & West points
        int[] ex = {cx + 22, cx + 2, cx - 2, cx + 2};
        int[] ey = {cy, cy + 5, cy, cy - 5};
        g.fillPolygon(ex, ey, 4);

        int[] wx = {cx - 22, cx - 2, cx + 2, cx - 2};
        int[] wy = {cy, cy + 5, cy, cy - 5};
        g.fillPolygon(wx, wy, 4);

        // Compass labels with FontMojiRenderer
        fontMoji.drawString(canvas, "N", cx - 4, cy - 38, FontMojiRenderer.COLOR_GOLD, 1);
        fontMoji.drawString(canvas, "S", cx - 4, cy + 26, FontMojiRenderer.COLOR_INFO_BLUE, 1);
        fontMoji.drawString(canvas, "W", cx - 36, cy - 8, FontMojiRenderer.COLOR_WHITE, 1);
        fontMoji.drawString(canvas, "E", cx + 28, cy - 8, FontMojiRenderer.COLOR_WHITE, 1);
    }

    private void drawFooterTelemetry(BufferedImage canvas, Graphics2D g, ZoneMap zm, int canvasW, int canvasH) {
        int fy = canvasH - 42;

        // Background footer bar
        g.setColor(new Color(18, 24, 38, 200));
        g.fillRoundRect(30, fy - 18, canvasW - 60, 40, 8, 8);
        g.setColor(new Color(80, 120, 160, 120));
        g.setStroke(new BasicStroke(1.0f));
        g.drawRoundRect(30, fy - 18, canvasW - 60, 40, 8, 8);

        float span = zm.mapSizePx() > 0 ? (float) zm.mapSizePx() : 640.0f;
        float xMin = zm.xOffset();
        float xMax = zm.xOffset() + span;
        float zMin = zm.yOffset();
        float zMax = zm.yOffset() + span;
        float scalePxPerM = 1024.0f / span;

        String telemetryLeft = String.format("Grid: 8x8 (%.0fm x %.0fm) | Offsets: X=%.1fm, Y=%.1fm | Scale: %.2f px/m",
                span, span, zm.xOffset(), zm.yOffset(), scalePxPerM);
        fontMoji.drawString(canvas, telemetryLeft, 45, fy - 8, FontMojiRenderer.COLOR_WHITE, 1);

        String telemetryRight = String.format("Extents: X[%.0f..%.0f] Z[%.0f..%.0f] | N=-Z S=+Z W=-X E=+X",
                xMin, xMax, zMin, zMax);
        int rw = measureTextWidth(telemetryRight, 1);
        fontMoji.drawString(canvas, telemetryRight, canvasW - rw - 45, fy - 8, FontMojiRenderer.COLOR_GOLD, 1);
    }

    private static int measureTextWidth(String text, int scale) {
        if (text == null || text.isEmpty()) return 0;
        int w = 0;
        for (char c : text.toCharArray()) {
            if (c >= 'A' && c <= 'Z') w += 10 * scale;
            else if (c >= 'a' && c <= 'z') w += 8 * scale;
            else if (c >= '0' && c <= '9') w += 8 * scale;
            else if (c == ' ' || c == '.' || c == ',') w += 6 * scale;
            else w += 8 * scale;
        }
        return w;
    }

    private static void collectTextures(DirectoryResource dir, List<TextureResource> list) {
        if (dir == null) return;
        for (ResourceNode child : dir.children()) {
            if (child instanceof TextureResource tr) {
                list.add(tr);
            } else if (child instanceof DirectoryResource subDir) {
                collectTextures(subDir, list);
            }
        }
    }

    private static BufferedImage toBufferedImage(byte[] rgba, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int off = i * 4;
            int r = rgba[off] & 0xFF;
            int g = rgba[off + 1] & 0xFF;
            int b = rgba[off + 2] & 0xFF;
            int a = rgba[off + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }
}
