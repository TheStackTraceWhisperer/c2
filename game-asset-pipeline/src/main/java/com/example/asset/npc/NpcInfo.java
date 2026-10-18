package com.example.asset.npc;

import com.example.asset.geometry.Vector3f;

import java.util.Arrays;

/**
 * Metadata and spawn definition for a non-player character (NPC) in a zone.
 */
public record NpcInfo(
        int id,
        int zoneId,
        float rotationRadians,
        Vector3f position,
        int flag,
        int nameVis,
        int status,
        int entityFlags,
        int modelType,
        int modelId,
        int[] equipment,
        int datId,
        int nameId
) {
    public boolean hasShadow() {
        return (flag & 0x8000) == 0;
    }

    public boolean shouldDisplayNameAndHp() {
        return (entityFlags & 0x0008) != 0;
    }

    public boolean isShip() {
        return modelType == 0x04;
    }

    public boolean isDefaultDisabled() {
        return status == 0x02 || status == 0x06;
    }

    @Override
    public String toString() {
        return "NpcInfo{" +
                "id=" + id +
                ", zoneId=" + zoneId +
                ", pos=" + position +
                ", rot=" + rotationRadians +
                ", modelType=" + modelType +
                ", modelId=" + modelId +
                ", equip=" + Arrays.toString(equipment) +
                '}';
    }
}
