package com.example.client.desktop.ui.map;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.interaction.ZoneInteraction;
import com.example.client.desktop.util.ZoneNameRegistry;
import com.example.client.state.ClientState;
import com.example.common.protocol.JobId;
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
import imgui.type.ImInt;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authentic live in-game FFXI cartography map overlay.
 * Follows retail FFXI and XIM's MapDrawer architecture:
 * - Dynamic texture loading from ZoneMapTable for any retail zone and sub-zone/floor
 * - Authentic 16x16 cartographic coordinate grid (A through P, 1 through 16)
 * - Real-time local player positioning with rotating directional heading indicator
 * - 100% dynamic POI pins derived directly from game files:
 *   - Retail ZoneInteraction records (zonelines to other zones, entrances, interactive doors)
 *   - Live remote entities within Area of Interest (AOI) managed by server deltas
 * - Zero hardcoded landmark tables or synthetic coordinates
 * - Toggleable via keybind [M] or UI action
 */
@Slf4j
@Prototype
public class InGameMapPanel implements AutoCloseable {

    public record DynamicMapMarker(
            String name,
            String gridCoord,
            float worldX,
            float worldZ,
            String category,
            int colorRgba
    ) {}

    @Getter
    @Setter
    private boolean visible = false;

    private ZoneMapTable zoneMapTable;
    private FileTableManager fileTableManager;
    private final Map<Long, Integer> zoneGlTextures = new HashMap<>();
    private final Map<Long, ZoneMap> zoneMapMetadata = new HashMap<>();

    private int activeZoneId = -1;
    private int activeSubZoneId = 0;

    public void toggle() {
        this.visible = !this.visible;
    }

    public void render(
            int zoneId,
            float playerX,
            float playerY,
            float playerZ,
            float playerYaw,
            List<ZoneInteraction> zoneInteractions,
            Collection<ClientState.RemoteEntityState> remoteEntities
    ) {
        render(zoneId, activeSubZoneId, playerX, playerY, playerZ, playerYaw, zoneInteractions, remoteEntities);
    }

