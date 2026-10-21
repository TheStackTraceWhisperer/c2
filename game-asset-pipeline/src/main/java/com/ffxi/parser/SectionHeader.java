package com.ffxi.parser;

import com.ffxi.io.ByteReader;
import com.ffxi.io.SectionBounds;

public class SectionHeader implements SectionBounds {
    private String sectionId;
    private SectionType sectionType;
    private int sectionStartPosition;
    private int dataStartPosition;
    private int sectionSize;

    public void read(ByteReader byteReader) {
        this.sectionStartPosition = byteReader.getPosition();
        this.dataStartPosition = sectionStartPosition + 0x10;
        this.sectionId = byteReader.nextString(4);

        int sectionMeta = byteReader.next32();
        this.sectionType = SectionType.fromCode(sectionMeta & 0x7F);
        this.sectionSize = ((sectionMeta >>> 7) & 0xFFFFF) * 0x10;

        byteReader.align0x10();
    }

    public String getSectionId() {
        return sectionId;
    }

    public SectionType getSectionType() {
        return sectionType;
    }

    public int getSectionStartPosition() {
        return sectionStartPosition;
    }

    public int getDataStartPosition() {
        return dataStartPosition;
    }

    public int getSectionSize() {
        return sectionSize;
    }

    public int getNextSectionOffset() {
        return sectionStartPosition + sectionSize;
    }

    @Override
    public String toString() {
        return String.format("[%s] Type: %s | Start: 0x%X | Size: 0x%X",
                sectionId, sectionType, sectionStartPosition, sectionSize);
    }
}
