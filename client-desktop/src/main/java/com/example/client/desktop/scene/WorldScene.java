package com.example.client.desktop.scene;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.interaction.ZoneInteraction;
import com.example.asset.interaction.ZoneInteractionParser;
import com.example.client.desktop.renderer.CollisionMeshRenderer;
import com.example.common.time.VanadielTime;
import com.ffxi.io.FileTableManager;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.tables.ZoneConfig;
import com.ffxi.tables.ZoneIdToResourceId;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.CharacterAssetCache;
import com.ffxi.viewer.environment.AuthenticSkyDome;
import com.ffxi.viewer.environment.WeatherParticleSystem;
import com.ffxi.viewer.environment.WeatherPreset;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.model.ZoneModelLoader;
import com.ffxi.viewer.renderer.CharacterShader;
import com.ffxi.viewer.renderer.ZoneMeshBuffer;
import com.ffxi.viewer.renderer.ZoneRenderChunk;
import com.ffxi.viewer.renderer.ZoneShader;
import com.ffxi.viewer.renderer.ZoneTextureLoader;
import com.ffxi.viewer.shader.ShadowMapRenderer;
import com.ffxi.viewer.shader.SsaoRenderer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified 3D World Scene Graph and Production Rendering Pipeline.
 * Orchestrates authentic zone geometry, skeletal character actors, sky dome,
 * weather particles, collision mesh, and directed in-game camera controls.
 */
@Slf4j
@Getter
public class WorldScene implements AutoCloseable {

    private final SceneCamera camera = new SceneCamera();

    // Scene Graph Entities
    private SceneActor localPlayerActor;
    private final Map<Long, SceneActor> actors = new ConcurrentHashMap<>();

    // Zone Environment
    private int currentZoneId = -1;
    private LoadedZone loadedZone;
    private ZoneShader zoneShader;
    private ZoneTextureLoader zoneTextureLoader;
    private final List<ZoneRenderChunk> zoneRenderChunks = new ArrayList<>();
    private List<ZoneInteraction> zoneInteractions = List.of();

    // Character Rendering & Shared Asset Cache
    private CharacterShader characterShader;
    private final CharacterAssetCache characterAssetCache = new CharacterAssetCache();

    // Advanced Lighting & Shadow Systems
    private ShadowMapRenderer shadowRenderer;
    private SsaoRenderer ssaoRenderer;
    @Setter private boolean enableShadows = true;
    @Setter private boolean enableSsao = true;

    // Atmospheric & Weather Systems
    private AuthenticSkyDome skyDome;
    private WeatherParticleSystem weatherParticleSystem;
    private WeatherPreset weatherPreset = WeatherPreset.SUNNY;
    private WeatherPreset lastSkyDomePreset;
    @Setter private boolean manualTimeOverride = false;
    @Setter private float manualTimeHour = 12.0f;

    // Collision & Zone Boundary Visuals
    private CollisionMeshRenderer collisionRenderer;

    // File Table Manager
    private FileTableManager fileTableManager;

    // Rendering toggles
    @Setter private boolean showVisualZone = true;
    @Setter private boolean showCharacterModels = true;
    @Setter private boolean showCollisionMesh = false;
    @Setter private boolean showCollisionMarkers = false;
    @Setter private boolean showSkyDome = true;
    @Setter private boolean showWeather = true;

    private boolean initialized = false;

    /**
     * Initializes core GPU shaders, sky dome, particle system, and collision renderer.
     */
    public void init() {
        if (initialized) {
            return;
        }
        try {
            this.zoneShader = new ZoneShader();
            this.characterShader = new CharacterShader();
            this.collisionRenderer = new CollisionMeshRenderer();
            this.collisionRenderer.init();

            try {
                this.shadowRenderer = new ShadowMapRenderer();
                this.ssaoRenderer = new SsaoRenderer(1280, 720);
            } catch (Exception e) {
                log.warn("Shadow/SSAO systems unavailable in current GL context: {}", e.getMessage());
            }

            try {
                this.skyDome = new AuthenticSkyDome();
                if (this.weatherPreset != null) {
                    this.skyDome.updateMesh(this.weatherPreset);
                    this.lastSkyDomePreset = this.weatherPreset;
                }
                this.weatherParticleSystem = new WeatherParticleSystem();
            } catch (Exception e) {
                log.warn("Atmospheric systems unavailable in current GL context: {}", e.getMessage());
            }

            this.initialized = true;
            log.info("Initialized production WorldScene rendering pipeline");
        } catch (Exception e) {
            log.error("Failed to initialize WorldScene rendering pipeline", e);
        }
    }

