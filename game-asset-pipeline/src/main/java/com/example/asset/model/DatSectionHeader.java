package com.example.asset.model;

import com.example.asset.reader.DatByteReader;

/**
 * 16-byte header preceding each resource section in an FFXI DAT file.
 *
 * @param sectionId            4-character section identifier tag
 * @param sectionType          classified type of this section
 * @param sectionStartPosition absolute stream byte offset where the header starts
 * @param dataStartPosition    absolute stream byte offset where payload data begins (+16 bytes)
 * @param sectionSize          total byte length of the section including the header
 */
public record DatSectionHeader(
        String sectionId,
        SectionType sectionType,
        int sectionStartPosition,
        int dataStartPosition,
        int sectionSize
) {

    public int sectionEndPosition() {
        return sectionStartPosition + sectionSize;
    }

    /**
     * Reads a 16-byte section header from the current position of the given reader.
     * Aligns the reader to the next 16-byte boundary (dataStartPosition).
     */
    public static DatSectionHeader read(DatByteReader reader) {
        int startPos = reader.position();
        int dataPos = startPos + 0x10;

        String id = reader.nextString(4);
        int sectionMeta = reader.nextI32();

        int typeCode = sectionMeta & 0x7F;
        int size = ((sectionMeta >>> 7) & 0xFFFFF) * 0x10;

        SectionType type = SectionType.fromCode(typeCode);
        reader.align(0x10);

        return new DatSectionHeader(id, type, startPos, dataPos, size);
    }
}
