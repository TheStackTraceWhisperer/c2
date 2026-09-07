package com.example.client.desktop.ui.radar;

import io.micronaut.context.annotation.Prototype;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Prototype
public class RadarViewportConfig {

    public static final float[] ZOOM_LEVELS = {25.0f, 50.0f, 100.0f, 200.0f};
    public static final float MIN_ZOOM_RADIUS = 25.0f;
    public static final float MAX_ZOOM_RADIUS = 200.0f;

    private int zoomIndex = 1; // Default: 50.0m
    private float radarRadiusMeters = 50.0f;
    private boolean showGrid = true;
    private boolean showAoiCircle = true;
    private boolean showNameplates = true;
    private boolean showCoordinates = true;
    private float aoiRadiusMeters = 50.0f;

    public float getRadarRadiusMeters() {
        return radarRadiusMeters;
    }

    public void setRadarRadiusMeters(float radius) {
        this.radarRadiusMeters = Math.clamp(radius, MIN_ZOOM_RADIUS, MAX_ZOOM_RADIUS);
        syncZoomIndex();
    }

    public void zoomSmooth(float deltaMeters) {
        setRadarRadiusMeters(this.radarRadiusMeters + deltaMeters);
    }

    public void zoomIn() {
        if (zoomIndex > 0) {
            zoomIndex--;
            this.radarRadiusMeters = ZOOM_LEVELS[zoomIndex];
        }
    }

    public void zoomOut() {
        if (zoomIndex < ZOOM_LEVELS.length - 1) {
            zoomIndex++;
            this.radarRadiusMeters = ZOOM_LEVELS[zoomIndex];
        }
    }

    private void syncZoomIndex() {
        int bestIdx = 0;
        float bestDist = Math.abs(ZOOM_LEVELS[0] - radarRadiusMeters);
        for (int i = 1; i < ZOOM_LEVELS.length; i++) {
            float d = Math.abs(ZOOM_LEVELS[i] - radarRadiusMeters);
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        this.zoomIndex = bestIdx;
    }
}
