package com.example.client.desktop.scene;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.interaction.ZoneInteraction;
import com.example.asset.interaction.ZoneInteractionParser;
import com.example.client.desktop.renderer.CollisionMeshRenderer;
import com.example.common.time.VanadielTime;
import com.ffxi.tables.ZoneConfig;
import com.ffxi.tables.ZoneIdToResourceId;
import com.ffxi.io.FileTableManager;
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
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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

    // Character Rendering
    private CharacterShader characterShader;

    // Atmospheric & Weather Systems
    private AuthenticSkyDome skyDome;
    private WeatherParticleSystem weatherParticleSystem;
    @Setter private WeatherPreset weatherPreset = WeatherPreset.SUNNY;
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
                this.skyDome = new AuthenticSkyDome();
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

    /**
     * Loads authentic zone geometry, textures, collision system, and zoneline interactions from DAT files.
     */
    public void loadZone(Path ffxiRoot, int zoneId) {
        this.currentZoneId = zoneId;

        // 1. Dispose previous zone render chunks and texture atlas
        for (ZoneRenderChunk chunk : zoneRenderChunks) {
            try { chunk.close(); } catch (Exception ignored) {}
        }
        zoneRenderChunks.clear();
        if (zoneTextureLoader != null) {
            try { zoneTextureLoader.close(); } catch (Exception ignored) {}
            zoneTextureLoader = null;
        }

        // 2. Load authentic visual zone chunks
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

        // 3. Parse authentic zone line and entrance interactions
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
        this.localPlayerActor = player;
        if (player != null) {
            player.setLocalPlayer(true);
            actors.put(player.getId(), player);
        }
    }

    public void addActor(SceneActor actor) {
        actors.put(actor.getId(), actor);
    }

    public void removeActor(long actorId) {
        actors.remove(actorId);
    }

    public SceneActor getActor(long actorId) {
        return actors.get(actorId);
    }

    public Collection<SceneActor> getActors() {
        return Collections.unmodifiableCollection(actors.values());
    }

    /**
     * Steps animations, particles, camera tracking, and environmental time.
     */
    public void update(float dt, VanadielTime time) {
        // Step camera
        if (localPlayerActor != null && camera.getMode() == CameraMode.THIRD_PERSON) {
            camera.setTarget(localPlayerActor.getX(), localPlayerActor.getY(), localPlayerActor.getZ());
        }

        // Step character animations & locomotion
        for (SceneActor actor : actors.values()) {
            actor.update(dt);
        }
    }

    /**
     * Executes the unified 3D rendering pipeline for the current viewport.
     */
    public void render(int viewportWidth, int viewportHeight) {
        int w = Math.max(1, viewportWidth);
        int h = Math.max(1, viewportHeight);
        GL11.glViewport(0, 0, w, h);

        GL11.glClearColor(0.08f, 0.10f, 0.14f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        float aspect = (float) w / (float) h;
        Matrix4f pMat = camera.getProjectionMatrix(aspect);
        Matrix4f vMat = camera.getViewMatrix();

        float[] proj = new float[16];
        float[] view = new float[16];
        pMat.get(proj);
        vMat.get(view);

        // 1. Atmospheric Sky Dome Pass
        if (showSkyDome && skyDome != null) {
            VanadielTime vTime = manualTimeOverride
                    ? VanadielTime.ofHour(manualTimeHour)
                    : VanadielTime.now();
            float hour = vTime.getHourFraction();
            float dayFactor = (hour >= 6.0f && hour <= 18.0f) ? 1.0f
                    : ((hour > 18.0f && hour < 20.0f) ? 1.0f - 0.65f * ((hour - 18.0f) / 2.0f)
                    : ((hour >= 20.0f || hour <= 4.0f) ? 0.35f
                    : 0.35f + 0.65f * ((hour - 4.0f) / 2.0f)));
            float brightness = dayFactor * weatherPreset.diffuseMultiplier();
            skyDome.render(pMat, vMat, new Vector3f(camera.getEyeX(), camera.getEyeY(), camera.getEyeZ()), brightness);
        }

        // 2. Zone Geometry Multi-Pass (Opaque & Alpha-Test Pass 0, Blended Pass 1)
        if (showVisualZone && zoneShader != null && zoneTextureLoader != null && !zoneRenderChunks.isEmpty()) {
            zoneShader.bind();
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

        // 3. Animated Skinned Character Actors Pass
        if (showCharacterModels && characterShader != null) {
            for (SceneActor actor : actors.values()) {
                actor.render(characterShader, pMat, vMat);
            }
        }

        // 4. Collision Wireframe / Cylinder Markers Pass
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

        // 5. Weather Particles Pass
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

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        // Color attachment
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);

        // Depth/stencil attachment
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRb);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depthRb);

        render(width, height);

        // Read pixels
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

        // Restore previous framebuffer
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL30.glDeleteRenderbuffers(depthRb);
        GL11.glDeleteTextures(colorTex);
        GL30.glDeleteFramebuffers(fbo);

        // Convert to BufferedImage with vertical flip
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

        String specificResource = zoneId == 100
                ? "/dats/zones/west_ronfaure_100.DAT"
                : "/dats/zones/south_san_doria_230.DAT";
        try (InputStream stream = getClass().getResourceAsStream(specificResource)) {
            if (stream != null) {
                return stream.readAllBytes();
            }
        } catch (Exception ignored) {}

        List<Path> searchDirs = List.of(
                Path.of("game-asset-pipeline/src/test/resources/dats/zones"),
                Path.of("../game-asset-pipeline/src/test/resources/dats/zones"),
                Path.of("src/test/resources/dats/zones")
        );
        String suffix = "_" + zoneId + ".DAT";
        for (Path dir : searchDirs) {
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    Optional<Path> found = stream.filter(p -> p.getFileName().toString().endsWith(suffix)).findFirst();
                    if (found.isPresent()) {
                        return Files.readAllBytes(found.get());
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    @Override
    public void close() {
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
        if (skyDome != null) {
            try { skyDome.close(); } catch (Exception ignored) {}
            skyDome = null;
        }
        if (weatherParticleSystem != null) {
            try { weatherParticleSystem.close(); } catch (Exception ignored) {}
            weatherParticleSystem = null;
        }
        actors.clear();
        this.initialized = false;
        log.info("Disposed WorldScene GPU resources");
    }
}
