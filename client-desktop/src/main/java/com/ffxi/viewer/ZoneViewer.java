package com.ffxi.viewer;

import com.ffxi.viewer.model.ZoneMeshMath;
import com.ffxi.viewer.debug.ZoneObjExporter;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.model.ZoneModelLoader;
import com.ffxi.viewer.renderer.ZoneTextureLoader;
import com.ffxi.viewer.renderer.ZoneMeshBuffer;
import com.ffxi.viewer.renderer.ZoneShader;
import imgui.ImGui;
import imgui.glfw.ImGuiImplGlfw;
import imgui.gl3.ImGuiImplGl3;
import imgui.type.ImInt;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class ZoneViewer {
    private static final int WINDOW_WIDTH = 1600;
    private static final int WINDOW_HEIGHT = 900;
    private static final String WINDOW_TITLE = "FFXI Zone Viewer";
    private static final int DEFAULT_ZONE_ID = 100;
    private static final Path REFERENCE_OBJ = Path.of("dumps/zone-geometry/exported.obj");

    private long window;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    private float orbitYaw = 1.5707963f;
    private float orbitPitch = -0.45f;
    private float orbitDistance = 1800.0f;
    private float targetOffsetX = 0.0f;
    private float targetOffsetY = 0.0f;
    private float targetOffsetZ = 0.0f;

    private Path ffxiRoot;
    private int currentZoneId = DEFAULT_ZONE_ID;
    private String currentZoneLabel = "";
    private String loadingStatus = "";
    private boolean isLoading = false;
    private boolean zoneReloadPending = false;

    private List<ZoneModelLoader.ZoneSelectionOption> zoneOptions = List.of();
    private final List<ZoneRenderChunk> zoneRenderChunks = new ArrayList<>();

    private ZoneShader zoneShader;
    private ZoneTextureLoader zoneTextureLoader;
    private final TaskScheduler taskScheduler = new TaskScheduler();

    private int zoneTriangleCount = 0;
    private Integer referenceObjTriangleCount = null;
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

        ZoneViewer viewer = new ZoneViewer();
        viewer.ffxiRoot = ffxiRoot;
        viewer.currentZoneId = Math.max(0, zoneId);
        viewer.run();
    }

    private void run() {
        initWindow();

        ImGui.createContext();
        ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
        ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();
        imGuiGlfw.init(window, true);
        imGuiGl3.init("#version 330");

        zoneShader = new ZoneShader();
        zoneTextureLoader = new ZoneTextureLoader(java.util.Map.of());

        try {
            glClearColor(0.5f, 0.5f, 0.52f, 1.0f);
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_CULL_FACE);  // Enable backface culling
            glCullFace(GL_BACK);     // Cull back faces
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            referenceObjTriangleCount = countObjTrianglesIfPresent(REFERENCE_OBJ);

            zoneOptions = ZoneModelLoader.loadZoneOptions(ffxiRoot);
            if (!zoneOptions.isEmpty()) {
                int optionIndex = findZoneOptionIndexById(zoneOptions, currentZoneId);
                if (optionIndex < 0) {
                    optionIndex = findZoneOptionIndexById(zoneOptions, DEFAULT_ZONE_ID);
                }
                if (optionIndex < 0) {
                    optionIndex = 0;
                }
                currentZoneId = zoneOptions.get(optionIndex).zoneId();
                loadAndApplyZone(currentZoneId);
                currentZoneLabel = zoneOptions.get(optionIndex).label();
            } else {
                loadAndApplyZone(currentZoneId);
            }

            glfwShowWindow(window);

            ImInt selectedZoneIdx = new ImInt(Math.max(0, findZoneOptionIndexById(zoneOptions, currentZoneId)));

            while (!glfwWindowShouldClose(window)) {
                glfwPollEvents();
                taskScheduler.processForegroundTasks();

                if (zoneReloadPending && !isLoading) {
                    zoneReloadPending = false;
                    isLoading = true;
                    loadingStatus = "Loading zone assets...";
                    final int zoneId = currentZoneId;
                    taskScheduler.submit(
                            () -> {
                                try {
                                    return ZoneModelLoader.loadZone(ffxiRoot, zoneId);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            this::applyLoadedZone,
                            (error) -> {
                                isLoading = false;
                                loadingStatus = "Error: " + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
                                System.err.println("[ZONE-VIEWER] Failed to load zone asynchronously: " + error.getMessage());
                            }
                    );
                }

                imGuiGlfw.newFrame();
                ImGui.newFrame();

                ImGui.begin("FFXI Zone Viewer Controls");
                if (isLoading) {
                    ImGui.textColored(1.0f, 0.8f, 0.0f, 1.0f, "Status: " + loadingStatus);
                    ImGui.separator();
                }

                if (!zoneOptions.isEmpty()) {
                    String[] zoneLabels = zoneOptions.stream().map(ZoneModelLoader.ZoneSelectionOption::label).toArray(String[]::new);
                    if (ImGui.combo("Map Zone", selectedZoneIdx, zoneLabels)) {
                        ZoneModelLoader.ZoneSelectionOption selected = zoneOptions.get(selectedZoneIdx.get());
                        currentZoneId = selected.zoneId();
                        currentZoneLabel = selected.label();
                        zoneReloadPending = true;
                    }
                } else {
                    ImGui.inputInt("Zone ID", selectedZoneIdx);
                    int typedZone = Math.max(0, selectedZoneIdx.get());
                    if (typedZone != currentZoneId) {
                        currentZoneId = typedZone;
                        zoneReloadPending = true;
                    }
                }

                ImGui.text("Loaded: " + currentZoneLabel);
                ImGui.text("Zone Triangles: " + zoneTriangleCount);
                if (referenceObjTriangleCount != null) {
                    ImGui.text("Reference OBJ Triangles: " + referenceObjTriangleCount);
                    ImGui.text("Delta: " + (zoneTriangleCount - referenceObjTriangleCount));
                }
                imgui.type.ImBoolean wf = new imgui.type.ImBoolean(wireframe);
                if (ImGui.checkbox("Wireframe Mode", wf)) {
                    wireframe = wf.get();
                }

                ImGui.separator();
                ImGui.text("Camera:");
                float[] yawArr = { orbitYaw };
                if (ImGui.sliderFloat("Orbit Yaw", yawArr, -3.14159f, 3.14159f)) orbitYaw = yawArr[0];
                float[] pitchArr = { orbitPitch };
                if (ImGui.sliderFloat("Orbit Pitch", pitchArr, -1.5f, 1.5f)) orbitPitch = pitchArr[0];
                float[] distArr = { orbitDistance };
                if (ImGui.sliderFloat("Orbit Dist", distArr, 1.0f, 20000.0f)) orbitDistance = distArr[0];
                float[] tx = { targetOffsetX };
                if (ImGui.sliderFloat("Target X", tx, -5000.0f, 5000.0f)) targetOffsetX = tx[0];
                float[] ty = { targetOffsetY };
                if (ImGui.sliderFloat("Target Y", ty, -5000.0f, 5000.0f)) targetOffsetY = ty[0];
                float[] tz = { targetOffsetZ };
                if (ImGui.sliderFloat("Target Z", tz, -5000.0f, 5000.0f)) targetOffsetZ = tz[0];

                ImGui.end();

                renderZoneFrame();

                ImGui.render();
                imGuiGl3.renderDrawData(ImGui.getDrawData());

                glfwSwapBuffers(window);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            for (ZoneRenderChunk chunk : zoneRenderChunks) {
                chunk.buffer().close();
            }
            zoneTextureLoader.close();
            zoneShader.close();
            taskScheduler.shutdown();

            imGuiGlfw.dispose();
            imGuiGl3.dispose();
            ImGui.destroyContext();
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private void loadAndApplyZone(int zoneId) throws IOException {
        isLoading = true;
        loadingStatus = "Loading zone assets...";
        LoadedZone zone = ZoneModelLoader.loadZone(ffxiRoot, zoneId);
        applyLoadedZone(zone);
    }

    private void applyLoadedZone(LoadedZone loadedZone) {
        try {
            for (ZoneRenderChunk renderChunk : zoneRenderChunks) {
                renderChunk.buffer().close();
            }
            zoneRenderChunks.clear();

            zoneTextureLoader.close();
            zoneTextureLoader = new ZoneTextureLoader(loadedZone.textureSources());

            currentZoneId = loadedZone.zoneId();
            currentZoneLabel = String.format(Locale.ROOT, "%03d - %s", loadedZone.zoneId(), loadedZone.zoneName());
            zoneTriangleCount = 0;

            for (LoadedZone.ZoneChunkInstance chunkInstance : loadedZone.chunkInstances()) {
                ZoneMeshBuffer buffer = new ZoneMeshBuffer(chunkInstance.chunk());
                zoneRenderChunks.add(new ZoneRenderChunk(
                        buffer,
                        new Matrix4f(chunkInstance.modelMatrix()),
                        chunkInstance.invertCull(),
                        chunkInstance.discardThreshold()
                ));
                if (ZoneMeshMath.hasValidVertices(chunkInstance.chunk())) {
                    zoneTriangleCount += ZoneMeshMath.expandTriangles(chunkInstance.chunk()).size() / 3;
                }
            }

            isLoading = false;
            loadingStatus = "Loaded";
            if (referenceObjTriangleCount != null) {
                System.out.printf("[ZONE-VIEWER] Triangles zone=%d refObj=%d delta=%d%n", zoneTriangleCount, referenceObjTriangleCount, zoneTriangleCount - referenceObjTriangleCount);
            } else {
                System.out.printf("[ZONE-VIEWER] Triangles zone=%d%n", zoneTriangleCount);
            }

            // Export OBJ for comparison
            try {
                Path debugObj = Path.of("dumps/zone-geometry/viewer-export-zone-" + loadedZone.zoneId() + ".obj");
                ZoneObjExporter.export(loadedZone, debugObj);
                System.out.printf("[ZONE-VIEWER] Exported OBJ to %s%n", debugObj);
                
                Path indexDump = Path.of("dumps/zone-geometry/viewer-indices-zone-" + loadedZone.zoneId() + ".txt");
                com.ffxi.viewer.debug.IndexExpansionDumper.dump(loadedZone, indexDump);
                System.out.printf("[ZONE-VIEWER] Dumped indices to %s%n", indexDump);
            } catch (Exception e) {
                System.err.printf("[ZONE-VIEWER] Failed to export debug files: %s%n", e.getMessage());
            }
        } catch (Exception ex) {
            isLoading = false;
            loadingStatus = "Error: " + ex.getMessage();
            System.err.println("[ZONE-VIEWER] Failed to apply loaded zone: " + ex.getMessage());
        }
    }

    private void renderZoneFrame() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        if (wireframe) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        } else {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }

        Matrix4f projectionMatrix = createProjectionMatrix();
        Matrix4f viewMatrix = createViewMatrix();

        zoneShader.bind();
        glActiveTexture(GL_TEXTURE0);

        for (int pass = 0; pass < 2; pass++) {
            boolean blendedPass = pass == 1;
            glDepthMask(!blendedPass);
            if (blendedPass) {
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            } else {
                glDisable(GL_BLEND);
            }
            for (ZoneRenderChunk renderChunk : zoneRenderChunks) {
                if (renderChunk.buffer().renderBlend() != blendedPass) {
                    continue;
                }
                zoneShader.setDiscardThreshold(renderChunk.discardThreshold());
                zoneShader.setMatrices(projectionMatrix, viewMatrix, renderChunk.modelMatrix());
                
                // Handle invertCull: when set, disable culling (both faces visible)
                if (renderChunk.invertCull()) {
                    glDisable(GL_CULL_FACE);
                } else {
                    glEnable(GL_CULL_FACE);
                    boolean inverted = renderChunk.modelMatrix().determinant() < 0.0f;
                    if (inverted) {
                        glFrontFace(GL_CW);
                    } else {
                        glFrontFace(GL_CCW);
                    }
                }
                
                glBindTexture(GL_TEXTURE_2D, zoneTextureLoader.textureId(renderChunk.buffer().textureName()));
                renderChunk.buffer().draw();
            }
        }
        glDisable(GL_BLEND);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);  // Restore culling state
        glFrontFace(GL_CCW);
    }

    private Matrix4f createProjectionMatrix() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(window, width, height);
            float aspect = Math.max(1, width.get(0)) / (float) Math.max(1, height.get(0));
            return new Matrix4f().perspective((float) Math.toRadians(50.0), aspect, 0.01f, 100000.0f);
        }
    }

    private Matrix4f createViewMatrix() {
        Vector3f target = new Vector3f(targetOffsetX, targetOffsetY, targetOffsetZ);
        Vector3f eye = new Vector3f(
                (float) (orbitDistance * Math.cos(orbitPitch) * Math.sin(orbitYaw)) + targetOffsetX,
                (float) (orbitDistance * Math.sin(orbitPitch)) + targetOffsetY,
                (float) (orbitDistance * Math.cos(orbitPitch) * Math.cos(orbitYaw)) + targetOffsetZ
        );
        return new Matrix4f().lookAt(eye, target, new Vector3f(0.0f, 1.0f, 0.0f));
    }

    private void initWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Failed to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_TITLE, NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("Failed to create GLFW window");
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        GL.createCapabilities();

        glfwSetFramebufferSizeCallback(window, (handle, width, height) -> glViewport(0, 0, width, height));

        glfwSetMouseButtonCallback(window, (handle, button, action, mods) -> {
            if (ImGui.getIO().getWantCaptureMouse()) {
                dragging = false;
                return;
            }
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                dragging = action == GLFW_PRESS;
                if (dragging) {
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        var x = stack.mallocDouble(1);
                        var y = stack.mallocDouble(1);
                        glfwGetCursorPos(window, x, y);
                        lastMouseX = x.get(0);
                        lastMouseY = y.get(0);
                    }
                }
            }
        });

        glfwSetCursorPosCallback(window, (handle, xpos, ypos) -> {
            if (ImGui.getIO().getWantCaptureMouse()) {
                dragging = false;
                return;
            }
            if (!dragging) return;
            double dx = xpos - lastMouseX;
            double dy = ypos - lastMouseY;
            lastMouseX = xpos;
            lastMouseY = ypos;
            orbitYaw += (float) (dx * 0.01f);
            orbitPitch = clamp(orbitPitch + (float) (dy * 0.01f), -1.5f, 1.5f);
        });

        glfwSetScrollCallback(window, (handle, xoffset, yoffset) -> {
            if (ImGui.getIO().getWantCaptureMouse()) return;
            orbitDistance = clamp(orbitDistance - (float) yoffset * 20.0f, 1.0f, 20000.0f);
        });

        glfwSetKeyCallback(window, (handle, key, scancode, action, mods) -> {
            if (action == GLFW_PRESS && key == GLFW_KEY_ESCAPE) {
                glfwSetWindowShouldClose(handle, true);
            }
        });
    }

    private static int findZoneOptionIndexById(List<ZoneModelLoader.ZoneSelectionOption> options, int zoneId) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).zoneId() == zoneId) {
                return i;
            }
        }
        return -1;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Integer countObjTrianglesIfPresent(Path objPath) {
        if (!Files.exists(objPath)) {
            return null;
        }
        try {
            int triangles = 0;
            for (String line : Files.readAllLines(objPath)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("f ")) {
                    continue;
                }
                String[] tokens = trimmed.split("\\s+");
                int vertices = Math.max(0, tokens.length - 1);
                if (vertices >= 3) {
                    triangles += (vertices - 2);
                }
            }
            return triangles;
        } catch (IOException e) {
            return null;
        }
    }

    private record ZoneRenderChunk(
            ZoneMeshBuffer buffer,
            Matrix4f modelMatrix,
            boolean invertCull,
            float discardThreshold
    ) {
    }
}
