package com.example.asset.npc;

import com.example.asset.geometry.Vector3f;
import com.example.asset.reader.DatByteReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and indexes all NPC spawn configurations from NpcTable-V3.DAT.
 */
public final class NpcTable {

    private static final int RECORD_SIZE = 0x34; // 52 bytes

    private final Map<Integer, NpcInfo> npcsById;
    private final Map<Integer, List<NpcInfo>> npcsByZoneId;

    public NpcTable(Map<Integer, NpcInfo> npcsById, Map<Integer, List<NpcInfo>> npcsByZoneId) {
        this.npcsById = Collections.unmodifiableMap(npcsById);
        Map<Integer, List<NpcInfo>> unmodifiableByZone = new HashMap<>();
        for (Map.Entry<Integer, List<NpcInfo>> entry : npcsByZoneId.entrySet()) {
            unmodifiableByZone.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        this.npcsByZoneId = Collections.unmodifiableMap(unmodifiableByZone);
    }

    public static NpcTable load(byte[] data) {
        DatByteReader reader = new DatByteReader(data);
        Map<Integer, NpcInfo> byId = new HashMap<>();
        Map<Integer, List<NpcInfo>> byZone = new HashMap<>();

        while (reader.remaining() >= RECORD_SIZE) {
            int startPos = reader.position();

            try {
                int id = reader.nextI32();
                float rawRotation = reader.nextFloat();
                float rotationRadians = (rawRotation / 255.0f) * 2.0f * (float) Math.PI;

                Vector3f position = reader.nextVector3f();
                int flag = reader.nextU16();
                int nameVis = reader.nextU8();
                int status = reader.nextU8();
                int entityFlags = reader.nextU16();

                int modelType = reader.nextU16();
                int modelId = reader.nextU16();

                int[] equipment = new int[8];
                for (int i = 0; i < 8; i++) {
                    equipment[i] = reader.nextU16();
                }

                int datId = reader.nextI32();
                int nameId = reader.nextU16();

                int zoneId = (id >>> 12) & 0xFFF;

                NpcInfo info = new NpcInfo(
                        id,
                        zoneId,
                        rotationRadians,
                        position,
                        flag,
                        nameVis,
                        status,
                        entityFlags,
                        modelType,
                        modelId,
                        equipment,
                        datId,
                        nameId
                );

                byId.put(id, info);
                byZone.computeIfAbsent(zoneId, k -> new ArrayList<>()).add(info);
            } catch (Exception ignored) {
            }

            reader.position(startPos + RECORD_SIZE);
        }

        return new NpcTable(byId, byZone);
    }

    public static NpcTable load(InputStream stream) throws IOException {
        return load(stream.readAllBytes());
    }

    public static NpcTable load(Path path) throws IOException {
        return load(Files.readAllBytes(path));
    }

    public Optional<NpcInfo> getById(int npcId) {
        return Optional.ofNullable(npcsById.get(npcId));
    }

    public List<NpcInfo> getByZone(int zoneId) {
        return npcsByZoneId.getOrDefault(zoneId, List.of());
    }

    public int totalNpcCount() {
        return npcsById.size();
    }

    public int totalZoneCount() {
        return npcsByZoneId.size();
    }
}
