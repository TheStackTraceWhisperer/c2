package com.example.client.desktop.ui;

import imgui.ImColor;
import imgui.ImGui;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Screen fader for cross-zone transitions and scene changes.
 * Provides smooth fade-to-black, asset-swap callback when fully black, and fade-in.
 */
@Slf4j
public class ScreenFader {

    public enum State {
        IDLE,
        FADING_OUT,
        BLACK,
        FADING_IN
    }

    @Getter
    private State state = State.IDLE;
    @Getter
    private float alpha = 0.0f;

    private float fadeSpeed = 2.5f; // Duration = ~0.4s (1.0 / 2.5)
    private Runnable onBlackCallback;

    public void startTransition(float durationSeconds, Runnable onBlack) {
        if (durationSeconds <= 0.001f) {
            if (onBlack != null) {
                onBlack.run();
            }
            this.state = State.IDLE;
            this.alpha = 0.0f;
            return;
        }

        this.fadeSpeed = 1.0f / durationSeconds;
        this.onBlackCallback = onBlack;
        this.state = State.FADING_OUT;
    }

    public void update(float dt) {
        if (state == State.IDLE) {
            alpha = 0.0f;
            return;
        }

        if (state == State.FADING_OUT) {
            alpha += fadeSpeed * dt;
            if (alpha >= 1.0f) {
                alpha = 1.0f;
                state = State.BLACK;
                if (onBlackCallback != null) {
                    try {
                        onBlackCallback.run();
                    } catch (Exception e) {
                        log.error("Error executing screen fader black callback", e);
                    }
                    onBlackCallback = null;
                }
                // Automatically begin fading in once black actions finish
                state = State.FADING_IN;
            }
        } else if (state == State.FADING_IN) {
            alpha -= fadeSpeed * dt;
            if (alpha <= 0.0f) {
                alpha = 0.0f;
                state = State.IDLE;
            }
        }
    }

    public void render(long windowHandle, int width, int height) {
        if (windowHandle == 0L || state == State.IDLE || alpha <= 0.001f) {
            return;
        }

        int clampedAlpha = Math.clamp((int) (alpha * 255.0f), 0, 255);
        int color = ImColor.intToColor(0, 0, 0, clampedAlpha);

        // Render black overlay on top of all windows and viewport
        ImGui.getForegroundDrawList().addRectFilled(0, 0, width, height, color);
    }

    public boolean isTransitioning() {
        return state != State.IDLE;
    }

    public boolean isInputLocked() {
        return state == State.FADING_OUT || state == State.BLACK;
    }
}
