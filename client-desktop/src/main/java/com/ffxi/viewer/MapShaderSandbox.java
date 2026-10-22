package com.ffxi.viewer;

import com.ffxi.viewer.environment.AuthenticSkyDome;
import com.ffxi.viewer.environment.WeatherParticleSystem;
import com.ffxi.viewer.environment.WeatherPreset;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.model.ZoneModelLoader;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import com.ffxi.viewer.renderer.ZoneMeshBuffer;
import com.ffxi.viewer.renderer.ZoneTextureLoader;
import com.ffxi.viewer.shader.NormalMapManager;
import com.ffxi.viewer.shader.SandboxMapShader;
import com.ffxi.viewer.shader.ShadowMapRenderer;
import com.ffxi.viewer.shader.SsaoRenderer;
import imgui.ImGui;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Interactive Map & Shader Sandbox for FFXI Zone Geometry.
 * Provides a dedicated experimental lab for:
 * - Authentic 8-slice hemispherical skybox dome & diurnal day/night cycles
 * - 20 authentic retail weather presets with elemental environment effects
 * - Directional sun/moon shadow mapping with percentage closer filtering (PCF)
 * - Tangent-space bump / normal mapping
 * - Screen-space ambient occlusion (SSAO)
 * - Interactive ImGui inspector with real-time controls, parameter tuning, and debug views
 */
public final class MapShaderSandbox {

    private static final int WINDOW_WIDTH = 1600;
    private static final int WINDOW_HEIGHT = 900;
    private static final String WINDOW_TITLE = "FFXI Map & Shader Sandbox [Elemental Weather, Shadows, Bump-Maps & SSAO]";
    private static final int DEFAULT_ZONE_ID = 230; // South San d'Oria reference zone

    private long window;
    private int screenWidth = WINDOW_WIDTH;
    private int screenHeight = WINDOW_HEIGHT;

    // Navigation & Camera
    private boolean flyCamera = false;
    private boolean mouseDragging = false;
    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;

    // Orbit Camera
    private float orbitYaw = 1.5707963f;
    private float orbitPitch = -0.45f;
    private float orbitDistance = 1200.0f;
    private float targetX = 0.0f;
    private float targetY = 0.0f;
    private float targetZ = 0.0f;

    // Fly Camera
    private final Vector3f flyPos = new Vector3f(0.0f, 25.0f, 0.0f);
    private float flyYaw = 0.0f;
    private float flyPitch = 0.0f;

    // Keyboard state
    private final boolean[] keyPressed = new boolean[512];

    // Pipeline Subsystems
    private SandboxMapShader mapShader;
    private ShadowMapRenderer shadowRenderer;
    private SsaoRenderer ssaoRenderer;
    private NormalMapManager normalMapManager;
    private AuthenticSkyDome skyDome;
    private WeatherParticleSystem particleSystem;
    private ZoneTextureLoader zoneTextureLoader;

    // Zone Data
    private Path ffxiRoot;
    private int currentZoneId = DEFAULT_ZONE_ID;
    private String currentZoneLabel = "230 - South San d'Oria";
    private final List<ZoneRenderChunk> zoneRenderChunks = new ArrayList<>();
    private List<ZoneModelLoader.ZoneSelectionOption> zoneOptions = List.of();
    private boolean isLoading = false;
    private String loadingStatus = "Ready";
    private int totalTriangles = 0;

    // Environment & Weather state
    private WeatherPreset currentWeather = WeatherPreset.FINE;
    private float vanaTimeHours = 12.0f; // Noon default
    private float timeMultiplier = 0.0f; // Paused by default for inspection
    private boolean enableSkyDome = true;
    private boolean enableWeatherParticles = true;

    // Lighting parameters
    private boolean lockToSunClock = true;
    private final Vector3f customSunDir = new Vector3f(0.3f, 0.9f, 0.25f);
    private float diffuseMultiplier = 1.0f;
    private boolean enableDistanceFog = true;

