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

    public FileLocation getFile(int fileId) {
        byteReader.setPosition(2 * fileId);
        int fileValue = byteReader.next16();

        int fileName = fileValue & 0x7F;
        int folderName = fileValue >>> 7;
        return new FileLocation(folderName, fileName);
    }
}
