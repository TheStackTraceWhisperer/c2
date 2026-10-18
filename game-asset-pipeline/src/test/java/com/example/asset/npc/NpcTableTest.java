package com.example.asset.npc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NpcTableTest {

    @Test
    @DisplayName("Parse and query town NPC spawns from NpcTable-V3.DAT")
    void testLoadNpcTable() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/dats/tables/landsandboat/NpcTable-V3.DAT");
        assumeTrue(stream != null, "Fixture NpcTable-V3.DAT is missing");

        NpcTable table = NpcTable.load(stream);
        assertNotNull(table);

        System.out.printf("Loaded NpcTable: %,d total NPCs across %d zones%n",
                table.totalNpcCount(), table.totalZoneCount());

        assertTrue(table.totalNpcCount() > 5000, "Expected thousands of NPCs in NpcTable-V3.DAT");
        assertTrue(table.totalZoneCount() > 100, "Expected over 100 zones with NPCs");

        // Query South San d'Oria (Zone 230)
        List<NpcInfo> southSanDoriaNpcs = table.getByZone(230);
        assertFalse(southSanDoriaNpcs.isEmpty(), "South San d'Oria should have town NPCs");
        System.out.printf("Found %d town NPCs in South San d'Oria (Zone 230)%n", southSanDoriaNpcs.size());

        assertTrue(southSanDoriaNpcs.size() >= 30, "Expected at least 30 town NPCs in South San d'Oria");

        for (NpcInfo npc : southSanDoriaNpcs) {
            assertNotNull(npc.position());
            assertTrue(npc.zoneId() == 230);
            assertNotNull(npc.equipment());
        }
    }
}
