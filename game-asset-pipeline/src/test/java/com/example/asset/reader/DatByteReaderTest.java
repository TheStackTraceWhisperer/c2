package com.example.asset.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class DatByteReaderTest {

    @Test
    @DisplayName("Should read unsigned and signed 8-bit integers")
    void testRead8() {
        byte[] data = new byte[] { (byte) 0xFF, (byte) 0x7F, 0x00 };
        DatByteReader reader = new DatByteReader(data);

        assertThat(reader.nextU8()).isEqualTo(255);
        assertThat(reader.nextI8()).isEqualTo((byte) 0x7F);
        assertThat(reader.nextU8()).isEqualTo(0);
        assertThat(reader.hasMore()).isFalse();
    }

    @Test
    @DisplayName("Should read little-endian 16-bit and 32-bit integers")
    void testRead16And32() {
        byte[] data = new byte[] {
                0x01, 0x02,             // 16-bit: 0x0201 = 513
                0x04, 0x03, 0x02, 0x01  // 32-bit: 0x01020304 = 16909060
        };
        DatByteReader reader = new DatByteReader(data);

        assertThat(reader.nextU16()).isEqualTo(513);
        assertThat(reader.nextI32()).isEqualTo(0x01020304);
    }

    @Test
    @DisplayName("Should read IEEE 754 little-endian floats")
    void testReadFloat() {
        ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putFloat(12.345f);
        DatByteReader reader = new DatByteReader(buf.array());

        assertThat(reader.nextFloat()).isEqualTo(12.345f);
    }

    @Test
    @DisplayName("Should read fixed and zero-terminated strings")
    void testReadStrings() {
        byte[] data = "zone\0extra_bytes\0".getBytes();
        DatByteReader reader = new DatByteReader(data);

        assertThat(reader.nextString(4)).isEqualTo("zone");
        reader.position(0);
        assertThat(reader.nextZeroTerminatedString()).isEqualTo("zone");
        assertThat(reader.nextZeroTerminatedString()).isEqualTo("extra_bytes");
    }

    @Test
    @DisplayName("Should align position to boundary")
    void testAlignment() {
        byte[] data = new byte[32];
        DatByteReader reader = new DatByteReader(data);

        reader.nextU8(); // pos = 1
        reader.align(4);
        assertThat(reader.position()).isEqualTo(4);

        reader.align(4); // already aligned
        assertThat(reader.position()).isEqualTo(4);

        reader.nextU8(); // pos = 5
        reader.align(16);
        assertThat(reader.position()).isEqualTo(16);
    }

    @Test
    @DisplayName("Should slice sub-readers independently")
    void testSlice() {
        byte[] data = new byte[] { 0, 1, 2, 3, 4, 5, 6, 7 };
        DatByteReader reader = new DatByteReader(data);

        DatByteReader slice = reader.slice(2, 4);
        assertThat(slice.remaining()).isEqualTo(4);
        assertThat(slice.nextU8()).isEqualTo(2);
        assertThat(slice.nextU8()).isEqualTo(3);
        assertThat(reader.position()).isEqualTo(0); // parent untouched
    }
}
