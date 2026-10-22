package com.example.client.desktop.state;

import com.example.asset.interaction.ZoneInteraction;
import com.example.asset.interaction.ZoneInteractionParser;
import com.example.asset.mesh.VisualZoneLoader;
import com.example.asset.mesh.VisualZoneScene;
import com.example.client.desktop.camera.Camera;
import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.engine.services.window.WindowService;
import com.example.client.desktop.input.MovementInputHandler;
import com.example.client.desktop.net.DesktopKeepAliveService;
import com.example.client.desktop.net.DesktopNetworkAdapter;
import com.example.asset.config.FfxiPathConfig;
import com.example.asset.loader.RaceModelLoader;
import com.example.asset.skeleton.AnimationState;
import com.example.asset.skeleton.AnimationStateMachine;
import com.example.asset.skeleton.BlendedPose;
import com.example.asset.skeleton.CompositeCharacterModel;
import com.example.asset.skeleton.SkeletonAnimation;
import com.example.asset.skeleton.StandardAnimations;
import com.example.client.desktop.renderer.CharacterRenderer;
import com.example.client.desktop.renderer.CollisionMeshRenderer;
import com.example.client.desktop.renderer.VisualZoneRenderer;
import com.example.client.desktop.ui.DebugLogPanel;
import com.example.client.desktop.ui.InGamePanel;
import com.example.client.desktop.ui.ScreenFader;
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
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImInt;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private long accountId;
    private long characterId;
    private String characterName;
    @Getter
    private int currentZoneId;
    private long keepaliveIntervalMs;

    @Getter
    private final ScreenFader screenFader = new ScreenFader();

    private VisualZoneRenderer visualRenderer;
    private CollisionMeshRenderer collisionRenderer;
    private CharacterRenderer characterRenderer;

    private final AnimationStateMachine localPlayerAnimation = StandardAnimations.createLocomotionStateMachine();
    private final Map<Long, AnimationStateMachine> remoteEntityAnimations = new HashMap<>();

    private VisualZoneScene visualScene;
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
        if (visualRenderer == null) {
            visualRenderer = new VisualZoneRenderer();
            collisionRenderer = new CollisionMeshRenderer();
            characterRenderer = new CharacterRenderer();
            try {
                visualRenderer.init();
                collisionRenderer.init();
                characterRenderer.init();

                // Wire authentic FFXI locomotion animations from game ROMs
                try {
                    RaceModelLoader raceLoader = characterRenderer.getRaceModelLoader();
                    Map<String, SkeletonAnimation> humeAnims = raceLoader.loadRaceAnimations(RaceModelLoader.CharacterRace.HUME_MALE);
                    if (humeAnims.containsKey("idl0")) localPlayerAnimation.registerAnimation(AnimationState.IDLE, humeAnims.get("idl0"));
                    if (humeAnims.containsKey("wlk0")) localPlayerAnimation.registerAnimation(AnimationState.WALK, humeAnims.get("wlk0"));
                    if (humeAnims.containsKey("run0")) localPlayerAnimation.registerAnimation(AnimationState.RUN, humeAnims.get("run0"));
                } catch (Exception ex) {
                    log.warn("Could not load authentic ROM animations for local player: {}", ex.getMessage());
                }
            } catch (Exception e) {
                log.error("Failed to initialize 3D OpenGL renderers in InGameState", e);
            }
        }
        loadZone(currentZoneId <= 0 ? 230 : currentZoneId);
    }

    private void loadZone(int zoneId) {
        byte[] bytes = loadZoneBytes(zoneId);
        if (bytes == null || bytes.length == 0) {
            log.warn("InGameState: DAT asset for zone {} could not be loaded", zoneId);
            return;
        }

        try {
            // 1. Visual Textured Meshes
            this.visualScene = VisualZoneLoader.load(bytes);
            if (visualRenderer != null) {
                visualRenderer.upload(this.visualScene);
            }

            // 2. Zoneline Interactions
            this.zoneInteractions = ZoneInteractionParser.parseInteractions(bytes);
            long zonelineCount = zoneInteractions.stream().filter(ZoneInteraction::isZoneLine).count();
            log.info("InGameState: Loaded zone {} visual meshes ({} meshes, {} textures) and {} zonelines",
                    zoneId, visualScene.totalMeshCount(), visualScene.textures().size(), zonelineCount);
        } catch (Exception e) {
            log.error("Error loading visual zone DAT for InGameState", e);
        }
    }

    byte[] loadZoneBytes(int zoneId) {
        // 1. Check retail FFXI installation directory
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
        }

        screenFader.update(dt);

        if (screenFader.isInputLocked()) {
            clientState.updatePosition(
                    movementHandler.getSimX(), movementHandler.getSimY(), movementHandler.getSimZ(),
                    clientState.getYaw(), clientState.getPitch(),
                    clientState.getLastServerTick()
            );
            localPlayerAnimation.update(dt, MovementFlags.IDLE, 0.0f);
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
            localPlayerAnimation.update(dt, moveResult.moveFlags(), localSpeed);

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
        for (var entity : clientState.getRemoteEntities().values()) {
            InterpolatedEntityState interp = clientState.sampleInterpolatedEntity(entity.characterId(), now);
            float speed = (float) Math.hypot(interp.vx(), interp.vz());
            AnimationStateMachine anim = remoteEntityAnimations.computeIfAbsent(
                    entity.characterId(),
                    id -> StandardAnimations.createLocomotionStateMachine()
            );
            anim.update(dt, 0, speed);
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
            float[] proj = MatrixMath.perspective(60.0f, aspect, 0.2f, 2000.0f);
            float[] view = MatrixMath.lookAt(
                    camera.getEyeX(), camera.getEyeY(), camera.getEyeZ(),
                    camera.getTargetX(), camera.getTargetY(), camera.getTargetZ(),
                    0f, 1f, 0f
            );

            // Render 3D textured visual zone
            if (visualRenderer != null && showVisualZone) {
                visualRenderer.render(proj, view, wireframe, camera.getEyeX(), camera.getEyeY(), camera.getEyeZ());
            }

            // Render 3D animated character models for local player and remote actors / NPCs
            short localJob = resolveLocalJobId();
            if (characterRenderer != null && showCharacterModels) {
                CompositeCharacterModel localModel = characterRenderer.getOrCreateModel(localJob);
                if (localModel != null) {
                    BlendedPose localPose = localPlayerAnimation.evaluate(localModel.skeleton());
                    characterRenderer.render(
                            localJob, localPose.jointPoseMatrices(),
                            clientState.getX(), clientState.getY(), clientState.getZ(), clientState.getYaw(),
                            proj, view, camera.getEyeX(), camera.getEyeY(), camera.getEyeZ()
                    );
                }

                for (var entity : clientState.getRemoteEntities().values()) {
                    InterpolatedEntityState interp = clientState.sampleInterpolatedEntity(entity.characterId(), now);
                    AnimationStateMachine anim = remoteEntityAnimations.computeIfAbsent(
                            entity.characterId(),
                            id -> StandardAnimations.createLocomotionStateMachine()
                    );
                    CompositeCharacterModel model = characterRenderer.getOrCreateModel(entity.mainJobId());
                    if (model != null) {
                        BlendedPose pose = anim.evaluate(model.skeleton());
                        characterRenderer.render(
                                entity.mainJobId(), pose.jointPoseMatrices(),
                                interp.x(), interp.y(), interp.z(), interp.yaw(),
                                proj, view, camera.getEyeX(), camera.getEyeY(), camera.getEyeZ()
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

            // Render 3D overhead billboard nameplates
            renderNameplates(proj, view, fbWidth, fbHeight, now);

            // Render smooth fullscreen cross-zone fade overlay
            screenFader.render(handle, fbWidth, fbHeight);
        }

        // 4. Render UI Panels & Clock HUD
        if (handle != 0L) {
            VanadielTime vTime = manualTimeOverride
                    ? VanadielTime.ofHour(manualTimeHour)
                    : VanadielTime.now();
            renderClockBanner(vTime);
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

        if (visualRenderer != null) {
            visualRenderer.cleanup();
            visualRenderer = null;
        }
        if (collisionRenderer != null) {
            collisionRenderer.cleanup();
            collisionRenderer = null;
        }
        if (characterRenderer != null) {
            characterRenderer.cleanup();
            characterRenderer = null;
        }
        remoteEntityAnimations.clear();
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
