package com.ffxi.io;

public class FTable {
    private final ByteReader byteReader;

    public FTable(ByteReader byteReader) {
        this.byteReader = byteReader;
    }

    public ByteReader getByteReader() {
        return byteReader;
    }

    public static class FileLocation {
        public final int folderNumber;
        public final int fileNumber;

        public FileLocation(int folderNumber, int fileNumber) {
            this.folderNumber = folderNumber;
            this.fileNumber = fileNumber;
        }

        @Override
        public String toString() {
            return folderNumber + "/" + fileNumber + ".DAT";
        }
    }

    /** Stateless, thread-safe lookup. Throws IllegalArgumentException if fileId is outside the table. */
    public FileLocation getFile(int fileId) {
        byte[] bytes = byteReader.getBytes();
        long offset = 2L * fileId;
        if (fileId < 0 || offset + 1 >= bytes.length) {
            throw new IllegalArgumentException("fileId out of range: " + fileId);
        }
        int fileValue = (bytes[(int) offset] & 0xFF) | ((bytes[(int) offset + 1] & 0xFF) << 8);

        int fileName = fileValue & 0x7F;
        int folderName = fileValue >>> 7;
        return new FileLocation(folderName, fileName);
    }
}
