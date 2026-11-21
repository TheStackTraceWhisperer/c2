package com.example.client.desktop.state;

import com.example.asset.interaction.ZoneInteraction;
import com.example.client.desktop.scene.SceneActor;
import com.example.client.desktop.scene.WorldScene;
import com.example.client.desktop.util.MatrixMath;
import com.example.client.interp.InterpolatedEntityState;
import com.example.client.state.ClientState;
import com.example.common.protocol.JobId;
import com.example.common.time.VanadielTime;
import com.example.common.time.WeatherType;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.renderer.FontMojiGlRenderer;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;

/**
 * Renders the in-game Vana'diel environment HUD banner (diurnal time, weather override, active zoneline alert)
 * and 3D-projected floating actor nameplates. Extracted from {@link InGameState} to keep state lifecycle orchestration cohesive.
 */
@Getter
@Setter
public final class InGameHudOverlay {

    private WeatherType currentWeather = WeatherType.CLEAR;
    private boolean manualTimeOverride = false;
    private float manualTimeHour = 12.0f;

    public VanadielTime resolveCurrentTime() {
        return manualTimeOverride ? VanadielTime.ofHour(manualTimeHour) : VanadielTime.now();
    }

    public void renderClockBanner(VanadielTime vTime, ZoneInteraction activeZoneline) {
        float bannerX = Math.max(390.0f, (ImGui.getIO().getDisplaySizeX() - 520.0f) * 0.5f);
        ImGui.setNextWindowPos(bannerX, 20, ImGuiCond.Once);
        ImGui.setNextWindowSize(520, 0, ImGuiCond.Once);
        ImGui.begin("Vana'diel Environment");
        ImGui.textColored(1.0f, 0.85f, 0.35f, 1.0f, vTime.formatClockSummary());
        ImGui.sameLine();
        ImGui.textColored(0.4f, 0.85f, 1.0f, 1.0f, " | Weather: " + currentWeather.getDisplayName());

        String[] weatherNames = Arrays.stream(WeatherType.values()).map(WeatherType::getDisplayName).toArray(String[]::new);
        ImInt weatherIdx = new ImInt(currentWeather.ordinal());
        if (ImGui.combo("Weather", weatherIdx, weatherNames)) {
            currentWeather = WeatherType.values()[weatherIdx.get()];
        }

        if (ImGui.checkbox("Manual Time Override", manualTimeOverride)) {
            manualTimeOverride = !manualTimeOverride;
        }

        if (manualTimeOverride) {
            float[] hourArr = new float[]{manualTimeHour};
            if (ImGui.sliderFloat("Time of Day", hourArr, 0.0f, 24.0f, "%.1f h")) {
                manualTimeHour = hourArr[0];
            }
            if (ImGui.button("Dawn (05:00)")) manualTimeHour = 5.0f;
            ImGui.sameLine();
            if (ImGui.button("Noon (12:00)")) manualTimeHour = 12.0f;
            ImGui.sameLine();
            if (ImGui.button("Dusk (18:00)")) manualTimeHour = 18.0f;
            ImGui.sameLine();
            if (ImGui.button("Midnight (00:00)")) manualTimeHour = 0.0f;
        }

        if (activeZoneline != null) {
            ImGui.separator();
            ImGui.textColored(0.2f, 1.0f, 0.4f, 1.0f,
                    ">>> [ZONELINE] Gate to " + activeZoneline.getDestId() + " (Walk across to change zone) <<<");
        }
        ImGui.end();
    }

    public void renderNameplates(
            ClientState clientState,
            String localCharacterName,
            float[] proj,
            float[] view,
            int fbWidth,
            int fbHeight,
            long now
    ) {
        renderNameplates(clientState, localCharacterName, proj, view, fbWidth, fbHeight, now, null, null, null);
    }

