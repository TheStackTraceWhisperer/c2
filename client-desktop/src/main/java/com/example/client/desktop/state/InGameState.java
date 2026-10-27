package com.example.client.desktop.state;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.interaction.ZoneInteraction;
import com.example.asset.interaction.ZoneInteractionParser;
import com.example.client.desktop.camera.Camera;
import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.engine.services.window.WindowService;
import com.example.client.desktop.input.MovementInputHandler;
import com.example.client.desktop.net.DesktopKeepAliveService;
import com.example.client.desktop.net.DesktopNetworkAdapter;
import com.example.client.desktop.renderer.CollisionMeshRenderer;
import com.example.client.desktop.ui.DebugLogPanel;
import com.example.client.desktop.ui.InGamePanel;
import com.example.client.desktop.ui.ScreenFader;
import com.example.client.desktop.ui.map.InGameMapPanel;
import com.example.client.desktop.ui.radar.WorldRadarPanel;
import com.example.client.desktop.util.MatrixMath;
import com.example.client.interp.InterpolatedEntityState;
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.dto.PongMessage;
import com.example.common.protocol.JobId;
import com.example.common.protocol.MovementFlags;
import com.example.common.time.VanadielTime;
import com.example.common.time.WeatherType;
import com.example.world.dto.ZoneTransferNotification;
import com.example.zone.dto.PlayerMovementResponse;
import com.ffxi.io.FileTableManager;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.tables.ZoneConfig;
import com.ffxi.tables.ZoneIdToResourceId;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.environment.AuthenticSkyDome;
import com.ffxi.viewer.environment.WeatherParticleSystem;
import com.ffxi.viewer.environment.WeatherPreset;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.model.ZoneModelLoader;
import com.ffxi.viewer.renderer.CharacterShader;
import com.ffxi.viewer.renderer.ZoneMeshBuffer;
import com.ffxi.viewer.renderer.ZoneRenderChunk;
import com.ffxi.viewer.renderer.ZoneShader;
import com.ffxi.viewer.renderer.ZoneTextureLoader;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImInt;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@Prototype
@RequiredArgsConstructor
public class InGameState implements ApplicationState {

    private final InGamePanel panel;
    private final WorldRadarPanel radarPanel;
    private final DebugLogPanel debugLog;
    private final DesktopKeepAliveService keepAlive;
    private final ClientState clientState;
    private final DesktopNetworkAdapter networkAdapter;
    private final ApplicationStateService stateService;
    private final WindowService windowService;
    private final MovementInputHandler movementHandler;
    private final Camera camera;
    private final BeanProvider<UnauthenticatedState> unauthProvider;
    private final BeanProvider<AuthenticatedState> authProvider;
    private final BeanProvider<CharacterSelectedState> selectedProvider;
    private final InGameMapPanel inGameMapPanel;

    private long accountId;
    private long characterId;
    private String characterName;
    @Getter
    private int currentZoneId;
    private long keepaliveIntervalMs;

    @Getter
    private final ScreenFader screenFader = new ScreenFader();

    // Authentic Zone rendering
    private LoadedZone loadedZone;
    private ZoneShader zoneShader;
    private ZoneTextureLoader zoneTextureLoader;
    private final List<ZoneRenderChunk> zoneRenderChunks = new ArrayList<>();

    // Authentic Character rendering
    private CharacterShader characterShader;
    private AuthenticCharacterInstance localPlayerVisual;
    private final Map<Long, AuthenticCharacterInstance> remoteEntityVisuals = new HashMap<>();

    // Authentic Sky Dome, Weather Particles & File Table
    private AuthenticSkyDome skyDome;
    private WeatherParticleSystem weatherParticleSystem;
    private WeatherType lastSkyWeather = null;
    private boolean mKeyPressed = false;
    private FileTableManager fileTableManager;

