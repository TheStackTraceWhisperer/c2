package com.ffxi.crypto;

import com.ffxi.io.ByteReader;

public class KeyTables {
    private static byte[] keyTable1;
    private static byte[] keyTable2;

    public static byte[] getKeyTable1() {
        if (keyTable1 == null) throw new IllegalStateException("KeyTables not loaded — call loadFromDll() first");
        return keyTable1;
    }

    public static byte[] getKeyTable2() {
        if (keyTable2 == null) throw new IllegalStateException("KeyTables not loaded — call loadFromDll() first");
        return keyTable2;
    }

    public static void loadFromDll(ByteReader dllReader) {
        if (dllReader == null) throw new IllegalArgumentException("dllReader must not be null");

        int t1Offset = findOffset32BE(dllReader, 0xE2E506A9);
        int t2Offset = findOffset32BE(dllReader, (int) 0xB8C5F784L);

        if (t1Offset < 0) throw new IllegalStateException("Failed to find offset for zoneDecryptTable1 (hint 0xE2E506A9)");
        if (t2Offset < 0) throw new IllegalStateException("Failed to find offset for zoneDecryptTable2 (hint 0xB8C5F784)");

        keyTable1 = dllReader.subBuffer(t1Offset, 256);
        keyTable2 = dllReader.subBuffer(t2Offset, 256);
    }

    private static int findOffset32BE(ByteReader reader, int hint) {
        reader.setPosition(0x30000);
        for (int i = 0; i < 0xC000 && reader.remaining() >= 4; i++) {
            if (reader.next32BE() == hint) {
                return reader.getPosition() - 4;
            }
        }
        return -1;
    }
}
