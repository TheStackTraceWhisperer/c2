package com.ffxi.parser;

import com.ffxi.io.ByteReader;
import java.util.ArrayList;
import java.util.List;

public class BasicStringTableParser {

    public static List<String> read(ByteReader byteReader, byte bitMask) {
        List<String> entries = new ArrayList<>();
        if (byteReader == null || !byteReader.hasMore()) return entries;

        int tableSize = byteReader.next32();
        int baseOffset = byteReader.getPosition();

        if (bitMask != 0) {
            while (byteReader.hasMore()) {
                byteReader.xorNext8(bitMask);
            }
        }

        byteReader.setPosition(baseOffset);
        int firstEntryOffset = baseOffset + byteReader.next32();

        byteReader.setPosition(baseOffset);
        while (byteReader.getPosition() < firstEntryOffset && byteReader.hasMore()) {
            int currentPos = byteReader.getPosition();
            int stringOffset = baseOffset + byteReader.next32();

            byteReader.setPosition(stringOffset);
            entries.add(byteReader.nextZeroTerminatedString());
            byteReader.setPosition(currentPos + 4);
        }

        return entries;
    }
}
