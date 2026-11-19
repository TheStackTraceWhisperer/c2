package com.ffxi.viewer;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.geometry.Vector3f;
import com.ffxi.export.TextureDecoder;
import com.ffxi.io.ByteReader;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.SkeletonAnimationResource;
import com.ffxi.model.SkeletonMeshFaceVertex;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.model.SkeletonMeshResource;
import com.ffxi.model.SkeletonMeshVertex;
import com.ffxi.model.SkeletonResource;
import com.ffxi.model.TextureResource;
import com.ffxi.model.Vec3f;
import com.ffxi.parser.DatParser;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Software-rasterizer test helper for character/NPC snapshot suites, implemented on top of the
 * unified {@code com.ffxi.*} DAT parser, skeleton animator, and texture decoder.
 */
public final class SnapshotCharacterTestHelper {

    private SnapshotCharacterTestHelper() {}

    public record DecodedTexture(String name, int width, int height, byte[] rgbaPixels) {}

    public record SkinnedVertex(Vector3f position, float u, float v) {}

    public record SkinnedSubMesh(String textureName, List<SkinnedVertex> vertices, List<Integer> indices) {}

    public record SkinnedMesh(String sectionId, List<SkinnedSubMesh> subMeshes) {}

    public record CompositeCharacterModel(
            SkeletonResource skeleton,
            Map<Integer, SkinnedMesh> equipmentSlots,
            Map<String, DecodedTexture> textures
    ) {}

    public static final class RaceModelLoader {
        public enum CharacterRace {
            HUME_MALE(1, "Hume Male", "ROM/27/82.DAT", "ROM/27/87.DAT", "ROM/28/7.DAT", "ROM/28/52.DAT", "ROM/28/84.DAT", "ROM/28/116.DAT"),
            HUME_FEMALE(2, "Hume Female", "ROM/32/58.DAT", "ROM/32/63.DAT", "ROM/32/111.DAT", "ROM/33/28.DAT", "ROM/33/60.DAT", "ROM/33/92.DAT"),
            ELVAAN_MALE(3, "Elvaan Male", "ROM/37/31.DAT", "ROM/37/36.DAT", "ROM/37/83.DAT", "ROM/37/127.DAT", "ROM/38/30.DAT", "ROM/38/61.DAT"),
            ELVAAN_FEMALE(4, "Elvaan Female", "ROM/42/4.DAT", "ROM/42/9.DAT", "ROM/42/56.DAT", "ROM/42/100.DAT", "ROM/43/3.DAT", "ROM/43/34.DAT"),
            TARUTARU_MALE(5, "TaruTaru Male", "ROM/46/93.DAT", "ROM/46/98.DAT", "ROM/47/17.DAT", "ROM/47/61.DAT", "ROM/47/92.DAT", "ROM/47/123.DAT"),
            TARUTARU_FEMALE(6, "TaruTaru Female", "ROM/46/93.DAT", "ROM/51/55.DAT", "ROM/47/17.DAT", "ROM/47/61.DAT", "ROM/47/92.DAT", "ROM/47/123.DAT"),
            MITHRA(7, "Mithra", "ROM/51/89.DAT", "ROM/51/94.DAT", "ROM/52/13.DAT", "ROM/52/57.DAT", "ROM/52/88.DAT", "ROM/52/119.DAT"),
            GALKA(8, "Galka", "ROM/56/59.DAT", "ROM/56/64.DAT", "ROM/56/111.DAT", "ROM/57/27.DAT", "ROM/57/58.DAT", "ROM/57/89.DAT");

            private final int id;
            private final String displayName;
            private final String baseDatPath;
            private final String headDatPath;
            private final String bodyDatPath;
            private final String handsDatPath;
            private final String legsDatPath;
            private final String feetDatPath;

            CharacterRace(int id, String displayName, String baseDatPath, String headDatPath,
                          String bodyDatPath, String handsDatPath, String legsDatPath, String feetDatPath) {
                this.id = id;
                this.displayName = displayName;
                this.baseDatPath = baseDatPath;
                this.headDatPath = headDatPath;
                this.bodyDatPath = bodyDatPath;
                this.handsDatPath = handsDatPath;
                this.legsDatPath = legsDatPath;
                this.feetDatPath = feetDatPath;
            }

            public int id() { return id; }
            public String displayName() { return displayName; }
            public String baseDatPath() { return baseDatPath; }
            public String headDatPath() { return headDatPath; }
            public String bodyDatPath() { return bodyDatPath; }
            public String handsDatPath() { return handsDatPath; }
            public String legsDatPath() { return legsDatPath; }
            public String feetDatPath() { return feetDatPath; }
        }