    public void renderNameplates(
            ClientState clientState,
            String localCharacterName,
            float[] proj,
            float[] view,
            int fbWidth,
            int fbHeight,
            long now,
            WorldScene worldScene,
            FontMojiGlRenderer fontMojiGlRenderer,
            ZoneInteraction activeZoneline
    ) {
        boolean useFontMoji = fontMojiGlRenderer != null;
        if (useFontMoji) {
            fontMojiGlRenderer.begin(fbWidth, fbHeight);
        }

        SceneActor localActor = worldScene != null ? worldScene.getLocalPlayerActor() : null;
        float localOffsetY = resolveActorNameplateOffsetY(localActor, 2.0f);
        float[] playerScreen = MatrixMath.projectToScreen(
                clientState.getX(), clientState.getY() - localOffsetY, clientState.getZ(),
                proj, view, fbWidth, fbHeight
        );
        if (playerScreen != null) {
            if (useFontMoji) {
                fontMojiGlRenderer.queueCenteredText(
                        localCharacterName,
                        playerScreen[0],
                        playerScreen[1],
                        1.0f,
                        0.51f, 0.82f, 1.0f, 1.0f
                );
            } else {
                drawBillboardLabel(playerScreen[0], playerScreen[1], "##nameplate_local", localCharacterName, 0.4f, 0.8f, 1.0f);
            }
        }

        for (var entity : clientState.getRemoteEntities().values()) {
            InterpolatedEntityState interp = clientState.sampleInterpolatedEntity(entity.characterId(), now);
            float dx = interp.x() - clientState.getX();
            float dz = interp.z() - clientState.getZ();
            if (dx * dx + dz * dz > 2500.0f) {
                continue;
            }

            SceneActor remoteActor = worldScene != null ? worldScene.getActor(entity.characterId()) : null;
            float remoteOffsetY = resolveActorNameplateOffsetY(remoteActor, 1.9f);
            float[] screen = MatrixMath.projectToScreen(
                    interp.x(), interp.y() - remoteOffsetY, interp.z(),
                    proj, view, fbWidth, fbHeight
            );
            if (screen != null) {
                boolean isNpc = entity.mainJobId() == JobId.NPC;
                String label = isNpc
                        ? "[NPC] " + entity.name()
                        : entity.name() + " (" + JobId.toAbbreviation(entity.mainJobId()) + " Lv" + entity.level() + ")";
                if (useFontMoji) {
                    float r = isNpc ? 1.00f : 0.51f;
                    float g = isNpc ? 0.86f : 0.82f;
                    float b = isNpc ? 0.39f : 1.00f;
                    fontMojiGlRenderer.queueCenteredText(label, screen[0], screen[1], 1.0f, r, g, b, 1.0f);
                } else {
                    float r = isNpc ? 0.95f : 0.40f;
                    float g = isNpc ? 0.80f : 0.80f;
                    float b = isNpc ? 0.25f : 1.00f;
                    drawBillboardLabel(screen[0], screen[1], "##nameplate_" + entity.characterId(), label, r, g, b);
                }
            }
        }

        if (useFontMoji) {
            if (activeZoneline != null) {
                fontMojiGlRenderer.queueCenteredText(
                        "[ZONELINE] Gate to Zone #" + activeZoneline.getDestId(),
                        fbWidth * 0.5f,
                        36.0f,
                        1.0f,
                        0.55f, 0.96f, 0.60f, 1.0f
                );
            }
            fontMojiGlRenderer.flush();
        }
    }

    static float resolveActorNameplateOffsetY(SceneActor actor, float fallbackHeight) {
        if (actor == null || actor.getVisualModel() == null) {
            return fallbackHeight;
        }
        LoadedCharacter character = actor.getVisualModel().getCharacter();
        if (character == null) {
            return fallbackHeight;
        }
        float height = character.feetOffsetY() * 2.0f * character.effectiveScale();
        if (!Float.isFinite(height) || height <= 0.25f) {
            return fallbackHeight;
        }
        return height + 0.35f;
    }

    public static void drawBillboardLabel(float screenX, float screenY, String windowId, String text, float r, float g, float b) {
        int flags = ImGuiWindowFlags.NoDecoration
                | ImGuiWindowFlags.NoInputs
                | ImGuiWindowFlags.NoBackground
                | ImGuiWindowFlags.AlwaysAutoResize;
        ImGui.setNextWindowPos(screenX - 60, screenY, ImGuiCond.Always);
        ImGui.begin(windowId, flags);
        ImGui.textColored(r, g, b, 1.0f, text);
        ImGui.end();
    }
}
