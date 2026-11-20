package com.ffxi.parser;

import com.ffxi.io.ByteReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses FFXI {@link SectionType#S07_EffectRoutine} {@code "init"} scheduler chunks from a raw model DAT.
 *
 * <p>Extracts base model redirection ({@code 0x98}), scripted PC/NPC equipment look overrides ({@code 0x8d..0x95}),
 * initial motion selector opcodes ({@code 0x79, 0x8c, 0xa4, 0xa5}), and hidden weapon mesh bits ({@code wep0..wep9})
 * evaluated across {@code 0x3b} scheduler calls up to depth 4 via opcodes {@code 0x75} and {@code 0xa3}.
 */
public final class InitSchedulerParser {
    private static final int MAX_CALL_DEPTH = 4;
    private static final int MAX_WEAPON_SLOTS = 10;

    private InitSchedulerParser() {
    }

    public record ScriptedLook(
            int race,
            int face,
            int head,
            int body,
            int hands,
            int legs,
            int feet,
            int main,
            int sub
    ) {
    }

    public record MotionOp(int opcode, int value) {
    }

    public record InitSchedulerResult(
            Optional<Integer> baseModelId,
            Optional<ScriptedLook> scriptedLook,
            int hiddenWeaponMask,
            List<MotionOp> motionOps
    ) {
        public static final InitSchedulerResult EMPTY =
                new InitSchedulerResult(Optional.empty(), Optional.empty(), 0, List.of());

        public InitSchedulerResult {
            baseModelId = baseModelId != null ? baseModelId : Optional.empty();
            scriptedLook = scriptedLook != null ? scriptedLook : Optional.empty();
            motionOps = motionOps != null ? List.copyOf(motionOps) : List.of();
        }

        public boolean isWeaponHidden(int wepIndex) {
            return wepIndex >= 0 && wepIndex < 32 && ((hiddenWeaponMask >>> wepIndex) & 1) != 0;
        }
    }

    /**
     * Parses {@link SectionType#S07_EffectRoutine} chunks from {@code rawDatBytes}.
     */
    public static InitSchedulerResult parse(byte[] rawDatBytes) {
        if (rawDatBytes == null || rawDatBytes.length < 0x10) {
            return InitSchedulerResult.EMPTY;
        }

        List<SectionHeader> allSections = DatParser.parseSections(new ByteReader(rawDatBytes));
        List<RoutineChunk> routineChunks = new ArrayList<>();
        for (SectionHeader header : allSections) {
            if (header.getSectionType() == SectionType.S07_EffectRoutine) {
                int dataStart = header.getDataStartPosition();
                int dataEnd = Math.min(rawDatBytes.length, header.getNextSectionOffset());
                if (dataStart >= 0 && dataEnd > dataStart) {
                    routineChunks.add(new RoutineChunk(
                            normalizeChunkName(header.getSectionId()),
                            dataStart,
                            dataEnd
                    ));
                }
            }
        }

        if (routineChunks.isEmpty()) {
            return InitSchedulerResult.EMPTY;
        }

        Integer baseModelId = null;
        ScriptedLook scriptedLook = null;
        List<MotionOp> motionOps = new ArrayList<>();

        for (RoutineChunk chunk : routineChunks) {
            if (!"init".equals(chunk.name())) {
                continue;
            }
            int dataStart = chunk.dataStart();
            int dataEnd = chunk.dataEnd();
            int at = readRecordStreamOffset(rawDatBytes, dataStart, dataEnd);
            if (at < 0) {
                continue;
            }

            int[] values = new int[9];
            boolean[] hasValue = new boolean[9];

            while (dataStart + at + 2 <= dataEnd) {
                int pos = dataStart + at;
                int op = rawDatBytes[pos] & 0xFF;
                int len = (rawDatBytes[pos + 1] & 0xFF) * 4;
                if (op == 0 || len == 0 || pos + len > dataEnd) {
                    break;
                }

                boolean hasVal = len >= 12;
                int val = hasVal ? u32LE(rawDatBytes, pos + 8) : 0;

                if (op >= 0x8d && op <= 0x95) {
                    int slot = op - 0x8d;
                    hasValue[slot] = hasVal;
                    values[slot] = val;
                } else if (op == 0x98) {
                    baseModelId = hasVal ? (val & 0xFFFF) : null;
                } else if (op == 0x79 || op == 0x8c || op == 0xa4 || op == 0xa5) {
                    if (hasVal) {
                        motionOps.add(new MotionOp(op, val));
                    }
                }

                at += len;
            }

            if (hasValue[0]) {
                scriptedLook = new ScriptedLook(
                        values[0] & 0xFF,
                        hasValue[1] ? (values[1] & 0xFFFF) : 0,
                        hasValue[2] ? (values[2] & 0xFFFF) : 0,
                        hasValue[3] ? (values[3] & 0xFFFF) : 0,
                        hasValue[4] ? (values[4] & 0xFFFF) : 0,
                        hasValue[5] ? (values[5] & 0xFFFF) : 0,
                        hasValue[6] ? (values[6] & 0xFFFF) : 0,
                        hasValue[7] ? (values[7] & 0xFFFF) : 0,
                        hasValue[8] ? (values[8] & 0xFFFF) : 0
                );
            }
        }

        boolean[][] hide = new boolean[MAX_WEAPON_SLOTS][2];
        evaluateWeaponOps(rawDatBytes, routineChunks, "init", 0, hide);

        int hiddenWeaponMask = 0;
        for (int n = 0; n < MAX_WEAPON_SLOTS; n++) {
            if (hide[n][0] || hide[n][1]) {
                hiddenWeaponMask |= (1 << n);
            }
        }

        if (baseModelId == null && scriptedLook == null && hiddenWeaponMask == 0 && motionOps.isEmpty()) {
            return InitSchedulerResult.EMPTY;
        }

        return new InitSchedulerResult(
                Optional.ofNullable(baseModelId),
                Optional.ofNullable(scriptedLook),
                hiddenWeaponMask,
                motionOps
        );
    }

    private static void evaluateWeaponOps(
            byte[] raw,
            List<RoutineChunk> chunks,
            String name,
            int depth,
            boolean[][] hide
    ) {
        RoutineChunk target = null;
        for (RoutineChunk chunk : chunks) {
            if (chunk.name().equals(name)) {
                target = chunk;
                break;
            }
        }
        if (target == null) {
            return;
        }

        int dataStart = target.dataStart();
        int dataEnd = target.dataEnd();
        int at = readRecordStreamOffset(raw, dataStart, dataEnd);
        if (at < 0) {
            return;
        }

        while (dataStart + at + 2 <= dataEnd) {
            int pos = dataStart + at;
            int op = raw[pos] & 0xFF;
            int len = (raw[pos + 1] & 0xFF) * 4;
            if (op == 0 || len == 0 || pos + len > dataEnd) {
                break;
            }

            if (op == 0x3b && len >= 12 && depth < MAX_CALL_DEPTH) {
                String callee = normalizeChunkName(new String(raw, pos + 8, 4, StandardCharsets.ISO_8859_1));
                evaluateWeaponOps(raw, chunks, callee, depth + 1, hide);
            } else if ((op == 0x75 || op == 0xa3) && len >= 14) {
                short n = s16LE(raw, pos + 12);
                if (n >= 0 && n < MAX_WEAPON_SLOTS) {
                    hide[n][op == 0xa3 ? 1 : 0] = (u32LE(raw, pos + 8) == 1);
                }
            }

            at += len;
        }
    }

    private static int readRecordStreamOffset(byte[] raw, int dataStart, int dataEnd) {
        if (dataEnd - dataStart < 20) {
            return -1;
        }
        long at = u32LE(raw, dataStart + 16) & 0xFFFFFFFFL;
        if (at > (long) (dataEnd - dataStart)) {
            return -1;
        }
        return (int) at;
    }

    static String normalizeChunkName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int end = raw.length();
        while (end > 0) {
            char c = raw.charAt(end - 1);
            if (c == '\0' || c == ' ') {
                end--;
            } else {
                break;
            }
        }
        return raw.substring(0, end);
    }

    private static int u32LE(byte[] buf, int offset) {
        return (buf[offset] & 0xFF)
                | ((buf[offset + 1] & 0xFF) << 8)
                | ((buf[offset + 2] & 0xFF) << 16)
                | ((buf[offset + 3] & 0xFF) << 24);
    }

    private static short s16LE(byte[] buf, int offset) {
        return (short) ((buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8));
    }

    private record RoutineChunk(String name, int dataStart, int dataEnd) {
    }
}