    public void render(
            int zoneId,
            int subZoneId,
            float playerX,
            float playerY,
            float playerZ,
            float playerYaw,
            List<ZoneInteraction> zoneInteractions,
            Collection<ClientState.RemoteEntityState> remoteEntities
    ) {
        if (!visible) {
            return;
        }

        // Lazy initialize tables from FFXI installation if not yet loaded
        ensureTablesLoaded();

        if (this.activeZoneId != zoneId || this.activeSubZoneId != subZoneId) {
            this.activeZoneId = zoneId;
            this.activeSubZoneId = subZoneId;
            loadZoneTexture(zoneId, activeSubZoneId);
        }

        long mapKey = (((long) zoneId) << 32) | (activeSubZoneId & 0xFFFFFFFFL);
        ZoneMap zm = zoneMapMetadata.get(mapKey);
        Integer texId = zoneGlTextures.get(mapKey);
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

        // Floor / Multi-map selection if the zone contains multiple maps (e.g. Jeuno, dungeons)
        List<ZoneMap> mapsForZone = (zoneMapTable != null) ? zoneMapTable.getAllForZone(zoneId) : List.of();
        if (mapsForZone.size() > 1) {
            String[] floorNames = new String[mapsForZone.size()];
            for (int i = 0; i < mapsForZone.size(); i++) {
                floorNames[i] = "Floor #" + mapsForZone.get(i).subZoneId();
            }
            ImInt selSub = new ImInt(activeSubZoneId);
            if (ImGui.combo("##FloorSelector", selSub, floorNames)) {
                this.activeSubZoneId = selSub.get();
                loadZoneTexture(zoneId, activeSubZoneId);
            }
            ImGui.sameLine();
        }

        ImGui.textColored(1.0f, 0.85f, 0.35f, 1.0f, "16x16 Cartographic Grid (A-P, 1-16)");

        float span = (zm != null && zm.mapSizePx() > 0) ? (float) zm.mapSizePx() : 640.0f;
        float xOff = (zm != null) ? zm.xOffset() : -304.0f;
        float yOff = (zm != null) ? zm.yOffset() : -304.0f;

        // Compute player's map coordinate & grid
        float pMapX = 0.5f + (playerX / span) * 512.0f - xOff;
        float pMapY = -0.5f + (-playerZ / span) * 512.0f - yOff;
        float pNormX = pMapX / 512.0f;
        float pNormY = pMapY / 512.0f;
        String playerGridStr = formatGridCoord(pMapX, pMapY);

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

        // 4. Dynamically Harvest Markers from Game Data
        List<DynamicMapMarker> markers = new ArrayList<>();

        // (a) ZoneInteraction volumes parsed from DAT section 0x36 ("RID")
        if (zoneInteractions != null) {
            for (ZoneInteraction zi : zoneInteractions) {
                float mx = zi.getPosition().x();
                float mz = zi.getPosition().z();
                float lmMapX = 0.5f + (mx / span) * 512.0f - xOff;
                float lmMapY = -0.5f + (-mz / span) * 512.0f - yOff;
                String grid = formatGridCoord(lmMapX, lmMapY);

                if (zi.isZoneLine()) {
                    Integer targetZone = zi.getTargetZoneId();
                    String label = (targetZone != null)
                            ? "Zoneline -> " + ZoneNameRegistry.getZoneName(targetZone) + " (#" + targetZone + ")"
                            : (zi.isMogHouse() ? "Mog House Residence" : "Zoneline [" + zi.getDestId() + "]");
                    String category = zi.isMogHouse() ? "MOG_HOUSE" : "GATE";
                    int color = zi.isMogHouse() ? ImColor.rgba(255, 140, 220, 255) : ImColor.rgba(100, 220, 255, 255);
                    markers.add(new DynamicMapMarker(label, grid, mx, mz, category, color));
                } else if (zi.isZoneEntrance()) {
                    String label = "Spawn Point [" + zi.getSourceId() + "]";
                    markers.add(new DynamicMapMarker(label, grid, mx, mz, "ENTRANCE", ImColor.rgba(150, 255, 150, 255)));
                } else if (zi.isDoor()) {
                    String label = "Door / Gateway [" + zi.getSourceId() + "]";
                    markers.add(new DynamicMapMarker(label, grid, mx, mz, "DOOR", ImColor.rgba(200, 180, 255, 255)));
                } else if (zi.isFishingArea()) {
                    String label = "Fishing Boundary [" + zi.getSourceId() + "]";
                    markers.add(new DynamicMapMarker(label, grid, mx, mz, "FISHING", ImColor.rgba(130, 200, 255, 255)));
                }
            }
        }

        // (b) Live Entities managed by server AOI events (ZoneStateDeltaMessage)
        if (remoteEntities != null) {
            for (ClientState.RemoteEntityState entity : remoteEntities) {
                float mx = entity.x();
                float mz = entity.z();
                float lmMapX = 0.5f + (mx / span) * 512.0f - xOff;
                float lmMapY = -0.5f + (-mz / span) * 512.0f - yOff;
                String grid = formatGridCoord(lmMapX, lmMapY);

                boolean isNpc = entity.mainJobId() == JobId.NPC;
                String label = isNpc
                        ? "[NPC] " + entity.name()
                        : entity.name() + " (" + JobId.toAbbreviation(entity.mainJobId()) + " Lv" + entity.level() + ")";
                String cat = isNpc ? "NPC" : "PLAYER";
                int color = isNpc ? ImColor.rgba(255, 190, 70, 255) : ImColor.rgba(80, 255, 120, 255);
                markers.add(new DynamicMapMarker(label, grid, mx, mz, cat, color));
            }
        }

        // Render Dynamic Markers
        float mouseX = ImGui.getMousePosX();
        float mouseY = ImGui.getMousePosY();
        DynamicMapMarker hoveredMarker = null;

        for (DynamicMapMarker m : markers) {
            float lmMapX = 0.5f + (m.worldX() / span) * 512.0f - xOff;
            float lmMapY = -0.5f + (-m.worldZ() / span) * 512.0f - yOff;

            // Only draw if within map bounds
            if (lmMapX < -20.0f || lmMapX > 532.0f || lmMapY < -20.0f || lmMapY > 532.0f) {
                continue;
            }

            float lmNormX = Math.max(0.005f, Math.min(0.995f, lmMapX / 512.0f));
            float lmNormY = Math.max(0.005f, Math.min(0.995f, lmMapY / 512.0f));

            float lx = startX + lmNormX * mapSize;
            float ly = startY + lmNormY * mapSize;

            drawList.addCircle(lx, ly, 4.5f, m.colorRgba(), 12, 1.5f);
            drawList.addCircleFilled(lx, ly, 2.0f, ImColor.rgba(255, 255, 255, 255), 8);

            float dist = (float) Math.hypot(mouseX - lx, mouseY - ly);
            if (dist <= 8.0f) {
                hoveredMarker = m;
                drawList.addCircle(lx, ly, 9.0f, ImColor.rgba(255, 255, 255, 220), 16, 2.0f);
            }
        }

        // 5. Render Local Player Position & Facing Arrow
        if (pNormX >= 0.0f && pNormX <= 1.0f && pNormY >= 0.0f && pNormY <= 1.0f) {
            float px = startX + pNormX * mapSize;
            float py = startY + pNormY * mapSize;

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

        // 6. Interactive Marker Tooltip
        if (hoveredMarker != null) {
            ImGui.beginTooltip();
            ImGui.textColored(1.0f, 0.85f, 0.35f, 1.0f, "[" + hoveredMarker.gridCoord() + "] " + hoveredMarker.name());
            float dist = (float) Math.hypot(hoveredMarker.worldX() - playerX, hoveredMarker.worldZ() - playerZ);
            ImGui.text(String.format("Type: %s | Pos: (%.1f, %.1f) | Range: %.1fm",
                    hoveredMarker.category(), hoveredMarker.worldX(), hoveredMarker.worldZ(), dist));
            ImGui.endTooltip();
        }

        // Advance cursor past the map
        ImGui.setCursorScreenPos(startX - 20, endY + 28);
        ImGui.separator();

        // 7. Footer Telemetry
        float scaleMeters = span / 16.0f;
        ImGui.textColored(0.85f, 0.85f, 0.85f, 1.0f,
                String.format("Span: %.0fm (%.1fm/cell) | Offsets: X=%.0f, Y=%.0f | Active Markers: %d | Retail Engine: N=-Z, E=+X",
                        span, xOff, yOff, scaleMeters, markers.size()));

        ImGui.end();
    }

    public static String formatGridCoord(float mapPxX, float mapPxY) {
        int col = (int) Math.floor(15.0f * mapPxX / 512.0f);
        int row = 1 + (int) Math.floor(15.0f * mapPxY / 512.0f);
        col = Math.max(0, Math.min(15, col));
        row = Math.max(1, Math.min(16, row));
        char colLetter = (char) ('A' + col);
        return colLetter + "-" + row;
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
        ensureTablesLoaded();
        if (zoneMapTable == null || fileTableManager == null) {
            return;
        }

        long key = (((long) zoneId) << 32) | (subZoneId & 0xFFFFFFFFL);
        if (zoneGlTextures.containsKey(key)) {
            return;
        }

        Optional<ZoneMap> zmOpt = zoneMapTable.get(zoneId, subZoneId);
        if (zmOpt.isEmpty()) {
            return;
        }

        ZoneMap zm = zmOpt.get();
        zoneMapMetadata.put(key, zm);

        try {
            Path datPath = fileTableManager.getFileNioPath(zm.fileId());
            if (datPath == null || !Files.isRegularFile(datPath)) {
                return;
            }

            byte[] datBytes = Files.readAllBytes(datPath);
            DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(datBytes, datPath.getFileName().toString()));
            TextureResource texResource = findFirstTexture(graph.rootDirectory());

            if (texResource != null) {
                byte[] rgba = TextureDecoder.decode(datBytes, texResource);
                int texId = uploadGlTexture(rgba, texResource.width(), texResource.height());
                zoneGlTextures.put(key, texId);
                log.info("Uploaded authentic in-game map texture for zone {} subZone {}: {}x{} (GL texture #{})",
                        zoneId, subZoneId, texResource.width(), texResource.height(), texId);
            }
        } catch (Exception e) {
            log.warn("Failed loading map DAT for zone {} subZone {}: {}", zoneId, subZoneId, e.getMessage());
        }
    }

    private TextureResource findFirstTexture(DirectoryResource dir) {
        if (dir == null) return null;
        for (ResourceNode child : dir.children()) {
            if (child instanceof TextureResource tr) {
                return tr;
            }
            if (child instanceof DirectoryResource subDir) {
                TextureResource nested = findFirstTexture(subDir);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private int uploadGlTexture(byte[] rgba, int width, int height) {
        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        ByteBuffer buffer = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        buffer.put(rgba).flip();

        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return texId;
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
        this.activeZoneId = -1;
    }
}
