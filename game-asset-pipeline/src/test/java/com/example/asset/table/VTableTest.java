package com.example.asset.table;

import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VTableTest {

    @Test
    @DisplayName("Should return correct expansion version for valid fileIds")
    void testGetVersion() {
        byte[] data = new byte[] { 0, 1, 2, 3 };
        VTable vtable = new VTable(data);

        assertThat(vtable.size()).isEqualTo(4);
        assertThat(vtable.getVersion(0)).isEqualTo(0);
        assertThat(vtable.getVersion(1)).isEqualTo(1);
        assertThat(vtable.getVersion(2)).isEqualTo(2);
        assertThat(vtable.getVersion(3)).isEqualTo(3);
    }

    @Test
    @DisplayName("Should return 0 for out-of-bounds fileIds")
    void testOutOfBounds() {
        VTable vtable = new VTable(new byte[] { 1 });
        assertThat(vtable.getVersion(-1)).isEqualTo(0);
        assertThat(vtable.getVersion(10)).isEqualTo(0);
    }

    @Test
    @DisplayName("Should load from DatByteReader")
    void testLoad() {
        DatByteReader reader = new DatByteReader(new byte[] { 1, 2, 3 });
        VTable vtable = VTable.load(reader);
        assertThat(vtable.size()).isEqualTo(3);
        assertThat(vtable.getVersion(1)).isEqualTo(2);
    }
}
