package com.example.asset.parser;

import com.example.asset.model.DatSectionHeader;
import com.example.asset.model.SectionType;
import com.example.asset.reader.DatByteReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans binary DAT byte streams to index all contained section headers.
 */
public final class DatSectionScanner {

    private DatSectionScanner() {
    }

    /**
     * Scans through all sequential sections in a DAT stream until the enclosing root directory
     * closes via {@code S00_END}, the end of the buffer is reached, or an invalid section size
     * is encountered. Nested {@code S01_DIRECTORY} blocks increment directory depth so inner
     * {@code S00_END} markers close only their local directory.
     */
    public static List<DatSectionHeader> scanSections(DatByteReader reader) {
        List<DatSectionHeader> headers = new ArrayList<>();
        int directoryDepth = 0;

        while (reader.remaining() >= 16) {
            DatSectionHeader header = DatSectionHeader.read(reader);
            headers.add(header);

            if (header.sectionType() == SectionType.S01_DIRECTORY) {
                directoryDepth++;
            } else if (header.sectionType() == SectionType.S00_END) {
                directoryDepth--;
                if (directoryDepth <= 0) {
                    break;
                }
            }

            int nextOffset = header.sectionEndPosition();
            if (nextOffset <= header.sectionStartPosition() || nextOffset > reader.position() + reader.remaining()) {
                break;
            }
            reader.position(nextOffset);
        }

        return headers;
    }

    /**
     * Slices the payload data (excluding the 16-byte header) for a specific section.
     */
    public static DatByteReader extractPayload(DatByteReader streamReader, DatSectionHeader header) {
        int payloadSize = Math.max(0, header.sectionSize() - 16);
        return streamReader.slice(header.dataStartPosition(), payloadSize);
    }
}
