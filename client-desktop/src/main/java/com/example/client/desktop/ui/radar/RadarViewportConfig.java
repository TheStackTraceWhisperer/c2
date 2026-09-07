package com.example.client.desktop.ui.radar;

import io.micronaut.context.annotation.Prototype;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Prototype
public class RadarViewportConfig {

    public static final float[] ZOOM_LEVELS = {25.0f, 50.0f, 100.0f, 200.0f};

    private int zoomIndex = 1; // Default: 50.0m
    private boolean showGrid = true;
    private boolean showAoiCircle = true;
    private boolean showNameplates = true;
    private boolean showCoordinates = true;
    private float aoiRadiusMeters = 50.0f;

    public float getRadarRadiusMeters() {
        return ZOOM_LEVELS[Math.clamp(zoomIndex, 0, ZOOM_LEVELS.length - 1)];
    }

    public void zoomIn() {
        if (zoomIndex > 0) {
            zoomIndex--;
        }
    }

    public void zoomOut() {
        if (zoomIndex < ZOOM_LEVELS.length - 1) {
            zoomIndex++;
        }
    }
}
