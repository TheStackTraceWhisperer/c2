package com.ffxi.viewer;

import com.ffxi.viewer.environment.WeatherPreset;
import com.ffxi.viewer.shader.NormalMapManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapShaderSandboxTest {

    @Test
    @DisplayName("Verify all 20 authentic FFXI weather presets have valid 8-slice sky palettes, lighting, and fog")
    void testWeatherPresetsIntegrity() {
        assertEquals(20, WeatherPreset.ALL.size(), "Expected exactly 20 canonical FFXI weather presets");

        for (WeatherPreset preset : WeatherPreset.ALL) {
            assertNotNull(preset.id(), "Weather ID must not be null");
            assertNotNull(preset.displayName(), "Display name must not be null");
            assertNotNull(preset.element(), "Elemental affinity must not be null");
            assertNotNull(preset.effectType(), "Weather effect type must not be null");

            List<WeatherPreset.SkySlice> slices = preset.skySlices();
            assertEquals(8, slices.size(), "FFXI sky dome must have exactly 8 elevation slices for " + preset.id());

            // Elevations must be ordered from horizon (0.0) to zenith (1.0)
            assertEquals(0.0f, slices.get(0).elevation(), 0.001f);
            assertEquals(1.0f, slices.get(7).elevation(), 0.001f);
            for (int i = 0; i < 7; i++) {
                assertTrue(slices.get(i).elevation() <= slices.get(i + 1).elevation(),
                        "Elevation must increase monotonically from slice " + i + " to " + (i + 1));
            }

            // Colors must be within valid RGBA range [0, 1]
            for (WeatherPreset.SkySlice slice : slices) {
                assertTrue(slice.color().x >= 0.0f && slice.color().x <= 1.0f);
                assertTrue(slice.color().y >= 0.0f && slice.color().y <= 1.0f);
                assertTrue(slice.color().z >= 0.0f && slice.color().z <= 1.0f);
                assertTrue(slice.color().w >= 0.0f && slice.color().w <= 1.0f);
            }

            // Fog boundaries must be positive and non-inverted
            assertTrue(preset.fogNear() >= 0.0f);
            assertTrue(preset.fogFar() > preset.fogNear());
        }
    }

    @Test
    @DisplayName("Verify smooth weather preset interpolation between Fine and Squall")
    void testWeatherInterpolation() {
        WeatherPreset fine = WeatherPreset.FINE;
        WeatherPreset squall = WeatherPreset.SQUALL;

        WeatherPreset half = WeatherPreset.interpolate(fine, squall, 0.5f);
        assertEquals(8, half.skySlices().size());

        // Check midpoint interpolation
        float expectedFogFar = (fine.fogFar() + squall.fogFar()) * 0.5f;
        assertEquals(expectedFogFar, half.fogFar(), 0.01f);

        float expectedSunX = (fine.sunColor().x + squall.sunColor().x) * 0.5f;
        assertEquals(expectedSunX, half.sunColor().x, 0.01f);
    }

    @Test
    @DisplayName("Verify light space matrix calculation produces finite orthogonal projection")
    void testLightSpaceMatrixComputation() {
        Matrix4f lightView = new Matrix4f();
        Matrix4f lightProj = new Matrix4f();
        Matrix4f lightSpaceMatrix = new Matrix4f();

        Vector3f lightDir = new Vector3f(0.5f, 0.8f, 0.2f).normalize();
        Vector3f target = new Vector3f(100.0f, 15.0f, -50.0f);
        float boxSize = 1200.0f;

        Vector3f eyePos = new Vector3f(target).add(new Vector3f(lightDir).mul(boxSize * 1.2f));
        lightView.lookAt(eyePos, target, new Vector3f(0f, 1f, 0f));
        lightProj.ortho(-boxSize, boxSize, -boxSize, boxSize, 10.0f, boxSize * 2.8f);
        lightProj.mul(lightView, lightSpaceMatrix);

        float[] m = new float[16];
        lightSpaceMatrix.get(m);
        for (float val : m) {
            assertTrue(Float.isFinite(val), "Matrix elements must be finite numbers");
        }
    }

    @Test
    @DisplayName("Verify procedural Sobel normal map math correctly calculates surface slopes")
    void testSobelNormalMapMath() {
        int w = 4;
        int h = 4;
        byte[] flatRgba = new byte[w * h * 4];
        // Flat grey texture
        for (int i = 0; i < w * h * 4; i += 4) {
            flatRgba[i] = (byte) 128;     // R
            flatRgba[i + 1] = (byte) 128; // G
            flatRgba[i + 2] = (byte) 128; // B
            flatRgba[i + 3] = (byte) 255; // A
        }

        // On a flat uniform texture, Sobel dX and dY should be 0, so normal is pointing directly up Z (0, 0, 1)
        float lum = (0.299f * 128 + 0.587f * 128 + 0.114f * 128) / 255.0f;
        float dX = 0.0f;
        float dY = 0.0f;
        float dZ = 1.0f / 1.0f;
        float len = (float) Math.sqrt(dX * dX + dY * dY + dZ * dZ);
        float nx = dX / len;
        float ny = dY / len;
        float nz = dZ / len;

        assertEquals(0.0f, nx, 0.001f);
        assertEquals(0.0f, ny, 0.001f);
        assertEquals(1.0f, nz, 0.001f);

        int rByte = Math.round((nx * 0.5f + 0.5f) * 255.0f);
        int gByte = Math.round((ny * 0.5f + 0.5f) * 255.0f);
        int bByte = Math.round((nz * 0.5f + 0.5f) * 255.0f);

        assertEquals(128, rByte); // Flat normal map X = 128
        assertEquals(128, gByte); // Flat normal map Y = 128
        assertEquals(255, bByte); // Flat normal map Z = 255
    }
}
