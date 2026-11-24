package com.example.client.desktop.scene;

import com.example.asset.collision.CollisionTransform;
import com.example.asset.config.FfxiPathConfig;
import com.example.asset.interaction.ZoneInteraction;
import com.example.asset.interaction.ZoneInteractionParser;
import com.example.client.desktop.renderer.CollisionMeshRenderer;
import com.example.common.time.VanadielTime;
import com.example.zone.dto.ActorAppearance;
import com.ffxi.io.FileTableManager;
import com.ffxi.model.ZoneEffectGeneratorResource;
import com.ffxi.model.ZoneSpacePartitionNode;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.tables.ZoneConfig;
import com.ffxi.tables.ZoneIdToResourceId;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.CharacterAssetCache;
import com.ffxi.viewer.environment.AuthenticSkyDome;
import com.ffxi.viewer.environment.WeatherParticleSystem;
import com.ffxi.viewer.environment.WeatherPreset;
import com.ffxi.viewer.environment.ZoneEnvironmentTable;
import com.ffxi.viewer.environment.ZonePointLight;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.model.ZoneModelLoader;
import com.ffxi.viewer.renderer.CharacterShader;
import com.ffxi.viewer.renderer.FontMojiGlRenderer;
import com.ffxi.viewer.renderer.FontMojiRenderer;
import com.ffxi.viewer.renderer.RetailActorShadowRenderer;
import com.ffxi.viewer.renderer.ZoneEffectParams;
import com.ffxi.viewer.renderer.ZoneFrustumCuller;
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
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    private final SceneCamera camera;

    // Scene Graph Entities
    private SceneActor localPlayerActor;
    private final Map<Long, SceneActor> actors = new ConcurrentHashMap<>();

    // Zone Environment
    private int currentZoneId = -1;
    private LoadedZone loadedZone;
    private ZoneShader zoneShader;
    private ZoneTextureLoader zoneTextureLoader;
    private final List<ZoneRenderChunk> zoneRenderChunks = new ArrayList<>();
    private final Map<String, List<ZoneRenderChunk>> weatherSkyRenderChunks = new HashMap<>();
    private List<ZoneInteraction> zoneInteractions = List.of();
    private ZoneSpacePartitionNode zoneSpaceTreeRoot;
    private BitSet indexedZoneObjectIds;
    private int lastDrawnZoneChunkCount = 0;
    private int lastCulledZoneChunkCount = 0;
    private float elapsedSeconds = 0.0f;
    private final float[] chunkPlPosRange = new float[16];
    private final float[] chunkPlColorIntensity = new float[16];
    private final Vector4f liveEffectRgba = new Vector4f();

    // Character Rendering & Shared Asset Cache
    private CharacterShader characterShader;
    private final CharacterAssetCache characterAssetCache = new CharacterAssetCache();

    // Retail Actor Ground Shadows & Optional Enhanced Passes
    private RetailActorShadowRenderer actorShadowRenderer;
    @Setter private boolean enableActorShadows = true;
    @Setter private RetailActorShadowRenderer.ShadowMode actorShadowMode = RetailActorShadowRenderer.ShadowMode.HIGH;
    private ShadowMapRenderer shadowRenderer;
    private SsaoRenderer ssaoRenderer;
    @Setter private boolean enableShadows = false;
    @Setter private boolean enableSsao = false;

    // Atmospheric & Weather Systems
    private AuthenticSkyDome skyDome;
    private WeatherParticleSystem weatherParticleSystem;
    private WeatherPreset weatherPreset = WeatherPreset.SUNNY;
    private WeatherPreset lastSkyDomePreset;
    private float lastSkyUpdateMinute = Float.NaN;
    private String lastSkyWeatherTag = "";
    private int lastSkyZoneId = -1;
    @Setter private boolean manualTimeOverride = false;
    @Setter private float manualTimeHour = 12.0f;

    // Collision & Zone Boundary Visuals
    private CollisionMeshRenderer collisionRenderer;

    // In-Scene Font Moji Renderer
    private FontMojiGlRenderer fontMojiGlRenderer;

    // File Table Manager
    private FileTableManager fileTableManager;

    // Rendering toggles
    @Setter private boolean showVisualZone = true;
    @Setter private boolean showCharacterModels = true;
    @Setter private boolean showCollisionMesh = false;
    @Setter private boolean showCollisionMarkers = false;
    @Setter private boolean showSkyDome = true;
    @Setter private boolean showWeather = true;
    @Setter private boolean enableFog = true;
    @Setter private boolean enableFrustumCulling = true;

    private float clearR = 0.08f;
    private float clearG = 0.10f;
    private float clearB = 0.14f;

    private boolean initialized = false;

    public WorldScene() {
        this(new SceneCamera());
    }

    public WorldScene(SceneCamera camera) {
        this.camera = camera != null ? camera : new SceneCamera();
    }

    public void setClearColor(float r, float g, float b) {
        this.clearR = r;
        this.clearG = g;
        this.clearB = b;
    }

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
                this.actorShadowRenderer = new RetailActorShadowRenderer(
                        FfxiPathConfig.resolveFfxiInstallDirectory(),
                        this.fileTableManager
                );
            } catch (Exception e) {
                log.warn("RetailActorShadowRenderer unavailable in current GL context: {}", e.getMessage());
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

            try {
                FontMojiRenderer mojiFont = FontMojiRenderer.load(FfxiPathConfig.resolveFfxiInstallDirectory());
                this.fontMojiGlRenderer = new FontMojiGlRenderer(mojiFont);
                log.info("Initialized FontMojiGlRenderer from retail ROM/0/1.DAT ({}x{})",
                        mojiFont.getTexWidth(), mojiFont.getTexHeight());
            } catch (Exception e) {
                log.warn("FontMojiGlRenderer unavailable (falling back to ImGui labels): {}", e.getMessage());
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
        this.lastSkyWeatherTag = "";
        this.lastSkyUpdateMinute = Float.NaN;
        if (this.skyDome != null && this.loadedZone == null && preset != this.lastSkyDomePreset) {
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
     * Acquires an actor visual instance (equipped PC/NPC, chocobo, or standard NPC model)
     * using the shared {@link CharacterAssetCache}.
     */
    public AuthenticCharacterInstance createCharacterInstance(Path ffxiRoot, ActorAppearance appearance) throws IOException {
        return characterAssetCache.acquireInstance(ffxiRoot, appearance);
    }

    /**
     * Disposes and removes all non-local actors from the scene graph.
     */
    public void clearRemoteActors() {
        List<Long> remoteIds = new ArrayList<>();
        for (SceneActor actor : actors.values()) {
            if (!actor.isLocalPlayer() && actor != localPlayerActor) {
                remoteIds.add(actor.getId());
            }
        }
        for (Long id : remoteIds) {
            removeActor(id);
        }
    }

    /**
     * Loads authentic zone geometry, textures, collision system, and zoneline interactions from DAT files.
     */
    public void loadZone(Path ffxiRoot, int zoneId) {
        boolean zoneChanged = this.currentZoneId != -1 && this.currentZoneId != zoneId;
        if (zoneChanged) {
            clearRemoteActors();
            int evicted = characterAssetCache.evictUnusedAssets();
            if (evicted > 0) {
                log.info("WorldScene: Evicted {} unused cached GPU character bundles after zone transition to #{}",
                        evicted, zoneId);
            }
        }

        this.currentZoneId = zoneId;
        this.zoneSpaceTreeRoot = null;
        this.indexedZoneObjectIds = null;
        this.lastDrawnZoneChunkCount = 0;
        this.lastCulledZoneChunkCount = 0;
        this.lastSkyZoneId = -1;
        this.lastSkyWeatherTag = "";
        this.lastSkyUpdateMinute = Float.NaN;

        disposeZoneRenderChunks();

        if (initialized && ffxiRoot != null && Files.isDirectory(ffxiRoot)) {
            try {
                this.loadedZone = ZoneModelLoader.loadZone(ffxiRoot, zoneId);
                this.zoneSpaceTreeRoot = loadedZone.spacePartitioningTreeRoot();
                this.indexedZoneObjectIds = ZoneFrustumCuller.collectIndexedObjectIds(zoneSpaceTreeRoot);
                this.zoneTextureLoader = new ZoneTextureLoader(loadedZone.textureSources());
                for (LoadedZone.ZoneChunkInstance inst : loadedZone.chunkInstances()) {
                    zoneRenderChunks.add(new ZoneRenderChunk(
                            new ZoneMeshBuffer(inst.chunk()),
                            new Matrix4f(inst.modelMatrix()),
                            inst.invertCull(),
                            inst.discardThreshold(),
                            inst.zoneObjectIndex(),
                            inst.effectParams(),
                            inst.pointLightIndices(),
                            inst.environmentLink()
                    ));
                }
                for (var entry : loadedZone.weatherSkyEffectsByTag().entrySet()) {
                    List<ZoneRenderChunk> skyChunks = new ArrayList<>(entry.getValue().size());
                    for (LoadedZone.ZoneChunkInstance inst : entry.getValue()) {
                        skyChunks.add(new ZoneRenderChunk(
                                new ZoneMeshBuffer(inst.chunk()),
                                new Matrix4f(inst.modelMatrix()),
                                inst.invertCull(),
                                inst.discardThreshold(),
                                inst.zoneObjectIndex(),
                                inst.effectParams(),
                                inst.pointLightIndices(),
                                inst.environmentLink()
                        ));
                    }
                    weatherSkyRenderChunks.put(entry.getKey(), skyChunks);
                }
                if (loadedZone.collisionSystem() != null && collisionRenderer != null) {
                    var collisionTris = loadedZone.collisionSystem().getAllWorldTriangles();
                    collisionRenderer.upload(collisionTris);
                }
                log.info("WorldScene: Loaded zone #{}: {} chunk instances, {} textures, {} point lights, {} weather sky tags",
                        zoneId, zoneRenderChunks.size(), loadedZone.textureSources().size(),
                        loadedZone.pointLights().size(), weatherSkyRenderChunks.size());
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
        this.elapsedSeconds += Math.max(0.0f, dt);
        if (camera.getMode() == CameraMode.THIRD_PERSON) {
            if (localPlayerActor != null) {
                camera.setTarget(localPlayerActor.getX(), localPlayerActor.getY(), localPlayerActor.getZ());
            }
            if (loadedZone != null && loadedZone.collisionSystem() != null) {
                camera.applyCollisionClamp(loadedZone.collisionSystem());
            }
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

        BitSet visibleZoneObjectIds = (enableFrustumCulling && zoneSpaceTreeRoot != null)
                ? ZoneFrustumCuller.collectVisibleObjectIds(zoneSpaceTreeRoot, new Matrix4f(pMat).mul(vMat))
                : null;

        float[] proj = new float[16];
        float[] view = new float[16];
        pMat.get(proj);
        vMat.get(view);

        VanadielTime vTime = manualTimeOverride
                ? VanadielTime.ofHour(manualTimeHour)
                : VanadielTime.now();
        float minuteOfDay = vTime.getHourFraction() * 60.0f;
        float wrappedMinute = ((minuteOfDay % 1440.0f) + 1440.0f) % 1440.0f;
        float normalizedClock = wrappedMinute / 1440.0f;
        String weatherTag = (weatherPreset != null && weatherPreset.id() != null)
                ? weatherPreset.id().toLowerCase(Locale.ROOT)
                : "fine";

        ZoneEnvironmentTable envTable = (loadedZone != null) ? loadedZone.environmentTable() : null;
        ZoneEnvironmentTable.ResolvedEnvironment zoneEnv = (envTable != null && !envTable.isEmpty())
                ? envTable.resolve(weatherTag, wrappedMinute, null)
                        .orElseGet(() -> ZoneEnvironmentTable.fromFallbackPreset(weatherPreset, wrappedMinute))
                : ZoneEnvironmentTable.fromFallbackPreset(weatherPreset, wrappedMinute);

        Vector3f cameraWorldPos = new Vector3f(camera.getEyeX(), camera.getEyeY(), camera.getEyeZ());
        Vector3f shadowTarget = (localPlayerActor != null)
                ? new Vector3f(localPlayerActor.getX(), localPlayerActor.getY(), localPlayerActor.getZ())
                : new Vector3f(cameraWorldPos);

        // 1. Optional Directional Shadow Pass (non-retail override; disabled by default)
        boolean shadowsActive = enableShadows && shadowRenderer != null && !zoneRenderChunks.isEmpty();
        if (shadowsActive) {
            Vector3f activeDir = (zoneEnv.bgLight0Color().lengthSquared() >= zoneEnv.bgLight1Color().lengthSquared())
                    ? zoneEnv.bgLight0Dir()
                    : zoneEnv.bgLight1Dir();
            shadowRenderer.computeLightSpaceMatrix(activeDir, shadowTarget, 60.0f, 10.0f, 150.0f);
            shadowRenderer.beginShadowPass();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            for (ZoneRenderChunk chunk : zoneRenderChunks) {
                if (chunk.isEffect() || chunk.buffer().renderBlend()) {
                    continue;
                }
                if (zoneTextureLoader != null) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, zoneTextureLoader.textureId(chunk.buffer().textureName()));
                }
                shadowRenderer.setModelMatrix(chunk.modelMatrix(), chunk.discardThreshold());
                chunk.buffer().draw();
            }
            shadowRenderer.endShadowPass(w, h);
        }

        // 2. Optional SSAO Geometry & Blur Passes (disabled by default)
        boolean ssaoActive = enableSsao && ssaoRenderer != null && !zoneRenderChunks.isEmpty();
        if (ssaoActive) {
            ssaoRenderer.resize(w, h);
            ssaoRenderer.beginGeometryPass(pMat, vMat);
            for (ZoneRenderChunk chunk : zoneRenderChunks) {
                if (chunk.isEffect() || chunk.buffer().renderBlend()) {
                    continue;
                }
                if (!ZoneFrustumCuller.isChunkVisible(chunk.zoneObjectIndex(), indexedZoneObjectIds, visibleZoneObjectIds)) {
                    continue;
                }
                if (chunk.invertCull()) {
                    GL11.glDisable(GL11.GL_CULL_FACE);
                } else {
                    GL11.glEnable(GL11.GL_CULL_FACE);
                    GL11.glFrontFace(chunk.modelMatrix().determinant() < 0.0f ? GL11.GL_CW : GL11.GL_CCW);
                }
                ssaoRenderer.setGeometryModelMatrix(chunk.modelMatrix());
                chunk.buffer().draw();
            }
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glFrontFace(GL11.GL_CCW);
            ssaoRenderer.computeSsao(pMat, 3.5f, 0.025f, 1.6f, true);
        }

        // 3. Clear Target Framebuffer & Main Viewport Setup
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo);
        GL11.glViewport(0, 0, w, h);
        float bgR = clearR;
        float bgG = clearG;
        float bgB = clearB;
        if (showSkyDome) {
            bgR = zoneEnv.clearColor().x;
            bgG = zoneEnv.clearColor().y;
            bgB = zoneEnv.clearColor().z;
        }
        GL11.glClearColor(bgR, bgG, bgB, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);

        // 4. Retail Atmospheric Sky Dome & Celestial S05 Sky Layers
        if (showSkyDome) {
            if (skyDome != null && zoneEnv.hasDome()) {
                boolean skyNeedsUpdate = (currentZoneId != lastSkyZoneId)
                        || !weatherTag.equals(lastSkyWeatherTag)
                        || (weatherPreset != lastSkyDomePreset)
                        || Float.isNaN(lastSkyUpdateMinute)
                        || Math.abs(wrappedMinute - lastSkyUpdateMinute) > 0.25f;
                if (skyNeedsUpdate) {
                    skyDome.updateFromEnvironment(zoneEnv);
                    lastSkyZoneId = currentZoneId;
                    lastSkyWeatherTag = weatherTag;
                    lastSkyDomePreset = weatherPreset;
                    lastSkyUpdateMinute = wrappedMinute;
                }
                skyDome.render(pMat, vMat, cameraWorldPos, 1.0f);
            }
            renderWeatherSkyLayers(pMat, vMat, cameraWorldPos, zoneEnv, normalizedClock, weatherTag, w, h);
        }

        List<ZonePointLight> pointLights = (loadedZone != null) ? loadedZone.pointLights() : List.of();
        float[] pointLightIntensities = new float[pointLights.size()];
        for (int i = 0; i < pointLights.size(); i++) {
            pointLightIntensities[i] = pointLights.get(i).evaluateIntensity(normalizedClock);
        }

        float lightningBoost = (weatherParticleSystem != null && weatherParticleSystem.isLightningActive())
                ? weatherParticleSystem.getLightningIntensity()
                : 0.0f;

        // 5. Zone Geometry Multi-Pass (Opaque & Alpha-Test Pass 0, Blended Pass 1, S05 Effects & Lamps)
        if (showVisualZone && zoneShader != null && zoneTextureLoader != null && !zoneRenderChunks.isEmpty()) {
            zoneShader.bind();
            Matrix4f lightSpaceMat = shadowsActive ? shadowRenderer.getLightSpaceMatrix() : null;
            zoneShader.setCameraWorldPos(cameraWorldPos);
            bindZoneShaderEnvironment(zoneEnv, lightSpaceMat, shadowsActive, ssaoActive, lightningBoost, w, h);
            zoneShader.resetSceneryState();

            if (shadowsActive) {
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowRenderer.getDepthTextureId());
            }
            if (ssaoActive) {
                GL13.glActiveTexture(GL13.GL_TEXTURE3);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, ssaoRenderer.getFinalSsaoTextureId(true));
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);

            String boundEnvLink = "";
            int drawnChunks = 0;
            int culledChunks = 0;
            for (int pass = 0; pass < 2; pass++) {
                boolean blendedPass = (pass == 1);
                zoneShader.setOpaquePass(!blendedPass);
                GL11.glDepthMask(!blendedPass);
                if (blendedPass) {
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                    GL11.glPolygonOffset(-1.0f, -1.0f);
                } else {
                    GL11.glDisable(GL11.GL_BLEND);
                    GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                }
                for (ZoneRenderChunk chunk : zoneRenderChunks) {
                    if (chunk.isEffect() || chunk.buffer().renderBlend() != blendedPass) {
                        continue;
                    }
                    if (!ZoneFrustumCuller.isChunkVisible(chunk.zoneObjectIndex(), indexedZoneObjectIds, visibleZoneObjectIds)) {
                        culledChunks++;
                        continue;
                    }
                    drawnChunks++;

                    String envLink = chunk.environmentLink();
                    if (!envLink.equals(boundEnvLink)) {
                        boundEnvLink = envLink;
                        ZoneEnvironmentTable.ResolvedEnvironment chunkEnv = (!envLink.isEmpty() && envTable != null && !envTable.isEmpty())
                                ? envTable.resolve(weatherTag, wrappedMinute, envLink).orElse(zoneEnv)
                                : zoneEnv;
                        bindZoneShaderEnvironment(chunkEnv, lightSpaceMat, shadowsActive, ssaoActive, lightningBoost, w, h);
                    }

                    uploadChunkPointLights(chunk, pointLights, pointLightIntensities);
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
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            zoneShader.clearPointLights();
            if (!boundEnvLink.isEmpty()) {
                bindZoneShaderEnvironment(zoneEnv, lightSpaceMat, shadowsActive, ssaoActive, lightningBoost, w, h);
            }

            drawnChunks += renderAmbientZoneEffects(pMat, vMat, cameraWorldPos, zoneEnv, normalizedClock);

            this.lastDrawnZoneChunkCount = drawnChunks;
            this.lastCulledZoneChunkCount = culledChunks;
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glFrontFace(GL11.GL_CCW);
            zoneShader.setOpaquePass(false);
            zoneShader.resetSceneryState();
            zoneShader.setFog(enableFog, zoneEnv.fogStart(), zoneEnv.fogEnd(), zoneEnv.fogColor());
        } else {
            this.lastDrawnZoneChunkCount = 0;
            this.lastCulledZoneChunkCount = 0;
        }

        // 6. Animated Skinned Character Actors Pass (Retail S2F actor lighting, floor lamp & fog)
        if (showCharacterModels && characterShader != null) {
            for (SceneActor actor : actors.values()) {
                if (!actor.isVisible()) {
                    continue;
                }
                configureActorLighting(actor, envTable, zoneEnv, weatherTag, wrappedMinute, pointLights, pointLightIntensities);
                actor.render(characterShader, pMat, vMat);
            }
        }

        // 7. Retail Actor Ground Shadows Pass (Skinned Silhouettes / kage_tex on 3x3 collision cells)
        if (enableActorShadows
                && actorShadowMode != RetailActorShadowRenderer.ShadowMode.OFF
                && showCharacterModels
                && actorShadowRenderer != null
                && loadedZone != null
                && loadedZone.collisionSystem() != null
                && !actors.isEmpty()) {
            actorShadowRenderer.renderShadows(
                    targetFbo,
                    w,
                    h,
                    pMat,
                    vMat,
                    cameraWorldPos,
                    actors.values(),
                    characterShader,
                    loadedZone.collisionSystem().collisionMap(),
                    pointLights,
                    pointLightIntensities,
                    zoneEnv,
                    actorShadowMode
            );
        }

        // 8. Collision Wireframe / Cylinder Markers Pass
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

        // 9. Weather Particles Pass
        if (showWeather && weatherParticleSystem != null) {
            weatherParticleSystem.render(pMat, vMat, weatherPreset);
        }
    }

    /**
     * Renders offscreen to a high-resolution frame buffer and returns a BufferedImage.
     * Used by tests, automated snapshot suites, and tools to exercise production rendering logic.
     */
    public BufferedImage captureFrame(int width, int height) {
        return captureFrame(width, height, null);
    }

    /**
     * Renders offscreen to a high-resolution frame buffer, runs an optional post-render overlay pass
     * targeting the same FBO (for example in-scene HUD/nameplates), and returns an opaque BufferedImage.
     */
    public BufferedImage captureFrame(int width, int height, Runnable postRenderOverlay) {
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);

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

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Offscreen capture FBO incomplete, status: " + status);
            }

            renderToFramebuffer(fbo, width, height);

            if (postRenderOverlay != null) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                GL11.glViewport(0, 0, width, height);
                postRenderOverlay.run();
            }

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
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
                pixels[y * width + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
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

        String relRom = (zoneId == 230) ? "ROM/1/31.DAT" : (zoneId == 100 ? "ROM/0/120.DAT" : null);
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

        disposeZoneRenderChunks();
        if (zoneShader != null) {
            try { zoneShader.close(); } catch (Exception ignored) {}
            zoneShader = null;
        }
        if (characterShader != null) {
            try { characterShader.close(); } catch (Exception ignored) {}
            characterShader = null;
        }
        if (actorShadowRenderer != null) {
            try { actorShadowRenderer.close(); } catch (Exception ignored) {}
            actorShadowRenderer = null;
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
        if (fontMojiGlRenderer != null) {
            try { fontMojiGlRenderer.close(); } catch (Exception ignored) {}
            fontMojiGlRenderer = null;
        }
        this.lastSkyDomePreset = null;
        this.lastSkyUpdateMinute = Float.NaN;
        this.lastSkyWeatherTag = "";
        this.lastSkyZoneId = -1;
        this.zoneSpaceTreeRoot = null;
        this.indexedZoneObjectIds = null;
        this.lastDrawnZoneChunkCount = 0;
        this.lastCulledZoneChunkCount = 0;
        this.initialized = false;
        log.info("Disposed WorldScene GPU resources");
    }

    private void disposeZoneRenderChunks() {
        for (ZoneRenderChunk chunk : zoneRenderChunks) {
            try { chunk.close(); } catch (Exception ignored) {}
        }
        zoneRenderChunks.clear();
        for (List<ZoneRenderChunk> skyChunks : weatherSkyRenderChunks.values()) {
            for (ZoneRenderChunk chunk : skyChunks) {
                try { chunk.close(); } catch (Exception ignored) {}
            }
        }
        weatherSkyRenderChunks.clear();
        if (zoneTextureLoader != null) {
            try { zoneTextureLoader.close(); } catch (Exception ignored) {}
            zoneTextureLoader = null;
        }
    }

    private void bindZoneShaderEnvironment(
            ZoneEnvironmentTable.ResolvedEnvironment env,
            Matrix4f lightSpaceMat,
            boolean shadowsActive,
            boolean ssaoActive,
            float lightningBoost,
            int viewportWidth,
            int viewportHeight
    ) {
        Vector3f l0Color = (lightningBoost > 0.0f)
                ? new Vector3f(env.bgLight0Color()).add(lightningBoost, lightningBoost, lightningBoost)
                : env.bgLight0Color();
        zoneShader.setRetailEnvironment(
                env.bgLight0Dir(),
                l0Color,
                env.bgLight1Dir(),
                env.bgLight1Color(),
                env.bgAmbientColor(),
                lightSpaceMat,
                shadowsActive,
                ssaoActive,
                viewportWidth,
                viewportHeight
        );
        zoneShader.setFog(enableFog, env.fogStart(), env.fogEnd(), env.fogColor());
    }

    private void uploadChunkPointLights(
            ZoneRenderChunk chunk,
            List<ZonePointLight> pointLights,
            float[] pointLightIntensities
    ) {
        if (pointLights.isEmpty() || !chunk.hasPointLights()) {
            zoneShader.clearPointLights();
            return;
        }
        int plCount = 0;
        for (int slot = 0; slot < 4; slot++) {
            int plIdx = chunk.pointLightIndex(slot);
            if (plIdx < 0 || plIdx >= pointLights.size()) {
                continue;
            }
            float intensity = pointLightIntensities[plIdx];
            ZonePointLight pl = pointLights.get(plIdx);
            if (intensity <= 0.0001f || pl.range() <= 0.0f) {
                continue;
            }
            int base = plCount * 4;
            chunkPlPosRange[base] = pl.position().x;
            chunkPlPosRange[base + 1] = pl.position().y;
            chunkPlPosRange[base + 2] = pl.position().z;
            chunkPlPosRange[base + 3] = pl.range();
            chunkPlColorIntensity[base] = pl.color().x;
            chunkPlColorIntensity[base + 1] = pl.color().y;
            chunkPlColorIntensity[base + 2] = pl.color().z;
            chunkPlColorIntensity[base + 3] = intensity;
            plCount++;
        }
        zoneShader.setPointLights(plCount, chunkPlPosRange, chunkPlColorIntensity);
    }

    private void configureActorLighting(
            SceneActor actor,
            ZoneEnvironmentTable envTable,
            ZoneEnvironmentTable.ResolvedEnvironment zoneEnv,
            String weatherTag,
            float wrappedMinute,
            List<ZonePointLight> pointLights,
            float[] pointLightIntensities
    ) {
        if (loadedZone == null) {
            characterShader.bind();
            characterShader.setActorLighting(false, null, null, null, null, 0.0f, null, 0.0f);
            characterShader.setFog(false, 0.0f, 1.0f, null);
            return;
        }

        CollisionTransform floorTx = (loadedZone.collisionSystem() != null)
                ? loadedZone.collisionSystem().findFloorTransform(actor.getX(), actor.getY(), actor.getZ())
                : null;
        ZoneEnvironmentTable.ResolvedEnvironment actorEnv = (floorTx != null
                && !floorTx.environmentId().isEmpty()
                && envTable != null && !envTable.isEmpty())
                ? envTable.resolve(weatherTag, wrappedMinute, floorTx.environmentId()).orElse(zoneEnv)
                : zoneEnv;

        float bestWeight = 0.0f;
        ZonePointLight bestLamp = null;
        float bestIntensity = 0.0f;
        if (floorTx != null && !pointLights.isEmpty()) {
            for (int slot = 0; slot < 4; slot++) {
                int idx = floorTx.pointLightIndex(slot);
                if (idx < 0 || idx >= pointLights.size()) {
                    continue;
                }
                float intensity = pointLightIntensities[idx];
                ZonePointLight pl = pointLights.get(idx);
                if (intensity <= 0.0001f || pl.range() <= 0.0f) {
                    continue;
                }
                float dx = pl.position().x - actor.getX();
                float dy = pl.position().y - actor.getY();
                float dz = pl.position().z - actor.getZ();
                float d2 = dx * dx + dy * dy + dz * dz;
                if (Math.sqrt(d2) > pl.range()) {
                    continue;
                }
                float att2 = 1.0f / intensity;
                float wt = 1.0f / Math.max(d2 * att2, 1e-4f);
                if (wt > bestWeight) {
                    bestWeight = wt;
                    bestLamp = pl;
                    bestIntensity = intensity;
                }
            }
        }

        characterShader.bind();
        characterShader.setActorLighting(
                true,
                actorEnv.actorLightDir(),
                actorEnv.actorLightColor(),
                actorEnv.actorAmbientColor(),
                bestLamp != null ? bestLamp.position() : null,
                bestLamp != null ? bestLamp.range() : 0.0f,
                bestLamp != null ? bestLamp.color() : null,
                bestIntensity
        );
        characterShader.setFog(enableFog, actorEnv.fogStart(), actorEnv.fogEnd(), actorEnv.fogColor());
    }

    private void renderWeatherSkyLayers(
            Matrix4f pMat,
            Matrix4f vMat,
            Vector3f cameraWorldPos,
            ZoneEnvironmentTable.ResolvedEnvironment zoneEnv,
            float normalizedClock,
            String weatherTag,
            int viewportWidth,
            int viewportHeight
    ) {
        if (zoneShader == null || zoneTextureLoader == null || weatherSkyRenderChunks.isEmpty()) {
            return;
        }
        List<ZoneRenderChunk> skyChunks = weatherSkyRenderChunks.get(weatherTag);
        if (skyChunks == null || skyChunks.isEmpty()) {
            skyChunks = weatherSkyRenderChunks.get("suny");
        }
        if (skyChunks == null || skyChunks.isEmpty()) {
            skyChunks = weatherSkyRenderChunks.get("fine");
        }
        if (skyChunks == null || skyChunks.isEmpty()) {
            return;
        }

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);

        zoneShader.bind();
        bindZoneShaderEnvironment(zoneEnv, null, false, false, 0.0f, viewportWidth, viewportHeight);
        zoneShader.clearPointLights();
        zoneShader.setFog(false, zoneEnv.fogStart(), zoneEnv.fogEnd(), zoneEnv.fogColor());
        zoneShader.setCameraWorldPos(cameraWorldPos);
        zoneShader.setOpaquePass(false);
        zoneShader.setDiscardThreshold(0.0f);

        float elapsedFrames = elapsedSeconds * 60.0f;
        Vector3f baseTranslation = new Vector3f();

        for (ZoneRenderChunk chunk : skyChunks) {
            ZoneEffectParams ep = chunk.effectParams();
            ep.evaluateLiveColor(normalizedClock, liveEffectRgba);
            if (liveEffectRgba.w <= 0.001f) {
                continue;
            }
            if (ep.blendMode() == ZoneEffectParams.BlendMode.ADDITIVE
                    && (liveEffectRgba.x + liveEffectRgba.y + liveEffectRgba.z) <= 0.001f) {
                continue;
            }

            Matrix4f baseModel = new Matrix4f(chunk.modelMatrix());
            baseModel.getTranslation(baseTranslation);
            if (ep.attachType() == ZoneEffectGeneratorResource.ATTACH_TYPE_SUN) {
                if (zoneEnv.sunTravelDir().y < -0.15f) {
                    continue;
                }
                baseModel.setTranslation(
                        cameraWorldPos.x - zoneEnv.sunTravelDir().x * 1000.0f,
                        cameraWorldPos.y - zoneEnv.sunTravelDir().y * 1000.0f,
                        cameraWorldPos.z - zoneEnv.sunTravelDir().z * 1000.0f
                );
            } else if (ep.attachType() == ZoneEffectGeneratorResource.ATTACH_TYPE_MOON) {
                if (zoneEnv.moonTravelDir().y < -0.15f) {
                    continue;
                }
                baseModel.setTranslation(
                        cameraWorldPos.x - zoneEnv.moonTravelDir().x * 900.0f,
                        cameraWorldPos.y - zoneEnv.moonTravelDir().y * 900.0f,
                        cameraWorldPos.z - zoneEnv.moonTravelDir().z * 900.0f
                );
            } else if (ep.followCamera()) {
                baseModel.setTranslation(
                        baseTranslation.x + cameraWorldPos.x,
                        baseTranslation.y + cameraWorldPos.y,
                        baseTranslation.z + cameraWorldPos.z
                );
            } else {
                continue;
            }

            configureEffectBlendState(ep);
            GL11.glDepthMask(false);
            zoneShader.setTexCoordOffset(ep.uvScrollU() * elapsedFrames, ep.uvScrollV() * elapsedFrames);

            String texName = chunk.buffer().textureName();
            boolean hasTexture = texName != null && !texName.isBlank() && !texName.equalsIgnoreCase("none");
            zoneShader.setEffectState(
                    ep.shaderEffectMode(),
                    hasTexture,
                    ep.ignoreTextureAlpha(),
                    false,
                    liveEffectRgba.x,
                    liveEffectRgba.y,
                    liveEffectRgba.z,
                    liveEffectRgba.w,
                    0.0f, 0.0f, 0.0f, 0.0f
            );

            Matrix4f drawModel = resolveEffectModelMatrix(baseModel, ep.billboardMode(), cameraWorldPos, vMat);
            zoneShader.setMatrices(pMat, vMat, drawModel);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if (hasTexture) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, zoneTextureLoader.textureId(texName));
            }
            chunk.buffer().draw();
        }

        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        zoneShader.resetSceneryState();
    }

    private int renderAmbientZoneEffects(
            Matrix4f pMat,
            Matrix4f vMat,
            Vector3f cameraWorldPos,
            ZoneEnvironmentTable.ResolvedEnvironment zoneEnv,
            float normalizedClock
    ) {
        List<ZoneRenderChunk> effectChunks = new ArrayList<>();
        for (ZoneRenderChunk chunk : zoneRenderChunks) {
            if (chunk.isEffect()) {
                effectChunks.add(chunk);
            }
        }
        if (effectChunks.isEmpty()) {
            return 0;
        }
        effectChunks.sort(java.util.Comparator.comparingInt(c -> c.effectParams().blendMode().subPassOrder()));

        GL11.glDisable(GL11.GL_CULL_FACE);
        zoneShader.setOpaquePass(false);
        float elapsedFrames = elapsedSeconds * 60.0f;
        int count = 0;

        for (ZoneRenderChunk chunk : effectChunks) {
            ZoneEffectParams ep = chunk.effectParams();
            ep.evaluateLiveColor(normalizedClock, liveEffectRgba);
            if (liveEffectRgba.w <= 0.001f) {
                continue;
            }
            configureEffectBlendState(ep);
            zoneShader.setFog(enableFog && ep.fogEnabled(), zoneEnv.fogStart(), zoneEnv.fogEnd(), zoneEnv.fogColor());
            zoneShader.setTexCoordOffset(ep.uvScrollU() * elapsedFrames, ep.uvScrollV() * elapsedFrames);

            String texName = chunk.buffer().textureName();
            boolean hasTexture = texName != null && !texName.isBlank() && !texName.equalsIgnoreCase("none");
            zoneShader.setEffectState(
                    ep.shaderEffectMode(),
                    hasTexture,
                    ep.ignoreTextureAlpha(),
                    ep.specularEnabled(),
                    liveEffectRgba.x,
                    liveEffectRgba.y,
                    liveEffectRgba.z,
                    liveEffectRgba.w,
                    ep.specularR(),
                    ep.specularG(),
                    ep.specularB(),
                    ep.specularA()
            );
            zoneShader.setDiscardThreshold(chunk.discardThreshold());

            Matrix4f drawModel = resolveEffectModelMatrix(chunk.modelMatrix(), ep.billboardMode(), cameraWorldPos, vMat);
            zoneShader.setMatrices(pMat, vMat, drawModel);
            if (hasTexture) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, zoneTextureLoader.textureId(texName));
            }
            chunk.buffer().draw();
            count++;
        }
        return count;
    }

    private static void configureEffectBlendState(ZoneEffectParams ep) {
        switch (ep.blendMode()) {
            case OPAQUE -> {
                GL11.glDisable(GL11.GL_BLEND);
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glDepthMask(true);
            }
            case ALPHA -> {
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDepthMask(ep.depthWrite());
            }
            case ZERO_INV_SRC -> {
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDepthMask(ep.depthWrite());
            }
            case ADDITIVE -> {
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                GL11.glDepthMask(ep.depthWrite());
            }
            case SUBTRACTIVE -> {
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                GL11.glDepthMask(ep.depthWrite());
            }
        }
    }

    static Matrix4f resolveEffectModelMatrix(
            Matrix4f baseModel,
            ZoneEffectParams.BillboardMode billboardMode,
            Vector3f cameraWorldPos,
            Matrix4f vMat
    ) {
        if (billboardMode == ZoneEffectParams.BillboardMode.NONE) {
            return baseModel;
        }
        Vector3f pos = new Vector3f();
        Vector3f scl = new Vector3f();
        baseModel.getTranslation(pos);
        if (billboardMode == ZoneEffectParams.BillboardMode.CAMERA) {
            float dx = cameraWorldPos.x - pos.x;
            float dy = cameraWorldPos.y - pos.y;
            float dz = cameraWorldPos.z - pos.z;
            if (dx * dx + dy * dy + dz * dz <= 1e-12f) {
                return baseModel;
            }
            float lenXZ = (float) Math.hypot(dx, dz);
            float yaw = (float) -Math.atan2(dz, dx);
            float pitch = (float) Math.atan2(dy, lenXZ);
            Matrix4f local3x3 = new Matrix4f(baseModel).setTranslation(0.0f, 0.0f, 0.0f);
            return new Matrix4f().translate(pos).rotateY(yaw).rotateZ(pitch).mul(local3x3);
        }
        baseModel.getScale(scl);
        if (billboardMode == ZoneEffectParams.BillboardMode.XZ) {
            float yaw = (float) Math.atan2(cameraWorldPos.x - pos.x, cameraWorldPos.z - pos.z);
            return new Matrix4f().translate(pos).rotateY(yaw).scale(scl);
        }
        Matrix4f billRot = new Matrix4f(vMat).setTranslation(0.0f, 0.0f, 0.0f).transpose();
        return new Matrix4f().translate(pos).mul(billRot).scale(scl);
    }
}
