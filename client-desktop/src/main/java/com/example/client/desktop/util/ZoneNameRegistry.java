package com.example.client.desktop.util;

import java.util.Map;

public final class ZoneNameRegistry {

    private static final Map<Integer, String> ZONE_NAMES = Map.ofEntries(
            Map.entry(100, "West Ronfaure"),
            Map.entry(101, "East Ronfaure"),
            Map.entry(102, "La Theine Plateau"),
            Map.entry(103, "Valkurm Dunes"),
            Map.entry(104, "Jugner Forest"),
            Map.entry(105, "Batallia Downs"),
            Map.entry(106, "North Gustaberg"),
            Map.entry(107, "South Gustaberg"),
            Map.entry(108, "Konschtat Highlands"),
            Map.entry(109, "Pashhow Marshlands"),
            Map.entry(110, "Rolanberry Fields"),
            Map.entry(115, "West Sarutabaruta"),
            Map.entry(116, "East Sarutabaruta"),
            Map.entry(117, "Tahrongi Canyon"),
            Map.entry(118, "Buburimu Peninsula"),
            Map.entry(119, "Meriphataud Mountains"),
            Map.entry(120, "Sauromugue Champaign"),
            Map.entry(230, "Southern San d'Oria"),
            Map.entry(231, "Northern San d'Oria"),
            Map.entry(232, "Port San d'Oria"),
            Map.entry(233, "Chateau d'Oraguille"),
            Map.entry(234, "Bastok Mines"),
            Map.entry(235, "Bastok Markets"),
            Map.entry(236, "Port Bastok"),
            Map.entry(237, "Metalworks"),
            Map.entry(238, "Windurst Waters"),
            Map.entry(239, "Windurst Walls"),
            Map.entry(240, "Port Windurst"),
            Map.entry(241, "Windurst Woods"),
            Map.entry(242, "Heavens Tower"),
            Map.entry(243, "Ru'Lude Gardens"),
            Map.entry(244, "Upper Jeuno"),
            Map.entry(245, "Lower Jeuno"),
            Map.entry(246, "Port Jeuno")
    );

    private ZoneNameRegistry() {}

    public static String getZoneName(int zoneId) {
        return ZONE_NAMES.getOrDefault(zoneId, "Zone " + zoneId);
    }

    public static java.util.List<Integer> getRegisteredZoneIds() {
        return ZONE_NAMES.keySet().stream().sorted().toList();
    }

    public static Map<Integer, String> getAllZoneNames() {
        return ZONE_NAMES;
    }
}
