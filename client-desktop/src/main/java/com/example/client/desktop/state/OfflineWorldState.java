package com.example.client.desktop.state;

import com.example.asset.collision.CollisionMap;
import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.RayHit;
import com.example.asset.geometry.Vector3f;
import com.example.asset.parser.ZoneDefParser;
import com.example.asset.reader.DatByteReader;
import com.example.asset.mesh.VisualZoneLoader;
import com.example.asset.mesh.VisualZoneScene;
import com.example.asset.npc.NpcInfo;
import com.example.asset.npc.NpcTable;
import com.example.client.desktop.camera.PolarCamera;
import com.example.client.desktop.engine.services.state.ApplicationState;
import com.example.client.desktop.engine.services.state.ApplicationStateService;
import com.example.client.desktop.engine.services.window.WindowService;
import com.example.asset.skeleton.AnimationStateMachine;
import com.example.asset.skeleton.BlendedPose;
import com.example.asset.skeleton.CharacterMeshGenerator;
import com.example.asset.skeleton.CompositeCharacterModel;
import com.example.asset.skeleton.StandardAnimations;
import com.example.client.desktop.renderer.CharacterRenderer;
import com.example.client.desktop.renderer.CollisionMeshRenderer;
import com.example.client.desktop.renderer.VisualZoneRenderer;
import com.example.client.desktop.util.MatrixMath;
import com.example.common.protocol.JobId;
import com.example.common.time.VanadielTime;
import com.example.common.time.WeatherType;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImInt;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Interactive offline world simulation state.
 * Loads 3D zone collision geometry, authentic textured visual meshes, and town NPCs
 * directly from game DAT files, allowing real-time avatar movement, collision physics,
 * and camera control without requiring a running server.
 */
@Slf4j
@Prototype
@RequiredArgsConstructor
public class OfflineWorldState implements ApplicationState {

    private final WindowService windowService;
    private final PolarCamera camera;
    private final ApplicationStateService stateService;
    private final BeanProvider<UnauthenticatedState> unauthProvider;

    private CollisionMap collisionMap;
    private CollisionMeshRenderer collisionRenderer;
    private VisualZoneRenderer visualRenderer;
    private CharacterRenderer characterRenderer;
    private VisualZoneScene visualScene;
    private NpcTable npcTable;
    private List<NpcInfo> townNpcs = List.of();

    private final AnimationStateMachine playerAnimation = StandardAnimations.createLocomotionStateMachine();
    private final AnimationStateMachine npcAnimation = StandardAnimations.createLocomotionStateMachine();

    private float playerX = -50.0f;
    private float playerY = 2.0f;
    private float playerZ = -30.0f;
    private float velocityY = 0.0f;
    private boolean onGround = true;

    private float moveSpeed = 14.0f;
    private float gravity = -22.0f;
    private float jumpStrength = 8.5f;

    private boolean showVisualZone = true;
    private boolean showCollisionOverlay = false;
    private boolean showTownNpcs = true;
    private boolean showCharacterModels = true;
    private boolean wireframe = false;
    private int collisionTriangleCount = 0;
    private float lastFloorY = 2.0f;

    private WeatherType currentWeather = WeatherType.CLEAR;
    private boolean manualTimeOverride = false;
    private float manualTimeHour = 12.0f;

    @Override
    public void onEnter() {
        log.info("Entering OfflineWorldState — initializing 3D visual, collision, and character renderers");
        collisionRenderer = new CollisionMeshRenderer();
        visualRenderer = new VisualZoneRenderer();
        characterRenderer = new CharacterRenderer();
        try {
            collisionRenderer.init();
            visualRenderer.init();
            characterRenderer.init();
        } catch (Exception e) {
            log.error("Failed to initialize OpenGL renderers", e);
        }

        loadZone(230); // South San d'Oria

        // Position player at ground level
        if (collisionMap != null) {
            Optional<RayHit> hit = collisionMap.raycastFloor(new Vector3f(playerX, 15.0f, playerZ), 2.0f, 50.0f, true);
            if (hit.isPresent()) {
                playerY = hit.get().point().y();
                lastFloorY = playerY;
            }
        }
        camera.setTarget(playerX, playerY + 1.0f, playerZ);
        camera.setDistance(10.0f);
        camera.setPitch(25.0f);
    }

