package com.ffxi.parser;

import com.ffxi.io.ByteReader;
import java.util.ArrayList;
import java.util.List;

public class StringTableParser {

    public static class StringTableEntry {
        public final int flag;
        public final String string;

        public StringTableEntry(int flag, String string) {
            this.flag = flag;
            this.string = string;
        }
    }

    public static class StringTableBlock {
        public final List<StringTableEntry> entries;

        public StringTableBlock(List<StringTableEntry> entries) {
            this.entries = entries != null ? entries : new ArrayList<>();
        }

        public String getFirst() {
            return getString(0);
        }

        public String getString(int index) {
            if (index >= 0 && index < entries.size()) {
                return entries.get(index).string;
            }
            return "";
        }
    }

    public static List<StringTableBlock> read(ByteReader byteReader, byte bitMask) {
        int start = byteReader.getPosition();

        String header = byteReader.nextZeroTerminatedString();
        if (!"d_msg".equals(header)) {
            throw new IllegalArgumentException("Unexpected header in d_msg string table: " + header);
        }

        byteReader.setPosition(start + 0x10);
        int unk0 = byteReader.next32();
        int fileSize = byteReader.next32();

        int tableOffset = byteReader.next32();
        int tableSize = byteReader.next32();

        int stringBlockSize = byteReader.next32();
        int stringSectionSize = byteReader.next32();
        int numStrings = byteReader.next32();

        int unk1 = byteReader.next32();

        if (bitMask != 0) {
            byteReader.setPosition(start + tableOffset);
            for (int i = 0; i < fileSize - tableOffset && byteReader.hasMore(); i++) {
                byteReader.xorNext8(bitMask);
            }
        }

        byteReader.setPosition(start + tableOffset);
        if (tableSize == 0) {
            return parseStringsWithoutTable(byteReader, numStrings, stringBlockSize);
        } else {
            return parseStringsWithTable(byteReader, numStrings);
        }
    }

    private static List<StringTableBlock> parseStringsWithTable(ByteReader byteReader, int numStrings) {
        List<Integer> offsets = new ArrayList<>(numStrings);
        for (int i = 0; i < numStrings; i++) {
            offsets.add(byteReader.next32());
            byteReader.next32(); // unk
        }

        int stringStart = byteReader.getPosition();
        List<StringTableBlock> blocks = new ArrayList<>(numStrings);

        for (int i = 0; i < numStrings; i++) {
            byteReader.setPosition(stringStart + offsets.get(i));
            blocks.add(parseStringBlock(byteReader));
        }

        return blocks;
    }

    private static List<StringTableBlock> parseStringsWithoutTable(ByteReader byteReader, int numStrings, int stringBlockSize) {
        int start = byteReader.getPosition();
        List<StringTableBlock> blocks = new ArrayList<>(numStrings);

        for (int i = 0; i < numStrings; i++) {
            byteReader.setPosition(start + stringBlockSize * i);
            blocks.add(parseStringBlock(byteReader));
        }

        return blocks;
    }

    private static StringTableBlock parseStringBlock(ByteReader byteReader) {
        int start = byteReader.getPosition();
        int stringsInBlock = byteReader.next32();
        List<StringTableEntry> entries = new ArrayList<>(stringsInBlock);

        List<Integer> offsets = new ArrayList<>(stringsInBlock);
        for (int i = 0; i < stringsInBlock; i++) {
            offsets.add(byteReader.next32());
            byteReader.next32(); // unkFlag
        }

        for (int i = 0; i < stringsInBlock; i++) {
            byteReader.setPosition(start + offsets.get(i));

            int marker = byteReader.next32();
            String string = "";
            if (marker == 1) {
                byteReader.setPosition(byteReader.getPosition() + 0x18);
                string = byteReader.nextZeroTerminatedString();
            }

            entries.add(new StringTableEntry(marker, string));
        }

        return new StringTableBlock(entries);
    }
}
