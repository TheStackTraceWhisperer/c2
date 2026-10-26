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
import com.ffxi.model.ZoneMeshVertex;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

        List<ZoneMeshResource> allMeshes = new ArrayList<>();
        collectZoneMeshes(zoneDat.graph().rootDirectory(), allMeshes);

        Map<String, List<ZoneObject>> objectsByMeshName = new LinkedHashMap<>();
        collectZoneObjects(zoneDat.graph().rootDirectory(), objectsByMeshName);

        Map<String, List<ZoneObject>> objectsByExactName = new LinkedHashMap<>();
        Map<String, List<ZoneObject>> objectsByBaseName = new LinkedHashMap<>();
        for (Map.Entry<String, List<ZoneObject>> entry : objectsByMeshName.entrySet()) {
            String id = entry.getKey().toLowerCase(Locale.ROOT);
            objectsByExactName.put(id, entry.getValue());
            String base = stripLodSuffix(id);
            objectsByBaseName.putIfAbsent(base, entry.getValue());
        }

        Map<String, CharacterTextureLoader.TextureSource> textureSources = new LinkedHashMap<>();
        collectTextureSources(zoneDat.graph().rootDirectory(), zoneDat.rawData(), textureSources);
        if (!hasWaterTexture(textureSources)) {
            loadFallbackWaterTexture(ffxiRoot, textureSources);
        }

        List<LoadedZone.ZoneChunkInstance> chunkInstances = new ArrayList<>();
        int meshesWithObjects = 0;
        int meshesWithoutObjects = 0;
        int totalChunkInstances = 0;
        Set<String> processedMeshNames = new HashSet<>();

        Map<String, ZoneMeshResource> meshesByName = new LinkedHashMap<>();
        for (ZoneMeshResource m : allMeshes) {
            String name = sanitizeId(m.meshName()).toLowerCase(Locale.ROOT);
            if (!name.isBlank()) {
                ZoneMeshResource existing = meshesByName.get(name);
                if (existing == null || (existing.meshChunks().isEmpty() && !m.meshChunks().isEmpty())) {
                    meshesByName.put(name, m);
                }
            }
        }

        for (ZoneMeshResource mesh : allMeshes) {
            String rawMeshName = sanitizeId(mesh.meshName());
            String meshName = rawMeshName.toLowerCase(Locale.ROOT);
            if (meshName.isBlank() || processedMeshNames.contains(meshName)) {
                continue;
            }
            if (mesh.meshChunks().isEmpty() && meshesByName.get(meshName) != null && !meshesByName.get(meshName).meshChunks().isEmpty()) {
                continue; // Defer until the non-empty duplicate mesh is reached
            }
            processedMeshNames.add(meshName);

            if (isSkyMesh(meshName)) {
                continue;
            }

            List<ZoneObject> objects = objectsByExactName.get(meshName);
            if (objects == null || objects.isEmpty()) {
                String base = stripLodSuffix(meshName);
                objects = objectsByBaseName.get(base);
            }

            boolean isWater = isWaterMesh(meshName);
            float discardThreshold = meshName.startsWith("_") ? MASK_DISCARD_THRESHOLD : 0.0f;

            if (objects != null && !objects.isEmpty()) {
                meshesWithObjects++;

                // Procedural water volume: If mesh is empty, generate planar water tile instances
                if (mesh.meshChunks().isEmpty() && isWater) {
                    for (ZoneObject object : objects) {
                        Matrix4f modelMatrix = buildObjectTransform(object);
                        ZoneMeshChunk quadChunk = createProceduralWaterQuadChunk(modelMatrix, textureSources);
                        chunkInstances.add(new LoadedZone.ZoneChunkInstance(
                                quadChunk,
                                modelMatrix,
                                false,
                                0.0f
                        ));
                        totalChunkInstances++;
                    }
                    continue;
                }

                for (ZoneObject object : objects) {
                    Matrix4f modelMatrix = buildObjectTransform(object);
                    for (ZoneMeshChunk chunk : mesh.meshChunks()) {
                        if (isCollisionMesh(meshName, chunk)) {
                            continue;
                        }
                        boolean chunkWater = isWater || isWaterChunk(meshName, chunk);
                        boolean blend = chunk.renderBlend() || chunkWater;
                        ZoneMeshChunk preparedChunk = chunkWater ? prepareWaterChunk(chunk, modelMatrix, textureSources) : chunk;
                        chunkInstances.add(new LoadedZone.ZoneChunkInstance(
                                preparedChunk,
                                modelMatrix,
                                chunk.invertCull(),
                                blend ? 0.0f : discardThreshold
                        ));
                        totalChunkInstances++;
                    }

                    // Attach associated dynamic fountain water / spout meshes to fountain basin objects
                    if (meshName.contains("funsui")) {
                        String[] fountainParts = {"funmizu", "mizutw", "mizutw2"};
                        for (String partName : fountainParts) {
                            ZoneMeshResource partMesh = meshesByName.get(partName);
                            if (partMesh != null) {
                                for (ZoneMeshChunk partChunk : partMesh.meshChunks()) {
                                    ZoneMeshChunk prep = prepareWaterChunk(partChunk, modelMatrix, textureSources);
                                    chunkInstances.add(new LoadedZone.ZoneChunkInstance(
                                            prep,
                                            modelMatrix,
                                            partChunk.invertCull(),
                                            0.0f
                                    ));
                                    totalChunkInstances++;
                                }
                            }
                        }
                    }
                }
            } else {
                // Standalone unplaced geometry: In retail FFXI, only world-space water sheets (wt00..wt99 in suim)
                // are modeled directly in world coordinates without ZoneObject instances.
                // Dynamic effect routines, light cones (ltlanp, ltbfg), cutter volumes (lcut, ltcube),
                // and proxy volumes must NOT be dumped at origin (0, 0, 0).
                if (!isStandaloneWorldWaterMesh(meshName)) {
                    continue;
                }
                meshesWithoutObjects++;
                Matrix4f identity = new Matrix4f();
                for (ZoneMeshChunk chunk : mesh.meshChunks()) {
                    if (isCollisionMesh(meshName, chunk)) {
                        continue;
                    }
                    boolean chunkWater = true;
                    boolean blend = chunk.renderBlend() || chunkWater;
                    ZoneMeshChunk preparedChunk = prepareWaterChunk(chunk, identity, textureSources);
                    chunkInstances.add(new LoadedZone.ZoneChunkInstance(
                            preparedChunk,
                            identity,
                            chunk.invertCull(),
                            blend ? 0.0f : discardThreshold
                    ));
                    totalChunkInstances++;
                }
            }
        }

        // Procedural water volume fallback: instantiate any water objects not covered by meshes
        for (Map.Entry<String, List<ZoneObject>> entry : objectsByExactName.entrySet()) {
            String objName = entry.getKey();
            if (isWaterMesh(objName) && !processedMeshNames.contains(objName)) {
                processedMeshNames.add(objName);
                meshesWithObjects++;
                for (ZoneObject object : entry.getValue()) {
                    Matrix4f modelMatrix = buildObjectTransform(object);
                    ZoneMeshChunk quadChunk = createProceduralWaterQuadChunk(modelMatrix, textureSources);
                    chunkInstances.add(new LoadedZone.ZoneChunkInstance(
                            quadChunk,
                            modelMatrix,
                            false,
                            0.0f
                    ));
                    totalChunkInstances++;
                }
            }
        }

        System.out.printf("[LOADER] Zone %d: %d meshes total, %d with objects, %d standalone, %d chunk instances%n",
                zoneId, allMeshes.size(), meshesWithObjects, meshesWithoutObjects, totalChunkInstances);

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

    public static void collectZoneMeshes(ResourceNode node, List<ZoneMeshResource> meshes) {
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

    public static void collectZoneObjects(ResourceNode node, Map<String, List<ZoneObject>> objectsByMeshName) {
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
                CharacterTextureLoader.TextureSource src = new CharacterTextureLoader.TextureSource(textureResource, rawData);
                textureSources.putIfAbsent(name, src);
                if (name.contains(" ")) {
                    String shortName = name.substring(name.lastIndexOf(' ') + 1);
                    textureSources.putIfAbsent(shortName, src);
                }
            }
            return;
        }
        if (node instanceof DirectoryResource directory) {
            for (ResourceNode child : directory.children()) {
                collectTextureSources(child, rawData, textureSources);
            }
        }
    }

    private static boolean hasWaterTexture(Map<String, CharacterTextureLoader.TextureSource> textureSources) {
        for (String k : textureSources.keySet()) {
            if (k.contains("umi02") || k.contains("riv01")) {
                return true;
            }
        }
        return false;
    }

    private static void loadFallbackWaterTexture(Path ffxiRoot, Map<String, CharacterTextureLoader.TextureSource> textureSources) {
        String[] fallbackDats = {"ROM/0/81.DAT", "ROM/1/32.DAT", "ROM/1/31.DAT"};
        for (String datRel : fallbackDats) {
            Path p = ffxiRoot.resolve(datRel);
            if (Files.exists(p)) {
                try {
                    ParsedDat fallbackDat = loadDat(p);
                    collectTextureSources(fallbackDat.graph().rootDirectory(), fallbackDat.rawData(), textureSources);
                    if (hasWaterTexture(textureSources)) {
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static boolean isSkyMesh(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.startsWith("sunsphere") || n.startsWith("moonsphere") || n.startsWith("star")
                || n.startsWith("clod_") || n.startsWith("cld_") || n.startsWith("suny_")
                || n.startsWith("mist_") || n.equals("awan");
    }

    public static boolean isCollisionMesh(String meshName, ZoneMeshChunk chunk) {
        if (meshName == null) return false;
        String n = meshName.trim().toLowerCase(Locale.ROOT);
        boolean noTex = chunk.textureName() == null || chunk.textureName().isBlank() || chunk.textureName().equals("none");
        if (!noTex) return false;

        // Invisible collision hulls, light boxes, culling volumes, warp volumes, shadow proxies
        if (n.contains("col")
                || n.contains("cube")
                || n.contains("cut")
                || n.contains("warp")
                || n.contains("shadow")
                || (n.contains("light") && chunk.vertices().size() <= 8)) {
            return true;
        }

        // Untextured blended volume/culling quads (e.g. doorway culler in uwa_kaul)
        if (chunk.renderBlend() && chunk.vertices().size() <= 8 && isDegenerateUv(chunk.vertices())) {
            return true;
        }

        return false;
    }

    public static boolean isStandaloneWorldWaterMesh(String meshName) {
        if (meshName == null) return false;
        String n = meshName.trim().toLowerCase(Locale.ROOT);
        if (n.contains("col") || n.contains("cube") || n.contains("cut") || n.contains("warp") || n.contains("lanp")
                || n.contains("saku") || n.contains("kigumi") || n.contains("takibi") || n.contains("mon_")
                || n.contains("gesui") || n.contains("haisui")) {
            return false;
        }
        if (n.equals("funmizu") || n.equals("mizutw") || n.equals("mizutw2") || n.equals("funsui")) {
            return false;
        }
        if (n.contains("sea") || n.contains("umi") || n.contains("mizu") || n.contains("taki")
                || n.contains("suimen") || n.contains("suimwn") || n.contains("wave") || n.equals("wthand")) {
            return true;
        }
        if (n.contains("wt") || n.contains("hunsui")) {
            return true;
        }
        return false;
    }

    public static boolean isWaterMesh(String meshName) {
        if (meshName == null) return false;
        String n = meshName.trim().toLowerCase(Locale.ROOT);
        return n.contains("wt")
                || n.contains("sea")
                || n.contains("mizu")
                || n.contains("taki")
                || n.contains("funsui")
                || n.contains("hunsui")
                || n.contains("wave")
                || n.contains("river")
                || n.contains("water")
                || n.contains("dam")
                || n.contains("suimen")
                || n.contains("suimwn")
                || n.contains("umi")
                || n.equals("wthand");
    }

    public static ZoneMeshChunk createProceduralWaterQuadChunk(
            Matrix4f modelMatrix,
            Map<String, CharacterTextureLoader.TextureSource> textureSources
    ) {
        String texName = resolveWaterTexture(textureSources);

        // Planar water tile centered at (0, 0, 0) with extents [-20, 20] x [-20, 20]
        float[][] localCoords = {
                {-20.0f, 0.0f, -20.0f},
                { 20.0f, 0.0f, -20.0f},
                { 20.0f, 0.0f,  20.0f},
                {-20.0f, 0.0f,  20.0f}
        };

        List<ZoneMeshVertex> verts = new ArrayList<>(4);
        for (float[] pt : localCoords) {
            float lx = pt[0];
            float ly = pt[1];
            float lz = pt[2];

            // Transform local point to world space to calculate continuous planar UVs
            float wx = modelMatrix != null ? (modelMatrix.m00() * lx + modelMatrix.m10() * ly + modelMatrix.m20() * lz + modelMatrix.m30()) : lx;
            float wz = modelMatrix != null ? (modelMatrix.m02() * lx + modelMatrix.m12() * ly + modelMatrix.m22() * lz + modelMatrix.m32()) : lz;

            // Map continuous world coordinates to repeating water texture
            float u = (-wx) * 0.125f;
            float v = wz * 0.125f;

            verts.add(new ZoneMeshVertex(
                    lx, ly, lz,
                    lx, ly, lz,
                    0.0f, 1.0f, 0.0f,
                    128, 128, 128, 160,
                    u, v
            ));
        }

        // Two triangles forming horizontal quad with normal (0, 1, 0) pointing UP
        List<Integer> indices = List.of(0, 1, 2, 0, 2, 3);

        return new ZoneMeshChunk(
                texName,
                true,  // renderBlend = true
                false, // invertCull = false
                false, // isTriStrip = false
                verts,
                indices
        );
    }

    public static boolean isWaterChunk(String meshName, ZoneMeshChunk chunk) {
        if (isWaterMesh(meshName)) {
            return true;
        }
        if (chunk.textureName() == null || chunk.textureName().isBlank()) {
            List<ZoneMeshVertex> verts = chunk.vertices();
            if (verts.size() >= 3 && isPlanarHorizontal(verts) && isDegenerateUv(verts)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlanarHorizontal(List<ZoneMeshVertex> verts) {
        if (verts.size() < 3) return false;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (ZoneMeshVertex v : verts) {
            minY = Math.min(minY, v.py());
            maxY = Math.max(maxY, v.py());
        }
        return (maxY - minY) <= 0.25f;
    }

    private static boolean isDegenerateUv(List<ZoneMeshVertex> verts) {
        if (verts.isEmpty()) return false;
        float u0 = verts.get(0).u();
        float v0 = verts.get(0).v();
        for (int i = 1; i < verts.size(); i++) {
            if (Math.abs(verts.get(i).u() - u0) > 1e-4f || Math.abs(verts.get(i).v() - v0) > 1e-4f) {
                return false;
            }
        }
        return true;
    }

    public static String stripLodSuffix(String id) {
        if (id == null) return "";
        String s = id.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith("_h") || s.endsWith("_m") || s.endsWith("_l")) {
            return s.substring(0, s.length() - 2);
        }
        if (s.length() > 4 && (s.endsWith("h") || s.endsWith("m") || s.endsWith("l"))) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static ZoneMeshChunk prepareWaterChunk(
            ZoneMeshChunk chunk,
            Matrix4f modelMatrix,
            Map<String, CharacterTextureLoader.TextureSource> textureSources
    ) {
        String texName = chunk.textureName() != null ? chunk.textureName().trim().toLowerCase(Locale.ROOT) : "";
        if (texName.isBlank() || texName.equals("none")) {
            texName = resolveWaterTexture(textureSources);
        }

        List<ZoneMeshVertex> verts = chunk.vertices();
        boolean degenerate = isDegenerateUv(verts);

        List<ZoneMeshVertex> newVerts = verts;
        if (degenerate && !verts.isEmpty()) {
            newVerts = new ArrayList<>(verts.size());
            for (ZoneMeshVertex v : verts) {
                // Compute world coordinates for continuous tiling across terrain chips and meshes
                float wx = modelMatrix != null ? (modelMatrix.m00() * v.px() + modelMatrix.m10() * v.py() + modelMatrix.m20() * v.pz() + modelMatrix.m30()) : v.px();
                float wz = modelMatrix != null ? (modelMatrix.m02() * v.px() + modelMatrix.m12() * v.py() + modelMatrix.m22() * v.pz() + modelMatrix.m32()) : v.pz();

                // World coordinates: X = -wx, Z = wz
                // Map to repeating water texture with 8.0m tile period
                float u = (-wx) * 0.125f;
                float vCoord = wz * 0.125f;
                int alpha = v.colorA();
                if (alpha == 0 && verts.stream().allMatch(x -> x.colorA() == 0)) {
                    alpha = 128;
                }
                int cr = v.colorR();
                int cg = v.colorG();
                int cb = v.colorB();
                if (cr == 0 && cg == 0 && cb == 0) {
                    cr = 128;
                    cg = 128;
                    cb = 128;
                }
                newVerts.add(new ZoneMeshVertex(
                        v.px(), v.py(), v.pz(),
                        v.p1x(), v.p1y(), v.p1z(),
                        v.nx(), v.ny(), v.nz(),
                        cb, cg, cr, alpha,
                        u, vCoord
                ));
            }
        }

        return new ZoneMeshChunk(
                texName,
                true, // renderBlend = true for water
                chunk.invertCull(),
                chunk.isTriStrip(),
                newVerts,
                chunk.indices()
        );
    }

    private static String resolveWaterTexture(Map<String, CharacterTextureLoader.TextureSource> textureSources) {
        for (String key : textureSources.keySet()) {
            String k = key.toLowerCase(Locale.ROOT);
            if (k.contains("umi02")) return key;
        }
        for (String key : textureSources.keySet()) {
            String k = key.toLowerCase(Locale.ROOT);
            if (k.contains("riv") || k.contains("mz01") || k.contains("mz02") || k.contains("mz03") || k.contains("sea")) return key;
        }
        return "umi02";
    }

    public static ParsedDat loadDat(Path path) throws IOException {
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

    public static String sanitizeId(String rawId) {
        if (rawId == null) {
            return "";
        }
        return rawId.trim().replaceAll("[\\x00-\\x1F]", "");
    }

    public record ZoneSelectionOption(int zoneId, String label) {
    }

    public record ParsedDat(Path path, DatResourceGraph graph, byte[] rawData) {
    }
}
