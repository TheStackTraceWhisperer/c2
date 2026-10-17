package com.example.asset.table;

/**
 * Resolved folder and file numbers within an FFXI ROM directory.
 */
public record FTableEntry(int folderNumber, int fileNumber) {

    public String toFileName() {
        return fileNumber + ".DAT";
    }
}
