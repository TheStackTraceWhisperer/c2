package com.ffxi.crypto;

import com.ffxi.io.ByteReader;

public class KeyTables {
    private record Snapshot(byte[] table1, byte[] table2) {
    }

    private static volatile Snapshot snapshot;

    private static Snapshot require() {
        Snapshot s = snapshot;
        if (s == null) {
            throw new IllegalStateException("KeyTables not loaded — call loadFromDll() first");
        }
        return s;
    }

    /** Returns a defensive copy of key table 1. */
    public static byte[] getKeyTable1() {
        return require().table1().clone();
    }

    /** Returns a defensive copy of key table 2. */
    public static byte[] getKeyTable2() {
        return require().table2().clone();
    }

    /** Returns the unsigned byte at {@code index} of key table 1 without copying. */
    public static int keyTable1At(int index) {
        byte[] t = require().table1();
        if (index < 0 || index >= t.length) {
            throw new IndexOutOfBoundsException("keyTable1 index " + index);
        }
        return t[index] & 0xFF;
    }

    /** Returns the unsigned byte at {@code index} of key table 2 without copying. */
    public static int keyTable2At(int index) {
        byte[] t = require().table2();
        if (index < 0 || index >= t.length) {
            throw new IndexOutOfBoundsException("keyTable2 index " + index);
        }
        return t[index] & 0xFF;
    }

    public static void loadFromDll(ByteReader dllReader) {
        if (dllReader == null) throw new IllegalArgumentException("dllReader must not be null");

        // Search on a private reader so concurrent loads sharing a reader's cursor do not interfere.
        ByteReader local = new ByteReader(dllReader.getBytes(), dllReader.getResourceName());
        int t1Offset = findOffset32BE(local, 0xE2E506A9);
        int t2Offset = findOffset32BE(local, (int) 0xB8C5F784L);

        if (t1Offset < 0) throw new IllegalStateException("Failed to find offset for zoneDecryptTable1 (hint 0xE2E506A9)");
        if (t2Offset < 0) throw new IllegalStateException("Failed to find offset for zoneDecryptTable2 (hint 0xB8C5F784)");

        byte[] t1 = local.subBuffer(t1Offset, 256);
        byte[] t2 = local.subBuffer(t2Offset, 256);
        snapshot = new Snapshot(t1, t2);
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
