package com.ffxi.viewer;

import com.example.asset.loader.RaceModelLoader;
import com.example.asset.skeleton.AuthenticCharacterModelDecoder;
import com.example.asset.skeleton.SkinnedMesh;
import com.example.asset.skeleton.SkinnedSubMesh;
import com.example.asset.skeleton.SkinnedVertex;
import com.example.asset.texture.DecodedTexture;
import com.ffxi.io.ByteReader;
import com.ffxi.model.*;
import com.ffxi.parser.DatParser;
import org.joml.Matrix4f;
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

/**
 * Suite that evaluates skeletal animation playback, multi-slot layering,
 * dual-animation cross-fade blending, and dual-bone linear blend skinning across
 * authentic retail FFXI character rigs, outputting high-resolution snapshots.
 */
public class CharacterAnimationSnapshotSuiteTest {

    private static final Path FFXI_ROOT = Path.of("/home/samuel_l_hyman/Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI");
    private static final Path OUTPUT_DIR = Path.of("target/snapshots");

    record AnimCapture(
            String id,
            String label,
            RaceModelLoader.CharacterRace race,
            String slot0Anim,
            String slot1Anim,
            double time,
            float camAngleDeg,
            // For blending tests:
            String blendSlot0,
            String blendSlot1,
            double blendTime,
            float blendFactor
    ) {
        // Standard single/layered animation
        AnimCapture(String id, String label, RaceModelLoader.CharacterRace race, String slot0, String slot1, double time, float angle) {
            this(id, label, race, slot0, slot1, time, angle, null, null, 0.0, 0.0f);
        }
    }

