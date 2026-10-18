package com.example.asset.decoder;

/**
 * Decodes FFXI proprietary encrypted DAT blocks using bit-rotation.
 * Key bytes at offsets 0x02, 0x0B, and 0x0C determine the rotation amount
 * and remain unrotated.
 */
public final class DatBlockDecoder {

    private DatBlockDecoder() {
    }

    /**
     * Calculates the right-rotation bit count for a block based on the bit counts
     * of bytes at offsets 0x02, 0x0B, and 0x0C.
     */
    public static int calculateRotateAmount(byte byte02, byte byte0B, byte byte0C) {
        int factor2 = Integer.bitCount(byte02 & 0xFF);
        int factorB = Integer.bitCount(byte0B & 0xFF);
        int factorC = Integer.bitCount(byte0C & 0xFF);

        int factor = (factor2 - factorB + factorC) % 5;
        return switch (factor) {
            case 0 -> 7;
            case 1 -> 1;
            case 2 -> 6;
            case 3 -> 2;
            case 4 -> 5;
            default -> 0;
        };
    }

    /**
     * Rotates an 8-bit unsigned byte value to the right by the given bit count.
     */
    public static byte rotateRight8(byte value, int bits) {
        int shift = bits & 7;
        if (shift == 0) {
            return value;
        }
        int unsigned = value & 0xFF;
        int rotated = (unsigned >>> shift) | (unsigned << (8 - shift));
        return (byte) (rotated & 0xFF);
    }

    /**
     * Decodes an entire byte array in-place.
     */
    public static void decodeBlock(byte[] data) {
        decodeBlock(data, 0, data.length);
    }

    /**
     * Decodes a block of encrypted bytes in-place.
     *
     * @param data      byte array containing the block
     * @param offset    starting offset of the block
     * @param blockSize size of the block in bytes
     */
    public static void decodeBlock(byte[] data, int offset, int blockSize) {
        if (blockSize < 0x0D) {
            return;
        }
        int rotateAmount = calculateRotateAmount(
                data[offset + 0x02],
                data[offset + 0x0B],
                data[offset + 0x0C]
        );

        for (int i = 0; i < blockSize; i++) {
            if (i == 0x02 || i == 0x0B || i == 0x0C) {
                continue;
            }
            int index = offset + i;
            data[index] = rotateRight8(data[index], rotateAmount);
        }
    }
}
