package com.ffxi.viewer;

import com.example.asset.loader.RaceModelLoader;
import com.example.asset.skeleton.CompositeCharacterModel;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CharacterSnapshotSuiteTest {

    private static final Path OUTPUT_DIR = Path.of("target/snapshots");

    record RaceConfig(RaceModelLoader.CharacterRace race, String snapshotKey) {}

    @Test
    @DisplayName("Render and snapshot authentic idle animation poses for all playable race and gender combinations")
    void testRenderAndSnapshotAllPlayableRaces() throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        RaceConfig[] playableRaces = new RaceConfig[] {
                new RaceConfig(RaceModelLoader.CharacterRace.HUME_MALE, "humem"),
                new RaceConfig(RaceModelLoader.CharacterRace.HUME_FEMALE, "humef"),
                new RaceConfig(RaceModelLoader.CharacterRace.ELVAAN_MALE, "elvaanm"),
                new RaceConfig(RaceModelLoader.CharacterRace.ELVAAN_FEMALE, "elvaanf"),
                new RaceConfig(RaceModelLoader.CharacterRace.TARUTARU_MALE, "tarum"),
                new RaceConfig(RaceModelLoader.CharacterRace.TARUTARU_FEMALE, "taruf"),
                new RaceConfig(RaceModelLoader.CharacterRace.MITHRA, "mithra"),
                new RaceConfig(RaceModelLoader.CharacterRace.GALKA, "galka")
        };

        RaceModelLoader loader = new RaceModelLoader();

        for (RaceConfig cfg : playableRaces) {
            System.out.println("[SNAPSHOT-TEST] Loading authentic character model: " + cfg.race.displayName());
            CompositeCharacterModel model = loader.loadRaceModel(cfg.race);
            assertNotNull(model, "Character should load for " + cfg.race.displayName());
            assertNotNull(model.skeleton(), "Skeleton must exist for " + cfg.race.displayName());

            BufferedImage snapshot = renderCharacterSnapshot(model);
            File outFile = OUTPUT_DIR.resolve("character_idle_" + cfg.snapshotKey + ".png").toFile();
            ImageIO.write(snapshot, "PNG", outFile);
            assertTrue(outFile.exists() && outFile.length() > 0, "Snapshot file should be written");
            System.out.printf("[SNAPSHOT-TEST] Saved %s to %s (%d bytes)%n", cfg.race.displayName(), outFile.getPath(), outFile.length());
        }
    }

    private BufferedImage renderCharacterSnapshot(CompositeCharacterModel model) {
        int width = 1440;
        int height = 1620;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        float[] zBuffer = new float[width * height];
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);

        // Retail-style UI dark slate gradient background
        Graphics2D g = image.createGraphics();
        for (int y = 0; y < height; y++) {
            float ratio = (float) y / height;
            int r = (int) (18 + ratio * 10);
            int gr = (int) (22 + ratio * 12);
            int b = (int) (30 + ratio * 18);
            g.setColor(new Color(r, gr, b));
            g.drawLine(0, y, width, y);
        }
        g.dispose();

        float minX = 1e9f, maxX = -1e9f;
        float minY = 1e9f, maxY = -1e9f;
        float minZ = 1e9f, maxZ = -1e9f;

        for (SkinnedMesh mesh : model.equipmentSlots().values()) {
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
        }

        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float charHeight = Math.max(0.5f, maxY - minY);

        // Retail FFXI front-quarter angle
        float angle = (float) Math.toRadians(25.0);
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);
        float dist = charHeight * 1.55f;
        float fov = (float) Math.tan(Math.toRadians(20.0));

        System.out.printf("[DEBUG-TEST] Bounds: cx=%.3f cy=%.3f cz=%.3f height=%.3f slots=%d%n",
                cx, cy, cz, charHeight, model.equipmentSlots().size());
        int drawnCount = 0;
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        // Directional key light from camera upper-front-right
        float lx = 0.40f, ly = 0.70f, lz = 0.59f;

        for (SkinnedMesh mesh : model.equipmentSlots().values()) {
            for (SkinnedSubMesh sub : mesh.subMeshes()) {
                DecodedTexture tex = model.textures().get(sub.textureName());
                List<SkinnedVertex> verts = sub.vertices();
                List<Integer> indices = sub.indices();

                for (int i = 0; i + 2 < indices.size(); i += 3) {
                    SkinnedVertex v0 = verts.get(indices.get(i));
                    SkinnedVertex v1 = verts.get(indices.get(i + 1));
                    SkinnedVertex v2 = verts.get(indices.get(i + 2));

                    float[] c0 = toCamera(v0.position().x() - cx, -(v0.position().y() - cy), v0.position().z() - cz, cosA, sinA, dist);
                    float[] c1 = toCamera(v1.position().x() - cx, -(v1.position().y() - cy), v1.position().z() - cz, cosA, sinA, dist);
                    float[] c2 = toCamera(v2.position().x() - cx, -(v2.position().y() - cy), v2.position().z() - cz, cosA, sinA, dist);

                    if (c0[2] <= 0.1f || c1[2] <= 0.1f || c2[2] <= 0.1f) continue;

                    float sx0 = (c0[0] / (c0[2] * fov) * 0.5f + 0.5f) * width;
                    float sy0 = (0.5f - c0[1] / (c0[2] * fov) * 0.5f) * height;

                    float sx1 = (c1[0] / (c1[2] * fov) * 0.5f + 0.5f) * width;
                    float sy1 = (0.5f - c1[1] / (c1[2] * fov) * 0.5f) * height;

                    float sx2 = (c2[0] / (c2[2] * fov) * 0.5f + 0.5f) * width;
                    float sy2 = (0.5f - c2[1] / (c2[2] * fov) * 0.5f) * height;

                    float ax = c1[0] - c0[0], ay = c1[1] - c0[1], az = c1[2] - c0[2];
                    float bx = c2[0] - c0[0], by = c2[1] - c0[1], bz = c2[2] - c0[2];
                    float fnx = ay * bz - az * by;
                    float fny = az * bx - ax * bz;
                    float fnz = ax * by - ay * bx;
                    float fnlen = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
                    if (fnlen > 1e-4f) { fnx /= fnlen; fny /= fnlen; fnz /= fnlen; }

                    float dot = fnx * lx + fny * ly + fnz * lz;
                    float diff = Math.max(0.45f, Math.abs(dot) * 0.55f + 0.45f);

                    drawnCount++;
                    rasterizeTriangle(pixels, zBuffer, width, height,
                            sx0, sy0, c0[2], v0.u(), v0.v(), diff,
                            sx1, sy1, c1[2], v1.u(), v1.v(), diff,
                            sx2, sy2, c2[2], v2.u(), v2.v(), diff,
                            tex);
                }
            }
        }

        System.out.println("[DEBUG-TEST] Drawn triangles count: " + drawnCount);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static float[] toCamera(float fwd, float up, float right, float cosA, float sinA, float dist) {
        float camZ = dist - (cosA * fwd - sinA * right);
        float camX = sinA * fwd + cosA * right;
        float camY = up;
        return new float[] { camX, camY, camZ };
    }

    private static void rasterizeTriangle(int[] pixels, float[] zBuffer, int width, int height,
                                          float x0, float y0, float z0, float u0, float v0, float l0,
                                          float x1, float y1, float z1, float u1, float v1, float l1,
                                          float x2, float y2, float z2, float u2, float v2, float l2,
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
                        float diff = w0 * l0 + w1 * l1 + w2 * l2;

                        if (texture != null && texture.rgbaPixels() != null && texture.rgbaPixels().length > 0) {
                            int texColor = sampleOpaque(texture, u, v, diff);
                            if (texColor == 0) continue; // transparent / alpha discard
                            zBuffer[idx] = z;
                            pixels[idx] = texColor;
                        } else {
                            zBuffer[idx] = z;
                            int gray = (int) (180 * Math.min(1f, diff));
                            pixels[idx] = (0xFF << 24) | (gray << 16) | (gray << 8) | gray;
                        }
                    }
                }
            }
        }
    }

    private static int sampleOpaque(DecodedTexture tex, float u, float v, float light) {
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
        if (a < 32) return 0; // discard transparent fringe

        int r = rgba[offset] & 0xFF;
        int g = rgba[offset + 1] & 0xFF;
        int b = rgba[offset + 2] & 0xFF;

        r = Math.min(255, (int) (r * light));
        g = Math.min(255, (int) (g * light));
        b = Math.min(255, (int) (b * light));

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
}
