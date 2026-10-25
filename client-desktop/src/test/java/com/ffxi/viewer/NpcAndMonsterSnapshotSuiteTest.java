package com.ffxi.viewer;

import com.example.asset.skeleton.AuthenticCharacterModelDecoder;
import com.example.asset.skeleton.SkinnedMesh;
import com.example.asset.skeleton.SkinnedSubMesh;
import com.example.asset.skeleton.SkinnedVertex;
import com.example.asset.texture.DecodedTexture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suite that extracts authentic retail FFXI monsters and non-player characters (NPCs)
 * from game ROM DAT archives, transforms their skeletal meshes with authentic bone matrices,
 * rasterizes textured 3D snapshots, and outputs them to target/snapshots/.
 */
public class NpcAndMonsterSnapshotSuiteTest {

    private static final Path FFXI_ROOT = Path.of("/home/samuel_l_hyman/Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI");
    private static final Path OUTPUT_DIR = Path.of("target/snapshots");

    record EntityTarget(
            String id,
            String displayName,
            String category, // "Monster" or "NPC"
            String datPath,
            float camAngleDeg,
            float distMult,
            float yOffsetRatio
    ) {
        EntityTarget(String id, String displayName, String category, String datPath) {
            this(id, displayName, category, datPath, 25.0f, 1.55f, 0.0f);
        }
        EntityTarget(String id, String displayName, String category, String datPath, float camAngleDeg, float distMult) {
            this(id, displayName, category, datPath, camAngleDeg, distMult, 0.0f);
        }
    }

    @Test
    @DisplayName("Render and snapshot authentic retail FFXI monsters and NPCs")
    void testRenderAndSnapshotAllMonstersAndNpcs() throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        List<EntityTarget> targets = List.of(
                // --- Iconic Monsters ---
                new EntityTarget("monster_mandragora", "Mandragora", "Monster", "ROM/4/127.DAT", 25.0f, 1.60f),
                new EntityTarget("monster_goblin", "Goblin", "Monster", "ROM/3/59.DAT", 20.0f, 1.50f),
                new EntityTarget("monster_chocobo", "Chocobo", "Monster", "ROM/3/60.DAT", 35.0f, 1.65f),
                new EntityTarget("monster_rabbit", "Forest Rabbit", "Monster", "ROM/4/108.DAT", 25.0f, 1.55f),
                new EntityTarget("monster_crawler", "Crawler", "Monster", "ROM/5/50.DAT", 30.0f, 1.60f),
                new EntityTarget("monster_skeleton", "Skeleton Warrior", "Monster", "ROM/5/29.DAT", 20.0f, 1.55f),
                new EntityTarget("monster_sheep", "Wild Sheep", "Monster", "ROM/5/19.DAT", 30.0f, 1.55f),
                new EntityTarget("monster_cactuar", "Cactuar (Sabotender)", "Monster", "ROM/5/38.DAT", 20.0f, 1.55f),

                // --- Iconic Non-Player Characters (NPCs) ---
                new EntityTarget("npc_moogle", "Moogle", "NPC", "ROM/3/56.DAT", 20.0f, 1.55f),
                new EntityTarget("npc_lion", "Lion (Heroine)", "NPC", "ROM/3/34.DAT", 20.0f, 1.50f),
                new EntityTarget("npc_cid", "Cid (Bastok Chief Engineer)", "NPC", "ROM/3/37.DAT", 20.0f, 1.50f),
                new EntityTarget("npc_child_humem", "Hume Child (Boy)", "NPC", "ROM/3/88.DAT", 20.0f, 1.50f),
                new EntityTarget("npc_child_humef", "Hume Child (Girl)", "NPC", "ROM/3/93.DAT", 20.0f, 1.50f),
                new EntityTarget("npc_town_guard", "Town Guard (Elvaan)", "NPC", "ROM/3/116.DAT", 20.0f, 1.50f)
        );

