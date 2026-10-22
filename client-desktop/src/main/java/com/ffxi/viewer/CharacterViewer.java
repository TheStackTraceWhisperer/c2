package com.ffxi.viewer;

import com.ffxi.crypto.KeyTables;
import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import com.ffxi.io.FileUtil;
import com.ffxi.model.AnimationKeyFrame;
import com.ffxi.model.JointAnimation;
import com.ffxi.model.SkeletonAnimationResource;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.model.SkeletonResource;
import com.ffxi.model.Vec3f;
import com.ffxi.tables.CharacterIdToResourceId;
import com.ffxi.tables.ItemModelSlot;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.renderer.CharacterMeshBuffer;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import com.ffxi.viewer.renderer.ZoneTextureLoader;
import com.ffxi.viewer.renderer.CharacterShader;
import com.ffxi.viewer.renderer.ZoneMeshBuffer;
import com.ffxi.viewer.renderer.ZoneShader;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedZone;
import com.ffxi.viewer.collision.ZoneCollisionSystem;
import com.ffxi.viewer.model.ZoneModelLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.io.File;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import imgui.ImGui;
import imgui.type.ImInt;
import imgui.type.ImFloat;
import imgui.type.ImBoolean;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetTime;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetInputMode;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.opengl.GL11C.GL_BACK;
import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_CW;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.GL_CCW;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glBlendFunc;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL11C.glCullFace;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glFrontFace;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;

public final class CharacterViewer {
    // Visible equipment slot constants
    public static final int SLOT_HEAD = 1;
    public static final int SLOT_BODY = 2;
    public static final int SLOT_HANDS = 3;
    public static final int SLOT_LEGS = 4;
    public static final int SLOT_FEET = 5;

    // Slot base constants for item model mapping
    public static final int HEAD_BASE = 0xC320;
    public static final int BODY_BASE = 0xC3A8;
    public static final int HANDS_BASE = 0xC42E;
    public static final int LEGS_BASE = 0xC488;
    public static final int FEET_BASE = 0xC512;

    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final String WINDOW_TITLE = "FFXI Character Viewer";

    private static final RaceGenderConfig DEFAULT_RACE = RaceGenderConfig.ElvaanF;

    private long window;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;
    private float orbitYaw = 1.5707963f;
    private float orbitPitch = 0.0f;
    private float orbitDistance = 4.0f;
    private CharacterAnimator animator;
    private int baseAnimIndex = 0;
    private int upperAnimIndex = 0;
    private double animationTime = 0.0;
    private double lastFrameTime = 0.0;

    private LoadedCharacter character;
    private CharacterShader shader;
    private CharacterTextureLoader textureLoader;
    private ZoneShader zoneShader;
    private ZoneTextureLoader zoneTextureLoader;

    private Path ffxiRoot;
    private RaceGenderConfig currentRace;
    private int currentFaceId;
    private int currentHeadId;
    private int currentBodyId;
    private int currentHandsId;
    private int currentLegsId;
    private int currentFeetId;
    private float targetOffsetX = 0.0f;
    private float targetOffsetY = 0.0f;
    private float targetOffsetZ = 0.0f;
    private final TaskScheduler taskScheduler = new TaskScheduler();
    private boolean isLoading = false;
    private String loadingStatus = "";
    private boolean reloadPending = false;
    private boolean zoneReloadPending = false;
    private int currentZoneId = 100;
    private String currentZoneLabel = "";
    private List<ZoneModelLoader.ZoneSelectionOption> zoneOptions = List.of();
    private final List<ZoneRenderChunk> zoneRenderChunks = new ArrayList<>();
    private LoadedZone currentLoadedZone = null;

    // Character world-position controls — instance fields so applyLoadedZone can update them
    private final ImFloat charTx = new ImFloat(0.0f);
    private final ImFloat charTy = new ImFloat(0.0f);
    private final ImFloat charTz = new ImFloat(0.0f);
    private static final float CHAR_MOVE_SPEED = 8.0f;
    private final boolean[] keyPressed = new boolean[GLFW_KEY_LAST + 1];

