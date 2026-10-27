package com.example.client.desktop.state;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.interaction.ZoneInteraction;
import com.example.asset.interaction.ZoneInteractionParser;
import com.example.client.desktop.camera.Camera;
import com.example.client.desktop.camera.PolarCamera;
import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.engine.services.window.WindowService;
import com.example.client.desktop.input.MovementInputHandler;
import com.example.client.desktop.net.DesktopKeepAliveService;
import com.example.client.desktop.net.DesktopNetworkAdapter;
import com.example.client.desktop.scene.SceneActor;
import com.example.client.desktop.scene.WorldScene;
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
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.environment.WeatherPreset;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImInt;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Production gameplay application state.
 * Orchestrates player input, network replication, UI overlays, and delegates
 * 3D rendering and scene hierarchy to the unified WorldScene abstraction.
 */
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
    @Getter private int currentZoneId;
    private long keepaliveIntervalMs;

    @Getter private final ScreenFader screenFader = new ScreenFader();
    private final WorldScene worldScene = new WorldScene();

    private boolean mKeyPressed = false;
    private List<ZoneInteraction> zoneInteractions = List.of();
    @Getter private WeatherType currentWeather = WeatherType.CLEAR;
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

        long handle = windowService.getHandle();
        if (handle != 0L) {
            initRenderers();
        }

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
        worldScene.init();
        try {
            Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
            LoadedCharacter charData = CharacterModelLoader.loadCharacter(ffxiRoot, RaceGenderConfig.HumeM);
            AuthenticCharacterInstance localVisual = new AuthenticCharacterInstance(charData);
            SceneActor localActor = new SceneActor(characterId, characterName, SceneActor.ActorType.PLAYER);
            localActor.setVisualModel(localVisual);
            worldScene.setLocalPlayer(localActor);
            log.info("Initialized authentic local player visual actor in WorldScene");
        } catch (Exception e) {
            log.error("Failed to load character model for local player", e);
        }
        loadZone(currentZoneId <= 0 ? 230 : currentZoneId);
    }

    private void loadZone(int zoneId) {
        this.currentZoneId = zoneId;
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        worldScene.loadZone(ffxiRoot, zoneId);
        this.zoneInteractions = worldScene.getZoneInteractions();
    }

    public byte[] loadZoneBytes(int zoneId) {
        return worldScene.loadZoneBytes(zoneId);
    }

    @Override
    public void onUpdate(float dt) {
        long handle = windowService.getHandle();
        long now = System.currentTimeMillis();

        // 1. Process camera orientation and inputs
        camera.update(handle, dt);

        if (handle != 0L) {
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

            float zoomRate = 50.0f;
            if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_PAGE_UP) == GLFW.GLFW_PRESS) {
                radarPanel.getConfig().zoomSmooth(-zoomRate * dt);
            }
            if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_PAGE_DOWN) == GLFW.GLFW_PRESS) {
                radarPanel.getConfig().zoomSmooth(zoomRate * dt);
            }

            boolean mDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_M) == GLFW.GLFW_PRESS;
            if (mDown && !mKeyPressed && inGameMapPanel != null) {
                inGameMapPanel.toggle();
            }
            mKeyPressed = mDown;
        }

        screenFader.update(dt);

        float moveSpeed = 0.0f;
        int moveFlags = MovementFlags.IDLE;

        if (screenFader.isInputLocked()) {
            clientState.updatePosition(
                    movementHandler.getSimX(), movementHandler.getSimY(), movementHandler.getSimZ(),
                    clientState.getYaw(), clientState.getPitch(),
                    clientState.getLastServerTick()
            );
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

            moveSpeed = (float) Math.hypot(moveResult.vx(), moveResult.vz());
            moveFlags = moveResult.moveFlags();

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

        // 2. Sync Scene Actors
        SceneActor localActor = worldScene.getLocalPlayerActor();
        if (localActor != null) {
            localActor.setPosition(clientState.getX(), clientState.getY(), clientState.getZ());
            localActor.setRotation(clientState.getYaw());
            localActor.setMovementSpeed(moveSpeed);
            localActor.setMovementFlags(moveFlags);
        }

        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        for (var entity : clientState.getRemoteEntities().values()) {
            InterpolatedEntityState interp = clientState.sampleInterpolatedEntity(entity.characterId(), now);
            SceneActor actor = worldScene.getActor(entity.characterId());
            if (actor == null) {
                SceneActor.ActorType aType = (entity.mainJobId() == JobId.NPC)
                        ? SceneActor.ActorType.NPC
                        : SceneActor.ActorType.PLAYER;
                actor = new SceneActor(entity.characterId(), entity.name(), aType);
                try {
                    RaceGenderConfig race = resolveRaceForEntity(entity.mainJobId());
                    LoadedCharacter cd = CharacterModelLoader.loadCharacter(ffxiRoot, race);
                    actor.setVisualModel(new AuthenticCharacterInstance(cd));
                } catch (Exception ignored) {}
                worldScene.addActor(actor);
            }
            actor.setPosition(interp.x(), interp.y(), interp.z());
            actor.setRotation(interp.yaw());
            float speed = (float) Math.hypot(interp.vx(), interp.vz());
            actor.setMovementSpeed(speed);
            actor.setMovementFlags(speed > 0.1f ? MovementFlags.FORWARD : MovementFlags.IDLE);
        }

        for (SceneActor a : worldScene.getActors()) {
            if (!a.isLocalPlayer() && !clientState.getRemoteEntities().containsKey(a.getId())) {
                worldScene.removeActor(a.getId());
            }
        }

        // 3. Check Zoneline Intersections
        activeZoneline = null;
        for (ZoneInteraction zi : zoneInteractions) {
            if (zi.isZoneLine() && zi.containsHorizontal(clientState.getX(), clientState.getZ(), 3.0f)) {
                activeZoneline = zi;
                break;
            }
        }

        // 4. Render 3D OpenGL Viewport
        if (handle != 0L) {
            int[] width = new int[1];
            int[] height = new int[1];
            GLFW.glfwGetFramebufferSize(handle, width, height);
            int fbWidth = Math.max(1, width[0]);
            int fbHeight = Math.max(1, height[0]);

            VanadielTime vTime = manualTimeOverride
                    ? VanadielTime.ofHour(manualTimeHour)
                    : VanadielTime.now();

            worldScene.setWeatherPreset(WeatherPreset.getById(currentWeather.getDatCode()));
            worldScene.setManualTimeOverride(manualTimeOverride);
            worldScene.setManualTimeHour(manualTimeHour);
            worldScene.setShowVisualZone(showVisualZone);
            worldScene.setShowCharacterModels(showCharacterModels);
            worldScene.setShowCollisionMesh(wireframe);
            worldScene.setShowCollisionMarkers(showCollisionCylinders);

            // Sync camera
            worldScene.getCamera().setTarget(camera.getTargetX(), camera.getTargetY(), camera.getTargetZ());
            worldScene.getCamera().setYaw(camera.getYaw());
            worldScene.getCamera().setPitch(camera.getPitch());
            worldScene.getCamera().setDistance(camera.getDistance());

            worldScene.update(dt, vTime);
            worldScene.render(fbWidth, fbHeight);

            // Billboard Nameplates
            float aspect = (float) fbWidth / (float) fbHeight;
            float[] proj = new float[16];
            float[] view = new float[16];
            worldScene.getCamera().getProjectionMatrix(aspect).get(proj);
            worldScene.getCamera().getViewMatrix().get(view);
            renderNameplates(proj, view, fbWidth, fbHeight, now);

            // Smooth cross-zone fade overlay
            screenFader.render(handle, fbWidth, fbHeight);
        }

        // 5. Render UI Panels & Map
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

        if (panel.isPingRequested()) {
            debugLog.log("PING sent");
            keepAlive.sendPing();
        }
        if (panel.isLogoutRequested()) {
            doLogout();
        }
        if (panel.isCharacterSelectRequested()) {
            doCharacterSelect();
        }
        if (panel.getWarpRequestedZoneId() >= 0) {
            int warpZoneId = panel.getWarpRequestedZoneId();
            debugLog.log("WARP requested to Zone #" + warpZoneId);
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

        String[] weatherNames = Arrays.stream(WeatherType.values()).map(WeatherType::getDisplayName).toArray(String[]::new);
        ImInt weatherIdx = new ImInt(currentWeather.ordinal());
        if (ImGui.combo("Weather", weatherIdx, weatherNames)) {
            currentWeather = WeatherType.values()[weatherIdx.get()];
        }

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
        float[] playerScreen = MatrixMath.projectToScreen(
                clientState.getX(), clientState.getY() + 2.0f, clientState.getZ(),
                proj, view, fbWidth, fbHeight
        );
        if (playerScreen != null) {
            drawBillboardLabel(playerScreen[0], playerScreen[1], characterName, 0.4f, 0.8f, 1.0f);
        }

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

        worldScene.close();
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