        public CompositeCharacterModel loadRaceModel(CharacterRace race) {
            try {
                Optional<Path> baseOpt = FfxiPathConfig.resolveRomFile(race.baseDatPath());
                if (baseOpt.isEmpty()) {
                    throw new IllegalStateException("Missing base DAT: " + race.baseDatPath());
                }
                byte[] baseBytes = Files.readAllBytes(baseOpt.get());
                DatResourceGraph baseGraph = DatParser.parseResourceGraph(new ByteReader(baseBytes, race.baseDatPath()));
                List<SkeletonResource> skeletons = new ArrayList<>();
                List<SkeletonAnimationResource> anims = new ArrayList<>();
                collect(baseGraph.rootDirectory(), SkeletonResource.class, skeletons);
                collect(baseGraph.rootDirectory(), SkeletonAnimationResource.class, anims);
                SkeletonResource skeleton = skeletons.getFirst();
                SkeletonAnimationResource idle = anims.stream()
                        .filter(a -> a.sectionId() != null && a.sectionId().toLowerCase(Locale.ROOT).startsWith("idl"))
                        .findFirst()
                        .orElse(anims.isEmpty() ? null : anims.getFirst());

                CharacterAnimator animator = new CharacterAnimator(skeleton, idle, null);
                Matrix4f[] pose = idle != null ? animator.computePoseMatrices(0.0) : animator.computeTPoseMatrices();
                List<float[]> skelMatrices = toFloatMatrices(pose);

                Map<Integer, SkinnedMesh> slots = new LinkedHashMap<>();
                Map<String, DecodedTexture> textures = new HashMap<>();
                String[] slotPaths = {
                        race.headDatPath(), race.bodyDatPath(), race.handsDatPath(), race.legsDatPath(), race.feetDatPath()
                };
                for (int i = 0; i < slotPaths.length; i++) {
                    Optional<Path> p = FfxiPathConfig.resolveRomFile(slotPaths[i]);
                    if (p.isPresent()) {
                        byte[] bytes = Files.readAllBytes(p.get());
                        SkinnedMesh mesh = AuthenticCharacterModelDecoder.decodeModelChunk(bytes, skelMatrices, "slot_" + i);
                        if (!mesh.subMeshes().isEmpty()) {
                            slots.put(i, mesh);
                        }
                        textures.putAll(AuthenticCharacterModelDecoder.decodeTextures(bytes));
                    }
                }
                return new CompositeCharacterModel(skeleton, slots, textures);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load race model " + race, e);
            }
        }
    }

    public static final class AuthenticCharacterModelDecoder {
        private AuthenticCharacterModelDecoder() {}

        public static String normalizeTextureName(String rawName) {
            if (rawName == null) return "";
            String s = rawName.trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("tim") || s.startsWith("txd")) {
                s = s.substring(3).trim();
            }
            String[] parts = s.split("\\s+");
            return parts[parts.length - 1].replaceAll("[^a-zA-Z0-9_-]", "").trim();
        }