    public static void main(String[] args) throws Exception {
        Path ffxiRoot = Path.of(args.length > 0 && !args[0].startsWith("-") ? args[0] : App.DEFAULT_FFXI_PATH);
        RaceGenderConfig race = DEFAULT_RACE;

        int faceModelId = 0;
        int headModelId = 0;
        int bodyModelId = 8;
        int handsModelId = 8;
        int legsModelId = 8;
        int feetModelId = 8;
        int zoneId = 100;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--race") && i + 1 < args.length) {
                race = RaceGenderConfig.fromName(args[i + 1]);
                i++;
            } else if (args[i].equalsIgnoreCase("--face") && i + 1 < args.length) {
                faceModelId = Integer.parseInt(args[i + 1]);
                i++;
            } else if (args[i].equalsIgnoreCase("--head") && i + 1 < args.length) {
                headModelId = Integer.parseInt(args[i + 1]);
                i++;
            } else if (args[i].equalsIgnoreCase("--body") && i + 1 < args.length) {
                bodyModelId = Integer.parseInt(args[i + 1]);
                i++;
            } else if (args[i].equalsIgnoreCase("--hands") && i + 1 < args.length) {
                handsModelId = Integer.parseInt(args[i + 1]);
                i++;
            } else if (args[i].equalsIgnoreCase("--legs") && i + 1 < args.length) {
                legsModelId = Integer.parseInt(args[i + 1]);
                i++;
            } else if (args[i].equalsIgnoreCase("--feet") && i + 1 < args.length) {
                feetModelId = Integer.parseInt(args[i + 1]);
                i++;
            } else if (args[i].equalsIgnoreCase("--zone") && i + 1 < args.length) {
                zoneId = Integer.parseInt(args[i + 1]);
                i++;
            }
        }

        CharacterViewer viewer = new CharacterViewer();
        viewer.ffxiRoot = ffxiRoot;
        viewer.currentRace = race;
        viewer.currentFaceId = faceModelId;
        viewer.currentHeadId = headModelId;
        viewer.currentBodyId = bodyModelId;
        viewer.currentHandsId = handsModelId;
        viewer.currentLegsId = legsModelId;
        viewer.currentFeetId = feetModelId;
        viewer.currentZoneId = Math.max(0, zoneId);

        LoadedCharacter loadedCharacter = CharacterModelLoader.loadCharacter(ffxiRoot, race, faceModelId, headModelId, bodyModelId, handsModelId, legsModelId, feetModelId);
        viewer.currentFaceId = loadedCharacter.loadedModelIds().getOrDefault(ItemModelSlot.Face, faceModelId);
        viewer.currentHeadId = loadedCharacter.loadedModelIds().getOrDefault(ItemModelSlot.Head, headModelId);
        viewer.currentBodyId = loadedCharacter.loadedModelIds().getOrDefault(ItemModelSlot.Body, bodyModelId);
        viewer.currentHandsId = loadedCharacter.loadedModelIds().getOrDefault(ItemModelSlot.Hands, handsModelId);
        viewer.currentLegsId = loadedCharacter.loadedModelIds().getOrDefault(ItemModelSlot.Legs, legsModelId);
        viewer.currentFeetId = loadedCharacter.loadedModelIds().getOrDefault(ItemModelSlot.Feet, feetModelId);
        viewer.run(loadedCharacter);
    }

    private void updateAnimator(LoadedCharacter character) {
        SkeletonAnimationResource baseAnim = character.slot0Animations().isEmpty() ? null : character.slot0Animations().get(baseAnimIndex);
        SkeletonAnimationResource upperAnim = (upperAnimIndex >= 0 && upperAnimIndex < character.slot1Animations().size()) ? character.slot1Animations().get(upperAnimIndex) : null;
        animator = new CharacterAnimator(
                character.skeleton(),
                baseAnim,
                upperAnim
        );
        animationTime = 0.0;
    }

    private void run(LoadedCharacter initialCharacter) {
        initWindow();

        // 1. Initialize ImGui
        ImGui.createContext();
        ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
        ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();
        imGuiGlfw.init(window, true);
        imGuiGl3.init("#version 330");

        this.character = initialCharacter;

        // Initialize active indices to match idl0 and idl1 if possible
        baseAnimIndex = 0;
        if (!character.slot0Animations().isEmpty()) {
            for (int i = 0; i < character.slot0Animations().size(); i++) {
                if (character.slot0Animations().get(i).sectionId().equalsIgnoreCase("idl0")) {
                    baseAnimIndex = i;
                    break;
                }
            }
        }
        
        upperAnimIndex = -1; // Default to -1 (None / no upper body override)
        if (!character.slot1Animations().isEmpty()) {
            for (int i = 0; i < character.slot1Animations().size(); i++) {
                if (character.slot1Animations().get(i).sectionId().equalsIgnoreCase("idl1")) {
                    upperAnimIndex = i;
                    break;
                }
            }
        }

        updateAnimator(character);

        // ImGui state variables
        ImInt selectedRaceIdx = new ImInt(0);
        RaceGenderConfig[] races = RaceGenderConfig.values();
        for (int i = 0; i < races.length; i++) {
            if (races[i] == currentRace) {
                selectedRaceIdx.set(i);
                break;
            }
        }
        String[] raceNames = new String[races.length];
        for (int i = 0; i < races.length; i++) {
            raceNames[i] = races[i].name();
        }

        ImInt faceId = new ImInt(currentFaceId);
        ImInt headId = new ImInt(currentHeadId);
        ImInt bodyId = new ImInt(currentBodyId);
        ImInt handsId = new ImInt(currentHandsId);
        ImInt legsId = new ImInt(currentLegsId);
        ImInt feetId = new ImInt(currentFeetId);

        ImInt selectedBaseAnim = new ImInt(baseAnimIndex);
        ImInt selectedUpperAnim = new ImInt(upperAnimIndex + 1); // -1 is index 0
        ImInt selectedZoneIdx = new ImInt(0);

        ImFloat playbackSpeed = new ImFloat(1.0f);
        ImBoolean isPlaying = new ImBoolean(true);

        ImFloat charRx = new ImFloat(0.0f);
        ImFloat charRy = new ImFloat(0.0f);
        ImFloat charRz = new ImFloat(0.0f);
        ImFloat charScale = new ImFloat(1.0f);

        glfwSetKeyCallback(window, (handle, key, scancode, action, mods) -> {
            if (key >= 0 && key < keyPressed.length) {
                keyPressed[key] = (action != GLFW_RELEASE);
            }
            if (ImGui.getIO().getWantCaptureKeyboard()) return;
            if (action == GLFW_PRESS) {
                if (key == GLFW_KEY_ESCAPE) {
                    glfwSetWindowShouldClose(handle, true);
                } else if (key == GLFW_KEY_DOWN) {
                    if (!character.slot0Animations().isEmpty()) {
                        baseAnimIndex = (baseAnimIndex + 1) % character.slot0Animations().size();
                        selectedBaseAnim.set(baseAnimIndex);
                        updateAnimator(character);
                        System.out.println("[VIEWER] Switched base animation to: " + character.slot0Animations().get(baseAnimIndex).sectionId());
                    }
                } else if (key == GLFW_KEY_UP) {
                    if (!character.slot0Animations().isEmpty()) {
                        baseAnimIndex = (baseAnimIndex - 1 + character.slot0Animations().size()) % character.slot0Animations().size();
                        selectedBaseAnim.set(baseAnimIndex);
                        updateAnimator(character);
                        System.out.println("[VIEWER] Switched base animation to: " + character.slot0Animations().get(baseAnimIndex).sectionId());
                    }
                } else if (key == GLFW_KEY_RIGHT) {
                    if (!character.slot1Animations().isEmpty()) {
                        upperAnimIndex = upperAnimIndex + 1;
                        if (upperAnimIndex >= character.slot1Animations().size()) {
                            upperAnimIndex = -1;
                        }
                        selectedUpperAnim.set(upperAnimIndex + 1);
                        updateAnimator(character);
                        String animName = upperAnimIndex == -1 ? "None (Fallback to base)" : character.slot1Animations().get(upperAnimIndex).sectionId();
                        System.out.println("[VIEWER] Switched upper body animation to: " + animName);
                    }
                } else if (key == GLFW_KEY_LEFT) {
                    if (!character.slot1Animations().isEmpty()) {
                        upperAnimIndex = upperAnimIndex - 1;
                        if (upperAnimIndex < -1) {
                            upperAnimIndex = character.slot1Animations().size() - 1;
                        }
                        selectedUpperAnim.set(upperAnimIndex + 1);
                        updateAnimator(character);
                        String animName = upperAnimIndex == -1 ? "None (Fallback to base)" : character.slot1Animations().get(upperAnimIndex).sectionId();
                        System.out.println("[VIEWER] Switched upper body animation to: " + animName);
                    }
                }
            }
        });

        orbitDistance = Math.max(character.radius() * 2.6f, 1.75f);

        this.shader = new CharacterShader();
        this.textureLoader = new CharacterTextureLoader(character.textures());
        this.zoneShader = new ZoneShader();
        this.zoneTextureLoader = new ZoneTextureLoader(Map.of());
        List<CharacterMeshBuffer> meshBuffers = new ArrayList<>();
        for (SkeletonMeshMesh mesh : character.meshes()) {
            meshBuffers.add(new CharacterMeshBuffer(mesh));
        }

        try {
            zoneOptions = ZoneModelLoader.loadZoneOptions(ffxiRoot);
            if (!zoneOptions.isEmpty()) {
                int initialZoneIndex = findZoneOptionIndexById(zoneOptions, currentZoneId);
                if (initialZoneIndex < 0) {
                    initialZoneIndex = 0;
                    currentZoneId = zoneOptions.get(0).zoneId();
                }
                selectedZoneIdx.set(initialZoneIndex);
                currentZoneLabel = zoneOptions.get(initialZoneIndex).label();
                LoadedZone initialZone = ZoneModelLoader.loadZone(ffxiRoot, currentZoneId);
                applyLoadedZone(initialZone);
            } else {
                currentZoneLabel = "No loadable zones found";
            }
        } catch (IOException | IllegalStateException ex) {
            currentZoneLabel = "Zone load failed";
            System.err.println("[VIEWER] Failed to initialize zone list: " + ex.getMessage());
        }

        try {
            glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            glfwShowWindow(window);

            lastFrameTime = glfwGetTime();
            animationTime = 0.0;

            boolean firstFrame = true;
            while (!glfwWindowShouldClose(window)) {
                glfwPollEvents();

                taskScheduler.processForegroundTasks();

                // Dynamic reloading for character and zone assets
                if (!isLoading && (reloadPending || zoneReloadPending)) {
                    if (reloadPending) {
                        reloadPending = false;
                        isLoading = true;
                        loadingStatus = "Loading character assets...";

                        final RaceGenderConfig race = currentRace;
                        final int face = currentFaceId;
                        final int head = currentHeadId;
                        final int body = currentBodyId;
                        final int hands = currentHandsId;
                        final int legs = currentLegsId;
                        final int feet = currentFeetId;

                        taskScheduler.submit(
                                () -> {
                                    try {
                                        return CharacterModelLoader.loadCharacter(ffxiRoot, race, face, head, body, hands, legs, feet);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                },
                                (nextChar) -> {
                                    try {
                                        character = nextChar;

                                        for (CharacterMeshBuffer meshBuffer : meshBuffers) {
                                            meshBuffer.close();
                                        }
                                        meshBuffers.clear();
                                        for (SkeletonMeshMesh mesh : character.meshes()) {
                                            meshBuffers.add(new CharacterMeshBuffer(mesh));
                                        }

                                        textureLoader.close();
                                        textureLoader = new CharacterTextureLoader(character.textures());

                                        baseAnimIndex = 0;
                                        if (!character.slot0Animations().isEmpty()) {
                                            for (int i = 0; i < character.slot0Animations().size(); i++) {
                                                if (character.slot0Animations().get(i).sectionId().equalsIgnoreCase("idl0")) {
                                                    baseAnimIndex = i;
                                                    break;
                                                }
                                            }
                                        }
                                        upperAnimIndex = -1;
                                        if (!character.slot0Animations().isEmpty() && !character.slot1Animations().isEmpty()) {
                                            String baseName = character.slot0Animations().get(baseAnimIndex).sectionId();
                                            String basePrefix = baseName.substring(0, baseName.length() - 1);
                                            for (int i = 0; i < character.slot1Animations().size(); i++) {
                                                String upperName = character.slot1Animations().get(i).sectionId();
                                                String upperPrefix = upperName.substring(0, upperName.length() - 1);
                                                if (upperPrefix.equalsIgnoreCase(basePrefix)) {
                                                    upperAnimIndex = i;
                                                    break;
                                                }
                                            }
                                        }
                                        selectedBaseAnim.set(baseAnimIndex);
                                        selectedUpperAnim.set(upperAnimIndex + 1);

                                        Map<ItemModelSlot, Integer> actualIds = character.loadedModelIds();
                                        if (actualIds != null) {
                                            if (actualIds.containsKey(ItemModelSlot.Face)) {
                                                currentFaceId = actualIds.get(ItemModelSlot.Face);
                                                faceId.set(currentFaceId);
                                            }
                                            if (actualIds.containsKey(ItemModelSlot.Head)) {
                                                currentHeadId = actualIds.get(ItemModelSlot.Head);
                                                headId.set(currentHeadId);
                                            }
                                            if (actualIds.containsKey(ItemModelSlot.Body)) {
                                                currentBodyId = actualIds.get(ItemModelSlot.Body);
                                                bodyId.set(currentBodyId);
                                            }
                                            if (actualIds.containsKey(ItemModelSlot.Hands)) {
                                                currentHandsId = actualIds.get(ItemModelSlot.Hands);
                                                handsId.set(currentHandsId);
                                            }
                                            if (actualIds.containsKey(ItemModelSlot.Legs)) {
                                                currentLegsId = actualIds.get(ItemModelSlot.Legs);
                                                legsId.set(currentLegsId);
                                            }
                                            if (actualIds.containsKey(ItemModelSlot.Feet)) {
                                                currentFeetId = actualIds.get(ItemModelSlot.Feet);
                                                feetId.set(currentFeetId);
                                            }
                                        }

                                        updateAnimator(character);
                                        isLoading = false;
                                        System.out.println("[VIEWER] Character reloaded asynchronously.");
                                    } catch (Exception ex) {
                                        isLoading = false;
                                        loadingStatus = "Error: " + ex.getMessage();
                                        System.err.println("[VIEWER] Failed to apply loaded character: " + ex.getMessage());
                                    }
                                },
                                (error) -> {
                                    isLoading = false;
                                    loadingStatus = "Error: " + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
                                    System.err.println("[VIEWER] Failed to load character asynchronously: " + error.getMessage());
                                }
                        );
                    } else if (zoneReloadPending) {
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
                                (nextZone) -> {
                                    try {
                                        applyLoadedZone(nextZone);
                                        currentZoneLabel = String.format("%03d - %s", nextZone.zoneId(), nextZone.zoneName());
                                        isLoading = false;
                                        System.out.println("[VIEWER] Zone reloaded asynchronously: " + currentZoneLabel);
                                    } catch (Exception ex) {
                                        isLoading = false;
                                        loadingStatus = "Error: " + ex.getMessage();
                                        System.err.println("[VIEWER] Failed to apply loaded zone: " + ex.getMessage());
                                    }
                                },
                                (error) -> {
                                    isLoading = false;
                                    loadingStatus = "Error: " + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
                                    System.err.println("[VIEWER] Failed to load zone asynchronously: " + error.getMessage());
                                }
                        );
                    }
                }

                double currentTime = glfwGetTime();
                double deltaTime = currentTime - lastFrameTime;
                if (!isLoading && currentLoadedZone != null && !zoneRenderChunks.isEmpty()) {
                    float dt = (float) Math.min(deltaTime, 0.1);
                    float moveX = 0.0f;
                    float moveZ = 0.0f;
                    if (!ImGui.getIO().getWantCaptureKeyboard()) {
                        float sinYaw = (float) Math.sin(orbitYaw);
                        float cosYaw = (float) Math.cos(orbitYaw);
                        if (keyPressed[GLFW_KEY_W]) {
                            moveX -= sinYaw;
                            moveZ -= cosYaw;
                        }
                        if (keyPressed[GLFW_KEY_S]) {
                            moveX += sinYaw;
                            moveZ += cosYaw;
                        }
                        if (keyPressed[GLFW_KEY_A]) {
                            moveX -= cosYaw;
                            moveZ += sinYaw;
                        }
                        if (keyPressed[GLFW_KEY_D]) {
                            moveX += cosYaw;
                            moveZ -= sinYaw;
                        }
                    }
                    float len = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
                    if (len > 1e-5f) {
                        float speed = CHAR_MOVE_SPEED * dt;
                        charTx.set(charTx.get() + moveX / len * speed);
                        charTz.set(charTz.get() - moveZ / len * speed);
                        targetOffsetX = charTx.get();
                        targetOffsetZ = -charTz.get();
                        snapCharacterYToTerrain(currentLoadedZone);
                    }
                }
                lastFrameTime = currentTime;
                if (isPlaying.get()) {
                    animationTime += deltaTime * playbackSpeed.get();
                }

                // 2. New Frame for ImGui
                imGuiGlfw.newFrame();
                ImGui.newFrame();

                // Build GUI
                ImGui.begin("FFXI Character Viewer Controls");

                if (isLoading) {
                    ImGui.textColored(1.0f, 0.8f, 0.0f, 1.0f, "Status: " + loadingStatus);
                    ImGui.separator();
                }

                ImGui.text("Zone:");
                if (!zoneOptions.isEmpty()) {
                    String[] zoneLabels = zoneOptions.stream().map(ZoneModelLoader.ZoneSelectionOption::label).toArray(String[]::new);
                    if (ImGui.combo("Map Zone", selectedZoneIdx, zoneLabels)) {
                        ZoneModelLoader.ZoneSelectionOption selectedZone = zoneOptions.get(selectedZoneIdx.get());
                        currentZoneId = selectedZone.zoneId();
                        currentZoneLabel = selectedZone.label();
                        zoneReloadPending = true;
                    }
                } else {
                    ImGui.textDisabled("No selectable zones available");
                }
                ImGui.text("Loaded: " + currentZoneLabel);
                ImGui.separator();

                if (ImGui.combo("Race/Gender", selectedRaceIdx, raceNames)) {
                    currentRace = races[selectedRaceIdx.get()];
                    reloadPending = true;
                }
                if (ImGui.inputInt("Face ID", faceId)) {
                    currentFaceId = faceId.get();
                    reloadPending = true;
                }

                ImGui.separator();
                ImGui.text("Armor Model IDs:");
                if (ImGui.inputInt("Head ID", headId)) {
                    currentHeadId = headId.get();
                    reloadPending = true;
                }
                if (ImGui.inputInt("Body ID", bodyId)) {
                    currentBodyId = bodyId.get();
                    reloadPending = true;
                }
                if (ImGui.inputInt("Hands ID", handsId)) {
                    currentHandsId = handsId.get();
                    reloadPending = true;
                }
                if (ImGui.inputInt("Legs ID", legsId)) {
                    currentLegsId = legsId.get();
                    reloadPending = true;
                }
                if (ImGui.inputInt("Feet ID", feetId)) {
                    currentFeetId = feetId.get();
                    reloadPending = true;
                }

                ImGui.separator();
                ImGui.text("Animation Layers:");

                String[] baseAnimNames = new String[character.slot0Animations().size()];
                for (int i = 0; i < character.slot0Animations().size(); i++) {
                    baseAnimNames[i] = character.slot0Animations().get(i).sectionId();
                }
                if (ImGui.combo("Base / Lower Body (Slot 0)", selectedBaseAnim, baseAnimNames)) {
                    baseAnimIndex = selectedBaseAnim.get();
                    String selectedName = baseAnimNames[baseAnimIndex];
                    
                    // Match prefix to find corresponding Slot 1 animation if possible (e.g. ded0 -> ded1)
                    if (selectedName.length() > 1) {
                        String basePrefix = selectedName.substring(0, selectedName.length() - 1);
                        boolean foundMatch = false;
                        for (int j = 0; j < character.slot1Animations().size(); j++) {
                            String upperName = character.slot1Animations().get(j).sectionId();
                            if (upperName.length() > 1) {
                                String upperPrefix = upperName.substring(0, upperName.length() - 1);
                                if (upperPrefix.equalsIgnoreCase(basePrefix)) {
                                    upperAnimIndex = j;
                                    selectedUpperAnim.set(j + 1);
                                    foundMatch = true;
                                    break;
                                }
                            }
                        }
                        if (!foundMatch) {
                            upperAnimIndex = -1;
                            selectedUpperAnim.set(0);
                        }
                    } else {
                        upperAnimIndex = -1;
                        selectedUpperAnim.set(0);
                    }
                    updateAnimator(character);
                }

                String[] upperAnimNames = new String[character.slot1Animations().size() + 1];
                upperAnimNames[0] = "None (Fallback to base)";
                for (int i = 0; i < character.slot1Animations().size(); i++) {
                    upperAnimNames[i + 1] = character.slot1Animations().get(i).sectionId();
                }
                if (ImGui.combo("Upper Body Override (Slot 1)", selectedUpperAnim, upperAnimNames)) {
                    upperAnimIndex = selectedUpperAnim.get() - 1;
                    updateAnimator(character);
                }

                ImGui.separator();
                ImGui.text("Playback Controls:");
                ImGui.checkbox("Play Animation", isPlaying);
                ImGui.sliderFloat("Speed Multiplier", playbackSpeed.getData(), 0.0f, 3.0f);
                if (ImGui.button("Reset Time")) {
                    animationTime = 0.0;
                }

                ImGui.separator();
                ImGui.text("Character World Transform:");
                ImGui.sliderFloat("Pos X", charTx.getData(), -5000.0f, 5000.0f);
                ImGui.sliderFloat("Pos Y", charTy.getData(), -5000.0f, 5000.0f);
                ImGui.sliderFloat("Pos Z", charTz.getData(), -5000.0f, 5000.0f);
                ImGui.sliderFloat("Rot X", charRx.getData(), -180.0f, 180.0f);
                ImGui.sliderFloat("Rot Y", charRy.getData(), -180.0f, 180.0f);
                ImGui.sliderFloat("Rot Z", charRz.getData(), -180.0f, 180.0f);
                ImGui.sliderFloat("Scale", charScale.getData(), 0.1f, 5.0f);

                ImGui.separator();
                ImGui.text("Camera / Eye Settings:");
                float[] yawArr = { orbitYaw };
                if (ImGui.sliderFloat("Orbit Yaw", yawArr, -3.14159f, 3.14159f)) {
                    orbitYaw = yawArr[0];
                }
                float[] pitchArr = { orbitPitch };
                if (ImGui.sliderFloat("Orbit Pitch", pitchArr, -1.3f, 1.3f)) {
                    orbitPitch = pitchArr[0];
                }
                float[] distArr = { orbitDistance };
                if (ImGui.sliderFloat("Orbit Dist", distArr, 0.75f, 30.0f)) {
                    orbitDistance = distArr[0];
                }
                float[] targetXArr = { targetOffsetX };
                if (ImGui.sliderFloat("Target X", targetXArr, -5000.0f, 5000.0f)) {
                    targetOffsetX = targetXArr[0];
                }
                float[] targetYArr = { targetOffsetY };
                if (ImGui.sliderFloat("Target Y", targetYArr, -5000.0f, 5000.0f)) {
                    targetOffsetY = targetYArr[0];
                }
                float[] targetZArr = { targetOffsetZ };
                if (ImGui.sliderFloat("Target Z", targetZArr, -5000.0f, 5000.0f)) {
                    targetOffsetZ = targetZArr[0];
                }

                float eyeX = (float) (orbitDistance * Math.cos(orbitPitch) * Math.sin(orbitYaw)) + targetOffsetX;
                float eyeY = (float) (orbitDistance * Math.sin(orbitPitch)) + targetOffsetY;
                float eyeZ = (float) (orbitDistance * Math.cos(orbitPitch) * Math.cos(orbitYaw)) + targetOffsetZ;
                ImGui.text(String.format("Eye Coordinates: X=%.2f Y=%.2f Z=%.2f", eyeX, eyeY, eyeZ));

                ImGui.end();

                // 3. Render Game scene with dynamic frameModelMatrix
                Matrix4f frameModelMatrix = new Matrix4f()
                        .scale(1f, -1f, -1f)
                        .translate(character.modelOffset())
                        .translate(charTx.get(), charTy.get(), charTz.get())
                        .rotateXYZ(
                                (float) Math.toRadians(charRx.get()),
                                (float) Math.toRadians(charRy.get()),
                                (float) Math.toRadians(charRz.get())
                        )
                        .scale(charScale.get());
                renderFrame(shader, textureLoader, meshBuffers, animator, frameModelMatrix, animationTime, zoneShader, zoneTextureLoader, zoneRenderChunks);

                // 4. Render ImGui
                ImGui.render();
                imGuiGl3.renderDrawData(ImGui.getDrawData());

                glfwSwapBuffers(window);
                if (firstFrame) {
                    firstFrame = false;
                    int glErr = org.lwjgl.opengl.GL11.glGetError();
                    if (glErr != 0) {
                        System.err.printf("[VIEWER] OpenGL error after first frame: 0x%X%n", glErr);
                    } else {
                        System.out.println("[VIEWER] First frame rendered, no GL errors");
                    }
                }
            }

            for (CharacterMeshBuffer meshBuffer : meshBuffers) {
                meshBuffer.close();
            }
            for (ZoneRenderChunk chunk : zoneRenderChunks) {
                chunk.buffer().close();
            }
            textureLoader.close();
            zoneTextureLoader.close();
            shader.close();
            zoneShader.close();
            taskScheduler.shutdown();

        } finally {
            imGuiGlfw.dispose();
            imGuiGl3.dispose();
            ImGui.destroyContext();
            glfwDestroyWindow(window);
            glfwTerminate();
        }
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
            if (!dragging) {
                return;
            }
            double dx = xpos - lastMouseX;
            double dy = ypos - lastMouseY;
            lastMouseX = xpos;
            lastMouseY = ypos;
            orbitYaw += (float) (dx * 0.01f);
            orbitPitch = clamp(orbitPitch + (float) (dy * 0.01f), -1.3f, 1.3f);
        });
        glfwSetScrollCallback(window, (handle, xoffset, yoffset) -> {
            if (ImGui.getIO().getWantCaptureMouse()) return;
            orbitDistance = clamp(orbitDistance - (float) yoffset * 0.25f, 0.75f, 50.0f);
        });
    }

    private void renderFrame(
            CharacterShader shader,
            CharacterTextureLoader textureLoader,
            List<CharacterMeshBuffer> meshBuffers,
            CharacterAnimator animator,
            Matrix4f modelMatrix,
            double elapsedSeconds,
            ZoneShader zoneShader,
            ZoneTextureLoader zoneTextureLoader,
            List<ZoneRenderChunk> zoneRenderChunks
    ) {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        Matrix4f projectionMatrix = createProjectionMatrix();
        Matrix4f viewMatrix = createViewMatrix();
        renderZoneFrame(zoneShader, zoneTextureLoader, zoneRenderChunks, projectionMatrix, viewMatrix);

        Matrix4f[] jointMatrices = animator.computePoseMatrices(elapsedSeconds);

        shader.bind();
        shader.setMatrices(projectionMatrix, viewMatrix, modelMatrix);
        shader.setDiscardThreshold(0.0f); // Disabled for debugging
        shader.uploadJointMatrices(jointMatrices);

        glDisable(GL_CULL_FACE);
        glActiveTexture(GL_TEXTURE0);
        for (CharacterMeshBuffer meshBuffer : meshBuffers) {
            glBindTexture(GL_TEXTURE_2D, textureLoader.textureId(meshBuffer.textureName()));
            meshBuffer.draw();
        }
        glEnable(GL_CULL_FACE);
        glFrontFace(GL_CCW);
    }

    private void renderZoneFrame(
            ZoneShader zoneShader,
            ZoneTextureLoader zoneTextureLoader,
            List<ZoneRenderChunk> zoneRenderChunks,
            Matrix4f projectionMatrix,
            Matrix4f viewMatrix
    ) {
        if (zoneShader == null || zoneTextureLoader == null || zoneRenderChunks.isEmpty()) {
            return;
        }

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
        glEnable(GL_CULL_FACE);
        glFrontFace(GL_CCW);
    }

    private void applyLoadedZone(LoadedZone loadedZone) {
        for (ZoneRenderChunk renderChunk : zoneRenderChunks) {
            renderChunk.buffer().close();
        }
        zoneRenderChunks.clear();

        this.currentLoadedZone = loadedZone;
        zoneTextureLoader.close();
        zoneTextureLoader = new ZoneTextureLoader(loadedZone.textureSources());
        currentZoneId = loadedZone.zoneId();
        currentZoneLabel = String.format("%03d - %s", loadedZone.zoneId(), loadedZone.zoneName());

        for (LoadedZone.ZoneChunkInstance chunkInstance : loadedZone.chunkInstances()) {
            zoneRenderChunks.add(new ZoneRenderChunk(
                    new ZoneMeshBuffer(chunkInstance.chunk()),
                    new Matrix4f(chunkInstance.modelMatrix()),
                    chunkInstance.invertCull(),
                    chunkInstance.discardThreshold()
            ));
        }

        if (character != null) {
            snapCharacterToTerrain(loadedZone);
        }
    }

    private void snapCharacterToTerrain(LoadedZone loadedZone) {
        ZoneCollisionSystem collision = loadedZone.collisionSystem();
        if (collision == null) {
            System.out.println("[VIEWER] No collision system — cannot snap to terrain");
            return;
        }

        float[] spawnPos = null;
        float y0 = collision.findFloorY(0.0f, 0.0f, 2000.0f);
        if (Float.isFinite(y0)) {
            spawnPos = new float[]{0.0f, y0, 0.0f};
        } else {
            spawnPos = collision.findAnyFloor();
        }

        if (spawnPos == null) {
            System.out.println("[VIEWER] Could not find any floor — leaving character in place");
            return;
        }

        float floorWorldY = spawnPos[1];
        float worldX = spawnPos[0];
        float worldZ = spawnPos[2];

        charTx.set(worldX);
        charTz.set(-worldZ);
        charTy.set(-character.feetOffsetY() - floorWorldY);

        float charCenterY = character.feetOffsetY() + floorWorldY;
        targetOffsetX = worldX;
        targetOffsetY = charCenterY;
        targetOffsetZ = worldZ;
        orbitPitch = -0.25f;

        System.out.printf("[VIEWER] Snapped via collision: worldXYZ=(%.1f,%.1f,%.1f) floorY=%.2f charTy=%.4f%n",
                worldX, floorWorldY, worldZ, floorWorldY, charTy.get());
    }

    private void snapCharacterYToTerrain(LoadedZone zone) {
        if (zone == null || zone.collisionSystem() == null) {
            return;
        }
        float worldX = charTx.get();
        float worldZ = -charTz.get();
        float currentWorldY = -charTy.get();
        float y = zone.collisionSystem().findFloorY(worldX, worldZ, currentWorldY + 2.0f);
        if (!Float.isFinite(y)) {
            return;
        }
        charTy.set(-character.feetOffsetY() - y);
        targetOffsetY = character.feetOffsetY() + y;
    }

    private static int findZoneOptionIndexById(List<ZoneModelLoader.ZoneSelectionOption> options, int zoneId) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).zoneId() == zoneId) {
                return i;
            }
        }
        return -1;
    }

    private Matrix4f createProjectionMatrix() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(window, width, height);
            float aspect = Math.max(1, width.get(0)) / (float) Math.max(1, height.get(0));
            return new Matrix4f().perspective((float) Math.toRadians(45.0), aspect, 0.01f, 100000.0f);
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ZoneRenderChunk(
            ZoneMeshBuffer buffer,
            Matrix4f modelMatrix,
            boolean invertCull,
            float discardThreshold
    ) {
    }
}
