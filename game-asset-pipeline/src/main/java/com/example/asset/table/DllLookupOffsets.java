package com.example.asset.table;

public record DllLookupOffsets(
        int equipmentLookupTableOffset,
        int raceConfigLookupTableOffset,
        int weaponSkillAnimationFileTableOffset,
        int danceSkillAnimationFileTableOffset,
        int actionAnimationFileTableOffset,
        int fishingRodFileTableOffset,
        int zoneDecryptTable1Offset,
        int zoneDecryptTable2Offset,
        int zoneMapTableOffset
) {
}
