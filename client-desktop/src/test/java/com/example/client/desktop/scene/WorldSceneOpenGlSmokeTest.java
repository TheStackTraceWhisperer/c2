package com.example.client.desktop.scene;

import com.example.asset.config.FfxiPathConfig;
import com.ffxi.viewer.HeadlessGlContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldSceneOpenGlSmokeTest {

    @Test
    @DisplayName("WorldScene initializes all #version 460 core shaders and renders offscreen FBO frame under OpenGL 4.6 Core")
    void testWorldSceneInitializesAndCapturesOffscreenUnderOpenGl46() {
        assumeTrue(HeadlessGlContext.isDisplayAvailable(), "DISPLAY required (run via scripts/with-xvfb.sh)");

        try (HeadlessGlContext ignored = HeadlessGlContext.create();
             WorldScene scene = new WorldScene()) {
            scene.init();

            assertTrue(scene.isInitialized(), "WorldScene must initialize in OpenGL 4.6 Core context");
            assertNotNull(scene.getZoneShader(), "ZoneShader must compile and link");
            assertNotNull(scene.getCharacterShader(), "CharacterShader must compile and link");
            assertNotNull(scene.getShadowRenderer(), "ShadowMapRenderer must initialize");
            assertNotNull(scene.getSsaoRenderer(), "SsaoRenderer must initialize");
            assertNotNull(scene.getActorShadowRenderer(), "RetailActorShadowRenderer must initialize");
            if (Files.isDirectory(FfxiPathConfig.resolveFfxiInstallDirectory())) {
                assertTrue(scene.getActorShadowRenderer().isRetailKageTextureLoaded(),
                        "Retail system kage_tex must load from file ID 0x1b8c");
            }
            assertNotNull(scene.getSkyDome(), "AuthenticSkyDome must initialize");
            assertNotNull(scene.getWeatherParticleSystem(), "WeatherParticleSystem must initialize");
            assertNotNull(scene.getCollisionRenderer(), "CollisionMeshRenderer must initialize");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "Expected zero GL errors after WorldScene.init()");

            BufferedImage img = scene.captureFrame(64, 64);
            assertNotNull(img);
            assertEquals(64, img.getWidth());
            assertEquals(64, img.getHeight());
            assertEquals(0xFF, (img.getRGB(32, 32) >>> 24) & 0xFF, "Captured pixels must be opaque");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "Expected zero GL errors after captureFrame");
        }
    }
}
