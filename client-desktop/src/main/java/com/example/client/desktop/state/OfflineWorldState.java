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
import com.example.asset.config.FfxiPathConfig;
import com.example.client.desktop.renderer.CollisionMeshRenderer;
import com.example.client.desktop.renderer.VisualZoneRenderer;
import com.example.client.desktop.util.MatrixMath;
import com.example.common.time.VanadielTime;
import com.example.common.time.WeatherType;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.renderer.ZoneRenderChunk;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImInt;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import com.ffxi.model.SkeletonAnimationResource;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.CharacterAnimator;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.model.ZoneModelLoader;
import com.ffxi.viewer.renderer.CharacterMeshBuffer;
import com.ffxi.viewer.renderer.CharacterShader;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import com.ffxi.viewer.renderer.ZoneMeshBuffer;
import com.ffxi.viewer.renderer.ZoneShader;
import com.ffxi.viewer.renderer.ZoneTextureLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    // Authentic FFXI Zone rendering
    private LoadedZone loadedZone;
    private ZoneShader zoneShader;
    private ZoneTextureLoader zoneTextureLoader;
    private final List<ZoneRenderChunk> zoneRenderChunks = new ArrayList<>();

    // Authentic FFXI Character rendering
    private LoadedCharacter playerCharacter;
    private CharacterShader authenticCharShader;
    private AuthenticCharacterInstance playerVisual;
    private AuthenticCharacterInstance npcVisual;
    private float playerYaw = 0.0f;

    private CollisionMap collisionMap;
    private CollisionMeshRenderer collisionRenderer;
    private VisualZoneRenderer visualRenderer;
    private VisualZoneScene visualScene;
    private NpcTable npcTable;
    private List<NpcInfo> townNpcs = List.of();

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
        try {
            collisionRenderer.init();
            visualRenderer.init();
        } catch (Exception e) {
            log.error("Failed to initialize OpenGL renderers", e);
        }

        // 1. Try loading authentic FFXI zone and character models from retail install
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        if (Files.isDirectory(ffxiRoot)) {
            try {
                log.info("Loading authentic FFXI zone 230 from retail directory: {}", ffxiRoot);
                this.loadedZone = ZoneModelLoader.loadZone(ffxiRoot, 230);
                this.zoneShader = new ZoneShader();
                this.zoneTextureLoader = new ZoneTextureLoader(loadedZone.textureSources());
                for (LoadedZone.ZoneChunkInstance chunkInstance : loadedZone.chunkInstances()) {
                    zoneRenderChunks.add(new ZoneRenderChunk(
                            new ZoneMeshBuffer(chunkInstance.chunk()),
                            new org.joml.Matrix4f(chunkInstance.modelMatrix()),
                            chunkInstance.invertCull(),
                            chunkInstance.discardThreshold()
                    ));
                }
                if (loadedZone.collisionSystem() != null && collisionRenderer != null) {
                    var collisionTris = loadedZone.collisionSystem().getAllWorldTriangles();
                    this.collisionTriangleCount = collisionTris.size();
                    collisionRenderer.uploadTransformedTriangles(collisionTris);
                    log.info("Uploaded {} authentic zone collision triangles from loadedZone", collisionTris.size());
                }
                log.info("Loaded authentic zone 230: {} chunk instances, {} textures",
                        zoneRenderChunks.size(), loadedZone.textureSources().size());
            } catch (Exception e) {
                log.warn("Could not load authentic ZoneModelLoader zone, falling back: {}", e.getMessage());
            }

            try {
                log.info("Loading authentic Hume Male character from retail directory: {}", ffxiRoot);
                this.playerCharacter = CharacterModelLoader.loadCharacter(ffxiRoot, RaceGenderConfig.HumeM);
                this.authenticCharShader = new CharacterShader();
                this.playerVisual = new AuthenticCharacterInstance(playerCharacter);
                log.info("Loaded authentic Hume Male character instance: {} meshes, {} textures",
                        playerCharacter.meshes().size(), playerCharacter.textures().size());
            } catch (Exception e) {
                log.warn("Could not load authentic CharacterModelLoader character: {}", e.getMessage());
            }

            try {
                log.info("Loading authentic Elvaan Male guard model for town NPCs from retail directory: {}", ffxiRoot);
                LoadedCharacter npcChar = CharacterModelLoader.loadCharacter(ffxiRoot, RaceGenderConfig.ElvaanM);
                this.npcVisual = new AuthenticCharacterInstance(npcChar);
                log.info("Loaded authentic Elvaan Male NPC instance");
            } catch (Exception e) {
                log.warn("Could not load authentic NPC character model: {}", e.getMessage());
            }
        }

        if (loadedZone == null) {
            loadZone(230); // South San d'Oria (collision & standalone fallback)
        } else {
            // Load Town NPCs
            byte[] npcBytes = loadNpcBytes();
            if (npcBytes != null) {
                try {
                    this.npcTable = NpcTable.load(npcBytes);
                    this.townNpcs = npcTable.getByZone(230);
                    if (collisionRenderer != null) {
                        collisionRenderer.uploadNpcMarkers(this.townNpcs);
                    }
                    log.info("Loaded {} town NPCs for zone 230", townNpcs.size());
                } catch (Exception e) {
                    log.warn("Failed to load town NPCs: {}", e.getMessage());
                }
            }
        }

        // Position player at ground level
        if (loadedZone != null && loadedZone.collisionSystem() != null) {
            float floorY = loadedZone.collisionSystem().findFloorY(playerX, playerZ, 2000.0f);
            if (Float.isFinite(floorY)) {
                playerY = floorY;
                lastFloorY = playerY;
            }
        } else if (collisionMap != null) {
            Optional<RayHit> hit = collisionMap.raycastFloor(new Vector3f(playerX, 15.0f, playerZ), 2.0f, 50.0f, true);
            if (hit.isPresent()) {
                playerY = hit.get().point().y();
                lastFloorY = playerY;
            }
        }
        camera.setTarget(playerX, playerY + 1.0f, playerZ);
        camera.setDistance(10.0f);
        camera.setPitch(25.0f);
        camera.setYaw(PolarCamera.DEFAULT_YAW);
    }

    private void loadZone(int zoneId) {
        byte[] bytes = loadZoneBytes(zoneId);
        if (bytes == null || bytes.length == 0) {
            log.warn("DAT asset for zone {} could not be found or loaded", zoneId);
            return;
        }
        try {
            // 1. Collision Mesh
            DatByteReader reader = new DatByteReader(bytes.clone());
            Optional<CollisionMap> mapOpt = ZoneDefParser.parseFromDat(reader);
            if (mapOpt.isPresent()) {
                this.collisionMap = mapOpt.get();
                List<CollisionTriangle> tris = collisionMap.allWorldSpaceTriangles();
                this.collisionTriangleCount = tris.size();
                collisionRenderer.upload(tris);
                log.info("Loaded zone {} collision mesh: {} triangles", zoneId, collisionTriangleCount);
            }

            // 2. Visual Textured Zone Scene
            this.visualScene = VisualZoneLoader.load(bytes.clone());
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

        GL11.glClearColor(0.08f, 0.10f, 0.14f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);

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

        // Render visual zone: prefer authentic ZoneShader pipeline from reference tools
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
        } else if (visualRenderer != null && showVisualZone) {
            visualRenderer.render(proj, view, wireframe, camera.getEyeX(), camera.getEyeY(), camera.getEyeZ());
        }

        // Render collision overlay if enabled
        if (collisionRenderer != null && showCollisionOverlay) {
            collisionRenderer.render(proj, view, wireframe);
        }

        // Render 3D animated character models for player and town NPCs
        if (showCharacterModels) {
            if (playerVisual != null && authenticCharShader != null) {
                playerVisual.render(authenticCharShader, pMat, vMat, playerX, playerY, playerZ, playerYaw);
            }

            if (showTownNpcs && townNpcs != null && !townNpcs.isEmpty() && npcVisual != null && authenticCharShader != null) {
                for (NpcInfo npc : townNpcs) {
                    float npcYaw = (float) Math.toDegrees(npc.rotationRadians());
                    npcVisual.render(authenticCharShader, pMat, vMat,
                            npc.position().x(), npc.position().y(), npc.position().z(), npcYaw);
                }
            }
        }

        // Render fallback/debug collision markers
        if (collisionRenderer != null) {
            if (showTownNpcs && !showCharacterModels) {
                collisionRenderer.renderNpcMarkers(proj, view);
            }
            if (!showCharacterModels) {
                collisionRenderer.renderPlayerMarker(playerX, playerY, playerZ, playerYaw, proj, view);
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

        // Compute input direction from WASD relative to camera orientation
        float inputForward = (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS ? 1.0f : 0.0f)
                - (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS ? 1.0f : 0.0f);
        float inputStrafe = (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS ? 1.0f : 0.0f)
                - (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS ? 1.0f : 0.0f);

        float moveX = camera.getForwardX() * inputForward + camera.getRightX() * inputStrafe;
        float moveZ = camera.getForwardZ() * inputForward + camera.getRightZ() * inputStrafe;

        float len = (float) Math.hypot(moveX, moveZ);
        float horizSpeed = (len > 1e-4f) ? moveSpeed : 0.0f;
        int moveFlags = (len > 1e-4f) ? 1 : 0;
        if (!onGround) {
            moveFlags |= 16;
        }
        if (playerVisual != null) {
            playerVisual.update(dt, moveFlags, horizSpeed);
        }
        if (npcVisual != null) {
            npcVisual.update(dt, 0, 0.0f);
        }

        if (len > 1e-4f) {
            float speed = moveSpeed * dt;
            float candidateX = playerX + (moveX / len) * speed;
            float candidateZ = playerZ + (moveZ / len) * speed;
            playerYaw = (float) Math.toDegrees(Math.atan2(moveX, -moveZ));
            if (playerYaw < 0.0f) {
                playerYaw += 360.0f;
            }

            // Test terrain collision & step height
            if (loadedZone != null && loadedZone.collisionSystem() != null) {
                float floorY = loadedZone.collisionSystem().findFloorY(candidateX, candidateZ, playerY + 5.0f);
                if (Float.isFinite(floorY)) {
                    float stepDiff = floorY - playerY;
                    if (stepDiff <= 1.2f) {
                        playerX = candidateX;
                        playerZ = candidateZ;
                        lastFloorY = floorY;
                        if (onGround) {
                            playerY = floorY;
                        }
                    }
                } else {
                    playerX = candidateX;
                    playerZ = candidateZ;
                }
            } else if (collisionMap != null) {
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
            if (loadedZone != null && loadedZone.collisionSystem() != null) {
                float floorY = loadedZone.collisionSystem().findFloorY(playerX, playerZ, playerY + 3.0f);
                if (Float.isFinite(floorY)) {
                    lastFloorY = floorY;
                    playerY = floorY;
                }
            } else if (collisionMap != null) {
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
        ImGui.textDisabled("• Arrow Keys / Q / E : Free camera orbit");
        ImGui.textDisabled("• Left / Right-Click Drag : Free camera orbit");
        ImGui.textDisabled("• PageUp / PageDown : Zoom in / out");
        ImGui.textDisabled("• Space : Jump / test gravity");
        ImGui.textDisabled("• F1: Wireframe | F2: Visual Zone | F3: Collision Mesh | F5: Toggle Model");

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
        if (zoneShader != null) {
            try { zoneShader.close(); } catch (Exception ignored) {}
            zoneShader = null;
        }
        if (zoneTextureLoader != null) {
            try { zoneTextureLoader.close(); } catch (Exception ignored) {}
            zoneTextureLoader = null;
        }
        for (ZoneRenderChunk chunk : zoneRenderChunks) {
            try { chunk.buffer().close(); } catch (Exception ignored) {}
        }
        zoneRenderChunks.clear();

        if (authenticCharShader != null) {
            try { authenticCharShader.close(); } catch (Exception ignored) {}
            authenticCharShader = null;
        }
        if (playerVisual != null) {
            try { playerVisual.close(); } catch (Exception ignored) {}
            playerVisual = null;
        }
        if (npcVisual != null) {
            try { npcVisual.close(); } catch (Exception ignored) {}
            npcVisual = null;
        }

        if (collisionRenderer != null) {
            collisionRenderer.cleanup();
            collisionRenderer = null;
        }
        if (visualRenderer != null) {
            visualRenderer.cleanup();
            visualRenderer = null;
        }
    }
}
