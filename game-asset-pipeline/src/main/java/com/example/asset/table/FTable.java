package com.example.asset.table;

import com.example.asset.reader.DatByteReader;

/**
 * Parses and indexes FTABLE.DAT, mapping each fileId (16-bit word)
 * to a folder number (bits 7..15) and a file number (bits 0..6).
 */
public final class FTable {

    private final short[] entries;

    public FTable(short[] entries) {
        this.entries = entries;
    }

    public static FTable load(DatByteReader reader) {
        int count = reader.remaining() / 2;
        short[] entries = new short[count];
        for (int i = 0; i < count; i++) {
            entries[i] = reader.nextI16();
        }
        return new FTable(entries);
    }

    public FTableEntry getFileEntry(int fileId) {
        if (fileId < 0 || fileId >= entries.length) {
            return null;
        }
        int word = entries[fileId] & 0xFFFF;
        int fileNumber = word & 0x7F;
        int folderNumber = word >>> 7;
        return new FTableEntry(folderNumber, fileNumber);
    }

    public int size() {
        return entries.length;
    }
}
