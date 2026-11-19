package com.example.client.desktop.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UiCleanupTest {

    @AfterEach
    void clearProps() {
        System.clearProperty("c2.login.username");
        System.clearProperty("c2.login.password");
    }

    @Test
    @DisplayName("LoginPanel defaults to empty credentials instead of hardcoded dev/dev")
    void testLoginPanelDefaultsEmpty() {
        System.clearProperty("c2.login.username");
        System.clearProperty("c2.login.password");

        LoginPanel panel = new LoginPanel();
        assertEquals("", panel.getUsername());
        assertEquals("", panel.getPassword());
    }

    @Test
    @DisplayName("LoginPanel allows optional system property override for credentials")
    void testLoginPanelSystemPropertyOverride() {
        System.setProperty("c2.login.username", "tester");
        System.setProperty("c2.login.password", "s3cr3t");

        LoginPanel panel = new LoginPanel();
        assertEquals("tester", panel.getUsername());
        assertEquals("s3cr3t", panel.getPassword());
    }

    @Test
    @DisplayName("DebugLogPanel caps ring buffer at 500 entries and evicts oldest lines")
    void testDebugLogPanelCapsAt500() {
        DebugLogPanel panel = new DebugLogPanel();
        for (int i = 0; i < 625; i++) {
            panel.log("msg-" + i);
        }

        List<String> lines = panel.getLinesSnapshot();
        assertEquals(500, lines.size());
        assertTrue(lines.get(0).endsWith("msg-125"));
        assertTrue(lines.get(499).endsWith("msg-624"));
    }
}
