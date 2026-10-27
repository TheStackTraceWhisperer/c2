package com.example.client.desktop.ui;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;

@Prototype
public class InGamePanel {

    @Getter private boolean pingRequested;
    @Getter private boolean logoutRequested;
    @Getter private boolean characterSelectRequested;
    @Getter private boolean mapToggleRequested;
    @Getter private int warpRequestedZoneId = -1;

    private int selectedZoneIndex = 0;
    private static final int[] WARP_ZONE_IDS = {
            230, 231, 232, 233, 234, 235, 236, 238, 241, 244, 245, 248, 249, 100, 102
    };
    private static final String[] WARP_ZONE_NAMES = {
            "South San d'Oria (230)",
            "North San d'Oria (231)",
            "Port San d'Oria (232)",
            "Chateau d'Oraguille (233)",
            "Bastok Mines (234)",
            "Bastok Markets (235)",
            "Port Bastok (236)",
            "Windurst Waters (238)",
            "Windurst Woods (241)",
            "Upper Jeuno (244)",
            "Lower Jeuno (245)",
            "Selbina (248)",
            "Mhaura (249)",
            "West Ronfaure (100)",
            "La Theine Plateau (102)"
    };

    private String characterName = "";
    private String status = "";
    private String keepAliveStatus = "connected";
    private long lastRttMs = -1;

    public void setContext(String characterName, String sessionId) {
        this.characterName = characterName;
        this.status = "Playing as " + characterName + "\n(session=" + sessionId + ")";
    }

    public void updateKeepAlive(String ks, long rtt) {
        this.keepAliveStatus = ks;
        this.lastRttMs = rtt;
    }

    public void render() {
        ImGui.setNextWindowPos(20, 20, ImGuiCond.Once);
        ImGui.setNextWindowSize(360, 680, ImGuiCond.Once);
        ImGui.begin("In Game Session");

        ImGui.text(status);
        ImGui.separator();

        ImGui.text("Connection & Heartbeat:");
        if (lastRttMs >= 0) {
            ImGui.textColored(0.2f, 0.9f, 0.3f, 1.0f, "Status: " + keepAliveStatus + " (RTT: " + lastRttMs + "ms)");
        } else {
            ImGui.textColored(0.9f, 0.8f, 0.2f, 1.0f, "Status: " + keepAliveStatus + " (measuring...)");
        }

        if (ImGui.button("Ping Gateway")) {
            pingRequested = true;
        }

        ImGui.separator();
        ImGui.text("Session Navigation:");
        if (ImGui.button("Character Select")) {
            characterSelectRequested = true;
        }
        ImGui.sameLine();
        if (ImGui.button("Sign Out")) {
            logoutRequested = true;
        }

        ImGui.separator();
        ImGui.text("World Cartography & Travel:");
        if (ImGui.button("Toggle Cartography Map [M]")) {
            mapToggleRequested = true;
        }
        imgui.type.ImInt selZone = new imgui.type.ImInt(selectedZoneIndex);
        if (ImGui.combo("Zone", selZone, WARP_ZONE_NAMES)) {
            selectedZoneIndex = selZone.get();
        }
        ImGui.sameLine();
        if (ImGui.button("Warp")) {
            warpRequestedZoneId = WARP_ZONE_IDS[selectedZoneIndex];
        }

        ImGui.separator();
        ImGui.text("Movement Controls (Camera-Relative):");
        ImGui.bulletText("W / S: Move Forward / Backward");
        ImGui.bulletText("A / D: Move Left / Right");
        ImGui.bulletText("Left / Right Arrow (or Q / E): Orbit Cam");
        ImGui.bulletText("Up / Down Arrow: Tilt Cam");
        ImGui.bulletText("Right Mouse Drag: Free-Look Orbit");
        ImGui.bulletText("PageUp / PageDown (or Mouse Scroll): Zoom In / Out");
        ImGui.bulletText("Home: Reset Camera to North");

        ImGui.separator();
        ImGui.text("Tactical Radar:");
        ImGui.bulletText("Center Blip: Local Player");
        ImGui.bulletText("Green Pointer: Facing Yaw");
        ImGui.bulletText("Cyan Blips: Nearby Players");
        ImGui.bulletText("Hover Blip: Player Telemetry");

        ImGui.end();
    }

    public void clearIntents() {
        pingRequested = false;
        characterSelectRequested = false;
        logoutRequested = false;
        mapToggleRequested = false;
        warpRequestedZoneId = -1;
    }
}