    private CollisionMeshRenderer collisionRenderer;
    private List<ZoneInteraction> zoneInteractions = List.of();
    @Getter
    private WeatherType currentWeather = WeatherType.CLEAR;
    private boolean manualTimeOverride = false;
    private float manualTimeHour = 12.0f;
    private ZoneInteraction activeZoneline = null;

    private boolean showVisualZone = true;
    private boolean showCharacterModels = true;
    private boolean showCollisionCylinders = false;
    private boolean wireframe = false;

    public void init(long accountId, long characterId, String characterName, int zoneId, long keepaliveIntervalMs) {
        this.accountId = accountId;
        this.characterId = characterId;
        this.characterName = characterName;
        this.currentZoneId = zoneId;
        this.keepaliveIntervalMs = keepaliveIntervalMs;

        panel.setContext(characterName, Long.toString(characterId));
        debugLog.log("Entered zone " + zoneId + " as " + characterName);
    }

    @Override
    public void onEnter() {
        clientState.clearRemoteEntities();
        networkAdapter.setPongListener(this::handlePongPacket);
        keepAlive.start(Long.toString(characterId), keepaliveIntervalMs);
        movementHandler.setPosition(clientState.getX(), clientState.getY(), clientState.getZ());
        camera.setTarget(clientState.getX(), clientState.getY(), clientState.getZ());

        // Initialize 3D OpenGL renderers if running with an active window
        long handle = windowService.getHandle();
        if (handle != 0L) {
            initRenderers();
        }

        // Authoritative initial spawn sync: probe server if local position is default (0, 0, 0)
        if (clientState.getX() == 0.0f && clientState.getY() == 0.0f && clientState.getZ() == 0.0f) {
            networkAdapter.sendMovement(
                    0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f,
                    MovementFlags.IDLE,
                    0L
            );
        }
    }

    private void initRenderers() {
        if (zoneShader == null) {
            try {
                this.zoneShader = new ZoneShader();
                this.characterShader = new CharacterShader();
                this.collisionRenderer = new CollisionMeshRenderer();
                this.collisionRenderer.init();

                try {
                    this.skyDome = new AuthenticSkyDome();
                    this.weatherParticleSystem = new WeatherParticleSystem();
                    log.info("Initialized authentic sky dome and weather particle system");
                } catch (Exception e) {
                    log.warn("Failed to initialize sky dome or weather particle system: {}", e.getMessage());
                }

                Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
                try {
                    LoadedCharacter charData = CharacterModelLoader.loadCharacter(ffxiRoot, RaceGenderConfig.HumeM);
                    this.localPlayerVisual = new AuthenticCharacterInstance(charData);
                    log.info("Initialized authentic local player visual instance");
                } catch (Exception e) {
                    log.error("Failed to load authentic character model for local player", e);
                }
            } catch (Exception e) {
                log.error("Failed to initialize 3D OpenGL renderers in InGameState", e);
            }
        }
        loadZone(currentZoneId <= 0 ? 230 : currentZoneId);
    }

    private void loadZone(int zoneId) {
        this.currentZoneId = zoneId;
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();

        // 1. Clear previous zone render chunks
        for (ZoneRenderChunk chunk : zoneRenderChunks) {
            try { chunk.close(); } catch (Exception ignored) {}
        }
        zoneRenderChunks.clear();
        if (zoneTextureLoader != null) {
            try { zoneTextureLoader.close(); } catch (Exception ignored) {}
            zoneTextureLoader = null;
        }

        long handle = windowService.getHandle();
        if (handle != 0L) {
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
                log.info("InGameState: Loaded authentic zone {}: {} chunk instances, {} textures",
                        zoneId, zoneRenderChunks.size(), loadedZone.textureSources().size());
            } catch (Exception e) {
                log.error("Failed loading authentic zone {}", zoneId, e);
            }
        }

