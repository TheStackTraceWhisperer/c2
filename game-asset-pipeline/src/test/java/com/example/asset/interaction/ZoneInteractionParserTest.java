package com.example.asset.interaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZoneInteractionParserTest {

    @Test
    @DisplayName("Parse mock Section 0x36 RID interaction volumes covering zonelines, entrances, doors, and fishing")
    void testParseMockZoneInteractions() {
        byte[] mockDat = createMockInteractionDat();

        List<ZoneInteraction> interactions = ZoneInteractionParser.parseInteractions(mockDat);
        assertNotNull(interactions);
        assertEquals(4, interactions.size(), "Should parse exactly 4 mock interaction volumes");

        // 1. Zoneline check
        ZoneInteraction zoneline = interactions.stream().filter(ZoneInteraction::isZoneLine).findFirst().orElseThrow();
        assertEquals("z6e0", zoneline.getSourceId());
        assertEquals("z2t3", zoneline.getDestId());
        assertEquals(101, zoneline.getParam());
        assertNotNull(zoneline.getBoundingBox());
        assertTrue(zoneline.contains(10.0f, 2.0f, -30.0f), "Center must be inside bounding box");
        assertTrue(zoneline.containsHorizontal(10.0f, -30.0f, 1.0f));
        assertFalse(zoneline.contains(100.0f, 100.0f, 100.0f));

        // 2. Zone Entrance check
        ZoneInteraction entrance = interactions.stream().filter(ZoneInteraction::isZoneEntrance).findFirst().orElseThrow();
        assertEquals("z001", entrance.getSourceId());
        assertNull(entrance.getDestId());
        assertTrue(entrance.contains(0.0f, 0.0f, 0.0f));

        // 3. Door check
        ZoneInteraction door = interactions.stream().filter(ZoneInteraction::isDoor).findFirst().orElseThrow();
        assertEquals("_6cu", door.getSourceId());
        assertTrue(door.contains(5.0f, 0.0f, 10.0f));

        // 4. Fishing area check
        ZoneInteraction fishing = interactions.stream().filter(ZoneInteraction::isFishingArea).findFirst().orElseThrow();
        assertEquals("f6c0", fishing.getSourceId());
        assertTrue(fishing.contains(15.0f, -1.0f, 25.0f));
    }

    @Test
    @DisplayName("Gracefully handles null, empty, or malformed DAT payloads")
    void testParseNullOrMalformedDat() {
        assertTrue(ZoneInteractionParser.parseInteractions(null).isEmpty());
        assertTrue(ZoneInteractionParser.parseInteractions(new byte[0]).isEmpty());
        assertTrue(ZoneInteractionParser.parseInteractions(new byte[64]).isEmpty());
    }

    /**
     * Builds an authentic, in-memory mock DAT byte array containing a 16-byte section header
     * for Section 0x36 (S36_ZONE_INTERACTIONS) and an "RID\0" payload with 4 entries.
     */
    private static byte[] createMockInteractionDat() {
        int numEntries = 4;
        int payloadLen = 36 + numEntries * 64;
        int paddedPayloadLen = ((payloadLen + 15) / 16) * 16;
        int totalSecSize = 16 + paddedPayloadLen;
        int secUnits = totalSecSize / 16;
        int secMeta = (secUnits << 7) | 0x36; // SectionType 0x36

        ByteBuffer buf = ByteBuffer.allocate(totalSecSize).order(ByteOrder.LITTLE_ENDIAN);

        // 1. 16-byte DAT section header
        buf.put("zone".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(secMeta);
        buf.put(new byte[8]); // Pad to 16 bytes

        // 2. RID payload header
        buf.put("RID\0".getBytes(StandardCharsets.US_ASCII)); // Magic (4 bytes)
        buf.putInt(0); // unk0 (4 bytes)
        buf.put(new byte[8]); // Skip 8 bytes
        buf.putInt(20); // dataOffset = 20 (4 bytes)

        // At dataOffset = 20 in payload:
        buf.putInt(numEntries); // 4 bytes
        buf.put(new byte[12]); // Skip 12 bytes

        // 3. Four interaction entries (64 bytes each)
        // Entry 1: Zoneline (z6e0 -> z2t3, target zone 101)
        writeEntry(buf, 10.0f, 2.0f, -30.0f, 5.0f, 4.0f, 5.0f, "z6e0", "z2t3", 101, 1);
        // Entry 2: Zone Entrance (z001)
        writeEntry(buf, 0.0f, 0.0f, 0.0f, 3.0f, 3.0f, 3.0f, "z001", "", 0, 1);
        // Entry 3: Door (_6cu)
        writeEntry(buf, 5.0f, 0.0f, 10.0f, 2.0f, 3.0f, 2.0f, "_6cu", "", 0, 1);
        // Entry 4: Fishing spot (f6c0)
        writeEntry(buf, 15.0f, -1.0f, 25.0f, 8.0f, 2.0f, 8.0f, "f6c0", "", 0, 1);

        return buf.array();
    }

    private static void writeEntry(ByteBuffer buf,
                                   float px, float py, float pz,
                                   float sx, float sy, float sz,
                                   String src, String dst, int param, int mapId) {
        buf.putFloat(px).putFloat(py).putFloat(pz); // position (12 bytes)
        buf.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f); // orientation (12 bytes)
        buf.putFloat(sx).putFloat(sy).putFloat(sz); // size (12 bytes)

        byte[] srcBytes = new byte[4];
        byte[] rawSrc = src.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(rawSrc, 0, srcBytes, 0, Math.min(4, rawSrc.length));
        buf.put(srcBytes); // sourceId (4 bytes)

        byte[] dstBytes = new byte[4];
        byte[] rawDst = dst.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(rawDst, 0, dstBytes, 0, Math.min(4, rawDst.length));
        buf.put(dstBytes); // destId (4 bytes)

        buf.putInt(param); // param (4 bytes)
        buf.putShort((short) 0); // terrainFlags (2 bytes)
        buf.putShort((short) mapId); // mapId (2 bytes)
        buf.put(new byte[12]); // ev0, ev1, unk (12 bytes)
    }
}
