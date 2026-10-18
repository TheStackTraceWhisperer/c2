package com.example.asset.interaction;

import com.example.asset.geometry.Vector3f;
import com.example.asset.model.DatSectionHeader;
import com.example.asset.model.SectionType;
import com.example.asset.parser.DatSectionScanner;
import com.example.asset.reader.DatByteReader;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses zone interaction volumes (Zonelines, Entrances, Doors, Fishing boundaries)
 * from section 0x36 ("RID") within FFXI zone DAT files.
 */
@Slf4j
public final class ZoneInteractionParser {

    private ZoneInteractionParser() {
    }

    public static List<ZoneInteraction> parseInteractions(byte[] datBytes) {
        if (datBytes == null || datBytes.length == 0) {
            return Collections.emptyList();
        }
        DatByteReader reader = new DatByteReader(datBytes);
        List<DatSectionHeader> sections = DatSectionScanner.scanSections(reader);

        List<ZoneInteraction> interactions = new ArrayList<>();
        for (DatSectionHeader header : sections) {
            if (header.sectionType() == SectionType.S36_ZONE_INTERACTIONS) {
                try {
                    DatByteReader payload = DatSectionScanner.extractPayload(reader, header);
                    interactions.addAll(parseSection(payload));
                } catch (Exception e) {
                    log.warn("Failed to parse zone interactions in section at offset {}: {}", header.sectionStartPosition(), e.getMessage());
                }
            }
        }
        return Collections.unmodifiableList(interactions);
    }

    private static List<ZoneInteraction> parseSection(DatByteReader reader) {
        if (reader.remaining() < 24) {
            return Collections.emptyList();
        }

        String magic = reader.nextString(4);
        if (!magic.startsWith("RID")) {
            log.debug("Zone interaction section magic mismatch: expected 'RID', got '{}'", magic);
            return Collections.emptyList();
        }

        int unk0 = reader.nextI32();
        reader.skip(8);

        int dataOffset = reader.nextI32();
        if (dataOffset > 0 && dataOffset < reader.capacity()) {
            reader.position(dataOffset);
        }

        if (reader.remaining() < 16) {
            return Collections.emptyList();
        }

        int numEntries = reader.nextI32();
        reader.skip(12); // expect three 32-bit zeroes

        List<ZoneInteraction> list = new ArrayList<>(Math.max(0, numEntries));
        for (int i = 0; i < numEntries; i++) {
            if (reader.remaining() < 64) {
                break;
            }

            Vector3f position = reader.nextVector3f();
            Vector3f orientation = reader.nextVector3f();
            Vector3f size = reader.nextVector3f();

            String sourceId = cleanString(reader.nextString(4));
            String destIdRaw = cleanString(reader.nextString(4));
            String destId = (destIdRaw.isEmpty() || isAllNullChars(destIdRaw)) ? null : destIdRaw;

            int param = reader.nextI32();
            int terrainFlags = reader.nextU16();
            int mapId = reader.nextU16();

            // Skip ev0 (2 bytes) + ev1 (2 bytes) + unk (4 bytes) + unk (4 bytes) = 12 bytes
            reader.skip(12);

            list.add(new ZoneInteraction(position, orientation, size, sourceId, destId, param, mapId));
        }

        return list;
    }

    private static String cleanString(String s) {
        if (s == null) return "";
        return s.replace("\0", "").trim();
    }

    private static boolean isAllNullChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != 0 && s.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }
}
