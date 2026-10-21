package com.ffxi.viewer.model;

import com.ffxi.crypto.KeyTables;

import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import com.ffxi.io.FileUtil;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.TextureResource;
import com.ffxi.model.ZoneCollisionMap;
import com.ffxi.model.ZoneDefResource;
import com.ffxi.model.ZoneMeshChunk;
import com.ffxi.model.ZoneMeshResource;
import com.ffxi.model.ZoneObject;
import com.ffxi.parser.DatParser;
import com.ffxi.tables.ZoneConfig;
import com.ffxi.tables.ZoneIdToResourceId;
import com.ffxi.tables.ZoneNameTable;
import com.ffxi.viewer.collision.ZoneCollisionSystem;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ZoneModelLoader {
    private static final float MASK_DISCARD_THRESHOLD = 0.375f;

    private ZoneModelLoader() {
    }

    public static LoadedZone loadZone(Path ffxiRoot, int zoneId) throws IOException {
        if (!Files.isDirectory(ffxiRoot)) {
            throw new IllegalArgumentException("FFXI path does not exist: " + ffxiRoot.toAbsolutePath());
        }

        File dllFile = FileUtil.resolveCaseInsensitive(ffxiRoot.toFile(), "FFXiMain.dll");
        if (dllFile == null || !dllFile.exists()) {
            throw new IllegalArgumentException("FFXiMain.dll not found in: " + ffxiRoot.toAbsolutePath());
        }

        byte[] dllBytes = Files.readAllBytes(dllFile.toPath());
        KeyTables.loadFromDll(new ByteReader(dllBytes, dllFile.getName()));

        FileTableManager fileTableManager = new FileTableManager();
        fileTableManager.loadFromDirectory(ffxiRoot.toFile());

        String zonePath = ZoneIdToResourceId.getMainAreaResourcePath(new ZoneConfig(zoneId), fileTableManager);
        if (zonePath == null || zonePath.isBlank()) {
            throw new IllegalStateException("Unable to resolve zone DAT path for zoneId=" + zoneId);
        }

        ParsedDat zoneDat = loadDat(Path.of(zonePath));
        String zoneName = resolveZoneName(ffxiRoot, zoneId);

        Map<String, ZoneMeshResource> meshesByName = new LinkedHashMap<>();
        List<ZoneMeshResource> allMeshes = new ArrayList<>();
        collectZoneMeshes(zoneDat.graph().rootDirectory(), allMeshes);
        for (ZoneMeshResource mesh : allMeshes) {
            meshesByName.put(sanitizeId(mesh.meshName()), mesh);
        }

        Map<String, List<ZoneObject>> objectsByMeshName = new LinkedHashMap<>();
        collectZoneObjects(zoneDat.graph().rootDirectory(), objectsByMeshName);

        Map<String, CharacterTextureLoader.TextureSource> textureSources = new LinkedHashMap<>();
        collectTextureSources(zoneDat.graph().rootDirectory(), zoneDat.rawData(), textureSources);

        List<LoadedZone.ZoneChunkInstance> chunkInstances = new ArrayList<>();
        int meshesWithObjects = 0;
        int meshesWithoutObjects = 0;
        int totalChunkInstances = 0;
        
        for (Map.Entry<String, ZoneMeshResource> entry : meshesByName.entrySet()) {
            String meshName = entry.getKey();
            ZoneMeshResource mesh = entry.getValue();
            List<ZoneObject> objects = objectsByMeshName.get(meshName);
            if (objects == null || objects.isEmpty()) {
                meshesWithoutObjects++;
                continue;
            }

            meshesWithObjects++;
            float discardThreshold = meshName.startsWith("_") ? MASK_DISCARD_THRESHOLD : 0.0f;
            for (ZoneObject object : objects) {
                Matrix4f modelMatrix = buildObjectTransform(object);
                for (ZoneMeshChunk chunk : mesh.meshChunks()) {
                    chunkInstances.add(new LoadedZone.ZoneChunkInstance(
                            chunk,
                            modelMatrix,
                            chunk.invertCull(),
                            chunk.renderBlend() ? 0.0f : discardThreshold
                    ));
                    totalChunkInstances++;
                }
            }
        }

        System.out.printf("[LOADER] Zone %d: %d meshes total, %d with objects, %d without objects, %d chunk instances%n",
                zoneId, meshesByName.size(), meshesWithObjects, meshesWithoutObjects, totalChunkInstances);

        ZoneCollisionSystem collisionSystem = null;
        ZoneCollisionMap collisionMap = findCollisionMap(zoneDat.graph().rootDirectory());
        if (collisionMap != null) {
            collisionSystem = new ZoneCollisionSystem(collisionMap);
            System.out.printf("[LOADER] Zone %d: collision map loaded%n", zoneId);
        } else {
            System.out.printf("[LOADER] Zone %d: no collision map found%n", zoneId);
        }

        return new LoadedZone(zoneId, zoneName, chunkInstances, textureSources, collisionSystem);
    }

    public static List<ZoneSelectionOption> loadZoneOptions(Path ffxiRoot) throws IOException {
        if (!Files.isDirectory(ffxiRoot)) {
            return List.of();
        }

        File dllFile = FileUtil.resolveCaseInsensitive(ffxiRoot.toFile(), "FFXiMain.dll");
        if (dllFile == null || !dllFile.exists()) {
            return List.of();
        }
        byte[] dllBytes = Files.readAllBytes(dllFile.toPath());
        KeyTables.loadFromDll(new ByteReader(dllBytes, dllFile.getName()));

        FileTableManager fileTableManager = new FileTableManager();
        fileTableManager.loadFromDirectory(ffxiRoot.toFile());

        Path zoneNamePath = ffxiRoot.resolve(ZoneNameTable.RESOURCE_PATH);
        if (!Files.exists(zoneNamePath)) {
            return List.of();
        }
        ZoneNameTable zoneNames = ZoneNameTable.load(Files.readAllBytes(zoneNamePath));

        List<ZoneSelectionOption> options = new ArrayList<>();
        for (int zoneId = 0; zoneId < zoneNames.size(); zoneId++) {
            String zoneName = zoneNames.getName(zoneId).trim();
            if (zoneName.isBlank()) {
                continue;
            }
            String zonePath = ZoneIdToResourceId.getMainAreaResourcePath(new ZoneConfig(zoneId), fileTableManager);
            if (zonePath == null || zonePath.isBlank()) {
                continue;
            }
            if (!Files.exists(Path.of(zonePath))) {
                continue;
            }
            options.add(new ZoneSelectionOption(zoneId, String.format(Locale.ROOT, "%03d - %s", zoneId, zoneName)));
        }
        return List.copyOf(options);
    }

    private static Matrix4f buildObjectTransform(ZoneObject object) {
        float[] rotation = ZoneMeshMath.negateXYQuaternion(ZoneMeshMath.quaternionFromZYXEuler(object.rotX(), object.rotY(), object.rotZ()));
        return new Matrix4f()
                .translate(ZoneMeshMath.sanitizeFinite(-object.posX()), ZoneMeshMath.sanitizeFinite(-object.posY()), ZoneMeshMath.sanitizeFinite(object.posZ()))
                .rotate(new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]))
                .scale(ZoneMeshMath.sanitizeFinite(object.scaleX()), ZoneMeshMath.sanitizeFinite(object.scaleY()), ZoneMeshMath.sanitizeFinite(object.scaleZ()));
    }

    private static ZoneCollisionMap findCollisionMap(DirectoryResource dir) {
        if (dir == null) {
            return null;
        }
        for (ResourceNode child : dir.children()) {
            if (child instanceof ZoneDefResource zoneDef && zoneDef.collisionMap() != null) {
                return zoneDef.collisionMap();
            }
            if (child instanceof DirectoryResource subDir) {
                ZoneCollisionMap collisionMap = findCollisionMap(subDir);
                if (collisionMap != null) {
                    return collisionMap;
                }
            }
        }
        return null;
    }

    private static void collectZoneMeshes(ResourceNode node, List<ZoneMeshResource> meshes) {
        if (node instanceof ZoneMeshResource mesh) {
            meshes.add(mesh);
            return;
        }
        if (node instanceof DirectoryResource directory) {
            for (ResourceNode child : directory.children()) {
                collectZoneMeshes(child, meshes);
            }
        }
    }

    private static void collectZoneObjects(ResourceNode node, Map<String, List<ZoneObject>> objectsByMeshName) {
        if (node instanceof ZoneDefResource zoneDef) {
            for (ZoneObject object : zoneDef.zoneObjects()) {
                objectsByMeshName.computeIfAbsent(sanitizeId(object.id()), ignored -> new ArrayList<>()).add(object);
            }
            return;
        }
        if (node instanceof DirectoryResource directory) {
            for (ResourceNode child : directory.children()) {
                collectZoneObjects(child, objectsByMeshName);
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

    private static ParsedDat loadDat(Path path) throws IOException {
        byte[] rawData = Files.readAllBytes(path);
        DatResourceGraph graph = DatParser.parseResourceGraph(
                new ByteReader(rawData, path.getFileName() != null ? path.getFileName().toString() : path.toString())
        );
        return new ParsedDat(path, graph, rawData);
    }

    private static String resolveZoneName(Path ffxiRoot, int zoneId) {
        try {
            Path zoneNamePath = ffxiRoot.resolve(ZoneNameTable.RESOURCE_PATH);
            if (Files.exists(zoneNamePath)) {
                ZoneNameTable table = ZoneNameTable.load(Files.readAllBytes(zoneNamePath));
                String zoneName = table.getName(zoneId).trim();
                if (!zoneName.isBlank()) {
                    return zoneName;
                }
            }
        } catch (IOException ignored) {
            // Fall back to numeric identifier.
        }
        return "Zone " + zoneId;
    }

    private static String sanitizeId(String rawId) {
        if (rawId == null) {
            return "";
        }
        return rawId.trim().replaceAll("[\\x00-\\x1F]", "");
    }

    
    public record ZoneSelectionOption(int zoneId, String label) {
    }

    private record ParsedDat(Path path, DatResourceGraph graph, byte[] rawData) {
    }
}
