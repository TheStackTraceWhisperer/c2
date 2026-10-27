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
                                new Landmark("West Gate (West Ronfaure)", "F-10", -113.4f, -57.4f, "GATE"),
                                new Landmark("East Gate (East Ronfaure)", "L-10", 113.5f, -57.4f, "GATE"),
                                new Landmark("North San d'Oria Archway", "I-7", 0.0f, 58.0f, "GATE"),
                                new Landmark("Lion Springs Tavern", "K-6", 99.4f, 117.3f, "TAVERN"),
                                new Landmark("Tanners' Guild", "E-8", -187.7f, 19.4f, "GUILD"),
                                new Landmark("Mog House Residential", "M-5", 164.9f, 164.8f, "MOG_HOUSE"),
                                new Landmark("Count's Manor", "B-6", -294.1f, 93.0f, "LANDMARK")
                        )
                ),
                new ZoneMapScenario(
                        231, 0, "map_zone_231_north_san_doria",
                        "Northern San d'Oria", "Kingdom of San d'Oria - Cathedral & Royal District",
                        List.of(
                                new Landmark("Grand San d'Oria Cathedral", "M-6", 149.9f, 141.9f, "LANDMARK"),
                                new Landmark("Chateau d'Oraguille Gate", "I-7", 0.0f, 99.0f, "GATE"),
                                new Landmark("South Gate (Rue du Bois)", "I-10", -0.1f, -36.1f, "GATE"),
                                new Landmark("West Ronfaure Gate", "D-8", -252.2f, 43.9f, "GATE"),
                                new Landmark("Port San d'Oria Gate", "G-3", -123.5f, 270.5f, "GATE"),
                                new Landmark("Carpenters' Guild", "E-3", -170.1f, 266.7f, "GUILD"),
                                new Landmark("Mog House Residential", "M-10", 135.0f, -8.4f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        232, 0, "map_zone_232_port_san_doria",
                        "Port San d'Oria", "Kingdom of San d'Oria - Flyway Terminal & Harbor",
                        List.of(
                                new Landmark("North San d'Oria Gate", "F-10", -111.8f, -136.5f, "GATE"),
                                new Landmark("Airship Terminal / Departures", "H-6", -25.0f, 28.0f, "PORT"),
                                new Landmark("Cargo Room & Warehouses", "H-10", -16.0f, -126.1f, "LANDMARK"),
                                new Landmark("Rusty Anchor Tavern", "G-7", -56.7f, -36.0f, "TAVERN"),
                                new Landmark("Mog House Residential", "J-10", 83.3f, -139.3f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        233, 0, "map_zone_233_chateau_d_oraguille",
                        "Chateau d'Oraguille", "Kingdom of San d'Oria - Royal Palace & Seat of Power",
                        List.of(
                                new Landmark("Royal Throne Room (King Destin)", "H-8", 0.0f, 42.4f, "LANDMARK"),
                                new Landmark("Prince Royal's Room (Trion)", "H-7", -38.0f, 88.3f, "LANDMARK"),
                                new Landmark("Great Hall of Knights (Halver)", "I-9", 2.4f, 2.0f, "LANDMARK"),
                                new Landmark("Bostaunieux Oubliette", "I-8", 12.5f, 24.0f, "GATE"),
                                new Landmark("Temple Knight Quarters", "I-9", 19.4f, -4.0f, "LANDMARK")
                        )
                ),

                // 2. Republic of Bastok
                new ZoneMapScenario(
                        234, 0, "map_zone_234_bastok_mines",
                        "Bastok Mines", "Republic of Bastok - Industrial District & Mining Guild",
                        List.of(
                                new Landmark("South Gustaberg Gate", "H-10", -16.0f, -136.0f, "GATE"),
                                new Landmark("Zeruhn Mines Entrance", "D-7", -196.0f, -21.9f, "GATE"),
                                new Landmark("Bastok Markets Archway", "F-5", -104.1f, 84.6f, "GATE"),
                                new Landmark("Alchemists' Guild", "K-7", 94.0f, -4.0f, "GUILD"),
                                new Landmark("Bat's Lair Tavern", "H-6", -26.0f, 41.7f, "TAVERN"),
                                new Landmark("Mog House Residential", "K-8", 121.8f, -72.0f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        235, 0, "map_zone_235_bastok_markets",
                        "Bastok Markets", "Republic of Bastok - Commercial Center & Steaming Sheep",
                        List.of(
                                new Landmark("South Gustaberg Gate", "D-11", -365.7f, -185.6f, "GATE"),
                                new Landmark("Bastok Mines Archway", "H-11", -202.2f, -197.8f, "GATE"),
                                new Landmark("Port Bastok Archway", "G-4", -234.0f, 92.3f, "GATE"),
                                new Landmark("Metalworks Archway", "H-7", -212.0f, 0.0f, "GATE"),
                                new Landmark("Goldsmiths' Guild", "H-8", -202.9f, -65.6f, "GUILD"),
                                new Landmark("Steaming Sheep Restaurant", "E-6", -152.9f, 4.8f, "TAVERN"),
                                new Landmark("Mog House Residential", "J-6", -146.2f, 29.9f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        236, 0, "map_zone_236_port_bastok",
                        "Port Bastok", "Republic of Bastok - Heavy Airship & Steamer Docks",
                        List.of(
                                new Landmark("North Gustaberg Gate", "L-6", 150.3f, 6.3f, "GATE"),
                                new Landmark("Bastok Markets Archway", "D-9", -193.5f, -84.0f, "GATE"),
                                new Landmark("Airship Agency Terminal", "H-7", -30.0f, -70.0f, "PORT"),
                                new Landmark("Steamers Cargo Dock", "H-8", 0.0f, -130.0f, "PORT"),
                                new Landmark("Cid's Workshop (Chief Eng.)", "E-7", -129.3f, -20.0f, "LANDMARK"),
                                new Landmark("Mog House Residential", "J-13", 60.0f, -252.8f, "MOG_HOUSE")
                        )
                ),

                // 3. Federation of Windurst
                new ZoneMapScenario(
                        238, 1, "map_zone_238_windurst_waters_north",
                        "Windurst Waters (North)", "Federation of Windurst - Canals & Academic Guilds",
                        List.of(
                                new Landmark("West Sarutabaruta Gate", "G-4", -40.2f, 248.8f, "GATE"),
                                new Landmark("The Aurastery (Magic Guild)", "K-6", 165.0f, 179.9f, "GUILD"),
                                new Landmark("The Rhinostery (Herbology)", "H-14", 3.2f, -163.0f, "GUILD"),
                                new Landmark("Port Windurst Archway", "F-15", -59.6f, -209.0f, "GATE"),
                                new Landmark("Windurst Walls Archway", "K-9", 160.1f, 60.4f, "GATE"),
                                new Landmark("Mog House Residential", "K-12", 159.9f, -64.1f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        238, 2, "map_zone_238_windurst_waters_south",
                        "Windurst Waters (South)", "Federation of Windurst - Southern Canals & Taverns",
                        List.of(
                                new Landmark("Timbre Timbers Tavern", "H-3", -67.9f, 21.8f, "TAVERN"),
                                new Landmark("Culinarians' Guild", "G-3", -119.2f, 57.7f, "GUILD"),
                                new Landmark("The Optistery", "H-2", -57.0f, 92.1f, "GUILD"),
                                new Landmark("Federal Magic Research", "G-8", -88.0f, -151.3f, "LANDMARK"),
                                new Landmark("Canal Promenade", "J-2", 8.7f, 68.0f, "LANDMARK")
                        )
                ),
                new ZoneMapScenario(
                        241, 0, "map_zone_241_windurst_woods",
                        "Windurst Woods", "Federation of Windurst - Forest Promenade & Bazaar",
                        List.of(
                                new Landmark("East Sarutabaruta Gate", "K-10", 126.7f, -40.0f, "GATE"),
                                new Landmark("Port Windurst Archway", "F-11", -94.2f, -72.7f, "GATE"),
                                new Landmark("Windurst Walls Archway", "G-7", -60.7f, 92.0f, "GATE"),
                                new Landmark("Weavers' Guild", "G-12", -42.4f, -118.3f, "GUILD"),
                                new Landmark("Chocobo Stables", "K-12", 127.0f, -134.1f, "LANDMARK"),
                                new Landmark("The Manustery", "H-9", -9.1f, -13.1f, "GUILD"),
                                new Landmark("Mog House Residential", "E-8", -144.7f, 40.0f, "MOG_HOUSE")
                        )
                ),

                // 4. Grand Duchy of Jeuno
                new ZoneMapScenario(
                        244, 0, "map_zone_244_upper_jeuno",
                        "Upper Jeuno", "Grand Duchy of Jeuno - High Promenade & Embassy Bridge",
                        List.of(
                                new Landmark("Batallia Downs Gate", "F-5", -106.1f, 190.0f, "GATE"),
                                new Landmark("Ru'Lude Gardens Gate", "J-10", 46.0f, -30.9f, "GATE"),
                                new Landmark("Lower Jeuno Archway", "I-11", 4.8f, -54.9f, "GATE"),
                                new Landmark("Goddess Temple", "H-9", -35.1f, 23.1f, "LANDMARK"),
                                new Landmark("Marble Bridge Tavern", "F-7", -97.8f, 90.8f, "TAVERN"),
                                new Landmark("Mog House Residential", "J-11", 49.2f, -82.9f, "MOG_HOUSE")
                        )
                ),
                new ZoneMapScenario(
                        245, 0, "map_zone_245_lower_jeuno",
                        "Lower Jeuno", "Grand Duchy of Jeuno - Central Bazaar & Neptune's Spire",
                        List.of(
                                new Landmark("Rolanberry Fields Gate", "F-11", -123.1f, -200.7f, "GATE"),
                                new Landmark("Upper Jeuno Stairs", "I-5", 0.2f, 59.6f, "GATE"),
                                new Landmark("Port Jeuno Stairs", "J-6", 29.7f, 41.4f, "GATE"),
                                new Landmark("Neptune's Spire Tavern", "J-7", 28.1f, -8.3f, "TAVERN"),
                                new Landmark("Tenshodo H.Q.", "J-8", 38.2f, -57.8f, "LANDMARK"),
                                new Landmark("Merry Minstrel Tavern", "H-8", -28.7f, -61.2f, "TAVERN"),
                                new Landmark("Mog House Residential", "J-5", 43.9f, 88.6f, "MOG_HOUSE")
                        )
                ),

                // 5. Outposts, Ports & Open Fields
                new ZoneMapScenario(
                        248, 0, "map_zone_248_selbina",
                        "Selbina", "Zepwell / Kuzotz Sea Route - Coastal Free Port",
                        List.of(
                                new Landmark("Valkurm Dunes Town Gate", "H-6", 16.9f, 104.3f, "GATE"),
                                new Landmark("Passenger Ferry Pier", "H-11", 10.0f, -80.0f, "PORT"),
                                new Landmark("Mayor's Residence", "G-9", -59.9f, 1.9f, "LANDMARK"),
                                new Landmark("Weavers' Guild", "I-9", 21.1f, 7.6f, "GUILD"),
                                new Landmark("Fishermen's Guild", "H-9", -20.2f, 7.6f, "GUILD")
                        )
                ),
                new ZoneMapScenario(
                        249, 0, "map_zone_249_mhaura",
                        "Mhaura", "Li'Telor / Mindartia Trade Harbor & Ferry Pier",
                        List.of(
                                new Landmark("Buburimu Peninsula Gate", "H-7", -0.2f, 121.0f, "GATE"),
                                new Landmark("Steamship Ferry Pier", "I-8", 30.0f, 70.0f, "PORT"),
                                new Landmark("Blacksmiths' Guild", "G-9", -53.2f, 32.0f, "GUILD"),
                                new Landmark("Goldsmiths' Guild", "G-8", -30.9f, 66.9f, "GUILD"),
                                new Landmark("Sailors' Stay Tavern", "H-9", 20.0f, 57.2f, "TAVERN")
                        )
                ),
                new ZoneMapScenario(
                        100, 0, "map_zone_100_west_ronfaure",
                        "West Ronfaure", "Kingdom of San d'Oria - Emerald Canopy Woodlands",
                        List.of(
                                new Landmark("Southern San d'Oria Gates", "I-6", -119.1f, 280.9f, "GATE"),
                                new Landmark("Northern San d'Oria Gates", "I-6", -157.7f, 380.0f, "GATE"),
                                new Landmark("Ghelsba Outpost Entrance", "E-4", -740.6f, 623.3f, "GATE"),
                                new Landmark("La Theine Plateau Pass", "F-11", -560.4f, -613.2f, "GATE"),
                                new Landmark("Knightwell Watchtower", "E-8", -716.8f, 18.2f, "LANDMARK"),
                                new Landmark("Regional Outpost", "G-9", -446.3f, -260.6f, "OUTPOST")
                        )
                ),
                new ZoneMapScenario(
                        102, 0, "map_zone_102_la_theine_plateau",
                        "La Theine Plateau", "Gustaberg - Ronfaure Border - Rolling Highlands",
                        List.of(
                                new Landmark("Crag of Holla (Telepoint)", "J-8", 420.0f, 20.0f, "CRAG"),
                                new Landmark("West Ronfaure Pass", "E-4", -558.6f, 688.0f, "GATE"),
                                new Landmark("Valkurm Dunes Ravine", "I-12", 160.2f, -764.0f, "GATE"),
                                new Landmark("Ordelle's Caves Entrance", "F-7", -276.6f, 99.6f, "GATE"),
                                new Landmark("Jugner Forest Pass", "M-8", 801.8f, -37.6f, "GATE"),
                                new Landmark("Cavernous Maw", "E-4", -557.9f, 637.8f, "LANDMARK")
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
        float cellW = mw / 16.0f;
        float cellH = mh / 16.0f;

        // Grid lines inside map area (16x16 authentic FFXI cartography grid)
        for (int col = 1; col < 16; col++) {
            int cx = Math.round(mx + col * cellW);
            if (col % 2 == 0) {
                g.setStroke(new BasicStroke(1.2f));
                g.setColor(new Color(100, 190, 240, 80));
            } else {
                g.setStroke(new BasicStroke(0.6f));
                g.setColor(new Color(100, 190, 240, 35));
            }
            g.drawLine(cx, my, cx, my + mh);
        }
        for (int row = 1; row < 16; row++) {
            int cy = Math.round(my + row * cellH);
            if (row % 2 == 0) {
                g.setStroke(new BasicStroke(1.2f));
                g.setColor(new Color(100, 190, 240, 80));
            } else {
                g.setStroke(new BasicStroke(0.6f));
                g.setColor(new Color(100, 190, 240, 35));
            }
            g.drawLine(mx, cy, mx + mw, cy);
        }

        // Crosshairs at major grid intersections (every 2 cells = 8x8 major intersections)
        g.setColor(new Color(150, 225, 255, 140));
        g.setStroke(new BasicStroke(1.2f));
        int tick = 5;
        for (int col = 2; col < 16; col += 2) {
            int cx = Math.round(mx + col * cellW);
            for (int row = 2; row < 16; row += 2) {
                int cy = Math.round(my + row * cellH);
                g.drawLine(cx - tick, cy, cx + tick, cy);
                g.drawLine(cx, cy - tick, cx, cy + tick);
            }
        }

        // Coordinate Column Headers (A to P) using authentic FontMojiRenderer
        String[] cols = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P"};

        for (int i = 0; i < 16; i++) {
            int colCenter = Math.round(mx + (i + 0.5f) * cellW);
            int tw = measureTextWidth(cols[i], 1);

            // Top letter
            fontMoji.drawString(canvas, cols[i], colCenter - tw / 2, my - 24, FontMojiRenderer.COLOR_GOLD, 1);

            // Bottom letter
            fontMoji.drawString(canvas, cols[i], colCenter - tw / 2, my + mh + 8, FontMojiRenderer.COLOR_WHITE, 1);
        }

        // Coordinate Row Sidebars (1 to 16) using authentic FontMojiRenderer
        for (int i = 0; i < 16; i++) {
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
            // Authentic FFXI 512x512 map projection:
            // mapPxX = 0.5f + (worldX / mapSpan) * 512.0f - xOff
            // mapPxY = -0.5f + (-worldZ / mapSpan) * 512.0f - yOff
            float mapPxX = 0.5f + (lm.worldX() / mapSpan) * 512.0f - xOff;
            float mapPxY = -0.5f + (-lm.worldZ() / mapSpan) * 512.0f - yOff;
            float normX = mapPxX / 512.0f;
            float normY = mapPxY / 512.0f;

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

        String telemetryLeft = String.format("Grid: 16x16 (A-P, 1-16) | Span: %.0fm | Offsets: X=%.0f, Y=%.0f | Scale: %.2f px/m",
                span, zm.xOffset(), zm.yOffset(), scalePxPerM);
        fontMoji.drawString(canvas, telemetryLeft, 45, fy - 8, FontMojiRenderer.COLOR_WHITE, 1);

        String telemetryRight = String.format("Extents: X[%.0f..%.0f] Z[%.0f..%.0f] | North=-Z South=+Z West=-X East=+X",
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
