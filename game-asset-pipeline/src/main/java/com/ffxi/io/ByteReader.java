package com.ffxi.io;

import java.nio.charset.StandardCharsets;
import org.joml.Vector2f;
import org.joml.Vector3f;

public class ByteReader {
    private byte[] bytes;
    private int position;
    private final String resourceName;

    public ByteReader(byte[] bytes, String resourceName) {
        this.bytes = bytes != null ? bytes : new byte[0];
        this.position = 0;
        this.resourceName = resourceName != null ? resourceName : "";
    }

    public ByteReader(byte[] bytes) {
        this(bytes, "");
    }

    public byte[] getBytes() {
        return bytes;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getResourceName() {
        return resourceName;
    }

    public boolean hasMore() {
        return position < bytes.length;
    }

    public int remaining() {
        return Math.max(0, bytes.length - position);
    }

    public int next8() {
        if (position >= bytes.length) return 0;
        int b = bytes[position] & 0xFF;
        position++;
        return b;
    }

    public int next8Signed() {
        return (byte) next8();
    }

    public int next16() {
        return next8() | (next8() << 8);
    }

    public int read16(int offset) {
        if (offset < 0 || offset + 1 >= bytes.length) return 0;
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    public int next16Signed() {
        return (short) next16();
    }

    public int next32() {
        return next16() | (next16() << 16);
    }

    public int next16BE() {
        return (next8() << 8) | next8();
    }

    public int next32BE() {
        return (next16BE() << 16) | next16BE();
    }

    public long next64BE() {
        return (((long) next32BE()) << 32) | (next32BE() & 0xFFFFFFFFL);
    }

    public float nextFloat() {
        return Float.intBitsToFloat(next32());
    }

    public float[] nextFloat(int amount) {
        float[] dest = new float[amount];
        for (int i = 0; i < amount; i++) {
            dest[i] = nextFloat();
        }
        return dest;
    }

    public int[] next32(int amount) {
        int[] dest = new int[amount];
        for (int i = 0; i < amount; i++) {
            dest[i] = next32();
        }
        return dest;
    }

    public Vector2f nextVector2f() {
        return new Vector2f(nextFloat(), nextFloat());
    }

    public Vector3f nextVector3f() {
        return new Vector3f(nextFloat(), nextFloat(), nextFloat());
    }

    public char nextChar() {
        return (char) next8();
    }

    public char nextCharJis() {
        int code = next8();
        if ((code & 0x80) != 0) {
            code = (code << 8) | next8();
        }
        return (char) code;
    }

    public String nextString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(nextChar());
        }
        return sb.toString();
    }

    public String nextZeroTerminatedString() {
        StringBuilder sb = new StringBuilder();
        while (hasMore()) {
            char c = nextCharJis();
            if (c == 0) break;
            sb.append(c);
        }
        return sb.toString();
    }

    public byte[] subBuffer(int offset, int size) {
        if (offset < 0 || offset + size > bytes.length) {
            size = Math.max(0, bytes.length - offset);
        }
        byte[] result = new byte[size];
        System.arraycopy(bytes, offset, result, 0, size);
        return result;
    }

    public byte[] subBuffer(int size) {
        return subBuffer(position, size);
    }

    public void offsetFrom(SectionBounds sectionHeader, int offset) {
        if (sectionHeader != null) {
            this.position = sectionHeader.getSectionStartPosition() + offset;
        }
    }

    public void offsetFromDataStart(SectionBounds sectionHeader, int offset) {
        if (sectionHeader != null) {
            this.position = sectionHeader.getDataStartPosition() + offset;
        }
    }

    public void align0x04() {
        int remainder = position % 4;
        if (remainder != 0) {
            position += 4 - remainder;
        }
    }

    public void align0x10() {
        int remainder = position % 16;
        if (remainder != 0) {
            position += 16 - remainder;
        }
    }

    public void xorNext(byte mask) {
        if (position < bytes.length) {
            bytes[position] = (byte) (bytes[position] ^ mask);
            position++;
        }
    }

    public void xorNext8(byte mask) {
        xorNext(mask);
    }

    public void rotateNext8(int amount) {
        if (position < bytes.length) {
            int val = bytes[position] & 0xFF;
            int rotAmount = amount & 7;
            int rotated = ((val >>> rotAmount) | (val << (8 - rotAmount))) & 0xFF;
            bytes[position] = (byte) rotated;
            position++;
        }
    }

    public void rotateRight(int amount) {
        int rotAmount = amount & 7;
        for (int i = 0; i < bytes.length; i++) {
            int val = bytes[i] & 0xFF;
            bytes[i] = (byte) (((val >>> rotAmount) | (val << (8 - rotAmount))) & 0xFF);
        }
    }

    public void swapNext8(int offset, int repetitions) {
        for (int i = 0; i < repetitions; i++) {
            if (position + offset < bytes.length) {
                byte tmp = bytes[position];
                bytes[position] = bytes[position + offset];
                bytes[position + offset] = tmp;
            }
            position++;
        }
    }

    public void write8(int value) {
        if (position < bytes.length) {
            bytes[position] = (byte) (value & 0xFF);
            position++;
        }
    }

    public void write16(int value) {
        write8(value & 0xFF);
        write8(value >>> 8);
    }

    public void write32(int value) {
        write8(value & 0xFF);
        write8((value >>> 8) & 0xFF);
        write8((value >>> 16) & 0xFF);
        write8((value >>> 24) & 0xFF);
    }

    public void write32(float value) {
        write32(Float.floatToIntBits(value));
    }

    @Override
    public String toString() {
        return String.format("[%s] Pos: 0x%X | Size: 0x%X", resourceName, position, bytes.length);
    }
}
