package com.example.client.desktop.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ScreenFaderTest {

    @Test
    @DisplayName("ScreenFader transitions from IDLE -> FADING_OUT -> BLACK -> FADING_IN -> IDLE")
    void testScreenFaderCycle() {
        ScreenFader fader = new ScreenFader();
        assertEquals(ScreenFader.State.IDLE, fader.getState());
        assertEquals(0.0f, fader.getAlpha());
        assertFalse(fader.isTransitioning());
        assertFalse(fader.isInputLocked());

        AtomicBoolean blackActionExecuted = new AtomicBoolean(false);

        // Start 0.4s transition (duration = 0.4s -> speed = 2.5/s)
        fader.startTransition(0.4f, () -> blackActionExecuted.set(true));

        assertEquals(ScreenFader.State.FADING_OUT, fader.getState());
        assertTrue(fader.isTransitioning());
        assertTrue(fader.isInputLocked());

        // Step 0.2s -> alpha should be ~0.5
        fader.update(0.2f);
        assertTrue(fader.getAlpha() >= 0.49f && fader.getAlpha() <= 0.51f);
        assertFalse(blackActionExecuted.get());
        assertTrue(fader.isInputLocked());

        // Step another 0.25s -> reaches 1.0, executes callback, and enters FADING_IN
        fader.update(0.25f);
        assertTrue(blackActionExecuted.get(), "Callback should execute when reaching black");
        assertEquals(ScreenFader.State.FADING_IN, fader.getState());
        assertFalse(fader.isInputLocked(), "Input should unlock when fading in");

        // Step 0.5s -> finishes fade in to IDLE
        fader.update(0.5f);
        assertEquals(ScreenFader.State.IDLE, fader.getState());
        assertEquals(0.0f, fader.getAlpha());
        assertFalse(fader.isTransitioning());
    }

    @Test
    @DisplayName("ScreenFader handles zero duration instantly")
    void testInstantTransition() {
        ScreenFader fader = new ScreenFader();
        AtomicBoolean actionExecuted = new AtomicBoolean(false);

        fader.startTransition(0.0f, () -> actionExecuted.set(true));

        assertTrue(actionExecuted.get());
        assertEquals(ScreenFader.State.IDLE, fader.getState());
        assertEquals(0.0f, fader.getAlpha());
    }
}
