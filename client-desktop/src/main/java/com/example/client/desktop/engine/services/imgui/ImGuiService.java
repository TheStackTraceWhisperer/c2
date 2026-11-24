package com.example.client.desktop.engine.services.imgui;

import com.example.client.desktop.engine.IService;
import com.example.client.desktop.engine.services.window.WindowService;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
@RequiredArgsConstructor
public class ImGuiService implements IService {

    private final WindowService windowService;

    private ImGuiImplGlfw implGlfw;
    private ImGuiImplGl3 implGl3;
    private boolean initialized = false;

    @Override
    public int executionOrder() {
        return Integer.MIN_VALUE + 2;
    }

    @Override
    public void start() {
        start(windowService.getHandle());
    }

    public void start(long windowHandle) {
        ImGui.createContext();
        try {
            ImGuiIO io = ImGui.getIO();
            io.setIniFilename(null);

            implGlfw = new ImGuiImplGlfw();
            implGl3 = new ImGuiImplGl3();
            implGlfw.init(windowHandle, true);
            implGl3.init("#version 460");
            initialized = true;
            log.info("Dear ImGui initialized");
        } catch (Throwable t) {
            disposeResources();
            throw t;
        }
    }

    @Override
    public void update() {
        implGlfw.newFrame();
        ImGui.newFrame();
    }

    public void beginFrame(int displayWidth, int displayHeight) {
        implGlfw.newFrame();
        ImGuiIO io = ImGui.getIO();
        io.setDisplaySize(Math.max(1, displayWidth), Math.max(1, displayHeight));
        io.setDisplayFramebufferScale(1.0f, 1.0f);
        ImGui.newFrame();
    }

    @Override
    public void postUpdate() {
        ImGui.render();
        implGl3.renderDrawData(ImGui.getDrawData());
    }

    @Override
    public void stop() {
        if (!initialized) {
            return;
        }
        disposeResources();
        initialized = false;
        log.info("Dear ImGui disposed");
    }

    private void disposeResources() {
        if (implGl3 != null) {
            implGl3.dispose();
            implGl3 = null;
        }
        if (implGlfw != null) {
            implGlfw.dispose();
            implGlfw = null;
        }
        ImGui.destroyContext();
    }
}
