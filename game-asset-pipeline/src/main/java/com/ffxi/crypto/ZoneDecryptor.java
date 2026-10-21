package com.ffxi.crypto;

import com.ffxi.io.ByteReader;
import com.ffxi.io.SectionBounds;

public class ZoneDecryptor {

    public static void decryptZoneObjects(SectionBounds sectionHeader, ByteReader byteReader) {
        byte[] keyTable1 = KeyTables.getKeyTable1();

        byteReader.offsetFrom(sectionHeader, 0x10);

        int metadata = byteReader.next32();
        int decodeLength = metadata & 0x00FFFFFF;
        int mode = (metadata >>> 24) & 0xFF;

        if (mode <= 0x1A) {
            return;
        }

        int keyNodeData = byteReader.next32();
        int nodeCount = keyNodeData & 0x00FFFFFF;
        int keyIndex = (keyNodeData >>> 24) & 0xFF;

        int key = keyTable1[(keyIndex ^ 0xFF) & 0xFF] & 0xFF;
        int keyCounter = 0;
        int consumed = 0;

        if (byteReader.getPosition() + decodeLength > sectionHeader.getNextSectionOffset()) {
            int extra = (byteReader.getPosition() + decodeLength) - sectionHeader.getNextSectionOffset();
            decodeLength -= Math.max(0, extra);
        }

        while (consumed < decodeLength) {
            int xorLength = ((key >>> 4) & 7) + 16;
            boolean shouldApplyMask = (key & 1) != 0;
            boolean hasRemainingLength = consumed + xorLength < decodeLength;

            if (shouldApplyMask && hasRemainingLength) {
                for (int i = 0; i < xorLength; i++) {
                    byteReader.xorNext((byte) 0xFF);
                }
            } else {
                byteReader.setPosition(byteReader.getPosition() + xorLength);
            }

            keyCounter++;
            key += keyCounter;
            consumed += xorLength;
        }

        // Deobfuscate object names
        byteReader.offsetFrom(sectionHeader, 0x30);
        int pos = byteReader.getPosition();
        for (int i = 0; i < nodeCount; i++) {
            byteReader.setPosition(pos + i * 0x64);
            for (int j = 0; j < 0x10; j++) {
                byteReader.xorNext((byte) 0x55);
            }
        }
    }

    public static void decryptZoneMesh(SectionBounds sectionHeader, ByteReader byteReader, boolean reencrypt) {
        if (reencrypt) {
            decryptZoneMeshSecondPass(sectionHeader, byteReader);
            decryptZoneMeshFirstPass(sectionHeader, byteReader);
        } else {
            decryptZoneMeshFirstPass(sectionHeader, byteReader);
            decryptZoneMeshSecondPass(sectionHeader, byteReader);
        }
        byteReader.offsetFrom(sectionHeader, 0x10);
    }

    public static void decryptZoneMesh(SectionBounds sectionHeader, ByteReader byteReader) {
        decryptZoneMesh(sectionHeader, byteReader, false);
    }

    private static void decryptZoneMeshFirstPass(SectionBounds sectionHeader, ByteReader byteReader) {
        byte[] keyTable1 = KeyTables.getKeyTable1();

        byteReader.offsetFrom(sectionHeader, 0x10);

        int metadata = byteReader.next32();
        int totalSize = metadata & 0x00FFFFFF;
        int decodeLength = totalSize - 0x8;

        int mode = (metadata >>> 24) & 0xFF;
        if (mode < 0x5) {
            return;
        }

        byteReader.next8(); // skip metadata byte
        int keyIndex = byteReader.next8();
        byteReader.next16(); // skip unused bytes

        int key = keyTable1[(keyIndex ^ 0xF0) & 0xFF] & 0xFF;
        int keyCounter = 0;

        for (int i = 0; i < decodeLength; i++) {
            int keyMod = ((key & 0xFF) << 8) | (key & 0xFF);
            keyCounter++;
            key += keyCounter;

            byte mask = (byte) (keyMod >>> (key & 0x7));
            byteReader.xorNext(mask);
            keyCounter++;
            key += keyCounter;
        }
    }

    private static void decryptZoneMeshSecondPass(SectionBounds sectionHeader, ByteReader byteReader) {
        byte[] keyTable2 = KeyTables.getKeyTable2();

        byteReader.offsetFrom(sectionHeader, 0x10);

        int metadata = byteReader.next32();
        int totalSize = metadata & 0x00FFFFFF;
        int decodeLength = totalSize - 0x8;

        byteReader.next8();

        int key1 = byteReader.next8() ^ 0xF0;
        int key2 = keyTable2[key1 & 0xFF] & 0xFF;

        int decodeCount = (decodeLength & (~0xF)) / 2;

        boolean needsSecondPass = byteReader.next16() == 0xFFFF;
        if (!needsSecondPass) {
            return;
        }

        for (int i = 0; i < decodeCount; i += 8) {
            if ((key2 & 1) == 1) {
                byteReader.swapNext8(decodeCount, 8);
            } else {
                byteReader.setPosition(byteReader.getPosition() + 8);
            }

            key1 += 9;
            key2 += key1;
        }
    }
}
