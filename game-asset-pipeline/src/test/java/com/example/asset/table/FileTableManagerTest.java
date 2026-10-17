package com.example.asset.table;

import com.example.asset.reader.DatByteReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileTableManagerTest {

    @Test
    @DisplayName("Should resolve synthetic fileId to canonical ROM path")
    void testSyntheticResolve() {
        // fileId 0: version 1 -> "ROM/", folder 2, file 10 -> "ROM/2/10.DAT"
        VTable vtable = new VTable(new byte[] { 1, 2, 0 });
        short word0 = (short) ((2 << 7) | 10);
        short word1 = (short) ((0 << 7) | 5);
        FTable ftable = new FTable(new short[] { word0, word1, 0 });

        FileTableManager manager = new FileTableManager(vtable, ftable);

        assertThat(manager.resolvePath(0)).isEqualTo("ROM/2/10.DAT");
        assertThat(manager.resolvePath(1)).isEqualTo("ROM2/0/5.DAT");
        assertThat(manager.resolvePath(2)).isNull(); // version 0
        assertThat(manager.resolvePath(99)).isNull(); // out of bounds
    }

    @Test
    @DisplayName("Should correctly resolve real starter zones using bundled FTABLE and VTABLE")
    void testRealTableResolution() throws Exception {
        InputStream vStream = getClass().getResourceAsStream("/dats/tables/VTABLE.DAT");
        InputStream fStream = getClass().getResourceAsStream("/dats/tables/FTABLE.DAT");

        assumeTrue(vStream != null && fStream != null, "Fixture tables not present; skipping.");

        VTable vTable;
        FTable fTable;
        try (vStream; fStream) {
            vTable = VTable.load(new DatByteReader(vStream.readAllBytes()));
            fTable = FTable.load(new DatByteReader(fStream.readAllBytes()));
        }

        FileTableManager manager = new FileTableManager(vTable, fTable);
        ZonePathResolver resolver = new ZonePathResolver(manager);

        // Zone 100: West Ronfaure (fileId = 200) -> ROM/0/120.DAT
        assertThat(resolver.resolveZonePath(100)).isEqualTo("ROM/0/120.DAT");

        // Zone 230: Southern San d'Oria (fileId = 330) -> ROM/1/31.DAT
        assertThat(resolver.resolveZonePath(230)).isEqualTo("ROM/1/31.DAT");

        // Zone 106: North Gustaberg (fileId = 206) -> ROM/0/123.DAT
        assertThat(resolver.resolveZonePath(106)).isEqualTo("ROM/0/123.DAT");

        // Zone 234: Bastok Mines (fileId = 334) -> ROM/1/34.DAT
        assertThat(resolver.resolveZonePath(234)).isEqualTo("ROM/1/34.DAT");

        // Zone 115: West Sarutabaruta (fileId = 215) -> ROM/0/127.DAT
        assertThat(resolver.resolveZonePath(115)).isEqualTo("ROM/0/127.DAT");

        // Zone 241: Windurst Woods (fileId = 341) -> ROM/0/81.DAT
        assertThat(resolver.resolveZonePath(241)).isEqualTo("ROM/0/81.DAT");
    }
}
