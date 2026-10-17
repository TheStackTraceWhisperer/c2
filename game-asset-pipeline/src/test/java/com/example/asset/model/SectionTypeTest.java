package com.example.asset.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SectionTypeTest {

    @Test
    @DisplayName("fromCode should map known section codes to their enum constants")
    void testFromCode() {
        assertThat(SectionType.fromCode(0x00)).isEqualTo(SectionType.S00_END);
        assertThat(SectionType.fromCode(0x01)).isEqualTo(SectionType.S01_DIRECTORY);
        assertThat(SectionType.fromCode(0x1C)).isEqualTo(SectionType.S1C_ZONE_DEF);
        assertThat(SectionType.fromCode(0x20)).isEqualTo(SectionType.S20_TEXTURE);
        assertThat(SectionType.fromCode(0x2E)).isEqualTo(SectionType.S2E_ZONE_MESH);
        assertThat(SectionType.fromCode(0x29)).isEqualTo(SectionType.S29_SKELETON);
        assertThat(SectionType.fromCode(0x2A)).isEqualTo(SectionType.S2A_SKELETON_MESH);
        assertThat(SectionType.fromCode(0x2B)).isEqualTo(SectionType.S2B_SKELETON_ANIMATION);
        assertThat(SectionType.fromCode(0x36)).isEqualTo(SectionType.S36_ZONE_INTERACTIONS);
    }

    @Test
    @DisplayName("fromCode should return UNHANDLED for unknown codes")
    void testUnknownCode() {
        assertThat(SectionType.fromCode(0x7F)).isEqualTo(SectionType.UNHANDLED);
        assertThat(SectionType.fromCode(9999)).isEqualTo(SectionType.UNHANDLED);
    }
}
