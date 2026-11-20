package com.ffxi.parser;

import com.ffxi.io.ByteReader;

import java.util.List;

/**
 * Parses retail FFXI {@link SectionType#S45_Info} ({@code 0x45}) model info chunks,
 * matching client size scaling behavior ({@code FUN_100212f0}: {@code info[10 + graphSize] / 100f}).
 */
public final class ModelInfoParser {

    public static final int UNSET = 0xFF;
    public static final int SIZE_COUNT = 4;

    private static final int INFO_MIN_PAYLOAD_BYTES = 16;
    private static final int SIZE_INFO_OFFSET = 10;
    private static final int MIN_VALID_SCALE_PERCENT = 10;

    private ModelInfoParser() {
    }

    /**
     * Returns a fresh 4-slot size table initialized to {@link #UNSET} ({@code 0xFF}).
     */
    public static int[] emptySizeInfo() {
        return new int[]{UNSET, UNSET, UNSET, UNSET};
    }

    /**
     * Extracts the 4 {@code size_info} percentage bytes ({@code info[10..13]}) from the first non-{@code moun}
     * {@code 0x45} chunk in {@code rawDatBytes}.
     */
    public static int[] parseSizeInfo(byte[] rawDatBytes) {
        int[] out = emptySizeInfo();
        if (rawDatBytes == null || rawDatBytes.length < 0x20) {
            return out;
        }

        List<SectionHeader> headers = DatParser.parseSections(new ByteReader(rawDatBytes));
        for (SectionHeader header : headers) {
            if (header.getSectionType() != SectionType.S45_Info) {
                continue;
            }
            String sectionId = header.getSectionId() == null ? "" : header.getSectionId().trim();
            if ("moun".equalsIgnoreCase(sectionId)) {
                continue;
            }
            int dataStart = header.getDataStartPosition();
            int dataEnd = Math.min(rawDatBytes.length, header.getNextSectionOffset());
            if (dataStart < 0 || dataEnd - dataStart < INFO_MIN_PAYLOAD_BYTES) {
                continue;
            }
            for (int i = 0; i < SIZE_COUNT; i++) {
                out[i] = rawDatBytes[dataStart + SIZE_INFO_OFFSET + i] & 0xFF;
            }
            return out;
        }
        return out;
    }

    /**
     * Merges {@code source} into {@code dest} using first-file-wins semantics (only unset {@code 0xFF} slots in
     * {@code dest} are filled).
     */
    public static void mergeSizeInfo(int[] dest, int[] source) {
        if (dest == null || source == null) {
            return;
        }
        int len = Math.min(Math.min(dest.length, source.length), SIZE_COUNT);
        for (int i = 0; i < len; i++) {
            if (dest[i] == UNSET && source[i] != UNSET) {
                dest[i] = source[i];
            }
        }
    }

    /**
     * Applies {@code preferred} values over {@code dest} wherever {@code preferred} is set (used when a variant DAT
     * inherits a base model via {@code init} opcode {@code 0x98}).
     */
    public static void preferSizeInfo(int[] dest, int[] preferred) {
        if (dest == null || preferred == null) {
            return;
        }
        int len = Math.min(Math.min(dest.length, preferred.length), SIZE_COUNT);
        for (int i = 0; i < len; i++) {
            if (preferred[i] != UNSET) {
                dest[i] = preferred[i];
            }
        }
    }

    /**
     * Resolves the uniform model scale multiplier for {@code graphSize} ({@code 0..3}).
     * Unset entries ({@code 0xFF}), mount-rider index sentinels ({@code < 10}), and out-of-range indices resolve to
     * {@code 1.0f}.
     */
    public static float resolveScale(int[] sizeInfo, int graphSize) {
        if (sizeInfo == null || graphSize < 0 || graphSize >= sizeInfo.length) {
            return 1.0f;
        }
        int pct = sizeInfo[graphSize];
        if (pct == UNSET || pct < MIN_VALID_SCALE_PERCENT) {
            return 1.0f;
        }
        return pct / 100.0f;
    }
}