    public void setWeatherPreset(WeatherPreset preset) {
        if (preset == null) {
            return;
        }
        this.weatherPreset = preset;
        if (this.skyDome != null && preset != this.lastSkyDomePreset) {
            this.skyDome.updateMesh(preset);
            this.lastSkyDomePreset = preset;
        }
    }

    /**
     * Acquires a character instance using the shared {@link CharacterAssetCache}.
     */
    public AuthenticCharacterInstance createCharacterInstance(Path ffxiRoot, RaceGenderConfig race) throws IOException {
        return characterAssetCache.acquireInstance(ffxiRoot, race);
    }

    /**
     * Loads authentic zone geometry, textures, collision system, and zoneline interactions from DAT files.
     */
    public void loadZone(Path ffxiRoot, int zoneId) {
        this.currentZoneId = zoneId;

        for (ZoneRenderChunk chunk : zoneRenderChunks) {
            try { chunk.close(); } catch (Exception ignored) {}
        }
        zoneRenderChunks.clear();
        if (zoneTextureLoader != null) {
            try { zoneTextureLoader.close(); } catch (Exception ignored) {}
            zoneTextureLoader = null;
        }

        if (initialized && ffxiRoot != null && Files.isDirectory(ffxiRoot)) {
            try {
                this.loadedZone = ZoneModelLoader.loadZone(ffxiRoot, zoneId);
                this.zoneTextureLoader = new ZoneTextureLoader(loadedZone.textureSources());
                for (LoadedZone.ZoneChunkInstance inst : loadedZone.chunkInstances()) {
                    zoneRenderChunks.add(new ZoneRenderChunk(
                            new ZoneMeshBuffer(inst.chunk()),
                            new Matrix4f(inst.modelMatrix()),
                            inst.invertCull(),
                            inst.discardThreshold()
                    ));
                }
                if (loadedZone.collisionSystem() != null && collisionRenderer != null) {
                    var collisionTris = loadedZone.collisionSystem().getAllWorldTriangles();
                    collisionRenderer.uploadTransformedTriangles(collisionTris);
                }
                log.info("WorldScene: Loaded zone #{}: {} chunk instances, {} textures",
                        zoneId, zoneRenderChunks.size(), loadedZone.textureSources().size());
            } catch (Exception e) {
                log.error("Failed loading authentic zone #{}", zoneId, e);
            }
        }

        byte[] zoneBytes = loadZoneBytes(zoneId);
        if (zoneBytes != null && zoneBytes.length > 0) {
            try {
                this.zoneInteractions = ZoneInteractionParser.parseInteractions(zoneBytes);
                log.info("WorldScene: Parsed {} zone interactions for zone #{}", zoneInteractions.size(), zoneId);
            } catch (Exception e) {
                log.warn("Failed parsing zone interactions for zone #{}: {}", zoneId, e.getMessage());
            }
        }
    }

    public void setLocalPlayer(SceneActor player) {
        SceneActor prevLocal = this.localPlayerActor;
        this.localPlayerActor = player;
        if (player != null) {
            player.setLocalPlayer(true);
            SceneActor prevAtId = actors.put(player.getId(), player);
            if (prevAtId != null && prevAtId != player) {
                prevAtId.close();
            }
        }
        if (prevLocal != null && prevLocal != player) {
            actors.remove(prevLocal.getId(), prevLocal);
            prevLocal.close();
        }
    }

    public void addActor(SceneActor actor) {
        if (actor == null) {
            return;
        }
        SceneActor prev = actors.put(actor.getId(), actor);
        if (prev != null && prev != actor) {
            prev.close();
        }
    }

    public void removeActor(long actorId) {
        SceneActor removed = actors.remove(actorId);
        if (removed != null) {
            if (removed == localPlayerActor) {
                localPlayerActor = null;
            }
            removed.close();
        }
    }

    public SceneActor getActor(long actorId) {
        return actors.get(actorId);
    }

    public Collection<SceneActor> getActors() {
        return Collections.unmodifiableCollection(actors.values());
    }