    private void loadZone(int zoneId) {
        byte[] bytes = loadZoneBytes(zoneId);
        if (bytes == null || bytes.length == 0) {
            log.warn("DAT asset for zone {} could not be found or loaded", zoneId);
            return;
        }
        try {
            // 1. Collision Mesh
            DatByteReader reader = new DatByteReader(bytes);
            Optional<CollisionMap> mapOpt = ZoneDefParser.parseFromDat(reader);
            if (mapOpt.isPresent()) {
                this.collisionMap = mapOpt.get();
                List<CollisionTriangle> tris = collisionMap.allWorldSpaceTriangles();
                this.collisionTriangleCount = tris.size();
                collisionRenderer.upload(tris);
                log.info("Loaded zone {} collision mesh: {} triangles", zoneId, collisionTriangleCount);
            }

            // 2. Visual Textured Zone Scene
            this.visualScene = VisualZoneLoader.load(bytes);
            visualRenderer.upload(this.visualScene);
            log.info("Loaded zone {} visual meshes: {} meshes, {} textures, {} visual triangles",
                    zoneId, visualScene.totalMeshCount(), visualScene.textures().size(), visualScene.totalTriangleCount());

            // 3. Town NPCs
            byte[] npcBytes = loadNpcBytes();
            if (npcBytes != null) {
                this.npcTable = NpcTable.load(npcBytes);
                this.townNpcs = npcTable.getByZone(zoneId);
                collisionRenderer.uploadNpcMarkers(this.townNpcs);
                log.info("Loaded {} town NPCs for zone {}", townNpcs.size(), zoneId);
            }
        } catch (Exception e) {
            log.error("Error loading zone DAT for offline exploration", e);
        }
    }

