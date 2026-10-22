package com.ffxi.viewer;

import com.ffxi.export.TextureDecoder;
import com.ffxi.model.*;
import com.ffxi.tables.RaceGenderConfig;
import com.ffxi.viewer.model.CharacterModelLoader;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CharacterSnapshotSuiteTest {

    private static final Path FFXI_ROOT = Path.of("/home/samuel_l_hyman/Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI");
    private static final Path OUTPUT_DIR = Path.of("target/snapshots");

    @Test
    @DisplayName("Render and snapshot idle animation poses for all playable race and gender combinations")
    void testRenderAndSnapshotAllPlayableRaces() throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        RaceGenderConfig[] playableRaces = new RaceGenderConfig[] {
                RaceGenderConfig.HumeM,
                RaceGenderConfig.HumeF,
                RaceGenderConfig.ElvaanM,
                RaceGenderConfig.ElvaanF,
                RaceGenderConfig.TaruM,
                RaceGenderConfig.TaruF,
                RaceGenderConfig.Mithra,
                RaceGenderConfig.Galka
        };

        for (RaceGenderConfig race : playableRaces) {
            System.out.println("[SNAPSHOT-TEST] Loading authentic character model: " + race.name());
            LoadedCharacter character = CharacterModelLoader.loadCharacter(FFXI_ROOT, race);
            assertNotNull(character, "Character should load for " + race.name());

            BufferedImage snapshot = renderCharacterSnapshot(character, race.name());
            File outFile = OUTPUT_DIR.resolve("character_idle_" + race.name().toLowerCase(Locale.ROOT) + ".png").toFile();
            ImageIO.write(snapshot, "PNG", outFile);
            assertTrue(outFile.exists() && outFile.length() > 0, "Snapshot file should be written");
            System.out.printf("[SNAPSHOT-TEST] Saved %s to %s (%d bytes)%n", race.name(), outFile.getPath(), outFile.length());
        }
    }

    private BufferedImage renderCharacterSnapshot(LoadedCharacter character, String raceName) {
        int width = 480;
        int height = 540;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        float[] zBuffer = new float[width * height];
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);

        // Dark gradient background
        Graphics2D g = image.createGraphics();
        for (int y = 0; y < height; y++) {
            float ratio = (float) y / height;
            int r = (int) (18 + ratio * 8);
            int gr = (int) (22 + ratio * 10);
            int b = (int) (28 + ratio * 14);
            g.setColor(new Color(r, gr, b));
            g.drawLine(0, y, width, y);
        }
        g.dispose();

        // Find idle animations
        SkeletonAnimationResource anim0 = null;
        SkeletonAnimationResource anim1 = null;
        for (SkeletonAnimationResource anim : character.slot0Animations()) {
            if (anim.sectionId().equalsIgnoreCase("idl0")) {
                anim0 = anim;
                break;
            }
        }
        for (SkeletonAnimationResource anim : character.slot1Animations()) {
            if (anim.sectionId().equalsIgnoreCase("idl1")) {
                anim1 = anim;
                break;
            }
        }
        if (anim0 == null && !character.slot0Animations().isEmpty()) anim0 = character.slot0Animations().get(0);

        CharacterAnimator animator = new CharacterAnimator(character.skeleton(), anim0, anim1);
        Matrix4f[] jointMatrices = animator.computePoseMatrices(0.0);

        // Decode textures
        Map<String, DecodedImage> decodedTextures = new HashMap<>();
        for (Map.Entry<String, CharacterTextureLoader.TextureSource> entry : character.textures().entrySet()) {
            byte[] rgba = TextureDecoder.decode(entry.getValue().rawData(), entry.getValue().resource());
            if (rgba != null) {
                decodedTextures.put(entry.getKey(), new DecodedImage(entry.getValue().resource().width(), entry.getValue().resource().height(), rgba));
            }
        }

        // First pass: compute exact skinned bounding box
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        Matrix4f model = new Matrix4f()
                .scale(1f, -1f, -1f)
                .translate(character.modelOffset());

        for (SkeletonMeshMesh mesh : character.meshes()) {
            for (SkeletonMeshVertex sv : mesh.resolvedVertices()) {
                Vector3f p = skinPoint(sv, jointMatrices);
                Vector4f wp = new Vector4f(p.x, p.y, p.z, 1f).mul(model);
                minX = Math.min(minX, wp.x); maxX = Math.max(maxX, wp.x);
                minY = Math.min(minY, wp.y); maxY = Math.max(maxY, wp.y);
                minZ = Math.min(minZ, wp.z); maxZ = Math.max(maxZ, wp.z);
            }
        }

        float charHeight = Math.max(0.5f, maxY - minY);
        float centerX = (minX + maxX) * 0.5f;
        float centerY = (minY + maxY) * 0.5f;
        float centerZ = (minZ + maxZ) * 0.5f;

        // Camera placed in front-quarter view looking at center of character
        float camDist = charHeight * 1.65f;
        Vector3f target = new Vector3f(centerX, centerY, centerZ);
        Vector3f eye = new Vector3f(centerX + camDist * 0.85f, centerY + charHeight * 0.05f, centerZ - camDist * 0.85f);
        Vector3f up = new Vector3f(0f, 1f, 0f);

        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(40.0), (float) width / height, 0.05f, 100.0f);
        Matrix4f view = new Matrix4f().lookAt(eye, target, up);

        Matrix4f mvp = new Matrix4f(proj).mul(view).mul(model);

        Vector3f lightDir = new Vector3f(0.4f, 0.8f, 0.45f).normalize();

        // Render each mesh
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        for (SkeletonMeshMesh mesh : character.meshes()) {
            List<SkeletonMeshVertex> resolved = mesh.resolvedVertices();
            List<SkeletonMeshFaceVertex> faceVerts = mesh.vertices();
            if (resolved.isEmpty() || faceVerts.isEmpty()) continue;

            DecodedImage texture = decodedTextures.get(mesh.textureName());

            // Build triangles matching expandToTriangleList
            List<int[]> tris = expandTriangles(mesh);

            for (int[] tri : tris) {
                int i0 = tri[0];
                int i1 = tri[1];
                int i2 = tri[2];

                SkeletonMeshVertex sv0 = resolved.get(i0);
                SkeletonMeshVertex sv1 = resolved.get(i1);
                SkeletonMeshVertex sv2 = resolved.get(i2);

                SkeletonMeshFaceVertex fv0 = faceVerts.get(i0);
                SkeletonMeshFaceVertex fv1 = faceVerts.get(i1);
                SkeletonMeshFaceVertex fv2 = faceVerts.get(i2);

                Vector3f p0 = skinPoint(sv0, jointMatrices);
                Vector3f p1 = skinPoint(sv1, jointMatrices);
                Vector3f p2 = skinPoint(sv2, jointMatrices);

                Vector3f n0 = skinNormal(sv0, jointMatrices);
                Vector3f n1 = skinNormal(sv1, jointMatrices);
                Vector3f n2 = skinNormal(sv2, jointMatrices);

                // Project to clip space
                Vector4f c0 = new Vector4f(p0.x, p0.y, p0.z, 1f).mul(mvp);
                Vector4f c1 = new Vector4f(p1.x, p1.y, p1.z, 1f).mul(mvp);
                Vector4f c2 = new Vector4f(p2.x, p2.y, p2.z, 1f).mul(mvp);

                if (c0.w <= 0.001f || c1.w <= 0.001f || c2.w <= 0.001f) continue;

                // Screen coordinates
                float sx0 = (c0.x / c0.w * 0.5f + 0.5f) * width;
                float sy0 = (1.0f - (c0.y / c0.w * 0.5f + 0.5f)) * height;
                float sz0 = c0.z / c0.w;

                float sx1 = (c1.x / c1.w * 0.5f + 0.5f) * width;
                float sy1 = (1.0f - (c1.y / c1.w * 0.5f + 0.5f)) * height;
                float sz1 = c1.z / c1.w;

                float sx2 = (c2.x / c2.w * 0.5f + 0.5f) * width;
                float sy2 = (1.0f - (c2.y / c2.w * 0.5f + 0.5f)) * height;
                float sz2 = c2.z / c2.w;

                // 2D Cross product for screen area
                float cross = (sx1 - sx0) * (sy2 - sy0) - (sy1 - sy0) * (sx2 - sx0);
                // Backface culling: with GL_CCW for normal and GL_CW for mirrored
                if (mesh.mirrored()) {
                    if (cross > 0) continue; // Cull CCW for mirrored
                } else {
                    if (cross < 0) continue; // Cull CW for normal
                }

                // Shading from normals
                float diff0 = Math.max(0f, n0.dot(lightDir));
                float diff1 = Math.max(0f, n1.dot(lightDir));
                float diff2 = Math.max(0f, n2.dot(lightDir));
                float l0 = 0.40f + 0.60f * diff0;
                float l1 = 0.40f + 0.60f * diff1;
                float l2 = 0.40f + 0.60f * diff2;

                rasterizeTriangle(pixels, zBuffer, width, height,
                        sx0, sy0, sz0, fv0.u(), fv0.v(), l0,
                        sx1, sy1, sz1, fv1.u(), fv1.v(), l1,
                        sx2, sy2, sz2, fv2.u(), fv2.v(), l2,
                        texture);
            }
        }

        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static Vector3f skinPoint(SkeletonMeshVertex v, Matrix4f[] joints) {
        Matrix4f j0 = joints[v.jointIndex0() != null ? v.jointIndex0() : 0];
        Matrix4f j1 = joints[v.jointIndex1() != null ? v.jointIndex1() : 0];
        float w0 = v.joint0Weight();
        float w1 = 1.0f - w0;

        Vector3f p0 = v.p0() != null ? new Vector3f(v.p0().x(), v.p0().y(), v.p0().z()) : new Vector3f();
        Vector3f p1 = v.p1() != null ? new Vector3f(v.p1().x(), v.p1().y(), v.p1().z()) : p0;

        Vector4f tp0 = new Vector4f(p0.x, p0.y, p0.z, 1.0f).mul(j0);
        Vector4f tp1 = new Vector4f(p1.x, p1.y, p1.z, 1.0f).mul(j1);

        return new Vector3f(
                tp0.x * w0 + tp1.x * w1,
                tp0.y * w0 + tp1.y * w1,
                tp0.z * w0 + tp1.z * w1
        );
    }

    private static Vector3f skinNormal(SkeletonMeshVertex v, Matrix4f[] joints) {
        Matrix4f j0 = joints[v.jointIndex0() != null ? v.jointIndex0() : 0];
        Matrix4f j1 = joints[v.jointIndex1() != null ? v.jointIndex1() : 0];
        float w0 = v.joint0Weight();
        float w1 = 1.0f - w0;

        Vector3f n0 = v.n0() != null ? new Vector3f(v.n0().x(), v.n0().y(), v.n0().z()) : new Vector3f(0, 1, 0);
        Vector3f n1 = v.n1() != null ? new Vector3f(v.n1().x(), v.n1().y(), v.n1().z()) : n0;

        Vector4f tn0 = new Vector4f(n0.x, n0.y, n0.z, 0.0f).mul(j0);
        Vector4f tn1 = new Vector4f(n1.x, n1.y, n1.z, 0.0f).mul(j1);

        Vector3f res = new Vector3f(
                tn0.x * w0 + tn1.x * w1,
                tn0.y * w0 + tn1.y * w1,
                tn0.z * w0 + tn1.z * w1
        );
        float len = res.length();
        return len > 1e-4f ? res.div(len) : new Vector3f(0, 1, 0);
    }

    private static List<int[]> expandTriangles(SkeletonMeshMesh mesh) {
        int n = mesh.vertices().size();
        List<int[]> tris = new ArrayList<>();
        if (n < 3) return tris;

        if (mesh.vertexCount() == mesh.triangleCount() * 3) {
            for (int i = 0; i + 2 < n; i += 3) {
                tris.add(new int[] { i, i + 2, i + 1 });
            }
        } else {
            for (int i = 2; i < n; i++) {
                int a, b, c;
                if ((i & 1) == 0) {
                    a = i - 2; b = i; c = i - 1;
                } else {
                    a = i - 1; b = i; c = i - 2;
                }
                if (a == b || b == c || a == c) continue;
                tris.add(new int[] { a, b, c });
            }
        }
        return tris;
    }

    private static void rasterizeTriangle(int[] pixels, float[] zBuffer, int width, int height,
                                          float x0, float y0, float z0, float u0, float v0, float l0,
                                          float x1, float y1, float z1, float u1, float v1, float l1,
                                          float x2, float y2, float z2, float u2, float v2, float l2,
                                          DecodedImage texture) {
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
                        zBuffer[idx] = z;

                        float u = w0 * u0 + w1 * u1 + w2 * u2;
                        float v = w0 * v0 + w1 * v1 + w2 * v2;
                        float light = Math.max(0f, Math.min(1.5f, w0 * l0 + w1 * l1 + w2 * l2));

                        int color;
                        if (texture != null) {
                            color = texture.sample(u, v, light);
                        } else {
                            int gray = (int) (180 * Math.min(1f, light));
                            color = (0xFF << 24) | (gray << 16) | (gray << 8) | gray;
                        }
                        pixels[idx] = color;
                    }
                }
            }
        }
    }

    private record DecodedImage(int width, int height, byte[] rgba) {
        int sample(float u, float v, float light) {
            float fu = u - (float) Math.floor(u);
            float fv = v - (float) Math.floor(v);
            int tx = Math.max(0, Math.min(width - 1, (int) (fu * width)));
            int ty = Math.max(0, Math.min(height - 1, (int) (fv * height)));
            int offset = (ty * width + tx) * 4;

            int r = rgba[offset] & 0xFF;
            int g = rgba[offset + 1] & 0xFF;
            int b = rgba[offset + 2] & 0xFF;
            int a = rgba[offset + 3] & 0xFF;

            r = Math.min(255, (int) (r * light));
            g = Math.min(255, (int) (g * light));
            b = Math.min(255, (int) (b * light));

            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
}
