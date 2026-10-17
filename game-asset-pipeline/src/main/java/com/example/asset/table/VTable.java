package com.example.asset.table;

import com.example.asset.reader.DatByteReader;

import java.util.Arrays;

/**
 * Parses and indexes VTABLE.DAT, determining which expansion ROM contains a given fileId.
 * Version 0 means file does not exist. Version 1 maps to "ROM/", version 2 to "ROM2/", etc.
 */
public final class VTable {

    private final byte[] versions;

    public VTable(byte[] versions) {
        this.versions = Arrays.copyOf(versions, versions.length);
    }

    public static VTable load(DatByteReader reader) {
        return new VTable(reader.nextBytes(reader.remaining()));
    }

    public int getVersion(int fileId) {
        if (fileId < 0 || fileId >= versions.length) {
            return 0;
        }
        return versions[fileId] & 0xFF;
    }

    public int size() {
        return versions.length;
    }
}