        // 2. Zoneline Interactions
        byte[] bytes = loadZoneBytes(zoneId);
        if (bytes != null && bytes.length > 0) {
            try {
                this.zoneInteractions = ZoneInteractionParser.parseInteractions(bytes);
            } catch (Exception ignored) {}
        }
    }

    byte[] loadZoneBytes(int zoneId) {
        // 1. Check retail FFXI installation directory with dynamic FileTableManager
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
                        log.info("Loading zone {} DAT from retail FFXI install: {}", zoneId, p.toAbsolutePath());
                        return Files.readAllBytes(p);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed resolving zone DAT via FileTableManager for zone {}: {}", zoneId, e.getMessage());
            }
        }

        String relRom = (zoneId == 230) ? "ROM/1/31.DAT" : (zoneId == 100 ? "ROM/1/30.DAT" : null);
        if (relRom != null) {
            Optional<Path> installedPath = FfxiPathConfig.resolveRomFile(relRom);
            if (installedPath.isPresent() && Files.isRegularFile(installedPath.get())) {
                try {
                    log.info("Loading zone DAT from retail FFXI install: {}", installedPath.get().toAbsolutePath());
                    return Files.readAllBytes(installedPath.get());
                } catch (Exception e) {
                    log.warn("Failed reading installed DAT at {}", installedPath.get(), e);
                }
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
                Path.of("src/test/resources/dats/zones"),
                Path.of(System.getProperty("c2.dat.path", "game-asset-pipeline/src/test/resources/dats/zones"))
        );

        String suffix = "_" + zoneId + ".DAT";
        for (Path dir : searchDirs) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> stream = Files.list(dir)) {
                    Optional<Path> match = stream
                            .filter(p -> p.getFileName().toString().endsWith(suffix) || p.getFileName().toString().equals(zoneId + ".DAT"))
                            .findFirst();
                    if (match.isPresent()) {
                        return Files.readAllBytes(match.get());
                    }
                } catch (Exception ignored) {}
            } else if (Files.isRegularFile(dir)) {
                try {
                    return Files.readAllBytes(dir);
                } catch (Exception ignored) {}
            }
        }

        // Fallback to default zone 230 DAT
        for (Path dir : searchDirs) {
            Path defaultPath = dir.resolve("south_san_doria_230.DAT");
            if (Files.isRegularFile(defaultPath)) {
                try {
                    return Files.readAllBytes(defaultPath);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    @Override
    public void onUpdate(float dt) {
        long handle = windowService.getHandle();
        long now = System.currentTimeMillis();

        // 1. Process camera orientation and keyboard/mouse movement inputs
        camera.update(handle, dt);

        if (handle != 0L) {
            // Hotkeys: F1 Wireframe, F2 Visual Zone, F3 3D Character Models, F4 Collision Cylinders
            if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F1) == GLFW.GLFW_PRESS) {
                wireframe = !wireframe;
            }
            if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F2) == GLFW.GLFW_PRESS) {
                showVisualZone = !showVisualZone;
            }
            if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F3) == GLFW.GLFW_PRESS) {
                showCharacterModels = !showCharacterModels;
            }
            if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F4) == GLFW.GLFW_PRESS) {
                showCollisionCylinders = !showCollisionCylinders;
            }

            // Radar zoom via PageUp / PageDown keys
            float zoomRate = 50.0f;
            if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_PAGE_UP) == GLFW.GLFW_PRESS) {
                radarPanel.getConfig().zoomSmooth(-zoomRate * dt);
            }
            if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_PAGE_DOWN) == GLFW.GLFW_PRESS) {
                radarPanel.getConfig().zoomSmooth(zoomRate * dt);
            }

            // Cartography map overlay toggle via [M]
            boolean mDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_M) == GLFW.GLFW_PRESS;
            if (mDown && !mKeyPressed) {
                if (inGameMapPanel != null) {
                    inGameMapPanel.toggle();
                }
            }
            mKeyPressed = mDown;
        }

        // Advance authentic weather simulation and dynamic sky dome mesh
        WeatherPreset weatherPreset = WeatherPreset.getById(currentWeather.getDatCode());
        if (skyDome != null && currentWeather != lastSkyWeather) {
            skyDome.updateMesh(weatherPreset);
            lastSkyWeather = currentWeather;
        }
        if (weatherParticleSystem != null && handle != 0L) {
            weatherParticleSystem.update(dt, new org.joml.Vector3f(camera.getEyeX(), camera.getEyeY(), camera.getEyeZ()), weatherPreset);
        }

        screenFader.update(dt);

        if (screenFader.isInputLocked()) {
            clientState.updatePosition(
                    movementHandler.getSimX(), movementHandler.getSimY(), movementHandler.getSimZ(),
                    clientState.getYaw(), clientState.getPitch(),
                    clientState.getLastServerTick()
            );
            if (localPlayerVisual != null) {
                localPlayerVisual.update(dt, MovementFlags.IDLE, 0.0f);
            }
        } else {
            var moveResult = movementHandler.pollAndCompute(
                    handle,
                    camera,
                    clientState.getX(),
                    clientState.getY(),
                    clientState.getZ(),
                    clientState.getYaw(),
                    clientState.getPitch(),
                    dt,
                    now
            );

            clientState.updatePosition(
                    moveResult.x(), moveResult.y(), moveResult.z(),
                    moveResult.yaw(), moveResult.pitch(),
                    clientState.getLastServerTick()
            );
            camera.setTarget(moveResult.x(), moveResult.y(), moveResult.z());

            float localSpeed = (float) Math.hypot(moveResult.vx(), moveResult.vz());
            if (localPlayerVisual != null) {
                localPlayerVisual.update(dt, moveResult.moveFlags(), localSpeed);
            }

            if (moveResult.shouldSendPacket()) {
                networkAdapter.sendMovement(
                        moveResult.simX(), moveResult.simY(), moveResult.simZ(),
                        moveResult.vx(), moveResult.vy(), moveResult.vz(),
                        moveResult.yaw(), moveResult.pitch(),
                        moveResult.moveFlags(),
                        movementHandler.getClientTickCounter()
                );
            }
        }

        // Update remote entity animation state machines
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        for (var entity : clientState.getRemoteEntities().values()) {
            InterpolatedEntityState interp = clientState.sampleInterpolatedEntity(entity.characterId(), now);
            float speed = (float) Math.hypot(interp.vx(), interp.vz());
            AuthenticCharacterInstance visual = remoteEntityVisuals.computeIfAbsent(
                    entity.characterId(),
                    id -> {
                        try {
                            RaceGenderConfig race = resolveRaceForEntity(entity.mainJobId());
                            LoadedCharacter cd = CharacterModelLoader.loadCharacter(ffxiRoot, race);
                            return new AuthenticCharacterInstance(cd);
                        } catch (Exception e) {
                            return null;
                        }
                    }
            );
            if (visual != null) {
                visual.update(dt, 0, speed);
            }
        }

        // 2. Check Zoneline Intersections
        activeZoneline = null;
        for (ZoneInteraction zi : zoneInteractions) {
            if (zi.isZoneLine() && zi.containsHorizontal(clientState.getX(), clientState.getZ(), 3.0f)) {
                activeZoneline = zi;
                break;
            }
        }

        // 3. Render 3D OpenGL Viewport (when running with valid window handle)
        if (handle != 0L) {
            int[] width = new int[1];
            int[] height = new int[1];
            GLFW.glfwGetFramebufferSize(handle, width, height);
            int fbWidth = Math.max(1, width[0]);
            int fbHeight = Math.max(1, height[0]);
            GL11.glViewport(0, 0, fbWidth, fbHeight);

            GL11.glClearColor(0.08f, 0.10f, 0.14f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            float aspect = (float) fbWidth / (float) fbHeight;
            org.joml.Matrix4f pMat = new org.joml.Matrix4f().perspective((float) Math.toRadians(50.0), aspect, 0.1f, 100000.0f);
            org.joml.Matrix4f vMat = new org.joml.Matrix4f().lookAt(
                    new org.joml.Vector3f(camera.getEyeX(), camera.getEyeY(), camera.getEyeZ()),
                    new org.joml.Vector3f(camera.getTargetX(), camera.getTargetY(), camera.getTargetZ()),
                    new org.joml.Vector3f(0f, 1f, 0f)
            );
            float[] proj = new float[16];
            float[] view = new float[16];
            pMat.get(proj);
            vMat.get(view);

            // Render authentic hemispherical sky dome backdrop
            if (skyDome != null) {
                VanadielTime vTime = manualTimeOverride
                        ? VanadielTime.ofHour(manualTimeHour)
                        : VanadielTime.now();
                float hour = vTime.getHourFraction();
                float dayFactor;
                if (hour >= 6.0f && hour <= 18.0f) {
                    dayFactor = 1.0f;
                } else if (hour > 18.0f && hour < 20.0f) {
                    dayFactor = 1.0f - 0.65f * ((hour - 18.0f) / 2.0f);
                } else if (hour >= 20.0f || hour <= 4.0f) {
                    dayFactor = 0.35f;
                } else {
                    dayFactor = 0.35f + 0.65f * ((hour - 4.0f) / 2.0f);
                }
                float brightness = dayFactor * weatherPreset.diffuseMultiplier();
                skyDome.render(pMat, vMat, new org.joml.Vector3f(camera.getEyeX(), camera.getEyeY(), camera.getEyeZ()), brightness);
            }

            // Render 3D textured visual zone using authentic ZoneShader pipeline
            if (zoneShader != null && !zoneRenderChunks.isEmpty() && showVisualZone) {
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

            // Render 3D animated character models for local player and remote actors / NPCs
            if (characterShader != null && showCharacterModels) {
                if (localPlayerVisual != null) {
                    localPlayerVisual.render(
                            characterShader, pMat, vMat,
                            clientState.getX(), clientState.getY(), clientState.getZ(), clientState.getYaw()
                    );
                }

                for (var entity : clientState.getRemoteEntities().values()) {
                    InterpolatedEntityState interp = clientState.sampleInterpolatedEntity(entity.characterId(), now);
                    AuthenticCharacterInstance visual = remoteEntityVisuals.get(entity.characterId());
                    if (visual != null) {
                        visual.render(
                                characterShader, pMat, vMat,
                                interp.x(), interp.y(), interp.z(), interp.yaw()
                        );
                    }
                }
            }

            // Render collision markers if enabled or as fallback
            if (collisionRenderer != null && (showCollisionCylinders || !showCharacterModels)) {
                collisionRenderer.renderPlayerMarker(
                        clientState.getX(), clientState.getY(), clientState.getZ(),
                        clientState.getYaw(), proj, view
                );

                for (var entity : clientState.getRemoteEntities().values()) {
                    InterpolatedEntityState interp = clientState.sampleInterpolatedEntity(entity.characterId(), now);
                    boolean isNpc = entity.mainJobId() == JobId.NPC;
                    float r = isNpc ? 0.95f : 0.20f;
                    float g = isNpc ? 0.78f : 0.70f;
                    float b = isNpc ? 0.22f : 1.00f;
                    collisionRenderer.renderRemoteMarker(interp.x(), interp.y(), interp.z(), interp.yaw(), r, g, b, proj, view);
                }
            }

            // Render authentic weather particles (rain streaks, heavy squalls, snow crystals, blizzard drifts, sandstorms, lightning)
            if (weatherParticleSystem != null) {
                weatherParticleSystem.render(pMat, vMat, weatherPreset);
            }

            // Render 3D overhead billboard nameplates
            renderNameplates(proj, view, fbWidth, fbHeight, now);

            // Render smooth fullscreen cross-zone fade overlay
            screenFader.render(handle, fbWidth, fbHeight);
        }

        // 4. Render UI Panels, Clock HUD & In-Game Cartography Map
        if (handle != 0L) {
            VanadielTime vTime = manualTimeOverride
                    ? VanadielTime.ofHour(manualTimeHour)
                    : VanadielTime.now();
            renderClockBanner(vTime);
            if (inGameMapPanel != null) {
                inGameMapPanel.render(
                        currentZoneId,
                        clientState.getX(), clientState.getY(), clientState.getZ(), clientState.getYaw(),
                        zoneInteractions,
                        clientState.getRemoteEntities().values()
                );
            }
        }
        panel.updateKeepAlive(keepAlive.getStatus(), keepAlive.getLastRttMs());
        panel.render();
        radarPanel.render();
        debugLog.render();

        // 5. User Intent Processing
        if (panel.isPingRequested()) {
            debugLog.log("PING sent");
            keepAlive.sendPing();
        }
        if (panel.isCharacterSelectRequested()) {
            doCharacterSelect();
        }
        if (panel.isLogoutRequested()) {
            doLogout();
        }
        if (panel.isMapToggleRequested()) {
            if (inGameMapPanel != null) {
                inGameMapPanel.toggle();
            }
        }
        if (panel.getWarpRequestedZoneId() > 0) {
            int warpZoneId = panel.getWarpRequestedZoneId();
            debugLog.log("Executing client warp to zone #" + warpZoneId);
            float spawnX = 0f;
            float spawnY = 0f;
            float spawnZ = 0f;
            byte[] targetBytes = loadZoneBytes(warpZoneId);
            if (targetBytes != null && targetBytes.length > 0) {
                List<ZoneInteraction> targetInteractions = ZoneInteractionParser.parseInteractions(targetBytes);
                for (ZoneInteraction zi : targetInteractions) {
                    if (zi.isZoneEntrance()) {
                        spawnX = zi.getPosition().x();
                        spawnY = zi.getPosition().y();
                        spawnZ = zi.getPosition().z();
                        break;
                    }
                }
            }
            handleZoneTransfer(new ZoneTransferNotification(warpZoneId, "127.0.0.1", 9001, spawnX, spawnY, spawnZ));
        }
        panel.clearIntents();

        if (clientState.getConnectionState() == ClientConnectionState.DISCONNECTED) {
            stateService.changeState(unauthProvider::get);
        }
    }

    private void renderClockBanner(VanadielTime vTime) {
        ImGui.setNextWindowPos(20, 20, ImGuiCond.Always);
        ImGui.setNextWindowSize(520, 0, ImGuiCond.Always);
        ImGui.begin("Vana'diel Environment");
        ImGui.textColored(1.0f, 0.85f, 0.35f, 1.0f, vTime.formatClockSummary());
        ImGui.sameLine();
        ImGui.textColored(0.4f, 0.85f, 1.0f, 1.0f, " | Weather: " + currentWeather.getDisplayName());

        // Weather Selection Dropdown
        String[] weatherNames = Arrays.stream(WeatherType.values()).map(WeatherType::getDisplayName).toArray(String[]::new);
        ImInt weatherIdx = new ImInt(currentWeather.ordinal());
        if (ImGui.combo("Weather", weatherIdx, weatherNames)) {
            currentWeather = WeatherType.values()[weatherIdx.get()];
        }

        // Time of Day Controls
        if (ImGui.checkbox("Manual Time Override", manualTimeOverride)) {
            manualTimeOverride = !manualTimeOverride;
        }

        if (manualTimeOverride) {
            float[] hourArr = new float[]{manualTimeHour};
            if (ImGui.sliderFloat("Time of Day", hourArr, 0.0f, 24.0f, "%.1f h")) {
                manualTimeHour = hourArr[0];
            }
            if (ImGui.button("Dawn (05:00)")) manualTimeHour = 5.0f;
            ImGui.sameLine();
            if (ImGui.button("Noon (12:00)")) manualTimeHour = 12.0f;
            ImGui.sameLine();
            if (ImGui.button("Dusk (18:00)")) manualTimeHour = 18.0f;
            ImGui.sameLine();
            if (ImGui.button("Midnight (00:00)")) manualTimeHour = 0.0f;
        }

        if (activeZoneline != null) {
            ImGui.separator();
            ImGui.textColored(0.2f, 1.0f, 0.4f, 1.0f,
                    ">>> [ZONELINE] Gate to " + activeZoneline.getDestId() + " (Walk across to change zone) <<<");
        }
        ImGui.end();
    }

    private void renderNameplates(float[] proj, float[] view, int fbWidth, int fbHeight, long now) {
        // Project local player nameplate
        float[] playerScreen = MatrixMath.projectToScreen(
                clientState.getX(), clientState.getY() + 2.0f, clientState.getZ(),
                proj, view, fbWidth, fbHeight
        );
        if (playerScreen != null) {
            drawBillboardLabel(playerScreen[0], playerScreen[1], characterName, 0.4f, 0.8f, 1.0f);
        }

        // Project remote entities and town NPCs within 50m
        for (var entity : clientState.getRemoteEntities().values()) {
            InterpolatedEntityState interp = clientState.sampleInterpolatedEntity(entity.characterId(), now);
            float dx = interp.x() - clientState.getX();
            float dz = interp.z() - clientState.getZ();
            if (dx * dx + dz * dz > 2500.0f) {
                continue;
            }

            float[] screen = MatrixMath.projectToScreen(
                    interp.x(), interp.y() + 1.9f, interp.z(),
                    proj, view, fbWidth, fbHeight
            );
            if (screen != null) {
                boolean isNpc = entity.mainJobId() == JobId.NPC;
                String label = isNpc
                        ? "[NPC] " + entity.name()
                        : entity.name() + " (" + JobId.toAbbreviation(entity.mainJobId()) + " Lv" + entity.level() + ")";
                float r = isNpc ? 0.95f : 0.40f;
                float g = isNpc ? 0.80f : 0.80f;
                float b = isNpc ? 0.25f : 1.00f;
                drawBillboardLabel(screen[0], screen[1], label, r, g, b);
            }
        }
    }

    private void drawBillboardLabel(float screenX, float screenY, String text, float r, float g, float b) {
        ImGui.setNextWindowPos(screenX - 60, screenY, ImGuiCond.Always);
        ImGui.setNextWindowSize(160, 32, ImGuiCond.Always);
        ImGui.begin("##nameplate_" + text.hashCode());
        ImGui.textColored(r, g, b, 1.0f, text);
        ImGui.end();
    }

    @Override
    public void onExit() {
        networkAdapter.setPongListener(null);
        keepAlive.stop();
        movementHandler.reset();
        clientState.resetZoneSession();

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
        if (localPlayerVisual != null) {
            try { localPlayerVisual.close(); } catch (Exception ignored) {}
            localPlayerVisual = null;
        }
        for (AuthenticCharacterInstance visual : remoteEntityVisuals.values()) {
            if (visual != null) {
                try { visual.close(); } catch (Exception ignored) {}
            }
        }
        remoteEntityVisuals.clear();

        if (collisionRenderer != null) {
            collisionRenderer.cleanup();
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
        if (inGameMapPanel != null) {
            try { inGameMapPanel.close(); } catch (Exception ignored) {}
        }
    }

    private static RaceGenderConfig resolveRaceForEntity(short jobId) {
        if (jobId == JobId.NPC) return RaceGenderConfig.ElvaanM;
        if (jobId == JobId.THIEF) return RaceGenderConfig.Mithra;
        if (jobId == JobId.WHITE_MAGE) return RaceGenderConfig.TaruM;
        if (jobId == JobId.BLACK_MAGE) return RaceGenderConfig.TaruF;
        if (jobId == JobId.PALADIN) return RaceGenderConfig.ElvaanM;
        if (jobId == JobId.RED_MAGE) return RaceGenderConfig.HumeF;
        if (jobId == JobId.MONK) return RaceGenderConfig.Galka;
        return RaceGenderConfig.HumeM;
    }

    private short resolveLocalJobId() {
        if (clientState.getSelectedCharacterId() != null) {
            long selId = clientState.getSelectedCharacterId();
            for (var c : clientState.getCharacterList()) {
                if (c.characterId() == selId) {
                    return c.mainJobId();
                }
            }
        }
        return JobId.WARRIOR;
    }

    @Override
    public void onHandlePacket(Object packet) {
        if (packet instanceof PongMessage pong) {
            handlePongPacket(pong);
        } else if (packet instanceof PlayerMovementResponse moveResp) {
            handleMovementResponse(moveResp);
        } else if (packet instanceof ZoneTransferNotification transfer) {
            handleZoneTransfer(transfer);
        }
    }

    public void handleZoneTransfer(ZoneTransferNotification transfer) {
        debugLog.log("Zone transition incoming -> Target Zone #" + transfer.targetZoneId());
        long handle = windowService.getHandle();
        Runnable swapAction = () -> {
            this.currentZoneId = transfer.targetZoneId();
            loadZone(transfer.targetZoneId());
            movementHandler.setPosition(transfer.spawnX(), transfer.spawnY(), transfer.spawnZ());
            clientState.updatePosition(transfer.spawnX(), transfer.spawnY(), transfer.spawnZ(), 0f, 0f, 0L);
            camera.setTarget(transfer.spawnX(), transfer.spawnY(), transfer.spawnZ());
            clientState.clearRemoteEntities();
            activeZoneline = null;
            debugLog.log(String.format("Arrived in Zone #%d at (%.1f, %.1f, %.1f)",
                    transfer.targetZoneId(), transfer.spawnX(), transfer.spawnY(), transfer.spawnZ()));
        };

        if (handle != 0L) {
            screenFader.startTransition(0.35f, swapAction);
        } else {
            swapAction.run();
        }
    }

    private void handlePongPacket(PongMessage pong) {
        keepAlive.handlePong(pong);
        debugLog.log(String.format("PONG received (RTT: %dms)", keepAlive.getLastRttMs()));
    }

    private void handleMovementResponse(PlayerMovementResponse moveResp) {
        float dx = movementHandler.getSimX() - moveResp.x();
        float dz = movementHandler.getSimZ() - moveResp.z();
        float dist = (float) Math.hypot(dx, dz);

        movementHandler.reconcile(moveResp.x(), moveResp.y(), moveResp.z(), moveResp.reconciled());
        clientState.setLastServerTick(moveResp.serverTick());

        if (moveResp.reconciled() || dist > 2.0f) {
            log.warn("Authoritative position reconciliation (dist={}): server=({}, {}, {}), sim=({}, {}, {})",
                    dist, moveResp.x(), moveResp.y(), moveResp.z(),
                    movementHandler.getSimX(), movementHandler.getSimY(), movementHandler.getSimZ());
            debugLog.log(String.format("Position reconciled with server (delta=%.2fm)", dist));
        }
    }

    private void doLogout() {
        keepAlive.stop();
        movementHandler.reset();
        clientState.reset();
        debugLog.log("Signing out...");
        networkAdapter.sendLogout();
        stateService.changeState(unauthProvider::get);
    }

    private void doCharacterSelect() {
        keepAlive.stop();
        movementHandler.reset();
        clientState.resetZoneSession();
        clientState.setSelectedCharacterId(null);
        clientState.setConnectionState(ClientConnectionState.IN_LOBBY);
        debugLog.log("Returning to Character Selection...");
        AuthenticatedState next = authProvider.get();
        next.init(accountId);
        stateService.changeState(() -> next);
    }
}
