package com.ffxi.tables;

import com.ffxi.io.FileTableManager;

public final class ZoneIdToResourceId {
    private ZoneIdToResourceId() {
    }

    public static String getMainAreaResourcePath(ZoneConfig zone, FileTableManager ftm) {
        int index = zone.zoneId() < 0x100
                ? 0x64 + zone.zoneId()
                : 0x147B3 + (zone.zoneId() - 0x100);
        return ftm.getFilePath(index);
    }

    public static String getSubAreaResourcePath(int subAreaId, FileTableManager ftm) {
        int offset = subAreaId < 0x271 ? 0x64 : (0x14768 - 0x271);
        return ftm.getFilePath(subAreaId + offset);
    }

    public static String getMainAreaBumpMapPath(int zoneId, FileTableManager ftm) {
        int offset = zoneId < 0x100 ? 0x9B97 : 0x149D3;
        return ftm.getFilePath(offset + zoneId);
    }
}
