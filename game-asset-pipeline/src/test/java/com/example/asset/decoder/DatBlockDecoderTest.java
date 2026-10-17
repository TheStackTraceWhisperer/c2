package com.example.asset.decoder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatBlockDecoderTest {

    @Test
    @DisplayName("rotateRight8 should rotate byte bits correctly")
    void testRotateRight8() {
        byte val = (byte) 0b10000000;
        assertThat(DatBlockDecoder.rotateRight8(val, 1)).isEqualTo((byte) 0b01000000);

        byte lsb = (byte) 0b00000001;
        assertThat(DatBlockDecoder.rotateRight8(lsb, 1)).isEqualTo((byte) 0x80);

        assertThat(DatBlockDecoder.rotateRight8((byte) 0x5A, 0)).isEqualTo((byte) 0x5A);
        assertThat(DatBlockDecoder.rotateRight8((byte) 0x5A, 8)).isEqualTo((byte) 0x5A);
    }

    @Test
    @DisplayName("calculateRotateAmount should map factors to expected rotation amounts")
    void testCalculateRotateAmount() {
        // factor = (0 - 0 + 0) % 5 = 0 -> 7
        assertThat(DatBlockDecoder.calculateRotateAmount((byte) 0, (byte) 0, (byte) 0)).isEqualTo(7);

        // factor = (1 - 0 + 0) % 5 = 1 -> 1
        assertThat(DatBlockDecoder.calculateRotateAmount((byte) 1, (byte) 0, (byte) 0)).isEqualTo(1);

        // factor = (2 - 0 + 0) % 5 = 2 -> 6
        assertThat(DatBlockDecoder.calculateRotateAmount((byte) 3, (byte) 0, (byte) 0)).isEqualTo(6);

        // factor = (3 - 0 + 0) % 5 = 3 -> 2
        assertThat(DatBlockDecoder.calculateRotateAmount((byte) 7, (byte) 0, (byte) 0)).isEqualTo(2);

        // factor = (4 - 0 + 0) % 5 = 4 -> 5
        assertThat(DatBlockDecoder.calculateRotateAmount((byte) 15, (byte) 0, (byte) 0)).isEqualTo(5);
    }

    @Test
    @DisplayName("decodeBlock should decode rotated bytes and leave key offsets untouched")
    void testDecodeBlock() {
        byte[] block = new byte[16];
        // Set key bytes at 0x02, 0x0B, 0x0C so factor = 1 -> rotateAmount = 1
        block[0x02] = 1; // 1 bit
        block[0x0B] = 0; // 0 bits
        block[0x0C] = 0; // 0 bits

        // Place a test byte at offset 0: rotateRight8(0b00000010, 1) should be 0b00000001
        block[0] = 0b00000010;
        // Place a test byte at offset 1: rotateRight8(0x80, 1) should be 0x40
        block[1] = (byte) 0x80;

        DatBlockDecoder.decodeBlock(block, 0, block.length);

        // Key bytes must remain untouched
        assertThat(block[0x02]).isEqualTo((byte) 1);
        assertThat(block[0x0B]).isEqualTo((byte) 0);
        assertThat(block[0x0C]).isEqualTo((byte) 0);

        // Decoded bytes
        assertThat(block[0]).isEqualTo((byte) 0b00000001);
        assertThat(block[1]).isEqualTo((byte) 0x40);
    }

    @Test
    @DisplayName("decodeBlock with block size smaller than minimum header should no-op")
    void testDecodeBlockSmallSize() {
        byte[] small = new byte[] { 1, 2, 3 };
        DatBlockDecoder.decodeBlock(small, 0, small.length);
        assertThat(small).containsExactly((byte) 1, (byte) 2, (byte) 3);
    }
}
