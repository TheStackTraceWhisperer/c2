package com.example.client.desktop.ui.radar;

import com.example.client.desktop.util.ZoneNameRegistry;
import com.example.client.state.ClientState;
import com.example.common.protocol.JobId;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Prototype
@RequiredArgsConstructor
public class WorldRadarPanel {

    @Getter private final RadarViewportConfig config;
    private final ClientState clientState;

    private final ImBoolean showGridWrapper = new ImBoolean(true);
    private final ImBoolean showAoiWrapper = new ImBoolean(true);
    private final ImBoolean showNamesWrapper = new ImBoolean(true);

    public static float[] worldToScreen(float worldX, float worldZ, float localX, float localZ,
                                         float centerX, float centerY, float pixelsPerMeter) {
        float dx = worldX - localX;
        float dz = worldZ - localZ;
        float screenX = centerX + (dx * pixelsPerMeter);
        float screenY = centerY - (dz * pixelsPerMeter); // +Z is North (screen up)
        return new float[]{screenX, screenY};
    }

    public void render() {
        ImGui.setNextWindowPos(540, 20, ImGuiCond.Once);
        ImGui.setNextWindowSize(520, 600, ImGuiCond.Once);
        ImGui.begin("World Radar");

        // 1. Telemetry Header Overlay
        int zoneId = clientState.getCurrentZoneId();
        String zoneName = ZoneNameRegistry.getZoneName(zoneId);
        int worldId = clientState.getCurrentWorldId() > 0 ? clientState.getCurrentWorldId() : 1;
        int nearbyCount = clientState.getRemoteEntities().size();

        ImGui.text(String.format("Zone: %s (ID: %d)  |  World: %d  |  Nearby Players: %d",
                zoneName, zoneId, worldId, nearbyCount));

        ImGui.text(String.format("Position: X: %.2f  Y: %.2f  Z: %.2f  |  Yaw: %.1f°",
                clientState.getX(), clientState.getY(), clientState.getZ(), clientState.getYaw()));

        ImGui.separator();

        // 2. Viewport Controls
        if (ImGui.button(" - ")) {
            config.zoomOut();
        }
        ImGui.sameLine();
        if (ImGui.button(" + ")) {
            config.zoomIn();
        }
        ImGui.sameLine();
        ImGui.text(String.format("Radius: %.0fm", config.getRadarRadiusMeters()));

        ImGui.sameLine();
        showGridWrapper.set(config.isShowGrid());
        if (ImGui.checkbox("Grid", showGridWrapper)) {
            config.setShowGrid(showGridWrapper.get());
        }

        ImGui.sameLine();
        showAoiWrapper.set(config.isShowAoiCircle());
        if (ImGui.checkbox("AoI (50m)", showAoiWrapper)) {
            config.setShowAoiCircle(showAoiWrapper.get());
        }

        ImGui.sameLine();
        showNamesWrapper.set(config.isShowNameplates());
        if (ImGui.checkbox("Names", showNamesWrapper)) {
            config.setShowNameplates(showNamesWrapper.get());
        }

        ImGui.separator();

        // 3. Radar Viewport Canvas via ImDrawList
        ImDrawList drawList = ImGui.getWindowDrawList();
        float canvasX0 = ImGui.getCursorScreenPosX();
        float canvasY0 = ImGui.getCursorScreenPosY();
        float canvasW = ImGui.getContentRegionAvailX();
        float canvasH = ImGui.getContentRegionAvailY();
        float canvasSize = Math.max(100.0f, Math.min(canvasW, canvasH));
        float centerX = canvasX0 + canvasW / 2.0f;
        float centerY = canvasY0 + canvasSize / 2.0f;
        float radarRadiusPixels = (canvasSize / 2.0f) - 8.0f;

        ImGui.dummy(canvasW, canvasSize);

        if (radarRadiusPixels <= 10.0f) {
            ImGui.end();
            return;
        }

        // Radar background and outer border
        int colBg = ImColor.rgba(21, 24, 34, 240);
        int colBorder = ImColor.rgba(69, 80, 101, 255);
        drawList.addCircleFilled(centerX, centerY, radarRadiusPixels, colBg, 64);
        drawList.addCircle(centerX, centerY, radarRadiusPixels, colBorder, 64, 2.0f);

        float pixelsPerMeter = radarRadiusPixels / config.getRadarRadiusMeters();

        // Grid lines (10m spacing)
        if (config.isShowGrid()) {
            int colGrid = ImColor.rgba(42, 51, 69, 96);
            float stepPixels = 10.0f * pixelsPerMeter;
            if (stepPixels >= 8.0f) {
                for (float off = stepPixels; off < radarRadiusPixels; off += stepPixels) {
                    float chord = (float) Math.sqrt(radarRadiusPixels * radarRadiusPixels - off * off);
                    // Vertical lines
                    drawList.addLine(centerX + off, centerY - chord, centerX + off, centerY + chord, colGrid, 1.0f);
                    drawList.addLine(centerX - off, centerY - chord, centerX - off, centerY + chord, colGrid, 1.0f);
                    // Horizontal lines
                    drawList.addLine(centerX - chord, centerY + off, centerX + chord, centerY + off, colGrid, 1.0f);
                    drawList.addLine(centerX - chord, centerY - off, centerX + chord, centerY - off, colGrid, 1.0f);
                }
            }
        }

        // Cardinal Crosshairs
        int colAxis = ImColor.rgba(85, 101, 128, 128);
        drawList.addLine(centerX - radarRadiusPixels, centerY, centerX + radarRadiusPixels, centerY, colAxis, 1.0f);
        drawList.addLine(centerX, centerY - radarRadiusPixels, centerX, centerY + radarRadiusPixels, colAxis, 1.0f);

        int colCardinalText = ImColor.rgba(148, 163, 184, 220);
        drawList.addText(centerX - 4.0f, centerY - radarRadiusPixels + 4.0f, colCardinalText, "N");
        drawList.addText(centerX - 4.0f, centerY + radarRadiusPixels - 16.0f, colCardinalText, "S");
        drawList.addText(centerX + radarRadiusPixels - 14.0f, centerY - 7.0f, colCardinalText, "E");
        drawList.addText(centerX - radarRadiusPixels + 5.0f, centerY - 7.0f, colCardinalText, "W");

        // Range Rings (25m, 50m, 100m, 200m)
        int colRing = ImColor.rgba(56, 68, 94, 112);
        int colRingText = ImColor.rgba(148, 163, 184, 160);
        for (float r : RadarViewportConfig.ZOOM_LEVELS) {
            if (r < config.getRadarRadiusMeters()) {
                float ringPixels = r * pixelsPerMeter;
                drawList.addCircle(centerX, centerY, ringPixels, colRing, 48, 1.0f);
                drawList.addText(centerX + 4.0f, centerY - ringPixels + 2.0f, colRingText, (int) r + "m");
            }
        }

        // Area of Interest Circle (50m radius)
        if (config.isShowAoiCircle() && config.getAoiRadiusMeters() <= config.getRadarRadiusMeters()) {
            float aoiPixels = config.getAoiRadiusMeters() * pixelsPerMeter;
            int colAoi = ImColor.rgba(0, 170, 204, 80);
            drawList.addCircle(centerX, centerY, aoiPixels, colAoi, 48, 1.5f);
        }

        // Local Player (centered)
        int colLocal = ImColor.rgba(34, 197, 94, 255);
        int colLocalOutline = ImColor.rgba(255, 255, 255, 220);
        drawList.addCircleFilled(centerX, centerY, 6.0f, colLocal, 16);
        drawList.addCircle(centerX, centerY, 6.0f, colLocalOutline, 16, 1.5f);

        // Local Player Heading Indicator (yaw degrees: 0 = North / -Y, 90 = East / +X)
        double yawRad = Math.toRadians(clientState.getYaw());
        float tipX = centerX + (float) (14.0 * Math.sin(yawRad));
        float tipY = centerY - (float) (14.0 * Math.cos(yawRad));
        int colLocalYaw = ImColor.rgba(134, 239, 172, 255);
        drawList.addLine(centerX, centerY, tipX, tipY, colLocalYaw, 2.5f);

        // Remote Player Characters within AoI
        float localX = clientState.getX();
        float localZ = clientState.getZ();
        int colRemote = ImColor.rgba(56, 189, 248, 255);
        int colRemoteYaw = ImColor.rgba(125, 211, 252, 255);
        int colRemoteText = ImColor.rgba(248, 250, 252, 230);

        float mouseX = ImGui.getMousePosX();
        float mouseY = ImGui.getMousePosY();

        for (var remote : clientState.getRemoteEntities().values()) {
            float dist = (float) Math.hypot(remote.x() - localX, remote.z() - localZ);
            if (dist <= config.getRadarRadiusMeters()) {
                float[] screenPos = worldToScreen(remote.x(), remote.z(), localX, localZ, centerX, centerY, pixelsPerMeter);
                float px = screenPos[0];
                float py = screenPos[1];

                // Marker dot
                drawList.addCircleFilled(px, py, 5.0f, colRemote, 16);

                // Remote heading
                double rRad = Math.toRadians(remote.yaw());
                float rx = px + (float) (9.0 * Math.sin(rRad));
                float ry = py - (float) (9.0 * Math.cos(rRad));
                drawList.addLine(px, py, rx, ry, colRemoteYaw, 1.8f);

                // Nameplate
                if (config.isShowNameplates()) {
                    String jobAbbr = JobId.toAbbreviation(remote.mainJobId());
                    String label = String.format("%s [%s Lv.%d]", remote.name(), jobAbbr, remote.level());
                    drawList.addText(px - 25.0f, py - 18.0f, colRemoteText, label);
                }

                // Interactive Hover Tooltip
                if (Math.hypot(mouseX - px, mouseY - py) <= 8.0f) {
                    ImGui.beginTooltip();
                    ImGui.text(remote.name() + " (ID: " + remote.characterId() + ")");
                    ImGui.text(JobId.toAbbreviation(remote.mainJobId()) + " Level " + remote.level());
                    ImGui.text(String.format("Distance: %.1fm", dist));
                    ImGui.text(String.format("Coords: (%.2f, %.2f, %.2f)", remote.x(), remote.y(), remote.z()));
                    ImGui.endTooltip();
                }
            }
        }

        if (clientState.getRemoteEntities().isEmpty()) {
            drawList.addText(centerX - 60.0f, centerY + radarRadiusPixels - 24.0f, colRingText, "No other players in range");
        }

        ImGui.end();
    }
}
