package com.ffxi.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffxi.crypto.KeyTables;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class FTableAndKeyTablesConcurrencyTest {

    @Test
    void ftableConcurrentLookupsAreStatelessAndAccurate() throws Exception {
        int entries = 4096;
        byte[] raw = new byte[entries * 2];
        for (int id = 0; id < entries; id++) {
            int folder = (id * 7 + 3) & 0x1FF;
            int file = (id * 13 + 5) & 0x7F;
            int val = (folder << 7) | file;
            raw[2 * id] = (byte) (val & 0xFF);
            raw[2 * id + 1] = (byte) ((val >>> 8) & 0xFF);
        }
        ByteReader sharedReader = new ByteReader(raw, "FTABLE.DAT");
        sharedReader.setPosition(123);
        FTable ftable = new FTable(sharedReader);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                tasks.add(() -> {
                    barrier.await();
                    for (int step = 0; step < 5000; step++) {
                        int id = (seed * 251 + step * 97) % entries;
                        FTable.FileLocation loc = ftable.getFile(id);
                        int expectedFolder = (id * 7 + 3) & 0x1FF;
                        int expectedFile = (id * 13 + 5) & 0x7F;
                        assertThat(loc.folderNumber).isEqualTo(expectedFolder);
                        assertThat(loc.fileNumber).isEqualTo(expectedFile);
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // Underlying reader cursor must not have been touched
        assertThat(sharedReader.getPosition()).isEqualTo(123);

        assertThatThrownBy(() -> ftable.getFile(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ftable.getFile(entries)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ftable.getFile(Integer.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Builds a synthetic DLL byte[] containing the two big-endian hint dwords in the 0x30000..0x3C000
     * scan window, followed by deterministic 256-byte tables tagged with {@code tag}.
     */
    public static byte[] syntheticDll(int tag) {
        byte[] dll = new byte[0x31000];
        int off1 = 0x30100;
        int off2 = 0x30400;
        writeBe32(dll, off1, 0xE2E506A9);
        writeBe32(dll, off2, (int) 0xB8C5F784L);
        for (int i = 4; i < 256; i++) {
            dll[off1 + i] = (byte) ((tag + i) & 0xFF);
            dll[off2 + i] = (byte) ((tag ^ i) & 0xFF);
        }
        return dll;
    }

    private static void writeBe32(byte[] buf, int off, int v) {
        buf[off] = (byte) ((v >>> 24) & 0xFF);
        buf[off + 1] = (byte) ((v >>> 16) & 0xFF);
        buf[off + 2] = (byte) ((v >>> 8) & 0xFF);
        buf[off + 3] = (byte) (v & 0xFF);
    }

    @Test
    void keyTablesReturnDefensiveCopiesAndProvideIndexedAccessors() {
        KeyTables.loadFromDll(new ByteReader(syntheticDll(0x20), "FFXiMain.dll"));

        byte[] t1 = KeyTables.getKeyTable1();
        byte[] t2 = KeyTables.getKeyTable2();
        assertThat(t1).hasSize(256);
        assertThat(t2).hasSize(256);
        int orig10 = t1[10] & 0xFF;
        int orig20 = t2[20] & 0xFF;
        assertThat(KeyTables.keyTable1At(10)).isEqualTo(orig10);
        assertThat(KeyTables.keyTable2At(20)).isEqualTo(orig20);

        // Mutating the returned arrays must not affect subsequent reads
        t1[10] = (byte) ~t1[10];
        t2[20] = (byte) ~t2[20];
        assertThat(KeyTables.getKeyTable1()[10] & 0xFF).isEqualTo(orig10);
        assertThat(KeyTables.getKeyTable2()[20] & 0xFF).isEqualTo(orig20);
        assertThat(KeyTables.keyTable1At(10)).isEqualTo(orig10);
        assertThat(KeyTables.keyTable2At(20)).isEqualTo(orig20);

        assertThatThrownBy(() -> KeyTables.keyTable1At(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> KeyTables.keyTable1At(256)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> KeyTables.keyTable2At(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> KeyTables.keyTable2At(256)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void keyTablesConcurrentLoadAndReadStayConsistent() throws Exception {
        byte[] dllA = syntheticDll(0x10);
        byte[] dllB = syntheticDll(0x90);
        KeyTables.loadFromDll(new ByteReader(dllA, "A.dll"));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int worker = t;
                tasks.add(() -> {
                    barrier.await();
                    for (int i = 0; i < 400; i++) {
                        if (worker % 4 == 0) {
                            KeyTables.loadFromDll(new ByteReader((i & 1) == 0 ? dllA : dllB, "shared.dll"));
                        } else {
                            byte[] t1 = KeyTables.getKeyTable1();
                            byte[] t2 = KeyTables.getKeyTable2();
                            // Verify internally consistent table1 (either tag 0x10 or 0x90)
                            int tag1 = (t1[4] - 4) & 0xFF;
                            assertThat(tag1).isIn(0x10, 0x90);
                            for (int k = 4; k < 256; k++) {
                                assertThat(t1[k] & 0xFF).isEqualTo((tag1 + k) & 0xFF);
                            }
                            int tag2 = (t2[4] ^ 4) & 0xFF;
                            assertThat(tag2).isIn(0x10, 0x90);
                            for (int k = 4; k < 256; k++) {
                                assertThat(t2[k] & 0xFF).isEqualTo((tag2 ^ k) & 0xFF);
                            }
                            assertThat(KeyTables.keyTable1At(0)).isEqualTo(0xE2);
                            assertThat(KeyTables.keyTable2At(0)).isEqualTo(0xB8);
                        }
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