    byte[] loadNpcBytes() {
        String resourcePath = "/dats/tables/landsandboat/NpcTable-V3.DAT";
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream != null) {
                return stream.readAllBytes();
            }
        } catch (Exception ignored) {}

        List<Path> candidatePaths = List.of(
                Path.of("game-asset-pipeline/src/test/resources/dats/tables/landsandboat/NpcTable-V3.DAT"),
                Path.of("../game-asset-pipeline/src/test/resources/dats/tables/landsandboat/NpcTable-V3.DAT"),
                Path.of("src/test/resources/dats/tables/landsandboat/NpcTable-V3.DAT")
        );

        for (Path p : candidatePaths) {
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readAllBytes(p);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    byte[] loadZoneBytes(int zoneId) {
        String resourcePath = "/dats/zones/south_san_doria_230.DAT";
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream != null) {
                return stream.readAllBytes();
            }
        } catch (Exception ignored) {}

        List<Path> candidatePaths = List.of(
                Path.of("game-asset-pipeline/src/test/resources/dats/zones/south_san_doria_230.DAT"),
                Path.of("../game-asset-pipeline/src/test/resources/dats/zones/south_san_doria_230.DAT"),
                Path.of("src/test/resources/dats/zones/south_san_doria_230.DAT"),
                Path.of(System.getProperty("c2.dat.path", "game-asset-pipeline/src/test/resources/dats/zones/south_san_doria_230.DAT"))
        );

        for (Path p : candidatePaths) {
            if (Files.isRegularFile(p)) {
                try {
                    log.info("Loading zone DAT from file: {}", p.toAbsolutePath());
                    return Files.readAllBytes(p);
                } catch (Exception e) {
                    log.warn("Failed reading DAT at {}", p, e);
                }
            }
        }
        return null;
    }

    @Override
    public void onUpdate(float dt) {
        long handle = windowService.getHandle();

        // 1. Process Input & Camera
        camera.update(handle, dt);

        handleMovement(handle, dt);
        camera.setTarget(playerX, playerY + 1.0f, playerZ);

        if (handle == 0L) {
            return;
        }

        // 2. Render 3D Scene
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetFramebufferSize(handle, width, height);
        int fbWidth = Math.max(1, width[0]);
        int fbHeight = Math.max(1, height[0]);
        GL11.glViewport(0, 0, fbWidth, fbHeight);

        VanadielTime vTime = manualTimeOverride
                ? VanadielTime.ofHour(manualTimeHour)
                : VanadielTime.now();
        com.example.asset.environment.DiurnalLighting lighting = com.example.asset.environment.DiurnalLighting.compute(vTime, currentWeather);
        float[] sky = lighting.getSkyColor();

        GL11.glClearColor(sky[0], sky[1], sky[2], 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        float aspect = (float) fbWidth / (float) fbHeight;
        float[] proj = MatrixMath.perspective(60.0f, aspect, 0.2f, 2000.0f);
        float[] view = MatrixMath.lookAt(
                camera.getEyeX(), camera.getEyeY(), camera.getEyeZ(),
                camera.getTargetX(), camera.getTargetY(), camera.getTargetZ(),
                0f, 1f, 0f
        );

        // Render visual zone if enabled
        if (visualRenderer != null && showVisualZone) {
            visualRenderer.render(proj, view, wireframe, lighting, camera.getEyeX(), camera.getEyeY(), camera.getEyeZ());
        }

        // Render collision overlay if enabled
        if (collisionRenderer != null && showCollisionOverlay) {
            collisionRenderer.render(proj, view, wireframe);
        }

        // Render 3D animated character models for player and town NPCs
        if (characterRenderer != null && showCharacterModels) {
            CompositeCharacterModel playerModel = characterRenderer.getOrCreateModel(JobId.WARRIOR);
            if (playerModel != null) {
                BlendedPose playerPose = playerAnimation.evaluate(playerModel.skeleton());
                characterRenderer.render(JobId.WARRIOR, playerPose.jointPoseMatrices(),
                        playerX, playerY, playerZ, camera.getYaw(),
                        proj, view, lighting, camera.getEyeX(), camera.getEyeY(), camera.getEyeZ());
            }

            if (showTownNpcs && townNpcs != null && !townNpcs.isEmpty()) {
                CompositeCharacterModel npcModel = characterRenderer.getOrCreateModel(JobId.NPC);
                if (npcModel != null) {
                    BlendedPose npcPose = npcAnimation.evaluate(npcModel.skeleton());
                    for (NpcInfo npc : townNpcs) {
                        float npcYaw = (float) Math.toDegrees(npc.rotationRadians());
                        characterRenderer.render(JobId.NPC, npcPose.jointPoseMatrices(),
                                npc.position().x(), npc.position().y(), npc.position().z(), npcYaw,
                                proj, view, lighting, camera.getEyeX(), camera.getEyeY(), camera.getEyeZ());
                    }
                }
            }
        }

        // Render fallback/debug collision markers
        if (collisionRenderer != null) {
            if (showTownNpcs && (!showCharacterModels || characterRenderer == null)) {
                collisionRenderer.renderNpcMarkers(proj, view);
            }
            if (!showCharacterModels) {
                collisionRenderer.renderPlayerMarker(playerX, playerY, playerZ, camera.getYaw(), proj, view);
            }
        }

        // 3. Render ImGui HUD
        renderImGuiHud(dt);
    }

    private void handleMovement(long handle, float dt) {
        if (handle == 0L) {
            return;
        }

        // Toggle wireframe on F1
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F1) == GLFW.GLFW_PRESS) {
            wireframe = !wireframe;
        }
        // Toggle visual zone on F2
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F2) == GLFW.GLFW_PRESS) {
            showVisualZone = !showVisualZone;
        }
        // Toggle collision overlay on F3
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F3) == GLFW.GLFW_PRESS) {
            showCollisionOverlay = !showCollisionOverlay;
        }
        // Toggle town NPCs on F4
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F4) == GLFW.GLFW_PRESS) {
            showTownNpcs = !showTownNpcs;
        }
        // Toggle 3D character models on F5
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F5) == GLFW.GLFW_PRESS) {
            showCharacterModels = !showCharacterModels;
        }

        // Compute input direction from WASD / Arrow keys
        float moveX = 0.0f;
        float moveZ = 0.0f;

        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS) {
            moveX += camera.getForwardX();
            moveZ += camera.getForwardZ();
        }
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS) {
            moveX -= camera.getForwardX();
            moveZ -= camera.getForwardZ();
        }
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS) {
            moveX += camera.getRightX();
            moveZ += camera.getRightZ();
        }
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS) {
            moveX -= camera.getRightX();
            moveZ -= camera.getRightZ();
        }

        float len = (float) Math.hypot(moveX, moveZ);
        float horizSpeed = (len > 1e-4f) ? moveSpeed : 0.0f;
        int moveFlags = (len > 1e-4f) ? 1 : 0;
        if (!onGround) {
            moveFlags |= 16;
        }
        playerAnimation.update(dt, moveFlags, horizSpeed);
        npcAnimation.update(dt, 0, 0.0f);

        if (len > 1e-4f) {
            moveX = (moveX / len) * moveSpeed * dt;
            moveZ = (moveZ / len) * moveSpeed * dt;

            float candidateX = playerX + moveX;
            float candidateZ = playerZ + moveZ;

            // Test terrain collision & step height
            if (collisionMap != null) {
                Optional<RayHit> floorHit = collisionMap.raycastFloor(
                        new Vector3f(candidateX, playerY + 2.0f, candidateZ),
                        2.0f,
                        50.0f,
                        true
                );

                if (floorHit.isPresent()) {
                    float newFloorY = floorHit.get().point().y();
                    float stepDiff = newFloorY - playerY;

                    // Allow climbing steps / slopes up to 1.2m
                    if (stepDiff <= 1.2f) {
                        playerX = candidateX;
                        playerZ = candidateZ;
                        lastFloorY = newFloorY;
                        if (onGround) {
                            playerY = newFloorY;
                        }
                    }
                } else {
                    // Fallback to moving on candidate coords
                    playerX = candidateX;
                    playerZ = candidateZ;
                }
            } else {
                playerX = candidateX;
                playerZ = candidateZ;
            }
        }

        // Jump
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS && onGround) {
            velocityY = jumpStrength;
            onGround = false;
        }

        // Apply Gravity
        if (!onGround) {
            velocityY += gravity * dt;
            playerY += velocityY * dt;

            if (playerY <= lastFloorY) {
                playerY = lastFloorY;
                velocityY = 0.0f;
                onGround = true;
            }
        } else {
            // Ground clamp check when stationary
            if (collisionMap != null) {
                Optional<RayHit> floorHit = collisionMap.raycastFloor(
                        new Vector3f(playerX, playerY + 2.0f, playerZ),
                        2.0f,
                        50.0f,
                        true
                );
                if (floorHit.isPresent()) {
                    lastFloorY = floorHit.get().point().y();
                    playerY = lastFloorY;
                }
            }
        }
    }

    private void renderImGuiHud(float dt) {
        ImGui.setNextWindowPos(20, 20, ImGuiCond.Once);
        ImGui.setNextWindowSize(460, 420, ImGuiCond.Once);
        ImGui.begin("Offline 3D World Explorer");

        ImGui.textColored(0.2f, 0.9f, 0.4f, 1.0f, "Zone: South San d'Oria (Zone 230)");
        ImGui.separator();

        ImGui.text(String.format("Coordinates: X=%.2f, Y=%.2f, Z=%.2f", playerX, playerY, playerZ));
        ImGui.text(String.format("Ground Height: %.2f (Contact: %s)", lastFloorY, onGround ? "YES" : "AIR"));
        ImGui.text(String.format("Camera Yaw: %.1f deg, Pitch: %.1f deg", camera.getYaw(), camera.getPitch()));
        if (visualRenderer != null && visualScene != null) {
            ImGui.text(String.format("Visual Meshes: %d (%d textures, %,d tris)",
                    visualScene.totalMeshCount(), visualScene.textures().size(), visualRenderer.totalTrianglesUploaded()));
        }
        ImGui.text(String.format("Collision Triangles: %,d", collisionTriangleCount));
        ImGui.text(String.format("Town NPCs: %,d (NpcTable-V3)", townNpcs.size()));
        ImGui.text(String.format("Framerate: %.1f FPS", 1.0f / Math.max(dt, 0.0001f)));
        ImGui.separator();

        // Environment & Weather Controls
        ImGui.textColored(1.0f, 0.85f, 0.35f, 1.0f, "Environment & Atmosphere");
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
            if (ImGui.button("Night (00:00)")) manualTimeHour = 0.0f;
        }
        ImGui.separator();

        if (ImGui.checkbox("Visual Zone (F2)", showVisualZone)) {
            showVisualZone = !showVisualZone;
        }
        ImGui.sameLine();
        if (ImGui.checkbox("Collision (F3)", showCollisionOverlay)) {
            showCollisionOverlay = !showCollisionOverlay;
        }
        ImGui.sameLine();
        if (ImGui.checkbox("NPCs (F4)", showTownNpcs)) {
            showTownNpcs = !showTownNpcs;
        }
        ImGui.sameLine();
        if (ImGui.checkbox("3D Chars (F5)", showCharacterModels)) {
            showCharacterModels = !showCharacterModels;
        }
        if (ImGui.checkbox("Wireframe Mode (F1)", wireframe)) {
            wireframe = !wireframe;
        }

        // Nearest NPC proximity info
        if (!townNpcs.isEmpty()) {
            NpcInfo nearest = null;
            float nearestDistSq = Float.MAX_VALUE;
            for (NpcInfo npc : townNpcs) {
                float dx = npc.position().x() - playerX;
                float dy = npc.position().y() - playerY;
                float dz = npc.position().z() - playerZ;
                float dSq = dx * dx + dy * dy + dz * dz;
                if (dSq < nearestDistSq) {
                    nearestDistSq = dSq;
                    nearest = npc;
                }
            }
            if (nearest != null) {
                ImGui.textColored(0.95f, 0.78f, 0.22f, 1.0f,
                        String.format("Nearest NPC #%d: dist=%.1fm, modelId=0x%X",
                                nearest.id(), (float) Math.sqrt(nearestDistSq), nearest.modelId()));
            }
        }

        float[] speedArr = new float[]{moveSpeed};
        if (ImGui.sliderFloat("Move Speed", speedArr, 2.0f, 40.0f)) {
            moveSpeed = speedArr[0];
        }

        float[] jumpArr = new float[]{jumpStrength};
        if (ImGui.sliderFloat("Jump Force", jumpArr, 3.0f, 20.0f)) {
            jumpStrength = jumpArr[0];
        }

        ImGui.separator();
        ImGui.textDisabled("Controls:");
        ImGui.textDisabled("• W / A / S / D : Move player avatar");
        ImGui.textDisabled("• Space : Jump / test gravity");
        ImGui.textDisabled("• Right-Click Drag : Orbit camera");
        ImGui.textDisabled("• Mouse Wheel : Zoom in / out");
        ImGui.textDisabled("• F1: Wireframe | F2: Visual Zone | F3: Collision Mesh");

        ImGui.separator();
        if (ImGui.button("Reset Spawn Position")) {
            playerX = -50.0f;
            playerZ = -30.0f;
            if (collisionMap != null) {
                Optional<RayHit> hit = collisionMap.raycastFloor(new Vector3f(playerX, 15.0f, playerZ), 2.0f, 50.0f, true);
                hit.ifPresent(rayHit -> playerY = rayHit.point().y());
            }
        }

        ImGui.sameLine();
        if (ImGui.button("Exit to Login")) {
            stateService.changeState(unauthProvider::get);
        }

        ImGui.end();
    }

    @Override
    public void onExit() {
        log.info("Exiting OfflineWorldState — cleaning up 3D renderers");
        if (collisionRenderer != null) {
            collisionRenderer.cleanup();
            collisionRenderer = null;
        }
        if (visualRenderer != null) {
            visualRenderer.cleanup();
            visualRenderer = null;
        }
        if (characterRenderer != null) {
            characterRenderer.cleanup();
            characterRenderer = null;
        }
    }
}
