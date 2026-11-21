package com.example.client.desktop.ui;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@Slf4j
@Singleton
public class DebugLogPanel {

    static final int MAX_LINES = 500;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Deque<String> lines = new ArrayDeque<>(MAX_LINES);
    private final ImBoolean autoScroll = new ImBoolean(true);
    @Getter private boolean screenshotRequested;
    @Getter private boolean renderDocCaptureRequested;

    public synchronized void log(String message) {
        if (lines.size() >= MAX_LINES) {
            lines.removeFirst();
        }
        lines.addLast("[" + LocalTime.now().format(FMT) + "] " + message);
    }

    public synchronized List<String> getLinesSnapshot() {
        return List.copyOf(lines);
    }

    public synchronized void render() {
        ImGui.setNextWindowPos(920, 20, ImGuiCond.Once);
        ImGui.setNextWindowSize(340, 680, ImGuiCond.Once);
        ImGui.begin("Debug Log");
        ImGui.checkbox("Auto scroll", autoScroll);
        ImGui.sameLine();
        if (ImGui.button("Clear")) lines.clear();
        ImGui.sameLine();
        if (ImGui.button("Screenshot [F11]")) screenshotRequested = true;
        ImGui.sameLine();
        if (ImGui.button("RenderDoc [F12]")) renderDocCaptureRequested = true;
        ImGui.separator();
        ImGui.beginChild("log-scroll");
        for (String line : lines) {
            ImGui.textUnformatted(line);
        }
        if (autoScroll.get()) {
            ImGui.setScrollHereY(1f);
        }
        ImGui.endChild();
        ImGui.end();
    }

    public void clearIntents() {
        screenshotRequested = false;
        renderDocCaptureRequested = false;
    }
}
