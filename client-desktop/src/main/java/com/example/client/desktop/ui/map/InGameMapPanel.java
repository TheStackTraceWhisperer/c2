package com.example.client.desktop.ui.map;

import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.util.ZoneNameRegistry;
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
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authentic live in-game FFXI cartography map overlay.
 * Inspired by retail FFXI and XIM's MapDrawer.
 * Features:
 * - Dynamic texture loading from ZoneMapTable for any retail zone
 * - Authentic 16x16 cartographic coordinate grid (A through P, 1 through 16)
 * - Real-time local player positioning with rotating directional heading indicator
 * - Verified LandSandBoat landmark POI pins with category-based styling and interactive tooltips
 * - Toggleable via keybind [M] or UI action
 */
@Slf4j
@Prototype
public class InGameMapPanel implements AutoCloseable {

    public record MapLandmark(
            String name,
            String gridCoord,
            float worldX,
            float worldZ,
            String category,
            int colorRgba
    ) {
        public MapLandmark(String name, String gridCoord, float worldX, float worldZ, String category) {
            this(name, gridCoord, worldX, worldZ, category, colorForCategory(category));
        }

        private static int colorForCategory(String category) {
            return switch (category.toUpperCase()) {
                case "GATE" -> ImColor.rgba(100, 220, 255, 255);       // Cyan
                case "LANDMARK" -> ImColor.rgba(255, 215, 0, 255);   // Gold
                case "GUILD" -> ImColor.rgba(150, 255, 150, 255);      // Soft Green
                case "PORT" -> ImColor.rgba(120, 180, 255, 255);       // Azure
                case "MOG_HOUSE" -> ImColor.rgba(255, 140, 220, 255);  // Pink
                case "TAVERN" -> ImColor.rgba(255, 170, 90, 255);     // Amber
                case "CRAG" -> ImColor.rgba(220, 160, 255, 255);       // Purple
                case "OUTPOST" -> ImColor.rgba(255, 100, 100, 255);    // Coral Red
                default -> ImColor.rgba(240, 240, 240, 255);
            };
        }
    }

    @Getter
    @Setter
    private boolean visible = false;

    private ZoneMapTable zoneMapTable;
    private FileTableManager fileTableManager;
    private final Map<Integer, Integer> zoneGlTextures = new HashMap<>();
    private final Map<Integer, ZoneMap> zoneMapMetadata = new HashMap<>();

    private int activeZoneId = -1;
    private int activeSubZoneId = 0;

    // Static verified landmark registry mapped by zone ID
    private static final Map<Integer, List<MapLandmark>> ZONE_LANDMARKS = new HashMap<>();

