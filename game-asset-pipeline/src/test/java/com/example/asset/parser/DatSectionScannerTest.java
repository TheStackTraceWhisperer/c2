package com.example.asset.parser;

import com.example.asset.model.DatSectionHeader;
import com.example.asset.model.SectionType;
import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatSectionScannerTest {

    @Test
    @DisplayName("scanSections should find all sequential section headers and extract payloads")
    void testScanSectionsAndExtractPayload() {
        ByteBuffer buf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);

        // Section 1: "tx01", S20_TEXTURE (code 0x20), size = 32 bytes (2 paragraphs)
        buf.put("tx01".getBytes());
        buf.putInt((2 << 7) | 0x20);
        buf.putLong(0L); // complete 16-byte header
        buf.put("16_bytes_payload".getBytes()); // 16 bytes payload

        // Section 2: "end0", S00_END (code 0x00), size = 16 bytes (1 paragraph)
        buf.put("end0".getBytes());
        buf.putInt((1 << 7) | 0x00);
        buf.putLong(0L); // complete 16-byte header

        DatByteReader reader = new DatByteReader(buf.array());
        List<DatSectionHeader> sections = DatSectionScanner.scanSections(reader);

        assertThat(sections).hasSize(2);

        DatSectionHeader first = sections.get(0);
        assertThat(first.sectionId()).isEqualTo("tx01");
        assertThat(first.sectionType()).isEqualTo(SectionType.S20_TEXTURE);
        assertThat(first.sectionSize()).isEqualTo(32);

        DatByteReader payload = DatSectionScanner.extractPayload(reader, first);
        assertThat(payload.remaining()).isEqualTo(16);
        assertThat(payload.nextString(16)).isEqualTo("16_bytes_payload");

        DatSectionHeader second = sections.get(1);
        assertThat(second.sectionId()).isEqualTo("end0");
        assertThat(second.sectionType()).isEqualTo(SectionType.S00_END);
    }
}