    @Test
    @DisplayName("Loop through character animations, layers, and blends to verify skeletal skinning and bone weights")
    void testRenderAndSnapshotAllCharacterAnimations() throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        List<AnimCapture> captures = List.of(
                // 1. Core Hume Male Locomotion & Poses
                new AnimCapture("humem_idle", "Hume Male - Authentic Idle", RaceModelLoader.CharacterRace.HUME_MALE, "idl0", "idl1", 0.0, -25.0f),
                new AnimCapture("humem_walk", "Hume Male - Striding Walk", RaceModelLoader.CharacterRace.HUME_MALE, "wlk0", "wlk1", 0.35, -25.0f),
                new AnimCapture("humem_run", "Hume Male - Athletic Sprint", RaceModelLoader.CharacterRace.HUME_MALE, "run0", "run1", 0.25, -25.0f),
                new AnimCapture("humem_retreat_back", "Hume Male - Backward Retreat", RaceModelLoader.CharacterRace.HUME_MALE, "mvb0", "mvb1", 0.35, -25.0f),
                new AnimCapture("humem_strafe_left", "Hume Male - Strafe Left", RaceModelLoader.CharacterRace.HUME_MALE, "mvl0", "mvl1", 0.25, -25.0f),
                new AnimCapture("humem_strafe_right", "Hume Male - Strafe Right", RaceModelLoader.CharacterRace.HUME_MALE, "mvr0", "mvr1", 0.25, -25.0f),
                new AnimCapture("humem_sit_rest", "Hume Male - Rest / Meditation", RaceModelLoader.CharacterRace.HUME_MALE, "std0", "std1", 0.50, -25.0f),
                new AnimCapture("humem_defeat_collapse", "Hume Male - Defeat Fall", RaceModelLoader.CharacterRace.HUME_MALE, "ded0", "ded1", 0.60, -25.0f),

                // 2. Combat & Action Poses
                new AnimCapture("humem_combat_1h", "Hume Male - Combat 1H Stance", RaceModelLoader.CharacterRace.HUME_MALE, "cm00", "cm01", 0.25, -25.0f),
                new AnimCapture("humem_combat_2h", "Hume Male - Heavy 2H Stance", RaceModelLoader.CharacterRace.HUME_MALE, "cm10", "cm11", 0.25, -25.0f),
                new AnimCapture("humem_cast_gather", "Hume Male - Spell Gathering", RaceModelLoader.CharacterRace.HUME_MALE, "mb00", "mb01", 0.30, -25.0f),
                new AnimCapture("humem_cast_channel", "Hume Male - Spell Channeling", RaceModelLoader.CharacterRace.HUME_MALE, "mb10", "mb11", 0.50, -25.0f),
                new AnimCapture("humem_cast_release", "Hume Male - Spell Release", RaceModelLoader.CharacterRace.HUME_MALE, "mb20", "mb21", 0.25, -25.0f),
                new AnimCapture("humem_item_use", "Hume Male - Item / Medicine Use", RaceModelLoader.CharacterRace.HUME_MALE, "mi00", "mi01", 0.30, -25.0f),
                new AnimCapture("humem_weapon_skill", "Hume Male - Martial Weapon Skill", RaceModelLoader.CharacterRace.HUME_MALE, "mw00", "mw01", 0.30, -25.0f),
                new AnimCapture("humem_hit_recoil", "Hume Male - Damage Recoil", RaceModelLoader.CharacterRace.HUME_MALE, "rx00", "rx01", 0.20, -25.0f),
                new AnimCapture("humem_jump", "Hume Male - Jump Leap", RaceModelLoader.CharacterRace.HUME_MALE, "jmp0", "jmp1", 0.20, -25.0f),
                new AnimCapture("humem_cheer", "Hume Male - Cheer Emote", RaceModelLoader.CharacterRace.HUME_MALE, "sh00", "sh01", 0.25, -25.0f),

                // 3. Animation Layering (Upper body override on top of independent lower body)
                new AnimCapture("layer_run_legs_idle_arms", "Layered - Sprint Legs + Idle Arms", RaceModelLoader.CharacterRace.HUME_MALE, "run0", "idl1", 0.25, -25.0f),
                new AnimCapture("layer_walk_legs_combat_arms", "Layered - Striding Walk + Combat 1H Arms", RaceModelLoader.CharacterRace.HUME_MALE, "wlk0", "cm01", 0.35, -25.0f),
                new AnimCapture("layer_combat_stance_cast_arms", "Layered - Combat Footing + Spell Channel Arms", RaceModelLoader.CharacterRace.HUME_MALE, "cm00", "mb01", 0.30, -25.0f),
                new AnimCapture("layer_run_legs_cast_arms", "Layered - Running Legs + Spell Cast Arms", RaceModelLoader.CharacterRace.HUME_MALE, "run0", "mb01", 0.25, -25.0f),

                // 4. Animation Blending (Dual-State Keyframe Cross-Fade Interpolation)
                new AnimCapture("blend_walk_to_run_50", "Blended - 50% Walk / 50% Run Cross-Fade", RaceModelLoader.CharacterRace.HUME_MALE, "wlk0", "wlk1", 0.35, -25.0f, "run0", "run1", 0.25, 0.50f),
                new AnimCapture("blend_idle_to_combat_50", "Blended - 50% Idle / 50% Combat Ready", RaceModelLoader.CharacterRace.HUME_MALE, "idl0", "idl1", 0.00, -25.0f, "cm00", "cm01", 0.25, 0.50f),

                // 5. Multi-Race Animation Verification
                new AnimCapture("mithra_walk", "Mithra - Striding Walk", RaceModelLoader.CharacterRace.MITHRA, "wlk0", "wlk1", 0.35, -25.0f),
                new AnimCapture("mithra_combat", "Mithra - Combat Ready Stance", RaceModelLoader.CharacterRace.MITHRA, "cm00", "cm01", 0.25, -25.0f),
                new AnimCapture("tarum_run", "TaruTaru Male - Sprint Run", RaceModelLoader.CharacterRace.TARUTARU_MALE, "run0", "run1", 0.25, -25.0f),
                new AnimCapture("tarum_magic_cast", "TaruTaru Male - Spell Channel", RaceModelLoader.CharacterRace.TARUTARU_MALE, "mb00", "mb01", 0.30, -25.0f),
                new AnimCapture("elvaanm_combat", "Elvaan Male - Combat 1H Ready", RaceModelLoader.CharacterRace.ELVAAN_MALE, "cm00", "cm01", 0.25, -25.0f),
                new AnimCapture("galka_run", "Galka - Heavy Sprint Run", RaceModelLoader.CharacterRace.GALKA, "run0", "run1", 0.25, -25.0f)
        );

        Map<RaceModelLoader.CharacterRace, RaceAssetCache> cacheByRace = new HashMap<>();