    static {
        ZONE_LANDMARKS.put(230, List.of(
                new MapLandmark("West Gate (West Ronfaure)", "F-10", -113.4f, -57.4f, "GATE"),
                new MapLandmark("East Gate (East Ronfaure)", "L-10", 113.5f, -57.4f, "GATE"),
                new MapLandmark("North San d'Oria Archway", "I-7", 0.0f, 58.0f, "GATE"),
                new MapLandmark("Lion Springs Tavern", "K-6", 99.4f, 117.3f, "TAVERN"),
                new MapLandmark("Tanners' Guild", "E-8", -187.7f, 19.4f, "GUILD"),
                new MapLandmark("Mog House Residential", "M-5", 164.9f, 164.8f, "MOG_HOUSE"),
                new MapLandmark("Count's Manor", "B-6", -294.1f, 93.0f, "LANDMARK")
        ));

        ZONE_LANDMARKS.put(231, List.of(
                new MapLandmark("Grand San d'Oria Cathedral", "M-6", 149.9f, 141.9f, "LANDMARK"),
                new MapLandmark("Chateau d'Oraguille Gate", "I-7", 0.0f, 99.0f, "GATE"),
                new MapLandmark("South Gate (Rue du Bois)", "I-10", -0.1f, -36.1f, "GATE"),
                new MapLandmark("West Ronfaure Gate", "D-8", -252.2f, 43.9f, "GATE"),
                new MapLandmark("Port San d'Oria Gate", "G-3", -123.5f, 270.5f, "GATE"),
                new MapLandmark("Carpenters' Guild", "E-3", -170.1f, 266.7f, "GUILD"),
                new MapLandmark("Mog House Residential", "M-10", 135.0f, -8.4f, "MOG_HOUSE")
        ));

        ZONE_LANDMARKS.put(232, List.of(
                new MapLandmark("North San d'Oria Gate", "F-10", -111.8f, -136.5f, "GATE"),
                new MapLandmark("Airship Terminal / Departures", "H-6", -25.0f, 28.0f, "PORT"),
                new MapLandmark("Cargo Room & Warehouses", "H-10", -16.0f, -126.1f, "LANDMARK"),
                new MapLandmark("Rusty Anchor Tavern", "G-7", -56.7f, -36.0f, "TAVERN"),
                new MapLandmark("Mog House Residential", "J-10", 83.3f, -139.3f, "MOG_HOUSE")
        ));

        ZONE_LANDMARKS.put(233, List.of(
                new MapLandmark("Royal Throne Room (King Destin)", "H-8", 0.0f, 42.4f, "LANDMARK"),
                new MapLandmark("Prince Royal's Room (Trion)", "H-7", -38.0f, 88.3f, "LANDMARK"),
                new MapLandmark("Great Hall of Knights (Halver)", "I-9", 2.4f, 2.0f, "LANDMARK"),
                new MapLandmark("Bostaunieux Oubliette", "I-8", 12.5f, 24.0f, "GATE"),
                new MapLandmark("Temple Knight Quarters", "I-9", 19.4f, -4.0f, "LANDMARK")
        ));

        ZONE_LANDMARKS.put(234, List.of(
                new MapLandmark("South Gustaberg Gate", "H-10", -16.0f, -136.0f, "GATE"),
                new MapLandmark("Zeruhn Mines Entrance", "D-7", -196.0f, -21.9f, "GATE"),
                new MapLandmark("Bastok Markets Archway", "F-5", -104.1f, 84.6f, "GATE"),
                new MapLandmark("Alchemists' Guild", "K-7", 94.0f, -4.0f, "GUILD"),
                new MapLandmark("Bat's Lair Tavern", "H-6", -26.0f, 41.7f, "TAVERN"),
                new MapLandmark("Mog House Residential", "K-8", 121.8f, -72.0f, "MOG_HOUSE")
        ));

        ZONE_LANDMARKS.put(235, List.of(
                new MapLandmark("South Gustaberg Gate", "D-11", -365.7f, -185.6f, "GATE"),
                new MapLandmark("Bastok Mines Archway", "H-11", -202.2f, -197.8f, "GATE"),
                new MapLandmark("Port Bastok Archway", "G-4", -234.0f, 92.3f, "GATE"),
                new MapLandmark("Metalworks Archway", "H-7", -212.0f, 0.0f, "GATE"),
                new MapLandmark("Goldsmiths' Guild", "H-8", -202.9f, -65.6f, "GUILD"),
                new MapLandmark("Steaming Sheep Restaurant", "E-6", -152.9f, 4.8f, "TAVERN"),
                new MapLandmark("Mog House Residential", "J-6", -146.2f, 29.9f, "MOG_HOUSE")
        ));

        ZONE_LANDMARKS.put(236, List.of(
                new MapLandmark("North Gustaberg Gate", "L-6", 150.3f, 6.3f, "GATE"),
                new MapLandmark("Bastok Markets Archway", "D-9", -193.5f, -84.0f, "GATE"),
                new MapLandmark("Airship Agency Terminal", "H-7", -30.0f, -70.0f, "PORT"),
                new MapLandmark("Steamers Cargo Dock", "H-8", 0.0f, -130.0f, "PORT"),
                new MapLandmark("Cid's Workshop (Chief Eng.)", "E-7", -129.3f, -20.0f, "LANDMARK"),
                new MapLandmark("Mog House Residential", "J-13", 60.0f, -252.8f, "MOG_HOUSE")
        ));

        ZONE_LANDMARKS.put(238, List.of(
                new MapLandmark("West Sarutabaruta Gate", "G-4", -40.2f, 248.8f, "GATE"),
                new MapLandmark("The Aurastery (Magic Guild)", "K-6", 165.0f, 179.9f, "GUILD"),
                new MapLandmark("The Rhinostery (Herbology)", "H-14", 3.2f, -163.0f, "GUILD"),
                new MapLandmark("Port Windurst Archway", "F-15", -59.6f, -209.0f, "GATE"),
                new MapLandmark("Windurst Walls Archway", "K-9", 160.1f, 60.4f, "GATE"),
                new MapLandmark("Mog House Residential", "K-12", 159.9f, -64.1f, "MOG_HOUSE")
        ));

        ZONE_LANDMARKS.put(241, List.of(
                new MapLandmark("East Sarutabaruta Gate", "K-10", 126.7f, -40.0f, "GATE"),
                new MapLandmark("Port Windurst Archway", "F-11", -94.2f, -72.7f, "GATE"),
                new MapLandmark("Windurst Walls Archway", "G-7", -60.7f, 92.0f, "GATE"),
                new MapLandmark("Weavers' Guild", "G-12", -42.4f, -118.3f, "GUILD"),
                new MapLandmark("Chocobo Stables", "K-12", 127.0f, -134.1f, "LANDMARK"),
                new MapLandmark("The Manustery", "H-9", -9.1f, -13.1f, "GUILD"),
                new MapLandmark("Mog House Residential", "E-8", -144.7f, 40.0f, "MOG_HOUSE")
        ));

        ZONE_LANDMARKS.put(244, List.of(
                new MapLandmark("Batallia Downs Gate", "F-5", -106.1f, 190.0f, "GATE"),
                new MapLandmark("Ru'Lude Gardens Gate", "J-10", 46.0f, -30.9f, "GATE"),
                new MapLandmark("Lower Jeuno Archway", "I-11", 4.8f, -54.9f, "GATE"),
                new MapLandmark("Goddess Temple", "H-9", -35.1f, 23.1f, "LANDMARK"),
                new MapLandmark("Marble Bridge Tavern", "F-7", -97.8f, 90.8f, "TAVERN"),
                new MapLandmark("Mog House Residential", "J-11", 49.2f, -82.9f, "MOG_HOUSE")
        ));

        ZONE_LANDMARKS.put(245, List.of(
                new MapLandmark("Rolanberry Fields Gate", "F-11", -123.1f, -200.7f, "GATE"),
                new MapLandmark("Upper Jeuno Stairs", "I-5", 0.2f, 59.6f, "GATE"),
                new MapLandmark("Port Jeuno Stairs", "J-6", 29.7f, 41.4f, "GATE"),
                new MapLandmark("Neptune's Spire Tavern", "J-7", 28.1f, -8.3f, "TAVERN"),
                new MapLandmark("Tenshodo H.Q.", "J-8", 38.2f, -57.8f, "LANDMARK"),
                new MapLandmark("Merry Minstrel Tavern", "H-8", -28.7f, -61.2f, "TAVERN"),
                new MapLandmark("Mog House Residential", "J-5", 43.9f, 88.6f, "MOG_HOUSE")
        ));

        ZONE_LANDMARKS.put(248, List.of(
                new MapLandmark("Valkurm Dunes Town Gate", "H-6", 16.9f, 104.3f, "GATE"),
                new MapLandmark("Passenger Ferry Pier", "H-11", 10.0f, -80.0f, "PORT"),
                new MapLandmark("Mayor's Residence", "G-9", -59.9f, 1.9f, "LANDMARK"),
                new MapLandmark("Weavers' Guild", "I-9", 21.1f, 7.6f, "GUILD"),
                new MapLandmark("Fishermen's Guild", "H-9", -20.2f, 7.6f, "GUILD")
        ));

        ZONE_LANDMARKS.put(249, List.of(
                new MapLandmark("Buburimu Peninsula Gate", "H-7", -0.2f, 121.0f, "GATE"),
                new MapLandmark("Steamship Ferry Pier", "I-8", 30.0f, 70.0f, "PORT"),
                new MapLandmark("Blacksmiths' Guild", "G-9", -53.2f, 32.0f, "GUILD"),
                new MapLandmark("Goldsmiths' Guild", "G-8", -30.9f, 66.9f, "GUILD"),
                new MapLandmark("Sailors' Stay Tavern", "H-9", 20.0f, 57.2f, "TAVERN")
        ));

        ZONE_LANDMARKS.put(100, List.of(
                new MapLandmark("Southern San d'Oria Gates", "I-6", -119.1f, 280.9f, "GATE"),
                new MapLandmark("Northern San d'Oria Gates", "I-6", -157.7f, 380.0f, "GATE"),
                new MapLandmark("Ghelsba Outpost Entrance", "E-4", -740.6f, 623.3f, "GATE"),
                new MapLandmark("La Theine Plateau Pass", "F-11", -560.4f, -613.2f, "GATE"),
                new MapLandmark("Knightwell Watchtower", "E-8", -716.8f, 18.2f, "LANDMARK"),
                new MapLandmark("Regional Outpost", "G-9", -446.3f, -260.6f, "OUTPOST")
        ));

        ZONE_LANDMARKS.put(102, List.of(
                new MapLandmark("Crag of Holla (Telepoint)", "J-8", 420.0f, 20.0f, "CRAG"),
                new MapLandmark("West Ronfaure Pass", "E-4", -558.6f, 688.0f, "GATE"),
                new MapLandmark("Valkurm Dunes Ravine", "I-12", 160.2f, -764.0f, "GATE"),
                new MapLandmark("Ordelle's Caves Entrance", "F-7", -276.6f, 99.6f, "GATE"),
                new MapLandmark("Jugner Forest Pass", "M-8", 801.8f, -37.6f, "GATE"),
                new MapLandmark("Cavernous Maw", "E-4", -557.9f, 637.8f, "LANDMARK")
        ));
    }

