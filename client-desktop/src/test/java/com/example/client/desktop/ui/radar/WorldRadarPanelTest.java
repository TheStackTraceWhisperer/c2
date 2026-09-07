package com.example.client.desktop.ui.radar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldRadarPanelTest {

    @Test
    @DisplayName("Local player position projects exactly to radar screen center")
    void testWorldToScreenCenter() {
        float centerX = 250.0f;
        float centerY = 250.0f;
        float pixelsPerMeter = 4.0f;

        float[] pos = WorldRadarPanel.worldToScreen(66.5f, -40.0f, 66.5f, -40.0f, centerX, centerY, pixelsPerMeter);

        assertArrayEquals(new float[]{250.0f, 250.0f}, pos, 0.001f);
    }

    @Test
    @DisplayName("Cardinal offsets project correctly: North is -Y, East is +X, South is +Y, West is -X")
    void testWorldToScreenCardinalDirections() {
        float centerX = 200.0f;
        float centerY = 200.0f;
        float localX = 0.0f;
        float localZ = 0.0f;
        float pixelsPerMeter = 2.0f;

        // North (+Z = +10m) -> screen Y should decrease by 20px
        float[] north = WorldRadarPanel.worldToScreen(0.0f, 10.0f, localX, localZ, centerX, centerY, pixelsPerMeter);
        assertEquals(200.0f, north[0], 0.001f);
        assertEquals(180.0f, north[1], 0.001f);

        // East (+X = +10m) -> screen X should increase by 20px
        float[] east = WorldRadarPanel.worldToScreen(10.0f, 0.0f, localX, localZ, centerX, centerY, pixelsPerMeter);
        assertEquals(220.0f, east[0], 0.001f);
        assertEquals(200.0f, east[1], 0.001f);

        // South (-Z = -10m) -> screen Y should increase by 20px
        float[] south = WorldRadarPanel.worldToScreen(0.0f, -10.0f, localX, localZ, centerX, centerY, pixelsPerMeter);
        assertEquals(200.0f, south[0], 0.001f);
        assertEquals(220.0f, south[1], 0.001f);

        // West (-X = -10m) -> screen X should decrease by 20px
        float[] west = WorldRadarPanel.worldToScreen(-10.0f, 0.0f, localX, localZ, centerX, centerY, pixelsPerMeter);
        assertEquals(180.0f, west[0], 0.001f);
        assertEquals(200.0f, west[1], 0.001f);
    }

    @Test
    @DisplayName("Zoom controls scale radius smoothly within bounds")
    void testZoomControls() {
        RadarViewportConfig config = new RadarViewportConfig();
        assertEquals(50.0f, config.getRadarRadiusMeters()); // Default 50m

        config.zoomIn();
        assertEquals(25.0f, config.getRadarRadiusMeters()); // Clamped to min 25m

        config.zoomIn();
        assertEquals(25.0f, config.getRadarRadiusMeters()); // Cannot zoom in further

        config.zoomOut();
        assertEquals(50.0f, config.getRadarRadiusMeters());

        config.zoomOut();
        assertEquals(100.0f, config.getRadarRadiusMeters());

        config.zoomOut();
        assertEquals(200.0f, config.getRadarRadiusMeters());

        config.zoomOut();
        assertEquals(200.0f, config.getRadarRadiusMeters()); // Clamped to max 200m
    }

    @Test
    @DisplayName("Smooth zoom adjusts continuous radius and clamps within [25m, 200m]")
    void testSmoothZoom() {
        RadarViewportConfig config = new RadarViewportConfig();
        assertEquals(50.0f, config.getRadarRadiusMeters());

        config.zoomSmooth(-15.0f);
        assertEquals(35.0f, config.getRadarRadiusMeters(), 0.001f);

        config.zoomSmooth(-50.0f);
        assertEquals(RadarViewportConfig.MIN_ZOOM_RADIUS, config.getRadarRadiusMeters(), 0.001f);

        config.zoomSmooth(300.0f);
        assertEquals(RadarViewportConfig.MAX_ZOOM_RADIUS, config.getRadarRadiusMeters(), 0.001f);
    }

    @Test
    @DisplayName("Fixed world grid lines shift across screen as player translates")
    void testFixedWorldGridProjection() {
        float centerX = 200.0f;
        float centerY = 200.0f;
        float pixelsPerMeter = 2.0f;

        // When player is at (0, 0), world line X=10m projects to screen X = 200 + 20 = 220px
        float[] atOrigin = WorldRadarPanel.worldToScreen(10.0f, 0.0f, 0.0f, 0.0f, centerX, centerY, pixelsPerMeter);
        assertEquals(220.0f, atOrigin[0], 0.001f);

        // When player moves East to (10, 0), world line X=10m projects directly under player at screen center (200px)
        float[] playerAt10 = WorldRadarPanel.worldToScreen(10.0f, 0.0f, 10.0f, 0.0f, centerX, centerY, pixelsPerMeter);
        assertEquals(200.0f, playerAt10[0], 0.001f);

        // When player moves further East to (20, 0), world line X=10m projects to the left at screen X = 200 - 20 = 180px
        float[] playerAt20 = WorldRadarPanel.worldToScreen(10.0f, 0.0f, 20.0f, 0.0f, centerX, centerY, pixelsPerMeter);
        assertEquals(180.0f, playerAt20[0], 0.001f);
    }
}
