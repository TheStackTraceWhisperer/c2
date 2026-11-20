package com.ffxi.viewer.model;

import com.example.zone.dto.ActorAppearance;
import com.example.zone.dto.ActorLookType;
import com.ffxi.io.ByteReader;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.SkeletonAnimationResource;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.model.SkeletonMeshOcclusion;
import com.ffxi.model.SkeletonMeshResource;
import com.ffxi.model.SkeletonMeshVertex;
import com.ffxi.model.SkeletonResource;
import com.ffxi.model.TextureResource;
import com.ffxi.model.Vec3f;
import com.ffxi.parser.DatParser;
import com.ffxi.parser.InitSchedulerParser;
import com.ffxi.tables.FfxiModelIndex;
import com.ffxi.tables.ItemModelSlot;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.CharacterAnimator;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import lombok.extern.slf4j.Slf4j;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public final class CharacterModelLoader {

    private static final int MAX_INIT_INHERITANCE_DEPTH = 4;

    private CharacterModelLoader() {
    }

    public static LoadedCharacter loadCharacter(Path ffxiRoot, RaceGenderConfig race) throws IOException {
        return loadCharacter(ffxiRoot, race, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static LoadedCharacter loadCharacter(
            Path ffxiRoot,
            RaceGenderConfig race,
            int faceModelId,
            int headModelId,
            int bodyModelId,
            int handsModelId,
            int legsModelId,
            int feetModelId
    ) throws IOException {
        return loadCharacter(
                ffxiRoot,
                race,
                faceModelId,
                headModelId,
                bodyModelId,
                handsModelId,
                legsModelId,
                feetModelId,
                0,
                0,
                0
        );
    }

    public static LoadedCharacter loadCharacter(
            Path ffxiRoot,
            RaceGenderConfig race,
            int faceModelId,
            int headModelId,
            int bodyModelId,
            int handsModelId,
            int legsModelId,
            int feetModelId,
            int mainWeaponId,
            int subShieldId,
            int rangedId
    ) throws IOException {
        RaceGenderConfig resolvedRace = race != null ? race : RaceGenderConfig.HumeM;
        ActorLookType lookType = resolvedRace.isChocobo() ? ActorLookType.CHOCOBO : ActorLookType.EQUIPPED;
        ActorAppearance appearance = new ActorAppearance(
                lookType,
                resolvedRace.index,
                ActorAppearance.stripSlotPrefix(faceModelId),
                ActorAppearance.stripSlotPrefix(headModelId),
                ActorAppearance.stripSlotPrefix(bodyModelId),
                ActorAppearance.stripSlotPrefix(handsModelId),
                ActorAppearance.stripSlotPrefix(legsModelId),
                ActorAppearance.stripSlotPrefix(feetModelId),
                ActorAppearance.stripSlotPrefix(mainWeaponId),
                ActorAppearance.stripSlotPrefix(subShieldId),
                ActorAppearance.stripSlotPrefix(rangedId),
                0,
                1,
                0,
                0,
                0
        );
        return loadActor(ffxiRoot, appearance);
    }

    public static LoadedCharacter loadActor(Path ffxiRoot, ActorAppearance appearance) throws IOException {
        if (appearance == null || appearance.lookType() == ActorLookType.NONE) {
            return loadActor(ffxiRoot, ActorAppearance.forPlayer(RaceGenderConfig.HumeM.index, 0, 1));
        }

        FfxiModelIndex index = FfxiModelIndex.getOrLoad(ffxiRoot);

        return switch (appearance.lookType()) {
            case EQUIPPED, CHOCOBO -> loadEquippedActor(index, appearance);
            case STANDARD, AUTOMATON -> loadStandardActor(index, appearance);
            case NONE -> loadEquippedActor(index, ActorAppearance.forPlayer(RaceGenderConfig.HumeM.index, 0, 1));
        };
    }

    private static LoadedCharacter loadEquippedActor(FfxiModelIndex index, ActorAppearance appearance) throws IOException {
        RaceGenderConfig race = (appearance.lookType() == ActorLookType.CHOCOBO)
                ? RaceGenderConfig.fromRaceIdOrDefault(appearance.raceId(), RaceGenderConfig.Chocobo)
                : RaceGenderConfig.fromRaceIdOrDefault(appearance.raceId(), RaceGenderConfig.HumeM);

        String skeletonPath = requirePath(index.getSkeletonPath(race), "race skeleton for " + race.name());
        ParsedDat skeletonDat = loadDat(Path.of(skeletonPath));

        String upperAnimPath = index.getUpperBodyAnimationPath(race);
        ParsedDat upperAnimationDat = (upperAnimPath != null && !upperAnimPath.isBlank())
                ? loadDat(Path.of(upperAnimPath))
                : null;

        List<SkeletonResource> skeletons = new ArrayList<>();
        List<SkeletonMeshResource> meshResources = new ArrayList<>();
        List<SkeletonAnimationResource> slot0Animations = new ArrayList<>();
        List<SkeletonAnimationResource> slot1Animations = new ArrayList<>();
        Map<String, CharacterTextureLoader.TextureSource> textureSources = new LinkedHashMap<>();

        collectSkeletons(skeletonDat.graph().rootDirectory(), skeletons);
        collectSkeletonMeshes(skeletonDat.graph().rootDirectory(), meshResources);
        collectAnimations(skeletonDat.graph().rootDirectory(), slot0Animations);
        collectTextureSources(skeletonDat.graph().rootDirectory(), skeletonDat.rawData(), textureSources);

        if (upperAnimationDat != null) {
            collectSkeletonMeshes(upperAnimationDat.graph().rootDirectory(), meshResources);
            collectAnimations(upperAnimationDat.graph().rootDirectory(), slot1Animations);
            collectTextureSources(upperAnimationDat.graph().rootDirectory(), upperAnimationDat.rawData(), textureSources);
        }

        Map<ItemModelSlot, Integer> requestedModelIds = new LinkedHashMap<>();
        requestedModelIds.put(ItemModelSlot.Face, ActorAppearance.stripSlotPrefix(appearance.faceId()));
        requestedModelIds.put(ItemModelSlot.Head, ActorAppearance.stripSlotPrefix(appearance.head()));
        requestedModelIds.put(ItemModelSlot.Body, ActorAppearance.stripSlotPrefix(appearance.body()));
        requestedModelIds.put(ItemModelSlot.Hands, ActorAppearance.stripSlotPrefix(appearance.hands()));
        requestedModelIds.put(ItemModelSlot.Legs, ActorAppearance.stripSlotPrefix(appearance.legs()));
        requestedModelIds.put(ItemModelSlot.Feet, ActorAppearance.stripSlotPrefix(appearance.feet()));
        requestedModelIds.put(ItemModelSlot.Main, ActorAppearance.stripSlotPrefix(appearance.mainWeapon()));
        requestedModelIds.put(ItemModelSlot.Sub, ActorAppearance.stripSlotPrefix(appearance.subShield()));
        requestedModelIds.put(ItemModelSlot.Range, ActorAppearance.stripSlotPrefix(appearance.ranged()));

        Map<ItemModelSlot, Integer> actualModelIds = new LinkedHashMap<>();

        for (Map.Entry<ItemModelSlot, Integer> entry : requestedModelIds.entrySet()) {
            ItemModelSlot slot = entry.getKey();
            int modelId = entry.getValue();

            if (slot == ItemModelSlot.Face && race.isChocobo()) {
                continue;
            }
            if (isWeaponSlot(slot) && (modelId == 0 || race.isNpcRace())) {
                continue;
            }

            boolean fallbackAllowed = isArmorOrFaceSlot(slot);
            int activeId = modelId;
            String meshPath = index.getEquipmentModelPath(race, slot, modelId);

            if (isDummyEquipmentPath(meshPath, skeletonPath, upperAnimPath)) {
                if (fallbackAllowed && modelId != 0) {
                    meshPath = null;
                } else if (isWeaponSlot(slot)) {
                    continue;
                }
            }

            if (meshPath == null && fallbackAllowed && modelId != 0) {
                meshPath = index.getEquipmentModelPath(race, slot, 0);
                activeId = 0;
                if (isDummyEquipmentPath(meshPath, skeletonPath, upperAnimPath)) {
                    meshPath = null;
                }
            }

            if (meshPath == null) {
                continue;
            }

            ParsedDat equipmentDat = loadDat(Path.of(meshPath));
            List<SkeletonMeshResource> slotMeshes = new ArrayList<>();
            collectSkeletonMeshes(equipmentDat.graph().rootDirectory(), slotMeshes);

            if (slotMeshes.isEmpty() && fallbackAllowed && activeId != 0) {
                String fallbackPath = index.getEquipmentModelPath(race, slot, 0);
                if (fallbackPath != null && !isDummyEquipmentPath(fallbackPath, skeletonPath, upperAnimPath)) {
                    equipmentDat = loadDat(Path.of(fallbackPath));
                    slotMeshes.clear();
                    collectSkeletonMeshes(equipmentDat.graph().rootDirectory(), slotMeshes);
                    activeId = 0;
                }
            }

            if (slotMeshes.isEmpty() && isWeaponSlot(slot)) {
                continue;
            }

            meshResources.addAll(slotMeshes);
            actualModelIds.put(slot, activeId);

            Map<String, CharacterTextureLoader.TextureSource> slotTextures = new LinkedHashMap<>();
            collectTextureSources(equipmentDat.graph().rootDirectory(), equipmentDat.rawData(), slotTextures);
            textureSources.putAll(slotTextures);
        }

        SkeletonResource skeleton = skeletons.stream()
                .filter(candidate -> !candidate.joints().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No skeleton resource found for " + race.name()));

        List<SkeletonMeshMesh> meshes = SkeletonMeshOcclusion.filterVisibleMeshes(
                meshResources,
                SkeletonMeshOcclusion.DEFAULT_HIDDEN_WEAPON_MASK
        );
        if (meshes.isEmpty()) {
            throw new IllegalStateException("No skeleton meshes found for " + race.name());
        }

        SkeletonAnimationResource idleLower = findAnimation(slot0Animations, "idl0");
        SkeletonAnimationResource idleUpper = findAnimation(slot1Animations, "idl1");

        CharacterAnimator animator = new CharacterAnimator(skeleton, idleLower, idleUpper);
        Bounds bounds = computeBounds(meshes, animator.computeTPoseMatrices());
        Vector3f modelOffset = new Vector3f(-bounds.center().x(), -bounds.center().y(), -bounds.center().z());

        log.debug(
                "[VIEWER] Loaded equipped actor race={} skeleton={} joints meshes={} textures={} lower={} upper={}",
                race.name(),
                skeleton.joints().size(),
                meshes.size(),
                textureSources.size(),
                idleLower != null ? idleLower.sectionId() : "missing",
                idleUpper != null ? idleUpper.sectionId() : "missing"
        );

        return new LoadedCharacter(
                skeleton,
                meshes,
                textureSources,
                slot0Animations,
                slot1Animations,
                bounds.radius(),
                bounds.feetOffsetY(),
                modelOffset,
                actualModelIds
        );
    }

    private static LoadedCharacter loadStandardActor(FfxiModelIndex index, ActorAppearance appearance) throws IOException {
        int modelId = appearance.modelId();
        StandardModelComponents components = loadStandardModelComponents(index, modelId, 0);

        if (components.scriptedLook() != null) {
            InitSchedulerParser.ScriptedLook sl = components.scriptedLook();
            ActorLookType lookType = (sl.race() >= 32 && sl.race() <= 36)
                    ? ActorLookType.CHOCOBO
                    : ActorLookType.EQUIPPED;
            ActorAppearance scriptedAppearance = new ActorAppearance(
                    lookType,
                    sl.race(),
                    ActorAppearance.stripSlotPrefix(sl.face()),
                    ActorAppearance.stripSlotPrefix(sl.head()),
                    ActorAppearance.stripSlotPrefix(sl.body()),
                    ActorAppearance.stripSlotPrefix(sl.hands()),
                    ActorAppearance.stripSlotPrefix(sl.legs()),
                    ActorAppearance.stripSlotPrefix(sl.feet()),
                    ActorAppearance.stripSlotPrefix(sl.main()),
                    ActorAppearance.stripSlotPrefix(sl.sub()),
                    0,
                    0,
                    appearance.size(),
                    appearance.animationSub(),
                    appearance.entityFlags(),
                    appearance.namePrefix()
            );
            return loadEquippedActor(index, scriptedAppearance);
        }

        SkeletonResource skeleton = components.skeleton();
        if (skeleton == null || skeleton.joints().isEmpty()) {
            throw new IllegalStateException("No skeleton resource found for standard model " + modelId);
        }

        int effectiveMask = SkeletonMeshOcclusion.DEFAULT_HIDDEN_WEAPON_MASK | components.hiddenWeaponMask();
        List<SkeletonMeshMesh> meshes = SkeletonMeshOcclusion.filterVisibleMeshes(components.meshResources(), effectiveMask);
        if (meshes.isEmpty()) {
            throw new IllegalStateException("No visible skeleton meshes found for standard model " + modelId);
        }

        List<SkeletonAnimationResource> slot0Animations = components.slot0Animations();
        SkeletonAnimationResource idleLower = findAnimation(slot0Animations, "idl0");
        CharacterAnimator animator = new CharacterAnimator(skeleton, idleLower, null);
        Bounds bounds = computeBounds(meshes, animator.computeTPoseMatrices());
        Vector3f modelOffset = new Vector3f(-bounds.center().x(), -bounds.center().y(), -bounds.center().z());

        log.debug(
                "[VIEWER] Loaded standard actor modelId={} skeleton={} joints meshes={} textures={} anims={} hiddenMask=0x{}",
                modelId,
                skeleton.joints().size(),
                meshes.size(),
                components.textureSources().size(),
                slot0Animations.size(),
                Integer.toHexString(effectiveMask)
        );

        return new LoadedCharacter(
                skeleton,
                meshes,
                components.textureSources(),
                slot0Animations,
                List.of(),
                bounds.radius(),
                bounds.feetOffsetY(),
                modelOffset,
                Map.of()
        );
    }

    private static StandardModelComponents loadStandardModelComponents(
            FfxiModelIndex index,
            int modelId,
            int depth
    ) throws IOException {
        String datPath = requirePath(index.getNpcModelPath(modelId), "standard NPC model " + modelId);
        ParsedDat dat = loadDat(Path.of(datPath));
        InitSchedulerParser.InitSchedulerResult init = InitSchedulerParser.parse(dat.rawData());

        List<SkeletonResource> skeletons = new ArrayList<>();
        List<SkeletonMeshResource> meshResources = new ArrayList<>();
        List<SkeletonAnimationResource> slot0Animations = new ArrayList<>();
        Map<String, CharacterTextureLoader.TextureSource> textureSources = new LinkedHashMap<>();

        collectSkeletons(dat.graph().rootDirectory(), skeletons);
        collectSkeletonMeshes(dat.graph().rootDirectory(), meshResources);
        collectAnimations(dat.graph().rootDirectory(), slot0Animations);
        collectTextureSources(dat.graph().rootDirectory(), dat.rawData(), textureSources);

        SkeletonResource localSkeleton = skeletons.stream()
                .filter(candidate -> !candidate.joints().isEmpty())
                .findFirst()
                .orElse(null);

        // Case 1: DAT directly contains a valid skeleton with joints.
        if (localSkeleton != null) {
            return new StandardModelComponents(
                    localSkeleton,
                    meshResources,
                    slot0Animations,
                    textureSources,
                    init.hiddenWeaponMask(),
                    null
            );
        }

        // Case 2: Skeleton-less variant inheriting a base model via init 0x98 opcode.
        if (init.baseModelId().isPresent() && depth < MAX_INIT_INHERITANCE_DEPTH) {
            int baseId = init.baseModelId().get();
            if (baseId != modelId && baseId > 0) {
                StandardModelComponents base = loadStandardModelComponents(index, baseId, depth + 1);
                if (base.scriptedLook() != null) {
                    return base;
                }
                List<SkeletonMeshResource> mergedMeshes = new ArrayList<>(base.meshResources());
                mergedMeshes.addAll(meshResources);

                Map<String, CharacterTextureLoader.TextureSource> mergedTextures = new LinkedHashMap<>(base.textureSources());
                mergedTextures.putAll(textureSources);

                List<SkeletonAnimationResource> mergedAnimations = new ArrayList<>(slot0Animations);
                mergedAnimations.addAll(base.slot0Animations());

                int mergedMask = base.hiddenWeaponMask() | init.hiddenWeaponMask();

                return new StandardModelComponents(
                        base.skeleton(),
                        mergedMeshes,
                        mergedAnimations,
                        mergedTextures,
                        mergedMask,
                        null
                );
            }
        }

        // Case 3: Skeleton-less init script specifying a PC/NPC equipped look (0x8d..0x95).
        if (init.scriptedLook().isPresent()) {
            return new StandardModelComponents(
                    null,
                    List.of(),
                    List.of(),
                    Map.of(),
                    init.hiddenWeaponMask(),
                    init.scriptedLook().get()
            );
        }

        throw new IllegalStateException("Standard model " + modelId + " has no skeleton and no resolvable init redirect");
    }

    private static boolean isArmorOrFaceSlot(ItemModelSlot slot) {
        return switch (slot) {
            case Face, Head, Body, Hands, Legs, Feet -> true;
            case Main, Sub, Range -> false;
        };
    }

    private static boolean isWeaponSlot(ItemModelSlot slot) {
        return switch (slot) {
            case Main, Sub, Range -> true;
            default -> false;
        };
    }

    private static boolean isDummyEquipmentPath(String meshPath, String skeletonPath, String upperAnimPath) {
        if (meshPath == null || meshPath.isBlank()) {
            return true;
        }
        try {
            Path path = Path.of(meshPath);
            if (!Files.isRegularFile(path)) {
                return true;
            }
            if (Files.size(path) < 1024) {
                return true;
            }
            if (skeletonPath != null && meshPath.equalsIgnoreCase(skeletonPath)) {
                return true;
            }
            if (upperAnimPath != null && meshPath.equalsIgnoreCase(upperAnimPath)) {
                return true;
            }
        } catch (Exception ignored) {
            return true;
        }
        return false;
    }

    private static ParsedDat loadDat(Path path) throws IOException {
        byte[] rawData = Files.readAllBytes(path);
        DatResourceGraph graph = DatParser.parseResourceGraph(
                new ByteReader(rawData, path.getFileName() != null ? path.getFileName().toString() : path.toString())
        );
        return new ParsedDat(path, graph, rawData);
    }

    private static void collectSkeletons(ResourceNode node, List<SkeletonResource> skeletons) {
        if (node instanceof SkeletonResource skeleton) {
            skeletons.add(skeleton);
        }
        if (node instanceof DirectoryResource directory) {
            for (ResourceNode child : directory.children()) {
                collectSkeletons(child, skeletons);
            }
        }
    }

    private static void collectSkeletonMeshes(ResourceNode node, List<SkeletonMeshResource> meshes) {
        if (node instanceof SkeletonMeshResource skeletonMesh) {
            meshes.add(skeletonMesh);
            return;
        }
        if (node instanceof DirectoryResource directory) {
            for (ResourceNode child : directory.children()) {
                collectSkeletonMeshes(child, meshes);
            }
        }
    }

    private static void collectAnimations(ResourceNode node, List<SkeletonAnimationResource> animations) {
        if (node instanceof SkeletonAnimationResource animation) {
            animations.add(animation);
            return;
        }
        if (node instanceof DirectoryResource directory) {
            for (ResourceNode child : directory.children()) {
                collectAnimations(child, animations);
            }
        }
    }

    private static void collectTextureSources(
            ResourceNode node,
            byte[] rawData,
            Map<String, CharacterTextureLoader.TextureSource> textureSources
    ) {
        if (node instanceof TextureResource textureResource) {
            String name = CharacterTextureLoader.normalizeName(textureResource.textureName());
            if (!name.isBlank()) {
                textureSources.putIfAbsent(name, new CharacterTextureLoader.TextureSource(textureResource, rawData));
            }
            return;
        }
        if (node instanceof DirectoryResource directory) {
            for (ResourceNode child : directory.children()) {
                collectTextureSources(child, rawData, textureSources);
            }
        }
    }

    private static SkeletonAnimationResource findAnimation(List<SkeletonAnimationResource> animations, String sectionId) {
        for (SkeletonAnimationResource animation : animations) {
            if (animation.sectionId().equalsIgnoreCase(sectionId)) {
                return animation;
            }
        }
        return null;
    }

    private static Bounds computeBounds(List<SkeletonMeshMesh> meshes, Matrix4f[] jointMatrices) {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector4f accum = new Vector4f();
        Vector4f scratch = new Vector4f();

        for (SkeletonMeshMesh mesh : meshes) {
            for (SkeletonMeshVertex vertex : mesh.resolvedVertices()) {
                Vector3f position = computeSkinnedPosition(vertex, jointMatrices, accum, scratch);
                min.min(position);
                max.max(position);
            }
        }

        if (!Float.isFinite(min.x)) {
            min.set(-0.5f, -0.5f, -0.5f);
            max.set(0.5f, 0.5f, 0.5f);
        }

        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        float radius = new Vector3f(max).sub(min).length() * 0.5f;
        // In FFXI's coordinate system, Y increases downward, so max.y corresponds to the feet.
        float feetOffsetY = max.y - center.y;
        return new Bounds(center, Math.max(radius, 0.5f), Math.max(feetOffsetY, 0.0f));
    }

    private static Vector3f computeSkinnedPosition(
            SkeletonMeshVertex vertex,
            Matrix4f[] jointMatrices,
            Vector4f accum,
            Vector4f scratch
    ) {
        float joint0Weight = vertex.joint0Weight();
        float joint1Weight = vertex.joint1Weight();
        if (joint1Weight <= 0.0f) {
            joint0Weight = 1.0f;
            joint1Weight = 0.0f;
        } else {
            float total = joint0Weight + joint1Weight;
            if (total > 0.0f && Math.abs(total - 1.0f) > 1.0e-6f) {
                joint0Weight /= total;
                joint1Weight /= total;
            }
        }

        accum.set(0.0f, 0.0f, 0.0f, 0.0f);
        transform(jointMatrices, vertex.jointIndex0(), vertex.p0(), joint0Weight, scratch);
        accum.add(scratch);
        transform(jointMatrices, vertex.jointIndex1(), vertex.p1(), joint1Weight, scratch);
        accum.add(scratch);
        return new Vector3f(accum.x, accum.y, accum.z);
    }

    private static void transform(
            Matrix4f[] jointMatrices,
            Integer jointIndex,
            Vec3f position,
            float weight,
            Vector4f dest
    ) {
        if (position == null || weight == 0.0f) {
            dest.set(0.0f, 0.0f, 0.0f, 0.0f);
            return;
        }

        int index = jointIndex == null ? 0 : Math.max(0, Math.min(jointMatrices.length - 1, jointIndex));
        jointMatrices[index].transform(position.x(), position.y(), position.z(), weight, dest);
    }

    private static String requirePath(String path, String description) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Unable to resolve " + description + " DAT path");
        }
        return path;
    }

    private record ParsedDat(Path path, DatResourceGraph graph, byte[] rawData) {
    }

    private record Bounds(Vector3f center, float radius, float feetOffsetY) {
    }

    private record StandardModelComponents(
            SkeletonResource skeleton,
            List<SkeletonMeshResource> meshResources,
            List<SkeletonAnimationResource> slot0Animations,
            Map<String, CharacterTextureLoader.TextureSource> textureSources,
            int hiddenWeaponMask,
            InitSchedulerParser.ScriptedLook scriptedLook
    ) {
    }
}
