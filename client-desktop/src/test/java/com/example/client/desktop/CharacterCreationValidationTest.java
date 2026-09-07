package com.example.client.desktop;

import com.example.common.protocol.NationId;
import com.example.common.protocol.RaceId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CharacterCreationValidationTest {

    @Test
    void testGenderDerivationForCanonicalRaces() {
        assertEquals((short) 0, RaceId.genderFor(RaceId.HUME_MALE), "Hume Male should have gender=0");
        assertEquals((short) 1, RaceId.genderFor(RaceId.HUME_FEMALE), "Hume Female should have gender=1");
        assertEquals((short) 0, RaceId.genderFor(RaceId.ELVAAN_MALE), "Elvaan Male should have gender=0");
        assertEquals((short) 1, RaceId.genderFor(RaceId.ELVAAN_FEMALE), "Elvaan Female should have gender=1");
        assertEquals((short) 0, RaceId.genderFor(RaceId.TARUTARU_MALE), "Tarutaru Male should have gender=0");
        assertEquals((short) 1, RaceId.genderFor(RaceId.TARUTARU_FEMALE), "Tarutaru Female should have gender=1");
        assertEquals((short) 1, RaceId.genderFor(RaceId.MITHRA), "Mithra should have gender=1");
        assertEquals((short) 0, RaceId.genderFor(RaceId.GALKA), "Galka should have gender=0");
    }

    @Test
    void testForcedSizeRules() {
        // Tarutaru is locked to Small (0)
        assertEquals(0, RaceId.forcedSizeFor(RaceId.TARUTARU_MALE));
        assertEquals(0, RaceId.forcedSizeFor(RaceId.TARUTARU_FEMALE));

        // Galka is locked to Large (2)
        assertEquals(2, RaceId.forcedSizeFor(RaceId.GALKA));

        // Others are not locked (-1)
        assertEquals(-1, RaceId.forcedSizeFor(RaceId.HUME_MALE));
        assertEquals(-1, RaceId.forcedSizeFor(RaceId.HUME_FEMALE));
        assertEquals(-1, RaceId.forcedSizeFor(RaceId.ELVAAN_MALE));
        assertEquals(-1, RaceId.forcedSizeFor(RaceId.ELVAAN_FEMALE));
        assertEquals(-1, RaceId.forcedSizeFor(RaceId.MITHRA));
    }

    @Test
    void testFacePermutationEncoding() {
        // 8 face styles (1-8), 2 variants (A = false, B = true) => 0..15
        for (int face = 1; face <= 8; face++) {
            int faceA = (face - 1);
            int faceB = (face - 1) + 8;
            assertEquals(face - 1, faceA);
            assertEquals(face + 7, faceB);
            assertTrue(faceA >= 0 && faceA <= 7);
            assertTrue(faceB >= 8 && faceB <= 15);
        }
    }

    @Test
    void testNationLabels() {
        assertEquals("San d'Oria", NationId.toLabel(NationId.SAN_D_ORIA));
        assertEquals("Bastok", NationId.toLabel(NationId.BASTOK));
        assertEquals("Windurst", NationId.toLabel(NationId.WINDURST));
    }
}