        for (AnimCapture cap : captures) {
            System.out.printf("[ANIM-SUITE] Capturing '%s' for %s...%n", cap.label, cap.race.displayName());
            RaceAssetCache assets = cacheByRace.computeIfAbsent(cap.race, r -> loadRaceAssets(r));
            assertNotNull(assets.skeleton, "Skeleton must exist for " + cap.race.displayName());

            Matrix4f[] jomlMatrices;
            if (cap.blendSlot0 != null) {
                // Cross-fade blend between two distinct animation states
                CharacterAnimator animA = createAnimator(assets, cap.slot0Anim, cap.slot1Anim);
                CharacterAnimator animB = createAnimator(assets, cap.blendSlot0, cap.blendSlot1);
                jomlMatrices = animA.computeBlendedPoseMatrices(animB, cap.time, cap.blendTime, cap.blendFactor);
            } else {
                // Standard or layered animation pose
                CharacterAnimator anim = createAnimator(assets, cap.slot0Anim, cap.slot1Anim);
                jomlMatrices = anim.computePoseMatrices(cap.time);
            }

            // Convert to column-major float[16] matrices for the model decoder
            List<float[]> animMatrices = new ArrayList<>(jomlMatrices.length);
            for (Matrix4f m : jomlMatrices) {
                float[] arr = new float[16];
                m.get(arr);
                animMatrices.add(arr);
            }

            // Decode mesh equipment slots using animated joint matrices
            Map<Integer, SkinnedMesh> slots = new HashMap<>();
            Map<String, DecodedTexture> textures = new HashMap<>();

            loadSlot(cap.race.headDatPath(), 0, animMatrices, slots, textures);
            loadSlot(cap.race.bodyDatPath(), 1, animMatrices, slots, textures);
            loadSlot(cap.race.handsDatPath(), 2, animMatrices, slots, textures);
            loadSlot(cap.race.legsDatPath(), 3, animMatrices, slots, textures);
            loadSlot(cap.race.feetDatPath(), 4, animMatrices, slots, textures);

            BufferedImage snapshot = render(slots, textures, cap.camAngleDeg);
            File outFile = OUTPUT_DIR.resolve("character_anim_" + cap.id + ".png").toFile();
            ImageIO.write(snapshot, "PNG", outFile);

            assertTrue(outFile.exists() && outFile.length() > 0, "Snapshot must be saved");
            System.out.printf("[ANIM-SUITE] Successfully saved %s (%d bytes)%n", outFile.getName(), outFile.length());
        }
    }

    private static CharacterAnimator createAnimator(RaceAssetCache assets, String slot0Name, String slot1Name) {
        SkeletonAnimationResource s0 = findAnim(assets.slot0Anims, slot0Name);
        SkeletonAnimationResource s1 = slot1Name != null ? findAnim(assets.slot1Anims, slot1Name) : null;
        if (s1 == null && slot1Name != null) {
            // Check base DAT in case upper animation is bundled in base
            s1 = findAnim(assets.slot0Anims, slot1Name);
        }
        return new CharacterAnimator(assets.skeleton, s0, s1);
    }

    private record RaceAssetCache(
            SkeletonResource skeleton,
            List<SkeletonAnimationResource> slot0Anims,
            List<SkeletonAnimationResource> slot1Anims
    ) {}

    private static RaceAssetCache loadRaceAssets(RaceModelLoader.CharacterRace race) {
        try {
            Path baseDat = FFXI_ROOT.resolve(race.baseDatPath());
            byte[] baseBytes = Files.readAllBytes(baseDat);
            DatResourceGraph baseGraph = DatParser.parseResourceGraph(new ByteReader(baseBytes, baseDat.getFileName().toString()));

            List<SkeletonResource> skeletons = new ArrayList<>();
            List<SkeletonAnimationResource> slot0Anims = new ArrayList<>();
            collectSkeletons(baseGraph.rootDirectory(), skeletons);
            collectAnimations(baseGraph.rootDirectory(), slot0Anims);

            SkeletonResource skeleton = skeletons.getFirst();

            // Upper animation DAT is conventionally base file + 1 (e.g. 82 -> 83, 58 -> 59, 31 -> 32)
            String upperDatPath = getUpperDatPath(race);
            List<SkeletonAnimationResource> slot1Anims = new ArrayList<>();
            if (upperDatPath != null) {
                Path upperDat = FFXI_ROOT.resolve(upperDatPath);
                if (Files.exists(upperDat)) {
                    byte[] upperBytes = Files.readAllBytes(upperDat);
                    DatResourceGraph upperGraph = DatParser.parseResourceGraph(new ByteReader(upperBytes, upperDat.getFileName().toString()));
                    collectAnimations(upperGraph.rootDirectory(), slot1Anims);
                }
            }

            return new RaceAssetCache(skeleton, slot0Anims, slot1Anims);
        } catch (Exception e) {
            throw new RuntimeException("Failed loading race assets for " + race.displayName(), e);
        }
    }

    private static String getUpperDatPath(RaceModelLoader.CharacterRace race) {
        return switch (race) {
            case HUME_MALE -> "ROM/27/83.DAT";
            case HUME_FEMALE -> "ROM/32/59.DAT";
            case ELVAAN_MALE -> "ROM/37/32.DAT";
            case ELVAAN_FEMALE -> "ROM/42/5.DAT";
            case TARUTARU_MALE -> "ROM/46/94.DAT";
            case TARUTARU_FEMALE -> "ROM/46/94.DAT";
            case MITHRA -> "ROM/51/90.DAT";
            case GALKA -> "ROM/56/60.DAT";
        };
    }

    private static void loadSlot(String relPath, int slotId, List<float[]> matrices,
                                 Map<Integer, SkinnedMesh> slots, Map<String, DecodedTexture> textures) throws Exception {
        if (relPath == null || relPath.isBlank()) return;
        Path path = FFXI_ROOT.resolve(relPath);
        if (!Files.exists(path)) return;
        byte[] bytes = Files.readAllBytes(path);
        SkinnedMesh mesh = AuthenticCharacterModelDecoder.decodeModelChunk(bytes, matrices, "slot_" + slotId);
        if (!mesh.subMeshes().isEmpty()) {
            slots.put(slotId, mesh);
        }
        textures.putAll(AuthenticCharacterModelDecoder.decodeTextures(bytes));
    }

    private static void collectSkeletons(ResourceNode node, List<SkeletonResource> list) {
        if (node instanceof SkeletonResource s) list.add(s);
        if (node instanceof DirectoryResource d) {
            for (ResourceNode c : d.children()) collectSkeletons(c, list);
        }
    }

    private static void collectAnimations(ResourceNode node, List<SkeletonAnimationResource> list) {
        if (node instanceof SkeletonAnimationResource a) list.add(a);
        if (node instanceof DirectoryResource d) {
            for (ResourceNode c : d.children()) collectAnimations(c, list);
        }
    }

    private static SkeletonAnimationResource findAnim(List<SkeletonAnimationResource> list, String id) {
        if (id == null) return null;
        for (SkeletonAnimationResource a : list) {
            if (a.sectionId().equalsIgnoreCase(id)) return a;
        }
        return null;
    }

    private static BufferedImage render(Map<Integer, SkinnedMesh> slots, Map<String, DecodedTexture> textures, float angleDeg) {
        int width = 1440;
        int height = 1620;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        float[] zBuffer = new float[width * height];
        Arrays.fill(zBuffer, Float.POSITIVE_INFINITY);

        // Retail dark gradient background
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

        for (SkinnedMesh mesh : slots.values()) {
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
        float charHeight = Math.max(1.5f, maxY - minY);

        float angle = (float) Math.toRadians(angleDeg);
        float cosA = (float) Math.cos(angle);
        float sinA = (float) Math.sin(angle);
        float dist = charHeight * 1.55f;
        float fov = (float) Math.tan(Math.toRadians(20.0));

        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        for (SkinnedMesh mesh : slots.values()) {
            for (SkinnedSubMesh sub : mesh.subMeshes()) {
                DecodedTexture tex = textures.get(sub.textureName());
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

                    rasterizeTriangle(pixels, zBuffer, width, height,
                            sx0, sy0, c0[2], v0.u(), v0.v(),
                            sx1, sy1, c1[2], v1.u(), v1.v(),
                            sx2, sy2, c2[2], v2.u(), v2.v(),
                            tex);
                }
            }
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static float[] toCamera(float x, float y, float z, float cosA, float sinA, float dist) {
        float rx = cosA * x - sinA * z;
        float ry = y;
        float rz = sinA * x + cosA * z + dist;
        return new float[]{rx, ry, rz};
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
        int a = rgba[offset + 3] & 0xFF;
        if (a < 10) return 0;

        int r = rgba[offset] & 0xFF;
        int g = rgba[offset + 1] & 0xFF;
        int b = rgba[offset + 2] & 0xFF;

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
}
