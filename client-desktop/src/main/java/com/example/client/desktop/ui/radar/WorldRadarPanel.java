package com.example.client.desktop.ui.radar;

import com.example.client.desktop.camera.Camera;
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
    private final Camera camera;

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
        ImGui.setNextWindowPos(390, 20, ImGuiCond.Once);
        ImGui.setNextWindowSize(520, 680, ImGuiCond.Once);
        ImGui.begin("World Radar");

        // 1. Telemetry Header Overlay
        int zoneId = clientState.getCurrentZoneId();
        String zoneName = ZoneNameRegistry.getZoneName(zoneId);
        int worldId = clientState.getCurrentWorldId() > 0 ? clientState.getCurrentWorldId() : 1;
        int nearbyCount = clientState.getRemoteEntities().size();

        ImGui.text(String.format("Zone: %s (ID: %d)  |  World: %d  |  Nearby Players: %d",
                zoneName, zoneId, worldId, nearbyCount));

        // Calculate 50m region cell bounds in world coordinates
        int regionX0 = (int) (Math.floor(clientState.getX() / 50.0) * 50);
        int regionX1 = regionX0 + 50;
        int regionZ0 = (int) (Math.floor(clientState.getZ() / 50.0) * 50);
        int regionZ1 = regionZ0 + 50;

        ImGui.text(String.format("Pos: X: %.2f  Y: %.2f  Z: %.2f  |  Region: [X: %d..%d, Z: %d..%d]",
                clientState.getX(), clientState.getY(), clientState.getZ(), regionX0, regionX1, regionZ0, regionZ1));

        ImGui.text(String.format("Facing: %.1f°  |  Cam: %.1f°  |  Radius: %.0fm (PageUp/Down to zoom)",
                clientState.getYaw(), camera.getYaw(), config.getRadarRadiusMeters()));

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
        if (ImGui.button("Reset Cam")) {
            camera.reset();
        }

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

        // Mouse wheel scroll zoom when hovering radar window
        float mouseWheel = ImGui.getIO().getMouseWheel();
        if (mouseWheel != 0.0f && ImGui.isWindowHovered()) {
            config.zoomSmooth(-mouseWheel * 10.0f);
        }

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
        float radarRadiusMeters = config.getRadarRadiusMeters();
        float localX = clientState.getX();
        float localZ = clientState.getZ();

        // Fixed World-Space Grid lines (fixed in world coordinates, scrolling as character moves)
        if (config.isShowGrid()) {
            float minorStep = (radarRadiusMeters <= 60.0f) ? 10.0f : (radarRadiusMeters <= 120.0f ? 20.0f : 50.0f);
            float majorStep = (radarRadiusMeters <= 60.0f) ? 50.0f : 100.0f;

            int colGridMinor = ImColor.rgba(45, 55, 75, 80);
            int colGridMajor = ImColor.rgba(75, 95, 125, 140);
            int colOriginAxis = ImColor.rgba(234, 179, 8, 180); // Gold origin axes
            int colCoordText = ImColor.rgba(148, 163, 184, 180);

            float minX = localX - radarRadiusMeters;
            float maxX = localX + radarRadiusMeters;
            float minZ = localZ - radarRadiusMeters;
            float maxZ = localZ + radarRadiusMeters;

            // --- Vertical Grid Lines (Constant World X) ---
            float startX = (float) Math.floor(minX / minorStep) * minorStep;
            float endX = (float) Math.ceil(maxX / minorStep) * minorStep;

            for (float wx = startX; wx <= endX; wx += minorStep) {
                float dx = (wx - localX) * pixelsPerMeter;
                if (Math.abs(dx) >= radarRadiusPixels) {
                    continue;
                }

                float chord = (float) Math.sqrt(radarRadiusPixels * radarRadiusPixels - dx * dx);
                float lineX = centerX + dx;
                float y1 = centerY - chord;
                float y2 = centerY + chord;

                boolean isOrigin = Math.abs(wx) < 0.01f;
                boolean isMajor = Math.abs(Math.round(wx / minorStep) % Math.round(majorStep / minorStep)) == 0;

                int col = isOrigin ? colOriginAxis : (isMajor ? colGridMajor : colGridMinor);
                float thickness = isOrigin ? 1.8f : (isMajor ? 1.4f : 1.0f);

                drawList.addLine(lineX, y1, lineX, y2, col, thickness);

                if (isMajor && chord > 24.0f) {
                    String lbl = ((int) wx) + "m";
                    drawList.addText(lineX + 3.0f, centerY - chord + 4.0f, colCoordText, lbl);
                }
            }

            // --- Horizontal Grid Lines (Constant World Z, +Z is North/Up) ---
            float startZ = (float) Math.floor(minZ / minorStep) * minorStep;
            float endZ = (float) Math.ceil(maxZ / minorStep) * minorStep;

            for (float wz = startZ; wz <= endZ; wz += minorStep) {
                float dz = (wz - localZ) * pixelsPerMeter;
                float dy = -dz; // Screen Y is inverted (+Z is up)
                if (Math.abs(dy) >= radarRadiusPixels) {
                    continue;
                }

                float chord = (float) Math.sqrt(radarRadiusPixels * radarRadiusPixels - dy * dy);
                float lineY = centerY + dy;
                float x1 = centerX - chord;
                float x2 = centerX + chord;

                boolean isOrigin = Math.abs(wz) < 0.01f;
                boolean isMajor = Math.abs(Math.round(wz / minorStep) % Math.round(majorStep / minorStep)) == 0;

                int col = isOrigin ? colOriginAxis : (isMajor ? colGridMajor : colGridMinor);
                float thickness = isOrigin ? 1.8f : (isMajor ? 1.4f : 1.0f);

                drawList.addLine(x1, lineY, x2, lineY, col, thickness);

                if (isMajor && chord > 24.0f) {
                    String lbl = ((int) wz) + "m";
                    drawList.addText(centerX - chord + 4.0f, lineY - 14.0f, colCoordText, lbl);
                }
            }

            // --- Origin Marker (0, 0) if visible within radar viewport ---
            float originDx = (0.0f - localX) * pixelsPerMeter;
            float originDy = -(0.0f - localZ) * pixelsPerMeter;
            if (originDx * originDx + originDy * originDy < radarRadiusPixels * radarRadiusPixels) {
                float ox = centerX + originDx;
                float oy = centerY + originDy;
                drawList.addCircleFilled(ox, oy, 4.0f, colOriginAxis, 12);
                drawList.addCircle(ox, oy, 7.0f, colOriginAxis, 12, 1.2f);
                drawList.addText(ox + 9.0f, oy - 7.0f, colOriginAxis, "(0,0)");
            }
        }

        // Rim Cardinal Tick Marks & Labels
        int colRimTick = ImColor.rgba(148, 163, 184, 160);
        drawList.addLine(centerX, centerY - radarRadiusPixels, centerX, centerY - radarRadiusPixels + 8.0f, colRimTick, 1.5f);
        drawList.addLine(centerX, centerY + radarRadiusPixels - 8.0f, centerX, centerY + radarRadiusPixels, colRimTick, 1.5f);
        drawList.addLine(centerX + radarRadiusPixels - 8.0f, centerY, centerX + radarRadiusPixels, centerY, colRimTick, 1.5f);
        drawList.addLine(centerX - radarRadiusPixels, centerY, centerX - radarRadiusPixels + 8.0f, centerY, colRimTick, 1.5f);

        int colCardinalText = ImColor.rgba(148, 163, 184, 220);
        drawList.addText(centerX - 4.0f, centerY - radarRadiusPixels + 10.0f, colCardinalText, "N");
        drawList.addText(centerX - 4.0f, centerY + radarRadiusPixels - 22.0f, colCardinalText, "S");
        drawList.addText(centerX + radarRadiusPixels - 18.0f, centerY - 7.0f, colCardinalText, "E");
        drawList.addText(centerX - radarRadiusPixels + 10.0f, centerY - 7.0f, colCardinalText, "W");

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

        // Camera View Frustum Cone
        double camRad = Math.toRadians(camera.getYaw());
        double leftRad = Math.toRadians(camera.getYaw() - 25.0);
        double rightRad = Math.toRadians(camera.getYaw() + 25.0);
        float coneLength = Math.min(radarRadiusPixels * 0.45f, 60.0f);

        float camTipX = centerX + (float) (coneLength * Math.sin(camRad));
        float camTipY = centerY - (float) (coneLength * Math.cos(camRad));

        float leftConeX = centerX + (float) (coneLength * Math.sin(leftRad));
        float leftConeY = centerY - (float) (coneLength * Math.cos(leftRad));

        float rightConeX = centerX + (float) (coneLength * Math.sin(rightRad));
        float rightConeY = centerY - (float) (coneLength * Math.cos(rightRad));

        int colFrustumFill = ImColor.rgba(96, 165, 250, 35);
        int colFrustumLine = ImColor.rgba(147, 197, 253, 110);
        int colFrustumCenter = ImColor.rgba(191, 219, 254, 160);

        drawList.addTriangleFilled(centerX, centerY, leftConeX, leftConeY, rightConeX, rightConeY, colFrustumFill);
        drawList.addLine(centerX, centerY, leftConeX, leftConeY, colFrustumLine, 1.2f);
        drawList.addLine(centerX, centerY, rightConeX, rightConeY, colFrustumLine, 1.2f);
        drawList.addLine(leftConeX, leftConeY, rightConeX, rightConeY, colFrustumLine, 1.0f);
        drawList.addLine(centerX, centerY, camTipX, camTipY, colFrustumCenter, 1.5f);

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