        for (EntityTarget target : targets) {
            Path datFile = FFXI_ROOT.resolve(target.datPath());
            assertTrue(Files.exists(datFile), "Retail DAT must exist: " + datFile);

            byte[] bytes = Files.readAllBytes(datFile);
            List<float[]> skelMatrices = AuthenticCharacterModelDecoder.decodeSkeletonMatrices(bytes);
            SkinnedMesh mesh = AuthenticCharacterModelDecoder.decodeModelChunk(bytes, skelMatrices, target.displayName());
            Map<String, DecodedTexture> textures = AuthenticCharacterModelDecoder.decodeTextures(bytes);

            assertNotNull(mesh, "Decoded mesh must not be null for " + target.displayName());
            assertTrue(!mesh.subMeshes().isEmpty(), "Mesh must contain submeshes for " + target.displayName());

            int totalTris = mesh.subMeshes().stream().mapToInt(s -> s.indices().size() / 3).sum();
            System.out.printf("[SNAPSHOT-SUITE] Rendering %s [%s] (%s): %d bones, %d submeshes, %d triangles, %d textures%n",
                    target.category(), target.displayName(), target.datPath(), skelMatrices.size(),
                    mesh.subMeshes().size(), totalTris, textures.size());

            BufferedImage snapshot = renderModelSnapshot(mesh, textures, target, totalTris);
            File outFile = OUTPUT_DIR.resolve(target.id() + ".png").toFile();
            ImageIO.write(snapshot, "PNG", outFile);

            assertTrue(outFile.exists() && outFile.length() > 0, "Snapshot file must be written for " + target.id());
            System.out.printf("[SNAPSHOT-SUITE] Saved snapshot -> %s (%,d bytes)%n", outFile.getPath(), outFile.length());
        }
    }

    private BufferedImage renderModelSnapshot(SkinnedMesh mesh, Map<String, DecodedTexture> textures,
                                               EntityTarget target, int totalTris) {
        int width = 1440;
        int height = 1620;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        float[] zBuffer = new float[width * height];
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);

        // Retail dark slate vignette background
        Graphics2D g = image.createGraphics();
        for (int y = 0; y < height; y++) {
            float ratio = (float) y / height;
            int r = (int) (16 + ratio * 12);
            int gr = (int) (20 + ratio * 14);
            int b = (int) (28 + ratio * 20);
            g.setColor(new Color(r, gr, b));
            g.drawLine(0, y, width, y);
        }

        // Bounding box calculation across all vertices
        float minX = 1e9f, maxX = -1e9f;
        float minY = 1e9f, maxY = -1e9f;
        float minZ = 1e9f, maxZ = -1e9f;

        for (SkinnedSubMesh sub : mesh.subMeshes()) {
            for (SkinnedVertex v : sub.vertices()) {
                float x = v.position().x();
                float y = v.position().y();
                float z = v.position().z();
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
        }

        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f + (maxY - minY) * target.yOffsetRatio();
        float cz = (minZ + maxZ) * 0.5f;
        float modelHeight = Math.max(0.5f, maxY - minY);

        float angle = (float) Math.toRadians(target.camAngleDeg());
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);
        float dist = modelHeight * target.distMult();
        float fov = (float) Math.tan(Math.toRadians(20.0));

        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        for (SkinnedSubMesh sub : mesh.subMeshes()) {
            DecodedTexture tex = resolveTexture(sub.textureName(), textures);
            List<SkinnedVertex> verts = sub.vertices();
            List<Integer> indices = sub.indices();

            for (int i = 0; i + 2 < indices.size(); i += 3) {
                SkinnedVertex v0 = verts.get(indices.get(i));
                SkinnedVertex v1 = verts.get(indices.get(i + 1));
                SkinnedVertex v2 = verts.get(indices.get(i + 2));

                // 3D coordinates relative to center
                float p0x = v0.position().x() - cx, p0y = -(v0.position().y() - cy), p0z = v0.position().z() - cz;
                float p1x = v1.position().x() - cx, p1y = -(v1.position().y() - cy), p1z = v1.position().z() - cz;
                float p2x = v2.position().x() - cx, p2y = -(v2.position().y() - cy), p2z = v2.position().z() - cz;

                float[] c0 = toCamera(p0x, p0y, p0z, cosA, sinA, dist);
                float[] c1 = toCamera(p1x, p1y, p1z, cosA, sinA, dist);
                float[] c2 = toCamera(p2x, p2y, p2z, cosA, sinA, dist);

                if (c0[2] <= 0.1f || c1[2] <= 0.1f || c2[2] <= 0.1f) continue;

                float sx0 = (c0[0] / (c0[2] * fov) * 0.5f + 0.5f) * width;
                float sy0 = (0.5f - c0[1] / (c0[2] * fov) * 0.5f) * height;

                float sx1 = (c1[0] / (c1[2] * fov) * 0.5f + 0.5f) * width;
                float sy1 = (0.5f - c1[1] / (c1[2] * fov) * 0.5f) * height;

                float sx2 = (c2[0] / (c2[2] * fov) * 0.5f + 0.5f) * width;
                float sy2 = (0.5f - c2[1] / (c2[2] * fov) * 0.5f) * height;

                rasterizeTriangle(pixels, zBuffer, width, height,
                        sx0, sy0, c0[2], v0.u(), v0.v(),
                        sx1, sy1, c1[2], v1.u(), v1.v(),
                        sx2, sy2, c2[2], v2.u(), v2.v(),
                        tex);
            }
        }

        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static DecodedTexture resolveTexture(String subTexName, Map<String, DecodedTexture> textures) {
        if (textures.isEmpty()) return null;
        DecodedTexture exact = textures.get(subTexName);
        if (exact != null) return exact;

        // Try substring or prefix matches
        for (Map.Entry<String, DecodedTexture> e : textures.entrySet()) {
            if (e.getKey().contains(subTexName) || subTexName.contains(e.getKey())) {
                return e.getValue();
            }
        }
        // Fallback to primary texture if single texture exists
        return textures.values().iterator().next();
    }

    private static float[] toCamera(float fwd, float up, float right, float cosA, float sinA, float dist) {
        float camZ = dist - (cosA * fwd - sinA * right);
        float camX = sinA * fwd + cosA * right;
        float camY = up;
        return new float[]{camX, camY, camZ};
    }

    private static void rasterizeTriangle(int[] pixels, float[] zBuffer, int width, int height,
                                          float x0, float y0, float z0, float u0, float v0,
                                          float x1, float y1, float z1, float u1, float v1,
                                          float x2, float y2, float z2, float u2, float v2,
                                          DecodedTexture texture) {
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));

        float denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(denom) < 1e-5f) return;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;
                float py = y + 0.5f;

                float w0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / denom;
                float w1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / denom;
                float w2 = 1.0f - w0 - w1;

                if (w0 >= 0f && w1 >= 0f && w2 >= 0f) {
                    float z = w0 * z0 + w1 * z1 + w2 * z2;
                    int idx = y * width + x;
                    if (z < zBuffer[idx]) {
                        float u = w0 * u0 + w1 * u1 + w2 * u2;
                        float v = w0 * v0 + w1 * v1 + w2 * v2;

                        if (texture != null && texture.rgbaPixels() != null && texture.rgbaPixels().length > 0) {
                            int texColor = sampleOpaque(texture, u, v);
                            if (texColor == 0) continue;
                            zBuffer[idx] = z;
                            pixels[idx] = texColor;
                        } else {
                            zBuffer[idx] = z;
                            pixels[idx] = 0xFFCCCCCC;
                        }
                    }
                }
            }
        }
    }

    private static int sampleOpaque(DecodedTexture tex, float u, float v) {
        int width = tex.width();
        int height = tex.height();
        float fu = u - (float) Math.floor(u);
        float fv = v - (float) Math.floor(v);
        int tx = Math.max(0, Math.min(width - 1, (int) (fu * width)));
        int ty = Math.max(0, Math.min(height - 1, (int) (fv * height)));
        int offset = (ty * width + tx) * 4;

        byte[] rgba = tex.rgbaPixels();
        if (offset + 3 >= rgba.length) return 0;
        int a = rgba[offset + 3] & 0xFF;
        if (a < 32) return 0; // alpha threshold discard

        int r = rgba[offset] & 0xFF;
        int g = rgba[offset + 1] & 0xFF;
        int b = rgba[offset + 2] & 0xFF;

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
}
