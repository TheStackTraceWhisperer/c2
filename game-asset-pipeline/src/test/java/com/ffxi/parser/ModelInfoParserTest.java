package com.ffxi.parser;

import com.example.asset.config.FfxiPathConfig;
import com.ffxi.tables.FfxiModelIndex;
import com.ffxi.tables.RaceGenderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ModelInfoParserTest {

    @Test
    @DisplayName("parseSizeInfo reads bytes 10..13 of non-moun 0x45 chunk and ignores short or empty inputs")
    void testParseSizeInfoSynthetic() {
        assertThat(ModelInfoParser.parseSizeInfo(null))
                .containsExactly(0xFF, 0xFF, 0xFF, 0xFF);
        assertThat(ModelInfoParser.parseSizeInfo(new byte[16]))
                .containsExactly(0xFF, 0xFF, 0xFF, 0xFF);

        byte[] payloadMoun = new byte[16];
        payloadMoun[10] = 70;
        payloadMoun[11] = 80;
        payloadMoun[12] = 90;
        payloadMoun[13] = 100;

        byte[] payloadInfo = new byte[16];
        payloadInfo[10] = 90;
        payloadInfo[11] = 95;
        payloadInfo[12] = 100;
        payloadInfo[13] = 106;

        byte[] dat = concat(
                section("moun", 0x45, payloadMoun),
                section("info", 0x45, payloadInfo),
                section("end ", 0x00, new byte[0])
        );

        assertThat(ModelInfoParser.parseSizeInfo(dat))
                .containsExactly(90, 95, 100, 106);
    }

    @Test
    @DisplayName("mergeSizeInfo uses first-file-wins while preferSizeInfo lets variant override base")
    void testMergeAndPreferSizeInfo() {
        int[] dest = new int[]{0xFF, 95, 0xFF, 0xFF};
        int[] fallback = new int[]{90, 100, 105, 106};
        ModelInfoParser.mergeSizeInfo(dest, fallback);
        assertThat(dest).containsExactly(90, 95, 105, 106);

        int[] preferred = new int[]{0xFF, 87, 0xFF, 110};
        ModelInfoParser.preferSizeInfo(dest, preferred);
        assertThat(dest).containsExactly(90, 87, 105, 110);
    }

    @Test
    @DisplayName("resolveScale maps valid percentages and guards UNSET, sentinel (<10), and out-of-range indices")
    void testResolveScaleEdgeCases() {
        int[] info = new int[]{82, 87, 92, 0};
        assertThat(ModelInfoParser.resolveScale(info, 0)).isCloseTo(0.82f, within(1e-5f));
        assertThat(ModelInfoParser.resolveScale(info, 1)).isCloseTo(0.87f, within(1e-5f));
        assertThat(ModelInfoParser.resolveScale(info, 2)).isCloseTo(0.92f, within(1e-5f));
        // Slot 3 for playable races carries mount-rider variant index (0/1), not a percentage scale
        assertThat(ModelInfoParser.resolveScale(info, 3)).isCloseTo(1.00f, within(1e-5f));

        assertThat(ModelInfoParser.resolveScale(new int[]{0xFF, 0xFF, 0xFF, 0xFF}, 1)).isCloseTo(1.00f, within(1e-5f));
        assertThat(ModelInfoParser.resolveScale(info, -1)).isCloseTo(1.00f, within(1e-5f));
        assertThat(ModelInfoParser.resolveScale(info, 4)).isCloseTo(1.00f, within(1e-5f));
        assertThat(ModelInfoParser.resolveScale(null, 1)).isCloseTo(1.00f, within(1e-5f));
    }

    @Test
    @DisplayName("Retail: parses canonical 0x45 size_info values across playable races and standard NPCs")
    void testRetailSizeInfoValues() throws Exception {
        Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
        assumeTrue(Files.isDirectory(ffxiRoot), "Retail FFXI installation required at " + ffxiRoot);

        FfxiModelIndex index = FfxiModelIndex.getOrLoad(ffxiRoot);

        assertThat(ModelInfoParser.parseSizeInfo(Files.readAllBytes(Path.of(index.getSkeletonPath(RaceGenderConfig.HumeM)))))
                .containsExactly(95, 100, 105, 0);
        assertThat(ModelInfoParser.parseSizeInfo(Files.readAllBytes(Path.of(index.getSkeletonPath(RaceGenderConfig.TaruM)))))
                .containsExactly(82, 87, 92, 0);
        assertThat(ModelInfoParser.parseSizeInfo(Files.readAllBytes(Path.of(index.getSkeletonPath(RaceGenderConfig.HumeChildM)))))
                .containsExactly(90, 95, 100, 106);

        assertThat(ModelInfoParser.parseSizeInfo(Files.readAllBytes(Path.of(index.getNpcModelPath(2474)))))
                .containsExactly(90, 95, 100, 106);
        assertThat(ModelInfoParser.parseSizeInfo(Files.readAllBytes(Path.of(index.getNpcModelPath(78)))))
                .containsExactly(95, 100, 105, 100);
        assertThat(ModelInfoParser.parseSizeInfo(Files.readAllBytes(Path.of(index.getNpcModelPath(64)))))
                .containsExactly(0xFF, 0xFF, 0xFF, 0xFF);
    }

    private static byte[] section(String id, int typeCode, byte[] payload) {
        int paddedPayload = (payload.length + 0x0F) & ~0x0F;
        int totalSize = 0x10 + paddedPayload;
        int meta = (typeCode & 0x7F) | (((totalSize / 0x10) & 0xFFFFF) << 7);
        ByteArrayOutputStream o = new ByteArrayOutputStream(totalSize);
        byte[] idBytes = id.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < 4; i++) {
            o.write(i < idBytes.length ? idBytes[i] : 0);
        }
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
        for (byte[] part : parts) {
            o.writeBytes(part);
        }
        return o.toByteArray();
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 24) & 0xFF);
    }
}
