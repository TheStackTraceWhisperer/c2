package com.ffxi.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ffxi.crypto.KeyTables;
import com.ffxi.io.ByteReader;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.EndSectionResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.SkeletonMeshResource;
import com.ffxi.model.SkeletonResource;
import com.ffxi.model.TextureResource;
import com.ffxi.model.WeightedMeshResource;
import com.ffxi.model.ZoneDefResource;
import com.ffxi.model.ZoneMeshResource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DatParserTest {

    @BeforeAll
    static void initKeys() {
        byte[] dll = new byte[0x31000];
        writeBe32(dll, 0x30100, 0xE2E506A9);
        writeBe32(dll, 0x30400, (int) 0xB8C5F784L);
        KeyTables.loadFromDll(new ByteReader(dll, "test.dll"));
    }

    private static void writeBe32(byte[] buf, int off, int v) {
        buf[off] = (byte) ((v >>> 24) & 0xFF);
        buf[off + 1] = (byte) ((v >>> 16) & 0xFF);
        buf[off + 2] = (byte) ((v >>> 8) & 0xFF);
        buf[off + 3] = (byte) (v & 0xFF);
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        le16(o, v & 0xFFFF);
        le16(o, v >>> 16);
    }

    private static void leFloat(ByteArrayOutputStream o, float f) {
        le32(o, Float.floatToRawIntBits(f));
    }

    private static void fixedAscii(ByteArrayOutputStream o, String s, int len) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < len; i++) {
            o.write(i < b.length ? b[i] : 0);
        }
    }

    /** Wraps payload (padded to 0x10 boundary) in a section header with the given 4-char ID and 7-bit type code. */
    private static byte[] section(String id, int typeCode, byte[] payload) {
        int paddedPayload = (payload.length + 0x0F) & ~0x0F;
        int totalSize = 0x10 + paddedPayload;
        int meta = (typeCode & 0x7F) | (((totalSize / 0x10) & 0xFFFFF) << 7);
        ByteArrayOutputStream o = new ByteArrayOutputStream(totalSize);
        fixedAscii(o, id, 4);
        le32(o, meta);
        le32(o, 0);
        le32(o, 0);
        o.writeBytes(payload);
        while (o.size() < totalSize) {
            o.write(0);
        }
        return o.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    private static byte[] minimalTexturePayload(String name, int w, int h) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x91);
        fixedAscii(o, name, 0x10);
        le32(o, 0x28);
        le32(o, w);
        le32(o, h);
        le16(o, 1);
        le16(o, 32);
        for (int i = 0; i < 5; i++) {
            le32(o, 0);
        }
        le32(o, 0x20);
        return o.toByteArray();
    }

    @Test
    void parsesMinimalDirectoryTextureAndEndGraph() {
        byte[] dat = concat(
                section("DIR1", SectionType.S01_Directory.getCode(), new byte[0]),
                section("TEX1", SectionType.S20_Texture.getCode(), minimalTexturePayload("albedo", 4, 4)),
                section("END1", SectionType.S00_End.getCode(), new byte[0]));

        List<SectionHeader> headers = DatParser.parseSections(new ByteReader(dat, "mini.dat"));
        assertThat(headers).extracting(SectionHeader::getSectionType)
                .containsExactly(SectionType.S01_Directory, SectionType.S20_Texture, SectionType.S00_End);

        DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(dat, "mini.dat"));
        assertThat(graph.rootDirectory().children()).hasSize(1);
        DirectoryResource dir = (DirectoryResource) graph.rootDirectory().children().get(0);
        assertThat(dir.sectionId()).isEqualTo("DIR1");
        assertThat(dir.children()).hasSize(2);
        assertThat(dir.children().get(0)).isInstanceOf(TextureResource.class);
        assertThat(dir.children().get(1)).isInstanceOf(EndSectionResource.class);
    }

    @Test
    void unknownSectionTypesAreSkippedWithoutThrowingNoSuchElementException() {
        // 0x7F and 0x02 are not in SectionType enum
        byte[] dat = concat(
                section("DIR0", SectionType.S01_Directory.getCode(), new byte[0]),
                section("UNK1", 0x7F, new byte[0x20]),
                section("UNK2", 0x02, new byte[0x10]),
                section("TEX2", SectionType.S20_Texture.getCode(), minimalTexturePayload("valid", 2, 2)),
                section("END0", SectionType.S00_End.getCode(), new byte[0]));

        List<SectionHeader> headers = DatParser.parseSections(new ByteReader(dat, "unknown.dat"));
        assertThat(headers).extracting(SectionHeader::getSectionId)
                .containsExactly("DIR0", "TEX2", "END0");

        DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(dat, "unknown.dat"));
        DirectoryResource dir = (DirectoryResource) graph.rootDirectory().children().get(0);
        assertThat(dir.children()).extracting(ResourceNode::sectionId)
                .containsExactly("TEX2", "END0");
    }

    @Test
    void weightedMeshValidAndOffByOneIndexBoundsCheck() {
        byte[] valid = buildWeightedMeshSection(0, 0);
        DatResourceGraph okGraph = DatParser.parseResourceGraph(new ByteReader(valid, "wm.dat"));
        assertThat(okGraph.rootDirectory().children()).hasSize(1);
        assertThat(okGraph.rootDirectory().children().get(0)).isInstanceOf(WeightedMeshResource.class);

        // positionCount == 1, so index == 1 must be rejected by the fixed >= check
        byte[] badPosIdx = buildWeightedMeshSection(1, 0);
        DatResourceGraph badPosGraph = DatParser.parseResourceGraph(new ByteReader(badPosIdx, "wm-bad-pos.dat"));
        assertThat(badPosGraph.rootDirectory().children()).isEmpty();

        // normalCount == 1, so normalIndex == 1 must also be rejected
        byte[] badNormIdx = buildWeightedMeshSection(0, 1);
        DatResourceGraph badNormGraph = DatParser.parseResourceGraph(new ByteReader(badNormIdx, "wm-bad-norm.dat"));
        assertThat(badNormGraph.rootDirectory().children()).isEmpty();
    }

    private static byte[] buildWeightedMeshSection(int posIdxToWrite, int normIdxToWrite) {
        // header is 0x20 bytes; 1 weighted section with 1 position (12B) + 1 normal (4B) = 16B -> offset 0x30
        // 1 primitive -> 3 vertices: colors 12B (0x30..0x3C), uvs 24B (0x3C..0x54), indices 12B (0x54..0x60)
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        le16(o, 1); // unk1 == 1
        o.write(1); // meshConfig: 1 section
        o.write(0); // offsetExtension
        le16(o, 1); // positionCount = 1
        le16(o, 1); // normalCount = 1
        le16(o, 0x54); // indexOffset
        le16(o, 1); // primitiveCount = 1 -> vertexCount = 3
        le16(o, 0x30); // colorOffset
        le16(o, 0x3C); // uvOffset
        fixedAscii(o, "wm_tex", 0x10);
        // position (12B) + packed normal (4B)
        leFloat(o, 1f);
        leFloat(o, 2f);
        leFloat(o, 3f);
        le32(o, 0);
        // 3 colors (12B)
        for (int i = 0; i < 3; i++) {
            le32(o, 0x80808080);
        }
        // 3 uvs (24B)
        for (int i = 0; i < 6; i++) {
            leFloat(o, 0.5f);
        }
        // 3 position indices + 3 normal indices
        for (int i = 0; i < 3; i++) {
            le16(o, posIdxToWrite);
        }
        for (int i = 0; i < 3; i++) {
            le16(o, normIdxToWrite);
        }
        return section("WM01", SectionType.S25_WeightedMesh.getCode(), o.toByteArray());
    }

    @Test
    void skeletonMeshZeroTriangleCountInTriStripIsHandledGracefully() {
        // Minimal S2A_SkeletonMesh with 1 joint, 1 single-joint vertex, and an instruction stream containing:
        // 0x5453 (textured tri-strip) with triangleCount=0, 0x4353 (untextured tri-strip) with triangleCount=0, 0xFFFF
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        // 0x2A-byte header (word offsets are multiplied by 2 relative to dataStart)
        o.write(0);
        o.write(0);
        o.write(0x80); // flags3: useJointArray=true, clothEffect=false (hasNormals=true)
        o.write(0);
        o.write(0);
        o.write(0);
        le32(o, 0x4C / 2); // instructionOffset = 0x4C
        o.write(0);
        o.write(0);
        le32(o, 0x2A / 2); // jointArrayOffset = 0x2A
        le16(o, 1); // jointCount = 1
        le32(o, 0x2C / 2); // vertexCountsOffset = 0x2C
        le16(o, 2); // vertexCountsEntryCount = 2
        le32(o, 0x30 / 2); // vertexJointMappingOffset = 0x30
        le16(o, 1);
        le32(o, 0x34 / 2); // vertexDataOffset = 0x34
        le16(o, 24);
        le32(o, 0x56 / 2); // endOffset
        le16(o, 0);
        // 0x2A: jointArray (1 * 2B)
        le16(o, 0);
        // 0x2C: vertexCounts (single=1, double=0)
        le16(o, 1);
        le16(o, 0);
        // 0x30: joint mapping for 1 single-joint vertex (4B) -> jointRef index 0
        le16(o, 0);
        le16(o, 0);
        // 0x34..0x4C: 24B position + normal
        for (int i = 0; i < 6; i++) {
            leFloat(o, 0f);
        }
        // 0x4C: instructions: 0x5453 count=0, 0x4353 count=0, 0xFFFF
        le16(o, 0x5453);
        le16(o, 0);
        le16(o, 0x4353);
        le16(o, 0);
        le16(o, 0xFFFF);
        ByteArrayOutputStream full = o;

        byte[] dat = section("SKM0", SectionType.S2A_SkeletonMesh.getCode(), full.toByteArray());
        DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(dat, "skm.dat"));
        assertThat(graph.rootDirectory().children()).hasSize(1);
        SkeletonMeshResource skm = (SkeletonMeshResource) graph.rootDirectory().children().get(0);
        assertThat(skm.meshes()).isEmpty();
    }

    @Test
    void skeletonSectionParsesValidAndRejectsOutOfBoundsJointReferenceCount() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        le16(o, 0);
        o.write(1); // 1 joint
        o.write(0);
        // 1 joint = 30 bytes
        o.write(0); // parent == self -> -1
        o.write(0);
        for (int i = 0; i < 7; i++) {
            leFloat(o, 0f);
        }
        // referenceCount = 0, referenceHeader = 0
        le16(o, 0);
        le16(o, 0);
        // terminator float 0xCDCDCDCD
        le32(o, 0xCDCDCDCD);

        byte[] valid = section("SKL0", SectionType.S29_Skeleton.getCode(), o.toByteArray());
        DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(valid, "skl.dat"));
        assertThat(graph.rootDirectory().children()).hasSize(1);
        SkeletonResource skl = (SkeletonResource) graph.rootDirectory().children().get(0);
        assertThat(skl.joints()).hasSize(1);

        // Now set referenceCount to 50000 on a tiny section -> must not allocate or crash
        byte[] payload = o.toByteArray();
        payload[34] = (byte) 0x50;
        payload[35] = (byte) 0xC3; // 50000
        byte[] malformed = section("SKL1", SectionType.S29_Skeleton.getCode(), payload);
        DatResourceGraph badGraph = DatParser.parseResourceGraph(new ByteReader(malformed, "skl-bad.dat"));
        assertThat(badGraph.rootDirectory().children()).isEmpty();
    }

    @Test
    void zoneSpacePartitionTreeCyclicOrDeepOffsetsAreCappedWithoutStackOverflow() {
        // Build an S1C_ZoneDef where the space partition tree root at relative offset 0x28 points its
        // first child back to itself (0x28), creating an infinite cycle if depth is not capped.
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        // 0x00..0x20: ZoneDef header (mode <= 0x1A so no encryption needed)
        le32(o, 0); // decryptInfo (mode 0)
        le32(o, 0); // nodeCount = 0
        le32(o, 0); // collisionMeshOffset = 0
        o.write(1);
        o.write(1);
        o.write(4);
        o.write(4);
        le32(o, 0x28); // spacePartitioningTreeOffset
        le32(o, 0x20); // cullingTablesOffset
        le32(o, 0xA0); // pointLightOffset
        le32(o, 0);
        // 0x20..0x28: empty culling tables (count=0, zero=0)
        le32(o, 0);
        le32(o, 0);
        // 0x28..0xA0: space partition node (96B bbox + 24B header = 120B = 0x78B -> ends at 0xA0)
        for (int i = 0; i < 24; i++) {
            leFloat(o, 0f);
        }
        le32(o, 0); // idxRef
        le32(o, 0); // indexCount
        le32(o, 0x28); // child[0] points back to self!
        le32(o, 0);
        le32(o, 0);
        le32(o, 0);
        le32(o, 0);
        le32(o, 0);
        // 0xA0: point light terminator (4 zero bytes)
        le32(o, 0);

        byte[] cyclic = section("ZDEF", SectionType.S1C_ZoneDef.getCode(), o.toByteArray());
        assertThatCode(() -> {
            DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(cyclic, "cyclic-zone.dat"));
            // Malformed cyclic section is skipped cleanly
            assertThat(graph.rootDirectory().children()).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void singlePassZoneDecryptionDoesNotMutateInputAndIsDeterministic() {
        // Valid S1C_ZoneDef (no cycle: childOffsets all 0) + valid hit-model S2E_ZoneMesh (meshCount0 == 0)
        ByteArrayOutputStream zdef = new ByteArrayOutputStream();
        le32(zdef, 0); // mode 0
        le32(zdef, 0); // 0 objects
        le32(zdef, 0); // no collision
        zdef.write(1);
        zdef.write(1);
        zdef.write(4);
        zdef.write(4);
        le32(zdef, 0x28); // tree
        le32(zdef, 0x20); // culling
        le32(zdef, 0xA0); // lights
        le32(zdef, 0);
        le32(zdef, 0);
        le32(zdef, 0);
        for (int i = 0; i < 24; i++) {
            leFloat(zdef, 1f);
        }
        for (int i = 0; i < 8; i++) {
            le32(zdef, 0);
        }
        le32(zdef, 0); // lights terminator

        // S2E_ZoneMesh: 0x20 header + 0x20 hit-model (meshCount0=0 + 6 floats + dataOffset=0x20) = 0x40 payload
        ByteArrayOutputStream zmesh = new ByteArrayOutputStream();
        le32(zmesh, 0); // metadata: mode 0 (no decrypt)
        le32(zmesh, 0); // keyConfigData
        fixedAscii(zmesh, "author", 8);
        fixedAscii(zmesh, "mesh0", 16);
        le32(zmesh, 0); // meshCount0 == 0
        for (int i = 0; i < 6; i++) {
            leFloat(zmesh, 0f);
        }
        le32(zmesh, 0x20);

        byte[] dat = concat(
                section("ZDF0", SectionType.S1C_ZoneDef.getCode(), zdef.toByteArray()),
                section("ZMS0", SectionType.S2E_ZoneMesh.getCode(), zmesh.toByteArray()));
        byte[] snapshot = dat.clone();

        ByteReader reader = new ByteReader(dat, "zone.dat");
        DatResourceGraph g1 = DatParser.parseResourceGraph(reader);
        DatResourceGraph g2 = DatParser.parseResourceGraph(reader);

        // Input array and reader position are untouched
        assertThat(dat).isEqualTo(snapshot);
        assertThat(reader.getPosition()).isZero();
        assertThat(g1.rootDirectory().children()).hasSize(2);
        assertThat(g1.rootDirectory().children().get(0)).isInstanceOf(ZoneDefResource.class);
        assertThat(g1.rootDirectory().children().get(1)).isInstanceOf(ZoneMeshResource.class);
        assertThat(g2.rootDirectory().children()).isEqualTo(g1.rootDirectory().children());
    }

    @Test
    void truncatedGarbageAndUntrustedHugeCountsNeverThrowOrOom() {
        assertThat(DatParser.parseSections(null)).isEmpty();
        assertThat(DatParser.parseResourceGraph(null).rootDirectory().children()).isEmpty();
        assertThat(DatParser.parseSections(new ByteReader(new byte[5], "tiny"))).isEmpty();
        assertThat(DatParser.parseResourceGraph(new ByteReader(new byte[5], "tiny")).rootDirectory().children()).isEmpty();

        // S1C_ZoneDef advertising 0x00FFFFFF (16,777,215) zone objects in a 0x40-byte section
        ByteArrayOutputStream hugeZone = new ByteArrayOutputStream();
        le32(hugeZone, 0);
        le32(hugeZone, 0x00FFFFFF); // nodeCount = 16M
        while (hugeZone.size() < 0x30) {
            le32(hugeZone, 0);
        }
        byte[] hugeZoneDat = section("HUGE", SectionType.S1C_ZoneDef.getCode(), hugeZone.toByteArray());
        assertThat(DatParser.parseResourceGraph(new ByteReader(hugeZoneDat, "huge.dat")).rootDirectory().children())
                .isEmpty();

        // S2B_SkeletonAnimation advertising 60000 joints in a 0x20-byte section
        ByteArrayOutputStream hugeAnim = new ByteArrayOutputStream();
        le16(hugeAnim, 0);
        le16(hugeAnim, 60000);
        le16(hugeAnim, 60000);
        leFloat(hugeAnim, 1f);
        byte[] hugeAnimDat = section("ANIM", SectionType.S2B_SkeletonAnimation.getCode(), hugeAnim.toByteArray());
        assertThat(DatParser.parseResourceGraph(new ByteReader(hugeAnimDat, "anim.dat")).rootDirectory().children())
                .isEmpty();

        // Follow a malformed section with a valid section to prove recovery continues to the next section
        byte[] combined = concat(
                hugeZoneDat,
                section("TEX9", SectionType.S20_Texture.getCode(), minimalTexturePayload("ok", 1, 1)));
        DatResourceGraph recovered = DatParser.parseResourceGraph(new ByteReader(combined, "recovered.dat"));
        assertThat(recovered.rootDirectory().children()).hasSize(1);
        assertThat(recovered.rootDirectory().children().get(0).sectionId()).isEqualTo("TEX9");
    }
}
