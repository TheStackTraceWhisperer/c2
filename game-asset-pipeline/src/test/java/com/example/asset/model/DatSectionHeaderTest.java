package com.example.asset.model;

import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class DatSectionHeaderTest {

    @Test
    @DisplayName("Should parse 16-byte section header correctly")
    void testReadSectionHeader() {
        ByteBuffer buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("zdef".getBytes()); // 4 bytes

        // sectionMeta: type = 0x1C (ZoneDef), size = 8 * 16 = 128 bytes
        int sectionMeta = (8 << 7) | 0x1C;
        buf.putInt(sectionMeta);

        // 8 bytes padding to complete 16-byte header
        buf.putLong(0L);

        DatByteReader reader = new DatByteReader(buf.array());
        DatSectionHeader header = DatSectionHeader.read(reader);

        assertThat(header.sectionId()).isEqualTo("zdef");
        assertThat(header.sectionType()).isEqualTo(SectionType.S1C_ZONE_DEF);
        assertThat(header.sectionStartPosition()).isEqualTo(0);
        assertThat(header.dataStartPosition()).isEqualTo(16);
        assertThat(header.sectionSize()).isEqualTo(128);
        assertThat(header.sectionEndPosition()).isEqualTo(128);
        assertThat(reader.position()).isEqualTo(16);
    }
}