    /**
     * Steps animations, weather particles, sky dome mesh, camera tracking, and environmental time.
     */
    public void update(float dt, VanadielTime time) {
        if (localPlayerActor != null && camera.getMode() == CameraMode.THIRD_PERSON) {
            camera.setTarget(localPlayerActor.getX(), localPlayerActor.getY(), localPlayerActor.getZ());
        }

        for (SceneActor actor : actors.values()) {
            actor.update(dt);
        }

        if (skyDome != null && weatherPreset != null && weatherPreset != lastSkyDomePreset) {
            skyDome.updateMesh(weatherPreset);
            lastSkyDomePreset = weatherPreset;
        }

        if (weatherParticleSystem != null && weatherPreset != null) {
            Vector3f cameraPos = new Vector3f(camera.getEyeX(), camera.getEyeY(), camera.getEyeZ());
            weatherParticleSystem.update(dt, cameraPos, weatherPreset);
        }
    }

    /**
     * Executes the unified 3D rendering pipeline into the default framebuffer (0).
     */
    public void render(int viewportWidth, int viewportHeight) {
        renderToFramebuffer(0, viewportWidth, viewportHeight);
    }

    /**
     * Executes the unified 3D rendering pipeline targeting {@code targetFbo} (0 for default window backbuffer,
     * or an offscreen FBO handle for {@link #captureFrame(int, int)}).
     */
    public void renderToFramebuffer(int targetFbo, int viewportWidth, int viewportHeight) {
        int w = Math.max(1, viewportWidth);
        int h = Math.max(1, viewportHeight);

        float aspect = (float) w / (float) h;
        Matrix4f pMat = camera.getProjectionMatrix(aspect);
        Matrix4f vMat = camera.getViewMatrix();

        float[] proj = new float[16];
        float[] view = new float[16];
        pMat.get(proj);
        vMat.get(view);

        VanadielTime vTime = manualTimeOverride
                ? VanadielTime.ofHour(manualTimeHour)
                : VanadielTime.now();
        float hour = vTime.getHourFraction();
        float sunAngle = (float) ((hour / 24.0f) * 2.0 * Math.PI);
        Vector3f sunDir = new Vector3f((float) Math.sin(sunAngle), (float) Math.cos(sunAngle), 0.25f).normalize();
        Vector3f moonDir = new Vector3f(-sunDir.x, -sunDir.y, -0.25f).normalize();
        Vector3f activeLightDir = sunDir.y < 0.0f ? sunDir : moonDir;

        float dayFactor = (hour >= 6.0f && hour <= 18.0f) ? 1.0f
                : ((hour > 18.0f && hour < 20.0f) ? 1.0f - 0.65f * ((hour - 18.0f) / 2.0f)
                : ((hour >= 20.0f || hour <= 4.0f) ? 0.35f
                : 0.35f + 0.65f * ((hour - 4.0f) / 2.0f)));

        Vector3f shadowTarget = (localPlayerActor != null)
                ? new Vector3f(localPlayerActor.getX(), localPlayerActor.getY(), localPlayerActor.getZ())
                : new Vector3f(camera.getEyeX(), camera.getEyeY(), camera.getEyeZ());

        // 1. Directional Shadow Pass (binds chunk diffuse texture on unit 0 so alpha-cutout shadows work)
        boolean shadowsActive = enableShadows && shadowRenderer != null && !zoneRenderChunks.isEmpty();
        if (shadowsActive) {
            shadowRenderer.computeLightSpaceMatrix(activeLightDir, shadowTarget, 60.0f, 10.0f, 150.0f);
            shadowRenderer.beginShadowPass();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            for (ZoneRenderChunk chunk : zoneRenderChunks) {
                if (zoneTextureLoader != null) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, zoneTextureLoader.textureId(chunk.buffer().textureName()));
                }
                shadowRenderer.setModelMatrix(chunk.modelMatrix(), chunk.discardThreshold());
                chunk.buffer().draw();
            }
            shadowRenderer.endShadowPass(w, h);
        }

        // 2. SSAO Geometry & Blur Passes (uses SsaoRenderer's internal G-buffer shader and chunk matrices)
        boolean ssaoActive = enableSsao && ssaoRenderer != null && !zoneRenderChunks.isEmpty();
        if (ssaoActive) {
            ssaoRenderer.resize(w, h);
            ssaoRenderer.beginGeometryPass(pMat, vMat);
            for (ZoneRenderChunk chunk : zoneRenderChunks) {
                if (chunk.buffer().renderBlend()) {
                    continue;
                }
                ssaoRenderer.setGeometryModelMatrix(chunk.modelMatrix());
                chunk.buffer().draw();
            }
            ssaoRenderer.computeSsao(pMat, 3.5f, 0.025f, 1.6f, true);
        }

        // 3. Clear Target Framebuffer & Main Viewport Setup
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
        GL11.glViewport(0, 0, w, h);
        GL11.glClearColor(0.08f, 0.10f, 0.14f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        // 4. Atmospheric Sky Dome Pass
        if (showSkyDome && skyDome != null) {
            if (weatherPreset != null && weatherPreset != lastSkyDomePreset) {
                skyDome.updateMesh(weatherPreset);
                lastSkyDomePreset = weatherPreset;
            }
            float brightness = dayFactor * weatherPreset.diffuseMultiplier();
            skyDome.render(pMat, vMat, new Vector3f(camera.getEyeX(), camera.getEyeY(), camera.getEyeZ()), brightness);
        }

        // 5. Zone Geometry Multi-Pass (Opaque & Alpha-Test Pass 0, Blended Pass 1)
        if (showVisualZone && zoneShader != null && zoneTextureLoader != null && !zoneRenderChunks.isEmpty()) {
            zoneShader.bind();

            float lightningBoost = (weatherParticleSystem != null && weatherParticleSystem.isLightningActive())
                    ? weatherParticleSystem.getLightningIntensity()
                    : 0.0f;
            float diffMult = weatherPreset.diffuseMultiplier() * dayFactor + lightningBoost;
            Vector3f sunColor = new Vector3f(
                    weatherPreset.sunColor().x * diffMult,
                    weatherPreset.sunColor().y * diffMult,
                    weatherPreset.sunColor().z * diffMult
            );
            float ambMult = Math.max(0.35f, dayFactor);
            Vector3f ambientColor = new Vector3f(
                    Math.max(0.45f, weatherPreset.ambientColor().x * ambMult * 2.0f),
                    Math.max(0.45f, weatherPreset.ambientColor().y * ambMult * 2.0f),
                    Math.max(0.48f, weatherPreset.ambientColor().z * ambMult * 2.0f)
            );
            Matrix4f lightSpaceMat = shadowsActive ? shadowRenderer.getLightSpaceMatrix() : null;

            zoneShader.setEnvironment(
                    activeLightDir,
                    sunColor,
                    ambientColor,
                    lightSpaceMat,
                    shadowsActive,
                    ssaoActive,
                    w,
                    h
            );
            zoneShader.setFog(true, weatherPreset.fogNear(), weatherPreset.fogFar(), weatherPreset.fogColor());

            if (shadowsActive) {
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowRenderer.getDepthTextureId());
            }
            if (ssaoActive) {
                GL13.glActiveTexture(GL13.GL_TEXTURE3);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, ssaoRenderer.getFinalSsaoTextureId(true));
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);

            for (int pass = 0; pass < 2; pass++) {
                boolean blendedPass = (pass == 1);
                GL11.glDepthMask(!blendedPass);
                if (blendedPass) {
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                } else {
                    GL11.glDisable(GL11.GL_BLEND);
                }
                for (ZoneRenderChunk chunk : zoneRenderChunks) {
                    if (chunk.buffer().renderBlend() != blendedPass) {
                        continue;
                    }
                    zoneShader.setDiscardThreshold(chunk.discardThreshold());
                    zoneShader.setMatrices(pMat, vMat, chunk.modelMatrix());

                    if (chunk.invertCull()) {
                        GL11.glDisable(GL11.GL_CULL_FACE);
                    } else {
                        GL11.glEnable(GL11.GL_CULL_FACE);
                        boolean inverted = chunk.modelMatrix().determinant() < 0.0f;
                        GL11.glFrontFace(inverted ? GL11.GL_CW : GL11.GL_CCW);
                    }

                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, zoneTextureLoader.textureId(chunk.buffer().textureName()));
                    chunk.buffer().draw();
                }
            }
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glFrontFace(GL11.GL_CCW);
        }

        // 6. Animated Skinned Character Actors Pass
        if (showCharacterModels && characterShader != null) {
            for (SceneActor actor : actors.values()) {
                actor.render(characterShader, pMat, vMat);
            }
        }

        // 7. Collision Wireframe / Cylinder Markers Pass
        if (collisionRenderer != null) {
            if (showCollisionMesh) {
                collisionRenderer.render(proj, view, true);
            }
            if (showCollisionMarkers) {
                for (SceneActor actor : actors.values()) {
                    boolean isNpc = actor.getType() == SceneActor.ActorType.NPC;
                    float r = isNpc ? 0.95f : 0.20f;
                    float g = isNpc ? 0.78f : 0.70f;
                    float b = isNpc ? 0.22f : 1.00f;
                    collisionRenderer.renderRemoteMarker(actor.getX(), actor.getY(), actor.getZ(), actor.getYaw(), r, g, b, proj, view);
                }
            }
        }

        // 8. Weather Particles Pass
        if (showWeather && weatherParticleSystem != null) {
            weatherParticleSystem.render(pMat, vMat, weatherPreset);
        }
    }

    /**
     * Renders offscreen to a high-resolution frame buffer and returns a BufferedImage.
     * Used by tests, automated snapshot suites, and tools to exercise production rendering logic.
     */
    public BufferedImage captureFrame(int width, int height) {
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        int fbo = GL30.glGenFramebuffers();
        int colorTex = GL11.glGenTextures();
        int depthRb = GL30.glGenRenderbuffers();
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);

        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);

            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRb);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depthRb);

            renderToFramebuffer(fbo, width, height);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            if (depthRb != 0) GL30.glDeleteRenderbuffers(depthRb);
            if (colorTex != 0) GL11.glDeleteTextures(colorTex);
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int srcIdx = ((height - 1 - y) * width + x) * 4;
                int r = buffer.get(srcIdx) & 0xFF;
                int g = buffer.get(srcIdx + 1) & 0xFF;
                int b = buffer.get(srcIdx + 2) & 0xFF;
                int a = buffer.get(srcIdx + 3) & 0xFF;
                pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    public byte[] loadZoneBytes(int zoneId) {
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        if (Files.isDirectory(ffxiRoot)) {
            try {
                if (fileTableManager == null) {
                    fileTableManager = new FileTableManager();
                    fileTableManager.loadFromDirectory(ffxiRoot.toFile());
                }
                String zonePath = ZoneIdToResourceId.getMainAreaResourcePath(new ZoneConfig(zoneId), fileTableManager);
                if (zonePath != null && !zonePath.isBlank()) {
                    Path p = Path.of(zonePath);
                    if (Files.isRegularFile(p)) {
                        return Files.readAllBytes(p);
                    }
                }
            } catch (Exception ignored) {}
        }

        String relRom = (zoneId == 230) ? "ROM/1/31.DAT" : (zoneId == 100 ? "ROM/1/30.DAT" : null);
        if (relRom != null) {
            Optional<Path> installedPath = FfxiPathConfig.resolveRomFile(relRom);
            if (installedPath.isPresent() && Files.isRegularFile(installedPath.get())) {
                try {
                    return Files.readAllBytes(installedPath.get());
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    @Override
    public void close() {
        for (SceneActor actor : actors.values()) {
            try { actor.close(); } catch (Exception ignored) {}
        }
        actors.clear();
        if (localPlayerActor != null) {
            try { localPlayerActor.close(); } catch (Exception ignored) {}
            localPlayerActor = null;
        }
        characterAssetCache.close();

        for (ZoneRenderChunk chunk : zoneRenderChunks) {
            try { chunk.close(); } catch (Exception ignored) {}
        }
        zoneRenderChunks.clear();

        if (zoneTextureLoader != null) {
            try { zoneTextureLoader.close(); } catch (Exception ignored) {}
            zoneTextureLoader = null;
        }
        if (zoneShader != null) {
            try { zoneShader.close(); } catch (Exception ignored) {}
            zoneShader = null;
        }
        if (characterShader != null) {
            try { characterShader.close(); } catch (Exception ignored) {}
            characterShader = null;
        }
        if (collisionRenderer != null) {
            try { collisionRenderer.close(); } catch (Exception ignored) {}
            collisionRenderer = null;
        }
        if (shadowRenderer != null) {
            try { shadowRenderer.close(); } catch (Exception ignored) {}
            shadowRenderer = null;
        }
        if (ssaoRenderer != null) {
            try { ssaoRenderer.close(); } catch (Exception ignored) {}
            ssaoRenderer = null;
        }
        if (skyDome != null) {
            try { skyDome.close(); } catch (Exception ignored) {}
            skyDome = null;
        }
        if (weatherParticleSystem != null) {
            try { weatherParticleSystem.close(); } catch (Exception ignored) {}
            weatherParticleSystem = null;
        }
        this.lastSkyDomePreset = null;
        this.initialized = false;
        log.info("Disposed WorldScene GPU resources");
    }
}
