package com.example.asset.reader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Sequential binary reader for FFXI DAT byte streams.
 * Uses Little-Endian byte order matching the original platform encoding.
 */
public final class DatByteReader {

    private final ByteBuffer buffer;

    public DatByteReader(byte[] bytes) {
        this(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
    }

    public DatByteReader(ByteBuffer buffer) {
        this.buffer = buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    public int position() {
        return buffer.position();
    }

    public void position(int newPosition) {
        buffer.position(newPosition);
    }

    public int remaining() {
        return buffer.remaining();
    }

    public boolean hasMore() {
        return buffer.hasRemaining();
    }

    public int nextU8() {
        return buffer.get() & 0xFF;
    }

    public byte nextI8() {
        return buffer.get();
    }

    public void xorNext(byte mask) {
        int pos = buffer.position();
        buffer.put(pos, (byte) (buffer.get(pos) ^ mask));
        buffer.position(pos + 1);
    }

    public void xorAt(int pos, byte mask) {
        buffer.put(pos, (byte) (buffer.get(pos) ^ mask));
    }

    public int nextU16() {
        return buffer.getShort() & 0xFFFF;
    }

    public short nextI16() {
        return buffer.getShort();
    }

    public int nextI32() {
        return buffer.getInt();
    }

    public float nextFloat() {
        return buffer.getFloat();
    }

    public com.example.asset.geometry.Vector3f nextVector3f() {
        return new com.example.asset.geometry.Vector3f(nextFloat(), nextFloat(), nextFloat());
    }

    public byte[] nextBytes(int length) {
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    public String nextString(int length) {
        byte[] bytes = nextBytes(length);
        int actualLen = 0;
        while (actualLen < length && bytes[actualLen] != 0) {
            actualLen++;
        }
        return new String(bytes, 0, actualLen, StandardCharsets.ISO_8859_1);
    }

    public String nextZeroTerminatedString() {
        StringBuilder sb = new StringBuilder();
        while (hasMore()) {
            byte b = buffer.get();
            if (b == 0) {
                break;
            }
            sb.append((char) (b & 0xFF));
        }
        return sb.toString();
    }

    public void align(int alignment) {
        int remainder = buffer.position() % alignment;
        if (remainder != 0) {
            buffer.position(buffer.position() + (alignment - remainder));
        }
    }

    public DatByteReader slice(int offset, int length) {
        ByteBuffer duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        duplicate.position(offset);
        duplicate.limit(offset + length);
        return new DatByteReader(duplicate.slice().order(ByteOrder.LITTLE_ENDIAN));
    }
}
