package com.example.client.desktop.state;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.interaction.ZoneInteraction;
import com.example.asset.interaction.ZoneInteractionParser;
import com.example.client.desktop.camera.Camera;
import com.example.client.desktop.capture.RenderDocCapture;
import com.example.client.desktop.capture.ScreenshotCapture;
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
import com.example.client.state.ClientConnectionState;
import com.example.client.state.ClientState;
import com.example.common.dto.PongMessage;
import com.example.common.protocol.MovementFlags;
import com.example.common.time.VanadielTime;
import com.example.common.time.WeatherType;
import com.example.world.dto.ZoneTransferNotification;
import com.example.zone.dto.PlayerMovementResponse;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.environment.WeatherPreset;
import imgui.ImGui;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.List;

/**
 * Production gameplay application state.
 * Orchestrates player input, network replication, UI overlays, and delegates
 * 3D rendering and scene hierarchy to {@link WorldScene}, {@link RemoteActorSynchronizer},
 * and {@link InGameHudOverlay}.
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
    private final InGameHudOverlay hudOverlay = new InGameHudOverlay();

    private boolean f1Pressed = false;
    private boolean f2Pressed = false;
    private boolean f3Pressed = false;
    private boolean f4Pressed = false;
    private boolean f11Pressed = false;
    private boolean f12Pressed = false;
    private boolean mKeyPressed = false;
    private boolean pendingScreenshotCapture = false;
    private boolean pendingRenderDocCapture = false;
    private List<ZoneInteraction> zoneInteractions = List.of();
    private ZoneInteraction activeZoneline = null;

    private boolean showVisualZone = true;
    private boolean showCharacterModels = true;
    private boolean showCollisionCylinders = false;
    private boolean wireframe = false;

    public WeatherType getCurrentWeather() {
        return hudOverlay.getCurrentWeather();
    }

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
            AuthenticCharacterInstance localVisual = worldScene.createCharacterInstance(
                    ffxiRoot,
                    clientState.getSelectedCharacterAppearance()
            );
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

        camera.update(handle, dt);

        boolean uiWantsKeyboard = pollHotkeys(handle, dt);
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
            long movementHandle = uiWantsKeyboard ? 0L : handle;
            var moveResult = movementHandler.pollAndCompute(
                    movementHandle,
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

        // Sync Scene Actors via RemoteActorSynchronizer + shared CharacterAssetCache
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        RemoteActorSynchronizer.syncActors(worldScene, clientState, ffxiRoot, moveSpeed, moveFlags, now);

        // Check Zoneline Intersections
        activeZoneline = null;
        for (ZoneInteraction zi : zoneInteractions) {
            if (zi.isZoneLine() && zi.containsHorizontal(clientState.getX(), clientState.getZ(), 3.0f)) {
                activeZoneline = zi;
                break;
            }
        }

        // Render 3D OpenGL Viewport & HUD overlays
        if (handle != 0L) {
            render3dViewportAndOverlays(handle, dt, now);
        }

        panel.updateKeepAlive(keepAlive.getStatus(), keepAlive.getLastRttMs());
        panel.render();
        radarPanel.render();
        debugLog.render();

        processPanelIntents();

        if (clientState.getConnectionState() == ClientConnectionState.DISCONNECTED) {
            stateService.changeState(unauthProvider::get);
        }
    }

    private boolean pollHotkeys(long handle, float dt) {
        if (handle == 0L) {
            return false;
        }
        boolean uiWantsKeyboard = false;
        try {
            var imguiCtx = ImGui.getCurrentContext();
            if (imguiCtx != null && imguiCtx.isValidPtr()) {
                uiWantsKeyboard = ImGui.getIO().getWantCaptureKeyboard();
            }
        } catch (Throwable ignored) {}

        boolean f1Down = !uiWantsKeyboard && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F1) == GLFW.GLFW_PRESS;
        if (f1Down && !f1Pressed) {
            wireframe = !wireframe;
        }
        f1Pressed = f1Down;

        boolean f2Down = !uiWantsKeyboard && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F2) == GLFW.GLFW_PRESS;
        if (f2Down && !f2Pressed) {
            showVisualZone = !showVisualZone;
        }
        f2Pressed = f2Down;

        boolean f3Down = !uiWantsKeyboard && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F3) == GLFW.GLFW_PRESS;
        if (f3Down && !f3Pressed) {
            showCharacterModels = !showCharacterModels;
        }
        f3Pressed = f3Down;

        boolean f4Down = !uiWantsKeyboard && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F4) == GLFW.GLFW_PRESS;
        if (f4Down && !f4Pressed) {
            showCollisionCylinders = !showCollisionCylinders;
        }
        f4Pressed = f4Down;

        boolean f11Down = !uiWantsKeyboard && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F11) == GLFW.GLFW_PRESS;
        if (f11Down && !f11Pressed) {
            pendingScreenshotCapture = true;
        }
        f11Pressed = f11Down;

        boolean f12Down = !uiWantsKeyboard && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F12) == GLFW.GLFW_PRESS;
        if (f12Down && !f12Pressed) {
            pendingRenderDocCapture = true;
        }
        f12Pressed = f12Down;

        float zoomRate = 50.0f;
        if (!uiWantsKeyboard && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_PAGE_UP) == GLFW.GLFW_PRESS) {
            radarPanel.getConfig().zoomSmooth(-zoomRate * dt);
        }
        if (!uiWantsKeyboard && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_PAGE_DOWN) == GLFW.GLFW_PRESS) {
            radarPanel.getConfig().zoomSmooth(zoomRate * dt);
        }

        boolean mDown = !uiWantsKeyboard && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_M) == GLFW.GLFW_PRESS;
        if (mDown && !mKeyPressed && inGameMapPanel != null) {
            inGameMapPanel.toggle();
        }
        mKeyPressed = mDown;
        return uiWantsKeyboard;
    }

    private void render3dViewportAndOverlays(long handle, float dt, long now) {
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetFramebufferSize(handle, width, height);
        int fbWidth = Math.max(1, width[0]);
        int fbHeight = Math.max(1, height[0]);

        VanadielTime vTime = hudOverlay.resolveCurrentTime();

        worldScene.setWeatherPreset(WeatherPreset.getById(hudOverlay.getCurrentWeather().getDatCode()));
        worldScene.setManualTimeOverride(hudOverlay.isManualTimeOverride());
        worldScene.setManualTimeHour(hudOverlay.getManualTimeHour());
        worldScene.setShowVisualZone(showVisualZone);
        worldScene.setShowCharacterModels(showCharacterModels);
        worldScene.setShowCollisionMesh(wireframe);
        worldScene.setShowCollisionMarkers(showCollisionCylinders);

        worldScene.getCamera().setTarget(camera.getTargetX(), camera.getTargetY(), camera.getTargetZ());
        worldScene.getCamera().setYaw(camera.getYaw());
        worldScene.getCamera().setPitch(camera.getPitch());
        worldScene.getCamera().setDistance(camera.getDistance());

        if (pendingRenderDocCapture) {
            pendingRenderDocCapture = false;
            triggerRenderDocCapture();
        }

        worldScene.update(dt, vTime);
        worldScene.render(fbWidth, fbHeight);

        float aspect = (float) fbWidth / (float) fbHeight;
        float[] proj = new float[16];
        float[] view = new float[16];
        worldScene.getCamera().getProjectionMatrix(aspect).get(proj);
        worldScene.getCamera().getViewMatrix().get(view);
        hudOverlay.renderNameplates(
                clientState,
                characterName,
                proj,
                view,
                fbWidth,
                fbHeight,
                now,
                worldScene,
                worldScene.getFontMojiGlRenderer(),
                activeZoneline
        );

        if (pendingScreenshotCapture) {
            pendingScreenshotCapture = false;
            captureViewportScreenshot();
        }

        screenFader.render(handle, fbWidth, fbHeight);

        hudOverlay.renderClockBanner(vTime, activeZoneline);
        if (inGameMapPanel != null) {
            inGameMapPanel.render(
                    currentZoneId,
                    clientState.getX(), clientState.getY(), clientState.getZ(), clientState.getYaw(),
                    zoneInteractions,
                    clientState.getRemoteEntities().values()
            );
        }
    }

    private void processPanelIntents() {
        if (panel.isPingRequested()) {
            debugLog.log("PING sent");
            keepAlive.sendPing();
        }
        if (panel.isMapToggleRequested() && inGameMapPanel != null) {
            inGameMapPanel.toggle();
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
        if (debugLog.isScreenshotRequested()) {
            pendingScreenshotCapture = true;
        }
        if (debugLog.isRenderDocCaptureRequested()) {
            pendingRenderDocCapture = true;
        }
        panel.clearIntents();
        debugLog.clearIntents();

        if (windowService.getHandle() == 0L) {
            if (pendingRenderDocCapture) {
                pendingRenderDocCapture = false;
                triggerRenderDocCapture();
            }
            if (pendingScreenshotCapture) {
                pendingScreenshotCapture = false;
                debugLog.log("Screenshot skipped (headless / no OpenGL viewport)");
            }
        }
    }

    private void captureViewportScreenshot() {
        try {
            Path saved = ScreenshotCapture.captureActiveFramebuffer(
                    "ingame_zone" + currentZoneId + "_" + System.currentTimeMillis()
            );
            debugLog.log("Screenshot saved -> " + saved.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to capture viewport screenshot: {}", e.getMessage(), e);
            debugLog.log("Screenshot capture failed: " + e.getMessage());
        }
    }

    private void triggerRenderDocCapture() {
        if (RenderDocCapture.triggerCapture()) {
            debugLog.log("RenderDoc frame capture triggered -> " + RenderDocCapture.getRenderDocDirectory().toAbsolutePath());
        } else {
            debugLog.log("RenderDoc not attached (launch under RenderDoc to capture .rdc)");
        }
    }

    @Override
    public void onExit() {
        pendingScreenshotCapture = false;
        pendingRenderDocCapture = false;
        keepAlive.stop();
        movementHandler.reset();
        clientState.resetZoneSession();

        worldScene.close();
        if (inGameMapPanel != null) {
            try { inGameMapPanel.close(); } catch (Exception ignored) {}
        }
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
        float preSimX = movementHandler.getSimX();
        float preSimY = movementHandler.getSimY();
        float preSimZ = movementHandler.getSimZ();
        float dx = preSimX - moveResp.x();
        float dz = preSimZ - moveResp.z();
        float dist = (float) Math.hypot(dx, dz);

        movementHandler.reconcile(moveResp.x(), moveResp.y(), moveResp.z(), moveResp.reconciled());
        clientState.setLastServerTick(moveResp.serverTick());

        if (moveResp.reconciled() || dist > 2.0f) {
            log.warn("Authoritative position reconciliation (dist={}): server=({}, {}, {}), sim=({}, {}, {})",
                    dist, moveResp.x(), moveResp.y(), moveResp.z(),
                    preSimX, preSimY, preSimZ);
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
