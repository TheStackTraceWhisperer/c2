package com.ffxi.viewer.model;

import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import com.ffxi.model.BgraColor;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.SkeletonAnimationResource;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.model.SkeletonMeshResource;
import com.ffxi.model.SkeletonMeshVertex;
import com.ffxi.model.SkeletonResource;
import com.ffxi.model.TextureResource;
import com.ffxi.model.Vec3f;
import com.ffxi.parser.DatParser;
import com.ffxi.tables.CharacterIdToResourceId;
import com.ffxi.tables.ItemModelSlot;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.CharacterAnimator;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CharacterModelLoader {

    public static LoadedCharacter loadCharacter(Path ffxiRoot, RaceGenderConfig race) throws IOException {
        return loadCharacter(ffxiRoot, race, 0, 0, 0, 0, 0, 0);
    }

    public static LoadedCharacter loadCharacter(Path ffxiRoot, RaceGenderConfig race,
            int faceModelId, int headModelId, int bodyModelId, int handsModelId, int legsModelId, int feetModelId) throws IOException {
        
        if (!Files.isDirectory(ffxiRoot)) {
            throw new IllegalArgumentException("FFXI path does not exist: " + ffxiRoot.toAbsolutePath());
        }

        File dllFile = com.ffxi.io.FileUtil.resolveCaseInsensitive(ffxiRoot.toFile(), "FFXiMain.dll");
        if (dllFile == null || !dllFile.exists()) {
            throw new IllegalArgumentException("FFXiMain.dll not found in: " + ffxiRoot.toAbsolutePath());
        }

        byte[] dllBytes = Files.readAllBytes(dllFile.toPath());
        com.ffxi.crypto.KeyTables.loadFromDll(new ByteReader(dllBytes, dllFile.getName()));

        FileTableManager fileTableManager = new FileTableManager();
        fileTableManager.loadFromDirectory(ffxiRoot.toFile());

        String skeletonPath = requirePath(
                CharacterIdToResourceId.getRaceConfigPath(race, new ByteReader(dllBytes, dllFile.getName()), fileTableManager),
                "race skeleton"
        );
        System.out.println("[VIEWER] Reading skeleton DAT: " + skeletonPath);
        ParsedDat skeletonDat = loadDat(Path.of(skeletonPath));

        String upperAnimPath = requirePath(
                CharacterIdToResourceId.getUpperBodyAnimationPath(race, new ByteReader(dllBytes, dllFile.getName()), fileTableManager),
                "upper-body animation"
        );
        System.out.println("[VIEWER] Reading upper animation DAT: " + upperAnimPath);
        ParsedDat upperAnimationDat = loadDat(Path.of(upperAnimPath));

        List<SkeletonResource> skeletons = new ArrayList<>();
        List<SkeletonMeshResource> meshResources = new ArrayList<>();
        List<SkeletonAnimationResource> slot0Animations = new ArrayList<>();
        List<SkeletonAnimationResource> slot1Animations = new ArrayList<>();
        Map<String, CharacterTextureLoader.TextureSource> textureSources = new LinkedHashMap<>();

        collectSkeletons(skeletonDat.graph().rootDirectory(), skeletons);
        collectSkeletonMeshes(skeletonDat.graph().rootDirectory(), meshResources);
        collectAnimations(skeletonDat.graph().rootDirectory(), slot0Animations);
        collectTextureSources(skeletonDat.graph().rootDirectory(), skeletonDat.rawData(), textureSources);

        collectSkeletonMeshes(upperAnimationDat.graph().rootDirectory(), meshResources);
        collectAnimations(upperAnimationDat.graph().rootDirectory(), slot1Animations);
        collectTextureSources(upperAnimationDat.graph().rootDirectory(), upperAnimationDat.rawData(), textureSources);

        System.out.println("[VIEWER] Loaded Slot 0 Animations (Base/Lower body):");
        for (SkeletonAnimationResource anim : slot0Animations) {
            System.out.println("  - " + anim.sectionId() + " (frames: " + anim.frameCount() + ")");
        }
        System.out.println("[VIEWER] Loaded Slot 1 Animations (Upper body override):");
        for (SkeletonAnimationResource anim : slot1Animations) {
            System.out.println("  - " + anim.sectionId() + " (frames: " + anim.frameCount() + ")");
        }

        Map<ItemModelSlot, Integer> modelIds = new LinkedHashMap<>();
        modelIds.put(ItemModelSlot.Face, faceModelId);
        modelIds.put(ItemModelSlot.Head, headModelId);
        modelIds.put(ItemModelSlot.Body, bodyModelId);
        modelIds.put(ItemModelSlot.Hands, handsModelId);
        modelIds.put(ItemModelSlot.Legs, legsModelId);
        modelIds.put(ItemModelSlot.Feet, feetModelId);

        Map<ItemModelSlot, Integer> actualModelIds = new LinkedHashMap<>();

        for (Map.Entry<ItemModelSlot, Integer> entry : modelIds.entrySet()) {
            ItemModelSlot slot = entry.getKey();
            int modelId = entry.getValue();
            System.out.println("[VIEWER-DEBUG] --- Start processing slot: " + slot + " (requested modelId: " + modelId + ") ---");
            
            int activeId = modelId;
            String meshPath = CharacterIdToResourceId.getEquipmentModelPath(
                    race,
                    slot,
                    modelId,
                    new ByteReader(dllBytes, dllFile.getName()),
                    fileTableManager
            );
            System.out.println("[VIEWER-DEBUG] Initial resolution for slot " + slot + ", modelId " + modelId + " returned path: " + meshPath);
            
            boolean isDummy = false;
            try {
                if (meshPath != null) {
                    long size = Files.size(Path.of(meshPath));
                    if (size < 1024) {
                        isDummy = true;
                        System.out.println("[VIEWER-DEBUG] File " + meshPath + " is a dummy (size: " + size + " bytes).");
                    }
                    if (meshPath.equalsIgnoreCase(skeletonPath) || meshPath.equalsIgnoreCase(upperAnimPath)) {
                        isDummy = true;
                        System.out.println("[VIEWER-DEBUG] File " + meshPath + " matches skeleton or upper animation path.");
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            if (isDummy && modelId != 0) {
                System.out.println("[VIEWER-DEBUG] Setting meshPath to null to trigger fallback-to-0.");
                meshPath = null;
            }
            
            if (meshPath == null && modelId != 0) {
                System.out.println("[VIEWER-DEBUG] Path was null. Attempting fallback to modelId 0 for slot: " + slot);
                meshPath = CharacterIdToResourceId.getEquipmentModelPath(
                        race,
                        slot,
                        0,
                        new ByteReader(dllBytes, dllFile.getName()),
                        fileTableManager
                );
                System.out.println("[VIEWER-DEBUG] Fallback resolution for slot " + slot + ", modelId 0 returned path: " + meshPath);
                activeId = 0;
            }
            
            System.out.println("[VIEWER] Slot " + slot + " (modelId: " + modelId + ") resolved to: " + meshPath);
            if (meshPath == null) {
                System.out.println("[VIEWER-DEBUG] No path found for slot " + slot + " (even after fallback to 0). Skipping slot.");
                continue;
            }
            
            System.out.println("[VIEWER-DEBUG] Loading DAT file: " + meshPath);
            ParsedDat equipmentDat = loadDat(Path.of(meshPath));
            
            List<SkeletonMeshResource> slotMeshes = new ArrayList<>();
            collectSkeletonMeshes(equipmentDat.graph().rootDirectory(), slotMeshes);
            System.out.println("[VIEWER-DEBUG] Collected " + slotMeshes.size() + " meshes from DAT: " + meshPath);
            
            if (slotMeshes.isEmpty() && modelId != 0) {
                System.out.println("[VIEWER-DEBUG] Mesh list is empty. Attempting fallback to modelId 0 due to empty meshes for slot: " + slot);
                String fallbackPath = CharacterIdToResourceId.getEquipmentModelPath(
                        race,
                        slot,
                        0,
                        new ByteReader(dllBytes, dllFile.getName()),
                        fileTableManager
                );
                System.out.println("[VIEWER-DEBUG] Mesh empty fallback path resolution returned: " + fallbackPath);
                
                if (fallbackPath != null) {
                    System.out.println("[VIEWER] Slot " + slot + " (modelId: " + modelId + ") resolved to empty mesh. Falling back to: " + fallbackPath);
                    equipmentDat = loadDat(Path.of(fallbackPath));
                    slotMeshes.clear();
                    collectSkeletonMeshes(equipmentDat.graph().rootDirectory(), slotMeshes);
                    System.out.println("[VIEWER-DEBUG] Collected " + slotMeshes.size() + " meshes from fallback DAT: " + fallbackPath);
                    activeId = 0;
                }
            }
            
            meshResources.addAll(slotMeshes);
            actualModelIds.put(slot, activeId);
            
            for (SkeletonMeshResource meshRes : slotMeshes) {
                System.out.println("  - Sub-resource Mesh: " + meshRes.sectionId());
            }
            
            Map<String, CharacterTextureLoader.TextureSource> slotTextures = new LinkedHashMap<>();
            collectTextureSources(equipmentDat.graph().rootDirectory(), equipmentDat.rawData(), slotTextures);
            System.out.println("[VIEWER-DEBUG] Collected " + slotTextures.size() + " textures from DAT: " + meshPath);
            textureSources.putAll(slotTextures);
            
            for (String texName : slotTextures.keySet()) {
                System.out.println("  - Sub-resource Texture: " + texName);
            }
        }

        SkeletonResource skeleton = skeletons.stream()
                .filter(candidate -> !candidate.joints().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No skeleton resource found for " + race.name()));

        List<SkeletonMeshMesh> meshes = new ArrayList<>();
        for (SkeletonMeshResource meshResource : meshResources) {
            meshes.addAll(meshResource.meshes());
        }
        if (meshes.isEmpty()) {
            throw new IllegalStateException("No skeleton meshes found for " + race.name());
        }

        SkeletonAnimationResource idleLower = findAnimation(slot0Animations, "idl0");
        SkeletonAnimationResource idleUpper = findAnimation(slot1Animations, "idl1");

        CharacterAnimator animator = new CharacterAnimator(skeleton, idleLower, idleUpper);
        Bounds bounds = computeBounds(meshes, animator.computeTPoseMatrices());
        System.out.printf("[VIEWER] Bounds: min=%s max=%s center=%s radius=%.3f%n",
                bounds.center().sub(new Vector3f(bounds.radius())),
                bounds.center().add(new Vector3f(bounds.radius())),
                bounds.center(), bounds.radius());
        Vector3f modelOffset = new Vector3f(
                -bounds.center().x(),
                -bounds.center().y(),
                -bounds.center().z()
        );
        System.out.printf("[VIEWER] ModelOffset: %s%n", modelOffset);

        System.out.printf("[VIEWER] Race=%s skeleton=%d joints meshes=%d textures=%d lower=%s upper=%s%n",
                race.name(),
                skeleton.joints().size(),
                meshes.size(),
                textureSources.size(),
                idleLower != null ? idleLower.sectionId() : "missing",
                idleUpper != null ? idleUpper.sectionId() : "missing");

        return new LoadedCharacter(skeleton, meshes, textureSources, slot0Animations, slot1Animations, bounds.radius(), bounds.feetOffsetY(), modelOffset, actualModelIds);
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
}
