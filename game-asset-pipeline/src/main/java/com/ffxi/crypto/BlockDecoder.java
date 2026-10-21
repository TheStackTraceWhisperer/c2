package com.ffxi.crypto;

import com.ffxi.io.ByteReader;

public class BlockDecoder {

    public static void decodeBlock(ByteReader byteReader, int blockSize) {
        if (byteReader == null || byteReader.getBytes() == null) return;
        byte[] bytes = byteReader.getBytes();
        int blockStart = byteReader.getPosition();

        if (blockStart + 0x0C >= bytes.length) return;

        int factor2 = Integer.bitCount(bytes[blockStart + 0x02] & 0xFF);
        int factorB = Integer.bitCount(bytes[blockStart + 0x0B] & 0xFF);
        int factorC = Integer.bitCount(bytes[blockStart + 0x0C] & 0xFF);

        // XIM: val factor = (factor2 - factorB + factorC) % 5
        // Kotlin % on Int can return negative results — the when's else branch gives rotateAmount=0
        int factor = (factor2 - factorB + factorC) % 5;

        int rotateAmount;
        switch (factor) {
            case 0: rotateAmount = 7; break;
            case 1: rotateAmount = 1; break;
            case 2: rotateAmount = 6; break;
            case 3: rotateAmount = 2; break;
            case 4: rotateAmount = 5; break;
            default: rotateAmount = 0; break; // negative result from %, same as XIM's else->0
        }

        for (int i = 0; i < blockSize; i++) {
            if (i == 0x02 || i == 0x0B || i == 0x0C) {
                byteReader.setPosition(byteReader.getPosition() + 1);
            } else {
                byteReader.rotateNext8(rotateAmount);
            }
        }
    }
}