        public static List<float[]> decodeSkeletonMatrices(byte[] datBytes) {
            if (datBytes == null || datBytes.length < 32) {
                return Collections.emptyList();
            }
            DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(datBytes.clone(), "skeleton"));
            List<SkeletonResource> skeletons = new ArrayList<>();
            collect(graph.rootDirectory(), SkeletonResource.class, skeletons);
            if (skeletons.isEmpty()) {
                return Collections.emptyList();
            }
            CharacterAnimator animator = new CharacterAnimator(skeletons.getFirst(), null, null);
            return toFloatMatrices(animator.computeTPoseMatrices());
        }

        public static Map<String, DecodedTexture> decodeTextures(byte[] datBytes) {
            if (datBytes == null || datBytes.length < 32) {
                return Collections.emptyMap();
            }
            byte[] copy = datBytes.clone();
            DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(copy, "textures"));
            List<TextureResource> texNodes = new ArrayList<>();
            collect(graph.rootDirectory(), TextureResource.class, texNodes);
            Map<String, DecodedTexture> textures = new HashMap<>();
            for (TextureResource tr : texNodes) {
                byte[] rgba = TextureDecoder.decode(copy, tr);
                if (rgba == null) continue;
                String name = normalizeTextureName(tr.textureName());
                DecodedTexture dt = new DecodedTexture(name, tr.width(), tr.height(), rgba);
                if (!name.isEmpty()) {
                    textures.put(name, dt);
                    int underscore = name.indexOf('_');
                    if (underscore >= 0 && underscore + 2 <= name.length()) {
                        textures.putIfAbsent(name.substring(0, underscore + 2), dt);
                    }
                }
            }
            return textures;
        }

        public static SkinnedMesh decodeModelChunk(byte[] datBytes, List<float[]> skelMatrices, String sectionId) {
            if (datBytes == null || datBytes.length < 52) {
                return new SkinnedMesh(sectionId, Collections.emptyList());
            }
            DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(datBytes.clone(), sectionId));
            List<SkeletonMeshResource> meshResources = new ArrayList<>();
            collect(graph.rootDirectory(), SkeletonMeshResource.class, meshResources);
            if (meshResources.isEmpty()) {
                return new SkinnedMesh(sectionId, Collections.emptyList());
            }

            Matrix4f[] jointMatrices;
            if (skelMatrices == null || skelMatrices.isEmpty()) {
                jointMatrices = new Matrix4f[]{new Matrix4f()};
            } else {
                jointMatrices = new Matrix4f[skelMatrices.size()];
                for (int i = 0; i < skelMatrices.size(); i++) {
                    jointMatrices[i] = new Matrix4f().set(skelMatrices.get(i));
                }
            }

            Vector4f accum = new Vector4f();
            Vector4f scratch = new Vector4f();
            List<SkinnedSubMesh> subMeshes = new ArrayList<>();

            for (SkeletonMeshResource res : meshResources) {
                for (SkeletonMeshMesh mesh : res.meshes()) {
                    List<SkeletonMeshFaceVertex> faceVerts = mesh.vertices();
                    List<SkeletonMeshVertex> resolvedVerts = mesh.resolvedVertices();
                    int n = Math.min(faceVerts.size(), resolvedVerts.size());
                    if (n < 3) continue;

                    List<SkinnedVertex> verts = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        SkeletonMeshFaceVertex fv = faceVerts.get(i);
                        SkeletonMeshVertex rv = resolvedVerts.get(i);
                        Vector3f pos = skinPosition(rv, jointMatrices, accum, scratch);
                        verts.add(new SkinnedVertex(pos, fv.u(), fv.v()));
                    }

                    List<Integer> indices = new ArrayList<>();
                    if (mesh.vertexCount() == mesh.triangleCount() * 3) {
                        for (int i = 0; i + 2 < n; i += 3) {
                            indices.add(i);
                            indices.add(i + 2);
                            indices.add(i + 1);
                        }
                    } else {
                        for (int i = 2; i < n; i++) {
                            int a = ((i & 1) == 0) ? (i - 2) : (i - 1);
                            int b = i;
                            int c = ((i & 1) == 0) ? (i - 1) : (i - 2);
                            indices.add(a);
                            indices.add(b);
                            indices.add(c);
                        }
                    }

                    String texName = normalizeTextureName(mesh.textureName());
                    subMeshes.add(new SkinnedSubMesh(texName.isEmpty() ? "default" : texName, verts, indices));
                }
            }
            return new SkinnedMesh(sectionId, subMeshes);
        }

        private static Vector3f skinPosition(SkeletonMeshVertex v, Matrix4f[] joints, Vector4f accum, Vector4f scratch) {
            float w0 = v.joint0Weight();
            float w1 = v.joint1Weight();
            if (w1 <= 0.0f) {
                w0 = 1.0f;
                w1 = 0.0f;
            } else {
                float total = w0 + w1;
                if (total > 0.0f && Math.abs(total - 1.0f) > 1.0e-6f) {
                    w0 /= total;
                    w1 /= total;
                }
            }
            accum.set(0.0f, 0.0f, 0.0f, 0.0f);
            transformJoint(joints, v.jointIndex0(), v.p0(), w0, scratch);
            accum.add(scratch);
            transformJoint(joints, v.jointIndex1(), v.p1(), w1, scratch);
            accum.add(scratch);
            return new Vector3f(accum.x, accum.y, accum.z);
        }

        private static void transformJoint(Matrix4f[] joints, Integer idx, Vec3f p, float w, Vector4f dest) {
            if (p == null || w == 0.0f) {
                dest.set(0.0f, 0.0f, 0.0f, 0.0f);
                return;
            }
            int i = idx == null ? 0 : Math.max(0, Math.min(joints.length - 1, idx));
            joints[i].transform(p.x(), p.y(), p.z(), w, dest);
        }
    }

    private static List<float[]> toFloatMatrices(Matrix4f[] mats) {
        List<float[]> out = new ArrayList<>(mats.length);
        for (Matrix4f m : mats) {
            float[] arr = new float[16];
            m.get(arr);
            out.add(arr);
        }
        return out;
    }

    private static <T> void collect(ResourceNode node, Class<T> type, List<T> out) {
        if (node == null) return;
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        if (node instanceof DirectoryResource dir) {
            for (ResourceNode child : dir.children()) {
                collect(child, type, out);
            }
        }
    }
}