    // Shadow parameters
    private boolean enableShadows = true;
    private float shadowIntensity = 0.65f;
    private float shadowBoxSize = 1400.0f;
    private float shadowBiasMin = 0.0005f;
    private float shadowBiasMax = 0.0035f;

    // Bump / Normal mapping parameters
    private boolean enableNormalMapping = true;
    private float normalStrength = 1.25f;

    // SSAO parameters
    private boolean enableSsao = true;
    private float ssaoRadius = 3.5f;
    private float ssaoBias = 0.025f;
    private float ssaoPower = 1.6f;
    private boolean enableSsaoBlur = true;

    // View & Debug modes
    // 0 = Full Lit, 1 = Diffuse Only, 2 = Normals Only, 3 = Shadows Only, 4 = SSAO Only, 5 = Lighting Only
    private int debugViewMode = 0;
    private boolean wireframe = false;

    public static void main(String[] args) throws Exception {
        Path ffxiRoot = Path.of(args.length > 0 && !args[0].startsWith("-") ? args[0] : App.DEFAULT_FFXI_PATH);
        int zoneId = DEFAULT_ZONE_ID;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--zone") && i + 1 < args.length) {
                zoneId = Integer.parseInt(args[i + 1]);
                i++;
            }
        }

        MapShaderSandbox sandbox = new MapShaderSandbox();
        sandbox.ffxiRoot = ffxiRoot;
        sandbox.currentZoneId = zoneId;
        sandbox.run();
    }

    public void run() {
        initWindow();

        ImGui.createContext();
        ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
        ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();
        imGuiGlfw.init(window, true);
        imGuiGl3.init("#version 330");

        // Initialize Render Pipeline Subsystems
        mapShader = new SandboxMapShader();
        shadowRenderer = new ShadowMapRenderer();
        ssaoRenderer = new SsaoRenderer(screenWidth, screenHeight);
        normalMapManager = new NormalMapManager();
        skyDome = new AuthenticSkyDome(2500.0f, 24);
        particleSystem = new WeatherParticleSystem();
        zoneTextureLoader = new ZoneTextureLoader(java.util.Map.of());

        skyDome.updateMesh(currentWeather);

        loadZone(currentZoneId);

        double lastTime = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            double currentTime = glfwGetTime();
            float dt = (float) Math.min(currentTime - lastTime, 0.1);
            lastTime = currentTime;

            glfwPollEvents();

            handleInput(dt);

            // Update Time-of-day clock
            if (timeMultiplier > 0.0f) {
                vanaTimeHours += (dt * timeMultiplier / 60.0f);
                if (vanaTimeHours >= 24.0f) vanaTimeHours -= 24.0f;
            }

            // Compute celestial celestial sun/moon vectors
            float sunAngle = (float) ((vanaTimeHours / 24.0f) * 2.0 * Math.PI);
            Vector3f sunDir;
            if (lockToSunClock) {
                sunDir = new Vector3f((float) Math.sin(sunAngle), (float) Math.cos(sunAngle), 0.25f).normalize();
            } else {
                sunDir = new Vector3f(customSunDir).normalize();
            }
            Vector3f moonDir = new Vector3f(-sunDir.x, -sunDir.y, -0.25f).normalize();

            // Compute camera transforms
            Matrix4f projMatrix = createProjectionMatrix();
            Matrix4f viewMatrix = createViewMatrix();
            Vector3f camPos = getCameraPosition();

            // Update particles
            particleSystem.update(dt, camPos, currentWeather);

            // Light space matrix for shadow mapping
            Matrix4f lightSpaceMatrix = shadowRenderer.computeLightSpaceMatrix(
                    sunDir.y > 0.0f ? sunDir : moonDir,
                    flyCamera ? flyPos : new Vector3f(targetX, targetY, targetZ),
                    shadowBoxSize, 10.0f, shadowBoxSize * 2.8f
            );

            // PASS 1: Directional Shadow Pass
            if (enableShadows && debugViewMode != 1) {
                shadowRenderer.beginShadowPass();
                for (ZoneRenderChunk chunk : zoneRenderChunks) {
                    shadowRenderer.setModelMatrix(chunk.modelMatrix(), chunk.discardThreshold());
                    chunk.buffer().draw();
                }
                shadowRenderer.endShadowPass(screenWidth, screenHeight);
            }

            // PASS 2 & 3: SSAO Geometry & Blur Passes
            if (enableSsao && debugViewMode != 1) {
                ssaoRenderer.resize(screenWidth, screenHeight);
                ssaoRenderer.beginGeometryPass();
                // Render view normals for SSAO
                for (ZoneRenderChunk chunk : zoneRenderChunks) {
                    chunk.buffer().draw();
                }
                ssaoRenderer.computeSsao(projMatrix, ssaoRadius, ssaoBias, ssaoPower, enableSsaoBlur);
            }

            // PASS 4: Clear Screen & Render Authentic Skybox Dome Backdrop
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, screenWidth, screenHeight);
            Vector4f clearCol = currentWeather.clearColor();
            glClearColor(clearCol.x, clearCol.y, clearCol.z, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            if (enableSkyDome && debugViewMode == 0) {
                float skyBrightness = Math.max(0.20f, (float) Math.sin(Math.max(0.0f, sunAngle)));
                if (particleSystem.isLightningActive()) {
                    skyBrightness += particleSystem.getLightningIntensity() * 0.7f;
                }
                skyDome.render(projMatrix, viewMatrix, camPos, skyBrightness);
            }

            // PASS 5: Render Main Map Geometry with Unified Shader
            renderMapGeometry(projMatrix, viewMatrix, lightSpaceMatrix, camPos, sunDir, moonDir);

            // PASS 6: Render Elemental Weather Particles (Rain, Snow, Blizzard, Sandstorm)
            if (enableWeatherParticles && debugViewMode == 0) {
                particleSystem.render(projMatrix, viewMatrix, currentWeather);
            }

            // PASS 7: Render ImGui Inspector Lab
            imGuiGlfw.newFrame();
            ImGui.newFrame();

            renderImGuiControls(dt);

            ImGui.render();
            imGuiGl3.renderDrawData(ImGui.getDrawData());

            glfwSwapBuffers(window);
        }

        // Cleanup
        imGuiGl3.dispose();
        imGuiGlfw.dispose();
        ImGui.destroyContext();

        for (ZoneRenderChunk chunk : zoneRenderChunks) {
            chunk.buffer().close();
        }
        zoneRenderChunks.clear();

        mapShader.close();
        shadowRenderer.close();
        ssaoRenderer.close();
        normalMapManager.close();
        skyDome.close();
        particleSystem.close();

        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private void renderMapGeometry(Matrix4f proj, Matrix4f view, Matrix4f lightSpace,
                                   Vector3f camPos, Vector3f sunDir, Vector3f moonDir) {
        if (zoneRenderChunks.isEmpty()) {
            return;
        }

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);

        if (wireframe) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        } else {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }

        mapShader.bind();

        // Bind global textures: Shadow Map (Unit 2) and SSAO (Unit 3)
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, shadowRenderer.getDepthTextureId());

        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, ssaoRenderer.getFinalSsaoTextureId(enableSsaoBlur));

        // Setup atmospheric lighting colors with lightning flash spikes
        Vector3f sunCol = new Vector3f(currentWeather.sunColor());
        Vector3f ambientCol = new Vector3f(currentWeather.ambientColor());
        if (particleSystem.isLightningActive()) {
            float flash = particleSystem.getLightningIntensity();
            sunCol.add(flash * 1.5f, flash * 1.4f, flash * 2.0f);
            ambientCol.add(flash * 0.8f, flash * 0.7f, flash * 1.1f);
        }

        mapShader.setLighting(sunDir, sunCol, moonDir, currentWeather.moonColor(), ambientCol, diffuseMultiplier);
        mapShader.setCameraAndScreen(camPos, screenWidth, screenHeight, 0.375f);
        mapShader.setFeatureControls(
                enableNormalMapping, normalStrength,
                enableShadows, shadowIntensity, shadowBiasMin, shadowBiasMax,
                enableSsao, 1.0f, debugViewMode
        );
        mapShader.setFog(enableDistanceFog, currentWeather.fogNear(), currentWeather.fogFar(), currentWeather.fogColor());

        // Render in 2 passes: Opaque then Blended
        for (boolean blendedPass : List.of(false, true)) {
            if (blendedPass) {
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glDepthMask(false);
            } else {
                glDisable(GL_BLEND);
                glDepthMask(true);
            }

            for (ZoneRenderChunk chunk : zoneRenderChunks) {
                if (chunk.buffer().renderBlend() != blendedPass) {
                    continue;
                }

                mapShader.setTransforms(proj, view, chunk.modelMatrix(), lightSpace);

                // Bind Diffuse Texture (Unit 0)
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, zoneTextureLoader.textureId(chunk.buffer().textureName()));

                // Bind Normal Map (Unit 1)
                glActiveTexture(GL_TEXTURE1);
                int normalTexId = normalMapManager.getFlatNormalTextureId();
                if (enableNormalMapping) {
                    var src = zoneTextureLoader.getTextureSource(chunk.buffer().textureName());
                    normalTexId = normalMapManager.getOrGenerateNormalMap(chunk.buffer().textureName(), src, normalStrength);
                }
                glBindTexture(GL_TEXTURE_2D, normalTexId);

                // Face culling
                if (chunk.invertCull()) {
                    glDisable(GL_CULL_FACE);
                } else {
                    glEnable(GL_CULL_FACE);
                    glFrontFace(chunk.modelMatrix().determinant() < 0.0f ? GL_CW : GL_CCW);
                }

                chunk.buffer().draw();
            }
        }

        glDepthMask(true);
        glDisable(GL_BLEND);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        mapShader.unbind();
    }

    private void renderImGuiControls(float dt) {
        ImGui.setNextWindowPos(20, 20, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(540, 780, imgui.flag.ImGuiCond.FirstUseEver);

        if (ImGui.begin("Shader Sandbox Lab")) {
            ImGui.textColored(0.4f, 0.85f, 1.0f, 1.0f, "FFXI Map & Shader Sandbox");
            ImGui.text(String.format(Locale.ROOT, "FPS: %.1f | Chunks: %d | Tris: %,d", ImGui.getIO().getFramerate(), zoneRenderChunks.size(), totalTriangles));
            ImGui.separator();

            if (ImGui.beginTabBar("SandboxTabs")) {
                // TAB 1: Weather & Atmosphere
                if (ImGui.beginTabItem("Weather & Sky")) {
                    ImGui.textColored(1.0f, 0.8f, 0.4f, 1.0f, "Elemental Weather System");

                    String[] weatherNames = WeatherPreset.ALL.stream().map(WeatherPreset::displayName).toArray(String[]::new);
                    int currentIdx = WeatherPreset.ALL.indexOf(currentWeather);
                    ImInt selWeather = new ImInt(currentIdx);
                    if (ImGui.combo("Weather Preset", selWeather, weatherNames)) {
                        currentWeather = WeatherPreset.ALL.get(selWeather.get());
                        skyDome.updateMesh(currentWeather);
                    }

                    ImGui.textDisabled("Elemental Affinity: " + currentWeather.element() + " | Effect: " + currentWeather.effectType());

                    ImGui.separator();
                    ImGui.textColored(1.0f, 0.8f, 0.4f, 1.0f, "Diurnal Day/Night Cycle");
                    float[] hourArr = { vanaTimeHours };
                    if (ImGui.sliderFloat("Vana'diel Hour", hourArr, 0.0f, 24.0f, "%.1f hrs")) {
                        vanaTimeHours = hourArr[0];
                    }

                    float[] speedArr = { timeMultiplier };
                    if (ImGui.sliderFloat("Time Multiplier", speedArr, 0.0f, 120.0f, "%.0fx")) {
                        timeMultiplier = speedArr[0];
                    }
                    if (ImGui.button("Pause (0x)")) timeMultiplier = 0.0f;
                    ImGui.sameLine();
                    if (ImGui.button("Realtime (1x)")) timeMultiplier = 1.0f;
                    ImGui.sameLine();
                    if (ImGui.button("Fast (30x)")) timeMultiplier = 30.0f;

                    ImGui.separator();
                    ImBoolean skyToggle = new ImBoolean(enableSkyDome);
                    if (ImGui.checkbox("Authentic 8-Slice Sky Dome", skyToggle)) {
                        enableSkyDome = skyToggle.get();
                    }

                    ImBoolean particleToggle = new ImBoolean(enableWeatherParticles);
                    if (ImGui.checkbox("Elemental Weather Particles (Rain/Snow/Dust)", particleToggle)) {
                        enableWeatherParticles = particleToggle.get();
                    }

                    if (currentWeather.effectType() == WeatherPreset.WeatherEffectType.THUNDERSTORM) {
                        ImGui.textColored(0.8f, 0.6f, 1.0f, 1.0f, String.format(Locale.ROOT, "Lightning Flash Active: %b (%.2f)", particleSystem.isLightningActive(), particleSystem.getLightningIntensity()));
                    }

                    ImGui.endTabItem();
                }

                // TAB 2: Shadows
                if (ImGui.beginTabItem("Shadows")) {
                    ImGui.textColored(1.0f, 0.8f, 0.4f, 1.0f, "Directional Shadow Mapping (PCF 3x3)");

                    ImBoolean shadowToggle = new ImBoolean(enableShadows);
                    if (ImGui.checkbox("Enable Directional Shadows", shadowToggle)) {
                        enableShadows = shadowToggle.get();
                    }

                    float[] sInt = { shadowIntensity };
                    if (ImGui.sliderFloat("Shadow Darkness", sInt, 0.0f, 1.0f)) {
                        shadowIntensity = sInt[0];
                    }

                    float[] boxSize = { shadowBoxSize };
                    if (ImGui.sliderFloat("Shadow Extent Box", boxSize, 400.0f, 3000.0f, "%.0fm")) {
                        shadowBoxSize = boxSize[0];
                    }

                    float[] bMin = { shadowBiasMin };
                    if (ImGui.sliderFloat("Shadow Bias Min", bMin, 0.0001f, 0.01f, "%.5f")) {
                        shadowBiasMin = bMin[0];
                    }

                    float[] bMax = { shadowBiasMax };
                    if (ImGui.sliderFloat("Shadow Bias Max", bMax, 0.001f, 0.02f, "%.5f")) {
                        shadowBiasMax = bMax[0];
                    }

                    ImGui.textDisabled("Shadow Map Texture: 2048 x 2048 (GL_DEPTH_COMPONENT32F)");
                    ImGui.endTabItem();
                }

                // TAB 3: Bump / Normal Mapping
                if (ImGui.beginTabItem("Bump / Normal Maps")) {
                    ImGui.textColored(1.0f, 0.8f, 0.4f, 1.0f, "Surface Relief & Normal Maps");

                    ImBoolean normToggle = new ImBoolean(enableNormalMapping);
                    if (ImGui.checkbox("Enable Bump / Normal Mapping", normToggle)) {
                        enableNormalMapping = normToggle.get();
                    }

                    float[] nStr = { normalStrength };
                    if (ImGui.sliderFloat("Normal Relief Strength", nStr, 0.1f, 3.5f)) {
                        normalStrength = nStr[0];
                    }

                    if (ImGui.button("Clear & Regenerate Normal Maps")) {
                        normalMapManager.clear();
                    }

                    ImGui.separator();
                    ImGui.textWrapped("Tangent-space normal maps are derived from texture luminance using 3x3 Sobel filtering matching retail FFXI DAT Section 0x5D specifications.");
                    ImGui.endTabItem();
                }

                // TAB 4: Ambient Occlusion (SSAO)
                if (ImGui.beginTabItem("Ambient Occlusion")) {
                    ImGui.textColored(1.0f, 0.8f, 0.4f, 1.0f, "Screen-Space Ambient Occlusion (SSAO)");

                    ImBoolean ssaoToggle = new ImBoolean(enableSsao);
                    if (ImGui.checkbox("Enable SSAO", ssaoToggle)) {
                        enableSsao = ssaoToggle.get();
                    }

                    float[] sRad = { ssaoRadius };
                    if (ImGui.sliderFloat("Sample Radius", sRad, 0.5f, 12.0f)) {
                        ssaoRadius = sRad[0];
                    }

                    float[] sBias = { ssaoBias };
                    if (ImGui.sliderFloat("Depth Bias", sBias, 0.001f, 0.10f)) {
                        ssaoBias = sBias[0];
                    }

                    float[] sPow = { ssaoPower };
                    if (ImGui.sliderFloat("Occlusion Power", sPow, 0.5f, 4.0f)) {
                        ssaoPower = sPow[0];
                    }

                    ImBoolean blurToggle = new ImBoolean(enableSsaoBlur);
                    if (ImGui.checkbox("Bilateral Blur (Edge-Preserving)", blurToggle)) {
                        enableSsaoBlur = blurToggle.get();
                    }

                    ImGui.separator();
                    ImGui.textDisabled("Kernel: 32 Hemisphere Cosine-Weighted Samples + 4x4 Noise");
                    ImGui.endTabItem();
                }

                // TAB 5: Lighting & Fog
                if (ImGui.beginTabItem("Lighting & Fog")) {
                    ImBoolean sunClock = new ImBoolean(lockToSunClock);
                    if (ImGui.checkbox("Lock Sun Angle to Diurnal Clock", sunClock)) {
                        lockToSunClock = sunClock.get();
                    }

                    if (!lockToSunClock) {
                        float[] sDir = { customSunDir.x, customSunDir.y, customSunDir.z };
                        if (ImGui.sliderFloat3("Manual Sun Dir", sDir, -1.0f, 1.0f)) {
                            customSunDir.set(sDir[0], sDir[1], sDir[2]);
                        }
                    }

                    float[] dMult = { diffuseMultiplier };
                    if (ImGui.sliderFloat("Diffuse Multiplier", dMult, 0.2f, 2.5f)) {
                        diffuseMultiplier = dMult[0];
                    }

                    ImBoolean fogToggle = new ImBoolean(enableDistanceFog);
                    if (ImGui.checkbox("Distance Atmospheric Fog", fogToggle)) {
                        enableDistanceFog = fogToggle.get();
                    }

                    ImGui.endTabItem();
                }

                // TAB 6: Map & Inspection
                if (ImGui.beginTabItem("Map & Inspection")) {
                    ImGui.textColored(1.0f, 0.8f, 0.4f, 1.0f, "Debug Render Modes");
                    String[] debugModes = {
                            "0: Full Authentic Lit",
                            "1: Diffuse Texture Only",
                            "2: Perturbed Surface Normals",
                            "3: Shadow Visibility Map",
                            "4: SSAO Ambient Occlusion Only",
                            "5: Total Lighting Accumulation"
                    };
                    ImInt dMode = new ImInt(debugViewMode);
                    if (ImGui.combo("Debug View", dMode, debugModes)) {
                        debugViewMode = dMode.get();
                    }

                    ImBoolean wireToggle = new ImBoolean(wireframe);
                    if (ImGui.checkbox("Wireframe Mode", wireToggle)) {
                        wireframe = wireToggle.get();
                    }

                    ImBoolean flyToggle = new ImBoolean(flyCamera);
                    if (ImGui.checkbox("Fly Camera Mode (WASD+Mouse)", flyToggle)) {
                        flyCamera = flyToggle.get();
                    }

                    ImGui.separator();
                    ImGui.text("Zone Selection:");
                    if (!zoneOptions.isEmpty()) {
                        String[] zLabels = zoneOptions.stream().map(ZoneModelLoader.ZoneSelectionOption::label).toArray(String[]::new);
                        int selZ = 0;
                        for (int i = 0; i < zoneOptions.size(); i++) {
                            if (zoneOptions.get(i).zoneId() == currentZoneId) {
                                selZ = i;
                                break;
                            }
                        }
                        ImInt selZInt = new ImInt(selZ);
                        if (ImGui.combo("Zone", selZInt, zLabels)) {
                            currentZoneId = zoneOptions.get(selZInt.get()).zoneId();
                            loadZone(currentZoneId);
                        }
                    }

                    ImGui.endTabItem();
                }

                ImGui.endTabBar();
            }
        }
        ImGui.end();
    }

    private void handleInput(float dt) {
        if (ImGui.getIO().getWantCaptureKeyboard() || ImGui.getIO().getWantCaptureMouse()) {
            return;
        }

        if (flyCamera) {
            float moveSpeed = (keyPressed[GLFW_KEY_LEFT_SHIFT] ? 450.0f : 150.0f) * dt;
            float yawRad = (float) Math.toRadians(flyYaw);
            float fwdX = (float) Math.sin(yawRad);
            float fwdZ = (float) -Math.cos(yawRad);
            float rightX = (float) Math.cos(yawRad);
            float rightZ = (float) Math.sin(yawRad);

            if (keyPressed[GLFW_KEY_W]) flyPos.add(fwdX * moveSpeed, 0f, fwdZ * moveSpeed);
            if (keyPressed[GLFW_KEY_S]) flyPos.sub(fwdX * moveSpeed, 0f, fwdZ * moveSpeed);
            if (keyPressed[GLFW_KEY_A]) flyPos.sub(rightX * moveSpeed, 0f, rightZ * moveSpeed);
            if (keyPressed[GLFW_KEY_D]) flyPos.add(rightX * moveSpeed, 0f, rightZ * moveSpeed);
            if (keyPressed[GLFW_KEY_SPACE]) flyPos.y += moveSpeed;
            if (keyPressed[GLFW_KEY_LEFT_CONTROL]) flyPos.y -= moveSpeed;
        }
    }

    private Matrix4f createProjectionMatrix() {
        return new Matrix4f().perspective((float) Math.toRadians(55.0), (float) screenWidth / screenHeight, 0.5f, 6000.0f);
    }

    private Matrix4f createViewMatrix() {
        if (flyCamera) {
            Matrix4f view = new Matrix4f();
            view.rotate((float) Math.toRadians(flyPitch), 1f, 0f, 0f);
            view.rotate((float) Math.toRadians(flyYaw), 0f, 1f, 0f);
            view.translate(-flyPos.x, -flyPos.y, -flyPos.z);
            return view;
        } else {
            Vector3f eye = new Vector3f(
                    (float) (orbitDistance * Math.cos(orbitPitch) * Math.sin(orbitYaw)) + targetX,
                    (float) (orbitDistance * Math.sin(-orbitPitch)) + targetY,
                    (float) (orbitDistance * Math.cos(orbitPitch) * Math.cos(orbitYaw)) + targetZ
            );
            return new Matrix4f().lookAt(eye, new Vector3f(targetX, targetY, targetZ), new Vector3f(0f, 1f, 0f));
        }
    }

    private Vector3f getCameraPosition() {
        if (flyCamera) {
            return new Vector3f(flyPos);
        } else {
            return new Vector3f(
                    (float) (orbitDistance * Math.cos(orbitPitch) * Math.sin(orbitYaw)) + targetX,
                    (float) (orbitDistance * Math.sin(-orbitPitch)) + targetY,
                    (float) (orbitDistance * Math.cos(orbitPitch) * Math.cos(orbitYaw)) + targetZ
            );
        }
    }

    private void loadZone(int zoneId) {
        isLoading = true;
        loadingStatus = "Loading zone " + zoneId + "...";

        for (ZoneRenderChunk chunk : zoneRenderChunks) {
            chunk.buffer().close();
        }
        zoneRenderChunks.clear();
        normalMapManager.clear();

        try {
            if (zoneOptions.isEmpty()) {
                zoneOptions = ZoneModelLoader.loadZoneOptions(ffxiRoot);
            }

            LoadedZone loaded = ZoneModelLoader.loadZone(ffxiRoot, zoneId);
            this.currentZoneId = loaded.zoneId();
            this.currentZoneLabel = String.format("%03d - %s", loaded.zoneId(), loaded.zoneName());
            this.zoneTextureLoader = new ZoneTextureLoader(loaded.textureSources());

            int triCount = 0;
            for (LoadedZone.ZoneChunkInstance inst : loaded.chunkInstances()) {
                ZoneMeshBuffer buf = new ZoneMeshBuffer(inst.chunk());
                triCount += buf.indexCount() / 3;
                zoneRenderChunks.add(new ZoneRenderChunk(buf, inst.modelMatrix(), inst.invertCull(), inst.discardThreshold()));
            }

            this.totalTriangles = triCount;
            this.loadingStatus = "Loaded " + zoneRenderChunks.size() + " chunks (" + triCount + " triangles)";
            isLoading = false;
        } catch (Exception e) {
            loadingStatus = "Error: " + e.getMessage();
            isLoading = false;
        }
    }

    private void initWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_TITLE, NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();

        glfwShowWindow(window);

        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            if (w > 0 && h > 0) {
                screenWidth = w;
                screenHeight = h;
                glViewport(0, 0, w, h);
            }
        });

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key >= 0 && key < keyPressed.length) {
                keyPressed[key] = (action != GLFW_RELEASE);
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (action == GLFW_PRESS) {
                mouseDragging = true;
                double[] x = new double[1], y = new double[1];
                glfwGetCursorPos(win, x, y);
                lastMouseX = x[0];
                lastMouseY = y[0];
            } else if (action == GLFW_RELEASE) {
                mouseDragging = false;
            }
        });

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (ImGui.getIO().getWantCaptureMouse()) return;
            if (mouseDragging) {
                double dx = xpos - lastMouseX;
                double dy = ypos - lastMouseY;
                lastMouseX = xpos;
                lastMouseY = ypos;

                if (flyCamera) {
                    flyYaw += (float) dx * 0.15f;
                    flyPitch = Math.max(-89.0f, Math.min(89.0f, flyPitch + (float) dy * 0.15f));
                } else {
                    orbitYaw += (float) dx * 0.008f;
                    orbitPitch = Math.max(-1.45f, Math.min(1.45f, orbitPitch + (float) dy * 0.008f));
                }
            }
        });

        glfwSetScrollCallback(window, (win, xoff, yoff) -> {
            if (ImGui.getIO().getWantCaptureMouse()) return;
            if (!flyCamera) {
                orbitDistance = Math.max(50.0f, Math.min(5000.0f, orbitDistance - (float) yoff * 60.0f));
            }
        });
    }

    private record ZoneRenderChunk(
            ZoneMeshBuffer buffer,
            Matrix4f modelMatrix,
            boolean invertCull,
            float discardThreshold
    ) {}
}
