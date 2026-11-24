package com.ffxi.viewer.model;

import lombok.extern.slf4j.Slf4j;
import com.ffxi.crypto.KeyTables;

import com.example.asset.collision.CollisionMap;
import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import com.ffxi.io.FileUtil;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.ZoneMeshMath;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ParticleKeyFrameResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.TextureResource;
import com.ffxi.model.Vec3f;
import com.ffxi.model.ZoneDefResource;
import com.ffxi.model.ZoneEffectGeneratorResource;
import com.ffxi.model.ZoneMeshChunk;
import com.ffxi.model.ZoneMeshResource;
import com.ffxi.model.ZoneMeshVertex;
import com.ffxi.model.ZoneObject;
import com.ffxi.model.ZoneSpacePartitionNode;
import com.ffxi.parser.DatParser;
import com.ffxi.tables.ZoneConfig;
import com.ffxi.tables.ZoneIdToResourceId;
import com.ffxi.tables.ZoneNameTable;
import com.ffxi.viewer.collision.ZoneCollisionSystem;
import com.ffxi.viewer.environment.ZoneEnvironmentTable;
import com.ffxi.viewer.environment.ZonePointLight;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import com.ffxi.viewer.renderer.ZoneEffectParams;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
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

        List<ZoneObject> zoneObjects = new ArrayList<>();
        collectZoneObjectsInOrder(zoneDat.graph().rootDirectory(), zoneObjects);

        Map<String, CharacterTextureLoader.TextureSource> textureSources = new LinkedHashMap<>();
        collectTextureSources(zoneDat.graph().rootDirectory(), zoneDat.rawData(), textureSources);

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

        List<LoadedZone.ZoneChunkInstance> chunkInstances = new ArrayList<>();
        int sceneryObjectCount = 0;
        int sceneryChunkCount = 0;

        for (ZoneObject object : zoneObjects) {
            String effectLink = sanitizeId(object.effectLink());
            if (!effectLink.isEmpty() && !effectLink.startsWith("_") && !effectLink.startsWith("@")) {
                continue;
            }
            String objId = sanitizeId(object.id()).toLowerCase(Locale.ROOT);
            if (objId.isBlank() || isSkyMesh(objId)) {
                continue;
            }
            ZoneMeshResource mesh = resolveLodMesh(objId, meshesByName);
            if (mesh == null || mesh.meshChunks().isEmpty()) {
                continue;
            }

            sceneryObjectCount++;
            Matrix4f modelMatrix = buildObjectTransform(object);
            String meshName = sanitizeId(mesh.meshName()).toLowerCase(Locale.ROOT);
            float cutoutThreshold = (objId.startsWith("_") || meshName.startsWith("_")) ? MASK_DISCARD_THRESHOLD : 0.0f;
            int[] pointLightIndices = new int[]{
                    object.pointLight0(),
                    object.pointLight1(),
                    object.pointLight2(),
                    object.pointLight3()
            };
            String environmentLink = sanitizeId(object.environmentLink()).toLowerCase(Locale.ROOT);

            for (ZoneMeshChunk chunk : mesh.meshChunks()) {
                if (isCollisionMesh(objId, chunk)) {
                    continue;
                }
                boolean blend = chunk.renderBlend();
                chunkInstances.add(new LoadedZone.ZoneChunkInstance(
                        chunk,
                        modelMatrix,
                        chunk.invertCull(),
                        blend ? 0.0f : cutoutThreshold,
                        object.index(),
                        null,
                        pointLightIndices,
                        environmentLink
                ));
                sceneryChunkCount++;
            }
        }

        ZoneEnvironmentTable environmentTable = ZoneEnvironmentTable.fromDirectory(zoneDat.graph().rootDirectory());
        List<ZonePointLight> pointLights = loadZonePointLights(zoneDat.graph().rootDirectory());

        List<EffeGeneratorEntry> effeGenerators = new ArrayList<>();
        collectEffeGenerators(zoneDat.graph().rootDirectory(), false, effeGenerators);
        int effectChunkCount = loadAmbientZoneEffects(
                zoneDat.graph().rootDirectory(),
                effeGenerators,
                meshesByName,
                chunkInstances
        );
        Map<String, List<LoadedZone.ZoneChunkInstance>> weatherSkyEffectsByTag = loadWeatherSkyEffects(
                zoneDat.graph().rootDirectory(),
                meshesByName
        );

        log.info(String.format("[LOADER] Zone %d: %d meshes total, %d scenery objects -> %d scenery chunks, %d S05 effect chunks, %d point lights, %d weather sky tags",
                zoneId, allMeshes.size(), sceneryObjectCount, sceneryChunkCount, effectChunkCount,
                pointLights.size(), weatherSkyEffectsByTag.size()));

        ZoneCollisionSystem collisionSystem = null;
        CollisionMap collisionMap = findCollisionMap(zoneDat.graph().rootDirectory());
        if (collisionMap != null) {
            collisionSystem = new ZoneCollisionSystem(collisionMap);
            log.info(String.format("[LOADER] Zone %d: collision map loaded", zoneId));
        } else {
            log.info(String.format("[LOADER] Zone %d: no collision map found", zoneId));
        }

        ZoneSpacePartitionNode spacePartitioningTreeRoot = findSpacePartitioningTreeRoot(zoneDat.graph().rootDirectory());
        return new LoadedZone(
                zoneId,
                zoneName,
                chunkInstances,
                textureSources,
                collisionSystem,
                spacePartitioningTreeRoot,
                environmentTable,
                pointLights,
                weatherSkyEffectsByTag
        );
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
        float[] rotation = ZoneMeshMath.quaternionFromZYXEuler(object.rotX(), object.rotY(), object.rotZ());
        return new Matrix4f()
                .translate(ZoneMeshMath.sanitizeFinite(object.posX()), ZoneMeshMath.sanitizeFinite(object.posY()), ZoneMeshMath.sanitizeFinite(object.posZ()))
                .rotate(new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]))
                .scale(ZoneMeshMath.sanitizeFinite(object.scaleX()), ZoneMeshMath.sanitizeFinite(object.scaleY()), ZoneMeshMath.sanitizeFinite(object.scaleZ()));
    }

    private static ZoneSpacePartitionNode findSpacePartitioningTreeRoot(DirectoryResource dir) {
        if (dir == null) {
            return null;
        }
        for (ResourceNode child : dir.children()) {
            if (child instanceof ZoneDefResource zoneDef && zoneDef.spacePartitioningTreeRoot() != null) {
                return zoneDef.spacePartitioningTreeRoot();
            }
            if (child instanceof DirectoryResource subDir) {
                ZoneSpacePartitionNode root = findSpacePartitioningTreeRoot(subDir);
                if (root != null) {
                    return root;
                }
            }
        }
        return null;
    }

    private static CollisionMap findCollisionMap(DirectoryResource dir) {
        if (dir == null) {
            return null;
        }
        for (ResourceNode child : dir.children()) {
            if (child instanceof ZoneDefResource zoneDef && zoneDef.collisionMap() != null) {
                return zoneDef.collisionMap();
            }
            if (child instanceof DirectoryResource subDir) {
                CollisionMap collisionMap = findCollisionMap(subDir);
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

    private record EffeGeneratorEntry(DirectoryResource dir, ZoneEffectGeneratorResource generator) {
    }

    private static void collectZoneObjectsInOrder(ResourceNode node, List<ZoneObject> zoneObjects) {
        if (node instanceof ZoneDefResource zoneDef) {
            zoneObjects.addAll(zoneDef.zoneObjects());
            return;
        }
        if (node instanceof DirectoryResource directory) {
            for (ResourceNode child : directory.children()) {
                collectZoneObjectsInOrder(child, zoneObjects);
            }
        }
    }

    private static ZoneMeshResource resolveLodMesh(String objId, Map<String, ZoneMeshResource> meshesByName) {
        if (objId.endsWith("_h") || objId.endsWith("_m") || objId.endsWith("_l")) {
            String base = objId.substring(0, objId.length() - 2);
            return firstNonEmptyMesh(meshesByName, base + "_h", base + "_m", base + "_l", objId);
        }
        return firstNonEmptyMesh(meshesByName, objId, objId + "_h", objId + "_m", objId + "_l");
    }

    private static ZoneMeshResource firstNonEmptyMesh(Map<String, ZoneMeshResource> meshesByName, String... candidates) {
        for (String key : candidates) {
            ZoneMeshResource candidate = meshesByName.get(key);
            if (candidate != null && !candidate.meshChunks().isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private static List<ZonePointLight> loadZonePointLights(DirectoryResource rootDir) {
        List<String> pointLightLinks = new ArrayList<>();
        collectPointLightLinks(rootDir, pointLightLinks);
        if (pointLightLinks.isEmpty()) {
            return List.of();
        }

        Map<String, EffeGeneratorEntry> pointLightGenerators = new LinkedHashMap<>();
        collectPointLightGenerators(rootDir, false, pointLightGenerators);
        if (pointLightGenerators.size() < pointLightLinks.size()) {
            // Fall back to anywhere in the DAT if a point-light S05 sits outside an 'effe' directory
            collectPointLightGenerators(rootDir, true, pointLightGenerators);
        }

        List<ZonePointLight> resolved = new ArrayList<>(pointLightLinks.size());
        for (String linkId : pointLightLinks) {
            String key = sanitizeId(linkId).toLowerCase(Locale.ROOT);
            EffeGeneratorEntry entry = pointLightGenerators.get(key);
            if (entry == null) {
                resolved.add(new ZonePointLight(sanitizeId(linkId), new Vector3f(), new Vector3f(), 0.0f, 0.0f, 0.0f, null));
                continue;
            }
            ZoneEffectGeneratorResource gen = entry.generator();
            Vec3f pos = gen.basePosition();
            Vector3f worldPos = new Vector3f(
                    ZoneMeshMath.sanitizeFinite(pos.x()),
                    ZoneMeshMath.sanitizeFinite(pos.y()),
                    ZoneMeshMath.sanitizeFinite(pos.z())
            );
            float r = (gen.hasColor() ? gen.colorR() : 128) / 128.0f;
            float g = (gen.hasColor() ? gen.colorG() : 128) / 128.0f;
            float b = (gen.hasColor() ? gen.colorB() : 128) / 128.0f;
            float range = gen.hasPointLightParams()
                    ? Math.max(0.0f, gen.pointLightRange() * gen.pointLightRangeMultiplier())
                    : 0.0f;
            float baseTheta = gen.hasPointLightParams() ? gen.pointLightTheta() : 0.0f;
            float thetaMult = gen.hasPointLightParams() ? gen.pointLightThetaMultiplier() : 1.0f;
            ParticleKeyFrameResource todTheta = findKeyFrameResource(entry.dir(), rootDir, gen.todPointLightThetaKeyFrameId());
            resolved.add(new ZonePointLight(
                    sanitizeId(linkId),
                    worldPos,
                    new Vector3f(r, g, b),
                    range,
                    baseTheta,
                    thetaMult,
                    todTheta
            ));
        }
        return Collections.unmodifiableList(resolved);
    }

    private static void collectPointLightLinks(ResourceNode node, List<String> out) {
        if (node instanceof ZoneDefResource zoneDef) {
            out.addAll(zoneDef.pointLightLinks());
            return;
        }
        if (node instanceof DirectoryResource directory) {
            for (ResourceNode child : directory.children()) {
                collectPointLightLinks(child, out);
            }
        }
    }

    private static void collectPointLightGenerators(
            DirectoryResource dir,
            boolean acceptAnyDirectory,
            Map<String, EffeGeneratorEntry> out
    ) {
        if (dir == null) {
            return;
        }
        boolean insideEffe = acceptAnyDirectory || "effe".equalsIgnoreCase(sanitizeId(dir.sectionId()));
        for (ResourceNode child : dir.children()) {
            if (insideEffe && child instanceof ZoneEffectGeneratorResource gen && gen.isPointLight()) {
                String key = sanitizeId(gen.sectionId()).toLowerCase(Locale.ROOT);
                if (!key.isEmpty()) {
                    out.putIfAbsent(key, new EffeGeneratorEntry(dir, gen));
                }
            } else if (child instanceof DirectoryResource subDir) {
                collectPointLightGenerators(subDir, insideEffe, out);
            }
        }
    }

    private static void collectEffeGenerators(DirectoryResource dir, boolean insideEffe, List<EffeGeneratorEntry> out) {
        if (dir == null) {
            return;
        }
        boolean nowInsideEffe = insideEffe || "effe".equalsIgnoreCase(sanitizeId(dir.sectionId()));
        for (ResourceNode child : dir.children()) {
            if (nowInsideEffe && child instanceof ZoneEffectGeneratorResource gen
                    && (gen.isAmbientSingletonMesh() || gen.isStationaryLampConeMesh())) {
                out.add(new EffeGeneratorEntry(dir, gen));
            } else if (child instanceof DirectoryResource subDir) {
                collectEffeGenerators(subDir, nowInsideEffe, out);
            }
        }
    }

    private static int loadAmbientZoneEffects(
            DirectoryResource rootDir,
            List<EffeGeneratorEntry> effeGenerators,
            Map<String, ZoneMeshResource> meshesByName,
            List<LoadedZone.ZoneChunkInstance> chunkInstances
    ) {
        int count = 0;
        for (EffeGeneratorEntry entry : effeGenerators) {
            ZoneEffectGeneratorResource gen = entry.generator();
            ZoneEffectParams params = buildEffectParams(entry.dir(), rootDir, gen, false);
            if (params == null) {
                continue;
            }

            ZoneMeshResource effectMesh = findEffectMeshResource(entry.dir(), rootDir, gen.linkedDataId(), meshesByName);
            if (effectMesh == null || effectMesh.meshChunks().isEmpty()) {
                continue;
            }

            Matrix4f effectMatrix = buildEffectTransform(gen);
            String effectMeshName = sanitizeId(effectMesh.meshName()).toLowerCase(Locale.ROOT);
            float effDiscard = (params.blendMode() == ZoneEffectParams.BlendMode.OPAQUE)
                    ? 0.0f
                    : (effectMeshName.startsWith("_")
                            ? MASK_DISCARD_THRESHOLD
                            : (gen.depthWrite() ? 0.01f : 0.0f));

            for (ZoneMeshChunk chunk : effectMesh.meshChunks()) {
                chunkInstances.add(new LoadedZone.ZoneChunkInstance(
                        chunk,
                        effectMatrix,
                        chunk.invertCull(),
                        effDiscard,
                        -1,
                        params
                ));
                count++;
            }
        }
        return count;
    }

    private static Map<String, List<LoadedZone.ZoneChunkInstance>> loadWeatherSkyEffects(
            DirectoryResource rootDir,
            Map<String, ZoneMeshResource> meshesByName
    ) {
        Map<String, List<EffeGeneratorEntry>> byTag = new LinkedHashMap<>();
        List<String> dirStack = new ArrayList<>();
        collectWeatherSkyGenerators(rootDir, true, dirStack, byTag);
        if (byTag.isEmpty()) {
            return Map.of();
        }

        Map<String, List<LoadedZone.ZoneChunkInstance>> result = new LinkedHashMap<>();
        for (var tagEntry : byTag.entrySet()) {
            List<LoadedZone.ZoneChunkInstance> tagChunks = new ArrayList<>();
            for (EffeGeneratorEntry entry : tagEntry.getValue()) {
                ZoneEffectGeneratorResource gen = entry.generator();
                ZoneEffectParams params = buildEffectParams(entry.dir(), rootDir, gen, true);
                if (params == null) {
                    continue;
                }
                ZoneMeshResource effectMesh = findEffectMeshResource(entry.dir(), rootDir, gen.linkedDataId(), meshesByName);
                if (effectMesh == null || effectMesh.meshChunks().isEmpty()) {
                    continue;
                }
                Matrix4f effectMatrix = buildEffectTransform(gen);
                for (ZoneMeshChunk chunk : effectMesh.meshChunks()) {
                    tagChunks.add(new LoadedZone.ZoneChunkInstance(
                            chunk,
                            effectMatrix,
                            chunk.invertCull(),
                            0.0f,
                            -1,
                            params
                    ));
                }
            }
            if (!tagChunks.isEmpty()) {
                result.put(tagEntry.getKey(), java.util.Collections.unmodifiableList(tagChunks));
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void collectWeatherSkyGenerators(
            DirectoryResource dir,
            boolean isRootWrapper,
            List<String> stack,
            Map<String, List<EffeGeneratorEntry>> outByTag
    ) {
        if (dir == null) {
            return;
        }
        String id = sanitizeId(dir.sectionId()).toLowerCase(Locale.ROOT);
        boolean pushed = !isRootWrapper && !"__root__".equals(id) && !id.isEmpty();
        if (pushed) {
            stack.add(id);
        }
        int n = stack.size();
        boolean insideAreaWeat = n >= 4 && "weat".equals(stack.get(2)) && !"weat".equals(stack.get(1));
        String weatherTag = null;
        if (!insideAreaWeat) {
            if (n >= 2 && "weat".equals(stack.get(n - 2))) {
                weatherTag = stack.get(n - 1);
            } else if (n >= 3 && "weat".equals(stack.get(n - 3))) {
                weatherTag = stack.get(n - 2);
            }
        }
        for (ResourceNode child : dir.children()) {
            if (weatherTag != null && child instanceof ZoneEffectGeneratorResource gen
                    && gen.isAmbientSingletonMesh()) {
                outByTag.computeIfAbsent(weatherTag, ignored -> new ArrayList<>())
                        .add(new EffeGeneratorEntry(dir, gen));
            } else if (child instanceof DirectoryResource subDir) {
                collectWeatherSkyGenerators(subDir, false, stack, outByTag);
            }
        }
        if (pushed) {
            stack.remove(stack.size() - 1);
        }
    }

    private static ZoneEffectParams buildEffectParams(
            DirectoryResource dir,
            DirectoryResource rootDir,
            ZoneEffectGeneratorResource gen,
            boolean skyLayer
    ) {
        float colorR = (gen.hasColor() ? gen.colorR() : 128) / 255.0f;
        float colorG = (gen.hasColor() ? gen.colorG() : 128) / 255.0f;
        float colorB = (gen.hasColor() ? gen.colorB() : 128) / 255.0f;
        float colorA = (gen.hasColor() ? gen.colorA() : 128) / 255.0f;
        if (gen.alphaOverride() >= 0) {
            colorA = gen.alphaOverride() / 255.0f;
        }

        if (!skyLayer && gen.isStationaryLampConeMesh()) {
            ParticleKeyFrameResource lifeKf = findKeyFrameResource(dir, rootDir, gen.lifeAlphaKeyFrameId());
            if (lifeKf != null) {
                float steadyAlpha = Math.max(
                        lifeKf.sampleNormalized(0.25f),
                        Math.max(lifeKf.sampleNormalized(0.5f), lifeKf.sampleNormalized(0.75f))
                );
                // 0x2D/0x1B sets initial particle alpha (replacing op 0x16's colorA=0), before optm scales it.
                if (gen.alphaOverride() < 0) {
                    colorA = steadyAlpha;
                } else {
                    colorA *= steadyAlpha;
                }
            }
        }

        ParticleKeyFrameResource kfR = findKeyFrameResource(dir, rootDir, gen.todRKeyFrameId());
        ParticleKeyFrameResource kfG = findKeyFrameResource(dir, rootDir, gen.todGKeyFrameId());
        ParticleKeyFrameResource kfB = findKeyFrameResource(dir, rootDir, gen.todBKeyFrameId());
        ParticleKeyFrameResource kfA = findKeyFrameResource(dir, rootDir, gen.todAKeyFrameId());
        if (colorA <= 0.001f && kfA == null) {
            return null;
        }

        ZoneEffectParams.BlendMode blendMode = ZoneEffectParams.blendModeFromByte(gen.blendFuncByte());
        ZoneEffectParams.BillboardMode billboardMode;
        if (gen.billboardCamera()) {
            billboardMode = ZoneEffectParams.BillboardMode.CAMERA;
        } else if (gen.billboardXZ()) {
            billboardMode = ZoneEffectParams.BillboardMode.XZ;
        } else if (gen.billboardXYZ()) {
            billboardMode = ZoneEffectParams.BillboardMode.XYZ;
        } else {
            billboardMode = ZoneEffectParams.BillboardMode.NONE;
        }

        float specR = gen.hasSpecularParams() ? gen.specularR() / 255.0f : 0.0f;
        float specG = gen.hasSpecularParams() ? gen.specularG() / 255.0f : 0.0f;
        float specB = gen.hasSpecularParams() ? gen.specularB() / 255.0f : 0.0f;
        float specA = gen.hasSpecularParams() ? gen.specularA() / 255.0f : 0.0f;

        return new ZoneEffectParams(
                sanitizeId(gen.sectionId()),
                blendMode,
                billboardMode,
                skyLayer ? false : gen.depthWrite(),
                skyLayer ? false : gen.specularEnabled(),
                skyLayer ? false : gen.fogEnabled(),
                gen.ignoreTextureAlpha(),
                colorR, colorG, colorB, colorA,
                specR, specG, specB, specA,
                gen.uvScrollU(),
                gen.uvScrollV(),
                kfR, kfG, kfB, kfA,
                gen.attachType(),
                gen.followCamera(),
                skyLayer
        );
    }

    private static Matrix4f buildEffectTransform(ZoneEffectGeneratorResource gen) {
        Vec3f pos = gen.basePosition();
        Vec3f rot = gen.baseRotation();
        Vec3f scl = gen.baseScale();
        Matrix4f m = new Matrix4f().translate(
                ZoneMeshMath.sanitizeFinite(pos.x()),
                ZoneMeshMath.sanitizeFinite(pos.y()),
                ZoneMeshMath.sanitizeFinite(pos.z())
        );
        float rx = ZoneMeshMath.sanitizeFinite(rot.x());
        float ry = ZoneMeshMath.sanitizeFinite(rot.y());
        float rz = ZoneMeshMath.sanitizeFinite(rot.z());
        float sx = ZoneMeshMath.sanitizeFinite(scl.x());
        float sy = ZoneMeshMath.sanitizeFinite(scl.y());
        float sz = ZoneMeshMath.sanitizeFinite(scl.z());
        boolean scaleBeforeRotate = (gen.billboardFlags() & 0x0002) != 0;
        boolean orderZYX = (gen.billboardFlags() & 0x0200) != 0;
        if (scaleBeforeRotate) {
            m.scale(sx, sy, sz);
        }
        if (orderZYX) {
            m.rotateZ(rz).rotateY(ry).rotateX(rx);
        } else {
            m.rotateX(rx).rotateY(ry).rotateZ(rz);
        }
        if (!scaleBeforeRotate) {
            m.scale(sx, sy, sz);
        }
        return m;
    }

    private static ParticleKeyFrameResource findKeyFrameResource(
            DirectoryResource startDir,
            DirectoryResource rootDir,
            String sectionId
    ) {
        if (sectionId == null || sectionId.isBlank()) {
            return null;
        }
        String target = sanitizeId(sectionId);
        return findInDirectoryHierarchy(
                startDir,
                rootDir,
                ParticleKeyFrameResource.class,
                kf -> sanitizeId(kf.sectionId()).equalsIgnoreCase(target)
        );
    }

    private static ZoneMeshResource findEffectMeshResource(
            DirectoryResource startDir,
            DirectoryResource rootDir,
            String linkedDataId,
            Map<String, ZoneMeshResource> meshesByName
    ) {
        String target = sanitizeId(linkedDataId);
        if (target.isEmpty()) {
            return null;
        }
        ZoneMeshResource bySectionId = findInDirectoryHierarchy(
                startDir,
                rootDir,
                ZoneMeshResource.class,
                mesh -> sanitizeId(mesh.sectionId()).equalsIgnoreCase(target) && !mesh.meshChunks().isEmpty()
        );
        if (bySectionId != null) {
            return bySectionId;
        }
        ZoneMeshResource byMeshName = meshesByName.get(target.toLowerCase(Locale.ROOT));
        return (byMeshName != null && !byMeshName.meshChunks().isEmpty()) ? byMeshName : null;
    }

    private static <T extends ResourceNode> T findInDirectoryHierarchy(
            DirectoryResource startDir,
            DirectoryResource rootDir,
            Class<T> type,
            java.util.function.Predicate<T> predicate
    ) {
        DirectoryResource cursor = startDir;
        while (cursor != null) {
            for (ResourceNode child : cursor.children()) {
                if (type.isInstance(child)) {
                    T typed = type.cast(child);
                    if (predicate.test(typed)) {
                        return typed;
                    }
                }
            }
            cursor = cursor.parent();
        }
        if (startDir != null && startDir.parent() != null) {
            T inParentSubtree = findRecursive(startDir.parent(), type, predicate);
            if (inParentSubtree != null) {
                return inParentSubtree;
            }
        }
        return findRecursive(rootDir, type, predicate);
    }

    private static <T extends ResourceNode> T findRecursive(
            DirectoryResource dir,
            Class<T> type,
            java.util.function.Predicate<T> predicate
    ) {
        if (dir == null) {
            return null;
        }
        for (ResourceNode child : dir.children()) {
            if (type.isInstance(child)) {
                T typed = type.cast(child);
                if (predicate.test(typed)) {
                    return typed;
                }
            } else if (child instanceof DirectoryResource subDir) {
                T found = findRecursive(subDir, type, predicate);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
