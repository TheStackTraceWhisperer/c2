package com.ffxi.io;

public class VTable {
    private final ByteReader byteReader;

    public VTable(ByteReader byteReader) {
        this.byteReader = byteReader;
    }

    public ByteReader getByteReader() {
        return byteReader;
    }

    public Integer getVersion(int fileId) {
        byte[] bytes = byteReader.getBytes();
        if (fileId < 0 || fileId >= bytes.length) {
            return null;
        }
        return bytes[fileId] & 0xFF;
    }
}
