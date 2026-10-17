package com.example.asset.table;

import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class FTableTest {

    @Test
    @DisplayName("Should extract folder and file number from 16-bit word")
    void testGetFileEntry() {
        // folder = 5, file = 12 -> word = (5 << 7) | 12 = 652
        short word = (short) ((5 << 7) | 12);
        FTable ftable = new FTable(new short[] { word });

        FTableEntry entry = ftable.getFileEntry(0);
        assertThat(entry).isNotNull();
        assertThat(entry.folderNumber()).isEqualTo(5);
        assertThat(entry.fileNumber()).isEqualTo(12);
        assertThat(entry.toFileName()).isEqualTo("12.DAT");
    }

    @Test
    @DisplayName("Should load from DatByteReader in little-endian order")
    void testLoad() {
        ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        // entry 0: folder 1, file 2 -> (1 << 7) | 2 = 130
        buf.putShort((short) ((1 << 7) | 2));
        // entry 1: folder 0, file 120 -> 120
        buf.putShort((short) 120);

        DatByteReader reader = new DatByteReader(buf.array());
        FTable ftable = FTable.load(reader);

        assertThat(ftable.size()).isEqualTo(2);

        FTableEntry entry0 = ftable.getFileEntry(0);
        assertThat(entry0.folderNumber()).isEqualTo(1);
        assertThat(entry0.fileNumber()).isEqualTo(2);

        FTableEntry entry1 = ftable.getFileEntry(1);
        assertThat(entry1.folderNumber()).isEqualTo(0);
        assertThat(entry1.fileNumber()).isEqualTo(120);
    }

    @Test
    @DisplayName("Should return null for out of bounds fileId")
    void testOutOfBounds() {
        FTable ftable = new FTable(new short[] { 1 });
        assertThat(ftable.getFileEntry(-1)).isNull();
        assertThat(ftable.getFileEntry(5)).isNull();
    }
}