    public static List<MapLandmark> getLandmarksForZone(int zoneId) {
        return ZONE_LANDMARKS.getOrDefault(zoneId, List.of());
    }

    public void toggle() {
        this.visible = !this.visible;
    }

    public void render(int zoneId, float playerX, float playerY, float playerZ, float playerYaw) {
        if (!visible) {
            return;
        }

        // Lazy initialize tables from FFXI installation if not yet loaded
        ensureTablesLoaded();

        if (this.activeZoneId != zoneId) {
            this.activeZoneId = zoneId;
            loadZoneTexture(zoneId, activeSubZoneId);
        }

        ZoneMap zm = zoneMapMetadata.get(zoneId);
        Integer texId = zoneGlTextures.get(zoneId);
        String zoneName = ZoneNameRegistry.getZoneName(zoneId);

        ImGui.setNextWindowPos(260, 40, ImGuiCond.Once);
        ImGui.setNextWindowSize(640, 720, ImGuiCond.Once);

        int flags = ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse;
        if (!ImGui.begin("Vana'diel Cartography — " + zoneName + " (Zone " + zoneId + ")###FFXIMapOverlay", flags)) {
            ImGui.end();
            return;
        }

        // 1. Header controls
        if (ImGui.button("Close Map [M]")) {
            this.visible = false;
        }
        ImGui.sameLine();
        ImGui.textColored(1.0f, 0.85f, 0.35f, 1.0f, "16x16 Cartographic Grid (A-P, 1-16)");

        float span = (zm != null && zm.mapSizePx() > 0) ? (float) zm.mapSizePx() : 640.0f;
        float xOff = (zm != null) ? zm.xOffset() : -304.0f;
        float yOff = (zm != null) ? zm.yOffset() : -304.0f;

        // Compute player's map coordinate & grid
        float pMapX = 0.5f + (playerX / span) * 512.0f - xOff;
        float pMapY = -0.5f + (-playerZ / span) * 512.0f - yOff;
        float pNormX = pMapX / 512.0f;
        float pNormY = pMapY / 512.0f;

        int pColIdx = (int) Math.floor(15.0 * pNormX);
        int pRowIdx = 1 + (int) Math.floor(15.0 * pNormY);
        char pColChar = (pColIdx >= 0 && pColIdx < 16) ? (char) ('A' + pColIdx) : '?';
        String playerGridStr = String.format("%c-%d", pColChar, pRowIdx);

        ImGui.sameLine();
        ImGui.text(String.format("| Pos: [%s] (X: %.1f, Z: %.1f)", playerGridStr, playerX, playerZ));

        ImGui.separator();

        // 2. Map Canvas Area (512x512 with margins for coordinate headers)
        float mapSize = 512.0f;
        float startX = ImGui.getCursorScreenPosX() + 24.0f;
        float startY = ImGui.getCursorScreenPosY() + 24.0f;
        float endX = startX + mapSize;
        float endY = startY + mapSize;

        ImDrawList drawList = ImGui.getWindowDrawList();

        // Outer border background
        int borderColor = ImColor.rgba(180, 150, 80, 255);
        int bgSlate = ImColor.rgba(14, 18, 28, 240);
        drawList.addRectFilled(startX - 22, startY - 22, endX + 22, endY + 22, bgSlate, 6.0f);
        drawList.addRect(startX - 1, startY - 1, endX + 1, endY + 1, borderColor, 0.0f, 0, 2.0f);

        // Render actual decoded zone map texture if available
        if (texId != null && texId > 0) {
            drawList.addImage(texId, startX, startY, endX, endY);
        } else {
            int placeholderBg = ImColor.rgba(30, 40, 55, 255);
            drawList.addRectFilled(startX, startY, endX, endY, placeholderBg);
            drawList.addText(startX + 160, startY + 240, ImColor.rgba(200, 200, 200, 255), "No In-Game Map Available");
        }

        // 3. Render Authentic 16x16 Cartography Grid
        float cellW = mapSize / 16.0f;
        float cellH = mapSize / 16.0f;
        int gridMinor = ImColor.rgba(100, 190, 240, 35);
        int gridMajor = ImColor.rgba(100, 190, 240, 80);
        int goldText = ImColor.rgba(255, 215, 0, 255);
        int whiteText = ImColor.rgba(240, 240, 240, 220);

        for (int col = 1; col < 16; col++) {
            float cx = startX + col * cellW;
            int colColor = (col % 2 == 0) ? gridMajor : gridMinor;
            float thickness = (col % 2 == 0) ? 1.2f : 0.6f;
            drawList.addLine(cx, startY, cx, endY, colColor, thickness);
        }
        for (int row = 1; row < 16; row++) {
            float cy = startY + row * cellH;
            int rowColor = (row % 2 == 0) ? gridMajor : gridMinor;
            float thickness = (row % 2 == 0) ? 1.2f : 0.6f;
            drawList.addLine(startX, cy, endX, cy, rowColor, thickness);
        }

        // Coordinate Column Letters (A to P)
        String[] cols = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P"};
        for (int i = 0; i < 16; i++) {
            float colCenter = startX + (i + 0.5f) * cellW;
            drawList.addText(colCenter - 4.0f, startY - 18.0f, goldText, cols[i]);
            drawList.addText(colCenter - 4.0f, endY + 4.0f, whiteText, cols[i]);
        }

        // Coordinate Row Numbers (1 to 16)
        for (int i = 0; i < 16; i++) {
            float rowCenter = startY + (i + 0.5f) * cellH;
            String rStr = String.valueOf(i + 1);
            float off = (i + 1 >= 10) ? -18.0f : -12.0f;
            drawList.addText(startX + off, rowCenter - 6.0f, goldText, rStr);
            drawList.addText(endX + 6.0f, rowCenter - 6.0f, whiteText, rStr);
        }

        // 4. Render Landmark Markers
        List<MapLandmark> landmarks = ZONE_LANDMARKS.get(zoneId);
        float mouseX = ImGui.getMousePosX();
        float mouseY = ImGui.getMousePosY();
        MapLandmark hoveredLandmark = null;

        if (landmarks != null) {
            for (MapLandmark lm : landmarks) {
                float lmMapX = 0.5f + (lm.worldX() / span) * 512.0f - xOff;
                float lmMapY = -0.5f + (-lm.worldZ() / span) * 512.0f - yOff;
                float lmNormX = Math.max(0.01f, Math.min(0.99f, lmMapX / 512.0f));
                float lmNormY = Math.max(0.01f, Math.min(0.99f, lmMapY / 512.0f));

                float lx = startX + lmNormX * mapSize;
                float ly = startY + lmNormY * mapSize;

                // Draw outer ring and inner dot
                drawList.addCircle(lx, ly, 5.0f, lm.colorRgba(), 12, 1.5f);
                drawList.addCircleFilled(lx, ly, 2.5f, ImColor.rgba(255, 255, 255, 255), 8);

                // Hover check
                float dist = (float) Math.hypot(mouseX - lx, mouseY - ly);
                if (dist <= 8.0f) {
                    hoveredLandmark = lm;
                    drawList.addCircle(lx, ly, 9.0f, ImColor.rgba(255, 255, 255, 200), 16, 2.0f);
                }
            }
        }

        // 5. Render Player Position & Facing Arrow
        if (pNormX >= 0.0f && pNormX <= 1.0f && pNormY >= 0.0f && pNormY <= 1.0f) {
            float px = startX + pNormX * mapSize;
            float py = startY + pNormY * mapSize;

            // Player blip
            int playerGlow = ImColor.rgba(50, 255, 100, 90);
            int playerColor = ImColor.rgba(50, 255, 100, 255);
            drawList.addCircleFilled(px, py, 7.0f, playerGlow);
            drawList.addCircleFilled(px, py, 3.5f, playerColor);
            drawList.addCircle(px, py, 3.5f, ImColor.rgba(255, 255, 255, 255), 10, 1.2f);

            // Facing arrow: FFXI yaw 0 = North (-Z = screen -Y), 90 = East (+X = screen +X)
            float angleRad = (float) Math.toRadians(playerYaw);
            float arrowLen = 14.0f;
            float tipX = px + (float) Math.sin(angleRad) * arrowLen;
            float tipY = py - (float) Math.cos(angleRad) * arrowLen;
            drawList.addLine(px, py, tipX, tipY, ImColor.rgba(255, 255, 255, 240), 2.0f);
        }

        // 6. Interactive Landmark Tooltip
        if (hoveredLandmark != null) {
            ImGui.beginTooltip();
            ImGui.textColored(1.0f, 0.85f, 0.35f, 1.0f, "[" + hoveredLandmark.gridCoord() + "] " + hoveredLandmark.name());
            ImGui.text(String.format("Category: %s | (X: %.1f, Z: %.1f)",
                    hoveredLandmark.category(), hoveredLandmark.worldX(), hoveredLandmark.worldZ()));
            ImGui.endTooltip();
        }

        // Advance cursor past the map
        ImGui.setCursorScreenPos(startX - 20, endY + 28);
        ImGui.separator();

        // 7. Footer Telemetry
        float scaleMeters = span / 16.0f;
        ImGui.textColored(0.85f, 0.85f, 0.85f, 1.0f,
                String.format("Span: %.0fm (%.1fm/cell) | Offsets: X=%.0f, Y=%.0f | North=-Z, South=+Z, West=-X, East=+X",
                        span, xOff, yOff, scaleMeters));

        ImGui.end();
    }

