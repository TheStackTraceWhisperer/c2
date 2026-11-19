package com.example.client.desktop.util;

import com.example.asset.config.FfxiPathConfig;
import com.ffxi.tables.ZoneNameTable;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Zone display-name lookup. Names are resolved from the retail zone name string table
 * ({@link ZoneNameTable}, {@code ROM/165/84.DAT}) when a retail installation is available;
 * otherwise a generic {@code "Zone <id>"} label is returned. The registry only keeps the
 * curated list of warp-selectable zone ids; it no longer duplicates retail names.
 */
@Slf4j
public final class ZoneNameRegistry {

    /** Curated zone ids offered by the debug warp list / zone dropdowns. */
    private static final List<Integer> REGISTERED_ZONE_IDS = List.of(
            100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110,
            115, 116, 117, 118, 119, 120,
            230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241,
            242, 243, 244, 245, 246);

    private static volatile ZoneNameTable table;
    private static volatile boolean tableLoadAttempted;

    private ZoneNameRegistry() {}

    public static String getZoneName(int zoneId) {
        ZoneNameTable t = zoneNameTable();
        if (t != null) {
            String name = t.getName(zoneId);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return "Zone " + zoneId;
    }

    public static List<Integer> getRegisteredZoneIds() {
        return REGISTERED_ZONE_IDS;
    }

    public static Map<Integer, String> getAllZoneNames() {
        Map<Integer, String> names = new LinkedHashMap<>();
        for (int id : REGISTERED_ZONE_IDS) {
            names.put(id, getZoneName(id));
        }
        return names;
    }

    private static ZoneNameTable zoneNameTable() {
        if (!tableLoadAttempted) {
            synchronized (ZoneNameRegistry.class) {
                if (!tableLoadAttempted) {
                    table = loadTable();
                    tableLoadAttempted = true;
                }
            }
        }
        return table;
    }

    private static ZoneNameTable loadTable() {
        try {
            Optional<Path> dat = FfxiPathConfig.resolveRomFile(ZoneNameTable.RESOURCE_PATH);
            if (dat.isPresent() && Files.isRegularFile(dat.get())) {
                return ZoneNameTable.load(Files.readAllBytes(dat.get()));
            }
        } catch (Exception e) {
            log.warn("Unable to load retail zone name table: {}", e.toString());
        }
        return null;
    }
}
