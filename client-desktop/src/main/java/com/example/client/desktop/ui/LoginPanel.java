package com.example.client.desktop.ui;

import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImString;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;

@Prototype
public class LoginPanel {

    private final ImString username = new ImString(resolveDefault("c2.login.username", "C2_LOGIN_USERNAME"), 64);
    private final ImString password = new ImString(resolveDefault("c2.login.password", "C2_LOGIN_PASSWORD"), 64);
    @Getter private boolean loginRequested;

    private static String resolveDefault(String sysProp, String envVar) {
        String val = System.getProperty(sysProp);
        if (val != null) {
            return val;
        }
        val = System.getenv(envVar);
        return val != null ? val : "";
    }

    public void render(ClientState clientState) {
        ImGui.setNextWindowPos(20, 20, ImGuiCond.Once);
        ImGui.setNextWindowSize(500, 240, ImGuiCond.Once);
        ImGui.begin("Login");

        // Display errors or status from ClientState
        if (clientState.getLastErrorMessage() != null && !clientState.getLastErrorMessage().isEmpty()) {
            ImGui.textColored(1.0f, 0.2f, 0.2f, 1.0f, "Error: " + clientState.getLastErrorMessage());
            ImGui.separator();
        }

        ImGui.inputText("Username", username);
        ImGui.inputText("Password##pw", password, ImGuiInputTextFlags.Password);

        if (clientState.getConnectionState() == ClientConnectionState.CONNECTING) {
            ImGui.textDisabled("Connecting & Authenticating...");
        } else {
            if (ImGui.button("Login")) {
                loginRequested = true;
            }
        }

        ImGui.end();
    }

    public String getUsername() { return username.get(); }
    public String getPassword() { return password.get(); }
    public void clearIntents() {
        loginRequested = false;
    }
}