    private void ensureTablesLoaded() {
        if (zoneMapTable != null && fileTableManager != null) {
            return;
        }

        try {
            Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
            if (!Files.isDirectory(ffxiRoot)) {
                return;
            }

            Path dllPath = ffxiRoot.resolve("FFXiMain.dll");
            if (Files.exists(dllPath)) {
                byte[] dllBytes = Files.readAllBytes(dllPath);
                DllLookupOffsets offsets = DllLookupExtractor.loadFromDll(new ByteReader(dllBytes, "FFXiMain.dll"));
                if (offsets.zoneMapTableOffset() > 0) {
                    this.zoneMapTable = ZoneMapTable.load(dllBytes, offsets.zoneMapTableOffset());
                }
            }

            this.fileTableManager = new FileTableManager();
            this.fileTableManager.loadFromDirectory(ffxiRoot.toFile());
        } catch (Exception e) {
            log.warn("Failed initializing ZoneMapTable: {}", e.getMessage());
        }
    }

    private void loadZoneTexture(int zoneId, int subZoneId) {
        if (zoneGlTextures.containsKey(zoneId)) {
            return;
        }

        ensureTablesLoaded();
        if (zoneMapTable == null || fileTableManager == null) {
            return;
        }

        Optional<ZoneMap> zmOpt = zoneMapTable.get(zoneId, subZoneId);
        if (zmOpt.isEmpty()) {
            return;
        }

        ZoneMap zm = zmOpt.get();
        zoneMapMetadata.put(zoneId, zm);

        try {
            Path datPath = fileTableManager.getFileNioPath(zm.fileId());
            if (datPath == null || !Files.exists(datPath)) {
                return;
            }

            byte[] datBytes = Files.readAllBytes(datPath);
            DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(datBytes, datPath.getFileName().toString()));
            if (graph == null) {
                return;
            }

            List<TextureResource> textures = new ArrayList<>();
            collectTextures(graph.rootDirectory(), textures);
            if (textures.isEmpty()) {
                return;
            }

            TextureResource mapTexture = textures.get(0);
            byte[] rgba = TextureDecoder.decode(datBytes, mapTexture);
            if (rgba == null) {
                return;
            }

            int texId = uploadGlTexture(rgba, mapTexture.width(), mapTexture.height());
            if (texId > 0) {
                zoneGlTextures.put(zoneId, texId);
                log.info("Loaded authentic in-game map texture for zone {} -> OpenGL Tex ID {}", zoneId, texId);
            }
        } catch (Exception e) {
            log.error("Failed loading in-game map texture for zone {}", zoneId, e);
        }
    }

    private int uploadGlTexture(byte[] rgba, int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buffer.put(rgba).flip();

        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer
        );
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return texId;
    }

    private void collectTextures(DirectoryResource dir, List<TextureResource> list) {
        if (dir == null) return;
        for (ResourceNode child : dir.children()) {
            if (child instanceof TextureResource tr) {
                list.add(tr);
            } else if (child instanceof DirectoryResource subDir) {
                collectTextures(subDir, list);
            }
        }
    }

    @Override
    public void close() {
        for (int texId : zoneGlTextures.values()) {
            if (texId > 0) {
                GL11.glDeleteTextures(texId);
            }
        }
        zoneGlTextures.clear();
        zoneMapMetadata.clear();
    }
}
