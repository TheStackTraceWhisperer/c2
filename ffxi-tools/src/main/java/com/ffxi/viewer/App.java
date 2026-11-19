package com.ffxi.viewer;

import lombok.extern.slf4j.Slf4j;
import com.example.asset.config.FfxiPathConfig;
import com.ffxi.crypto.DllLookupExtractor;
import com.ffxi.crypto.DllLookupOffsets;
import com.ffxi.crypto.KeyTables;
import com.ffxi.io.ByteReader;
import com.ffxi.io.FileTableManager;
import com.ffxi.io.FileUtil;
import com.ffxi.parser.DatParser;
import com.ffxi.parser.SectionHeader;
import com.ffxi.model.DatResourceGraph;
import com.ffxi.export.DatResourceGraphJsonExporter;
import com.ffxi.tables.ZoneNameTable;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

@Slf4j
public class App {
    public static final String DEFAULT_FFXI_PATH = FfxiPathConfig.resolveFfxiInstallDirectory().toString();

    public static void main(String[] args) {
        log.info("==================================================");
        log.info("   FFXI Viewer Java - Linux / WINE Core Engine    ");
        log.info("==================================================");

        String ffxiPath = (args.length > 0 && !args[0].trim().isEmpty()) ? args[0] : DEFAULT_FFXI_PATH;
        File rootDir = new File(ffxiPath);

        log.info("{}", "Target FFXI Installation Path: " + rootDir.getAbsolutePath());
        if (!rootDir.exists()) {
            log.info("{}", "[WARN] FFXI installation path does not exist: " + rootDir.getAbsolutePath());
            return;
        }

        // 1. Try loading FFXiMain.dll to dynamically extract KeyTables
        File dllFile = FileUtil.resolveCaseInsensitive(rootDir, "FFXiMain.dll");
        if (dllFile != null && dllFile.exists()) {
            try {
                byte[] dllBytes = Files.readAllBytes(dllFile.toPath());
                ByteReader dllReader = new ByteReader(dllBytes, dllFile.getName());
                KeyTables.loadFromDll(dllReader);
                log.info("{}", "[SUCCESS] Successfully extracted key tables from " + dllFile.getName() + " (" + dllBytes.length + " bytes)");
                DllLookupOffsets lookupOffsets = DllLookupExtractor.loadFromDll(new ByteReader(dllBytes, dllFile.getName()));
                printDllLookupOffsets(lookupOffsets);
            } catch (Exception e) {
                log.info("{}", "[WARN] Could not read FFXiMain.dll: " + e.getMessage());
            }
        } else {
            log.info("[INFO] FFXiMain.dll not found in root directory. Using fallback key tables.");
        }

        // 2. Load File Tables (VTABLE & FTABLE)
        FileTableManager tableManager = new FileTableManager();
        try {
            tableManager.loadFromDirectory(rootDir);
            log.info("{}", "[SUCCESS] Loaded " + tableManager.getLoadedVTables() + " VTABLEs and "
                    + tableManager.getLoadedFTables() + " FTABLEs from FFXI installation.");

            File zoneNameDat = new File(rootDir, ZoneNameTable.RESOURCE_PATH);
            if (zoneNameDat.exists()) {
                ZoneNameTable zoneNames = ZoneNameTable.load(Files.readAllBytes(zoneNameDat.toPath()));
                log.info("{}", "[SUCCESS] Loaded " + zoneNames.size() + " zone names from ROM/165/84.DAT");
                for (int i = 0; i < Math.min(10, zoneNames.size()); i++) {
                    String name = zoneNames.getName(i);
                    if (!name.isBlank()) {
                        log.info("{}", "  Zone " + i + ": " + name);
                    }
                }
            }

            // Resolve sample DAT file paths (e.g. File IDs 0x0001, 0x0064, 0x0100)
            int[] testFileIds = new int[]{ 1, 100, 500, 1000 };
            for (int fileId : testFileIds) {
                String datPath = tableManager.getFilePath(fileId);
                log.info("{}", "  - File ID " + fileId + " -> " + (datPath != null ? datPath : "NOT FOUND (Unassigned ID slot in VTABLE)"));

                if (datPath != null) {
                    File actualFile = new File(datPath);
                    if (actualFile.exists()) {
                        byte[] rawBytes = Files.readAllBytes(actualFile.toPath());
                        ByteReader datReader = new ByteReader(rawBytes, actualFile.getName());
                        List<SectionHeader> sections = DatParser.parseSections(datReader);
                        log.info("{}", "    --> Parsed " + sections.size() + " section headers in " + actualFile.getName() + " (" + rawBytes.length + " bytes)");
                        for (int i = 0; i < Math.min(3, sections.size()); i++) {
                            log.info("{}", "        [" + i + "] " + sections.get(i));
                        }
                        DatResourceGraph graph = DatParser.parseResourceGraph(new ByteReader(rawBytes, actualFile.getName()));
                        String graphJson = DatResourceGraphJsonExporter.toJson(graph);
                        log.info("    --> Resource Graph JSON:");
                        log.info("{}", graphJson);
                    }
                }
            }

            // Find first active DAT file ID >= 1000
            for (int id = 1000; id < 1050; id++) {
                String path = tableManager.getFilePath(id);
                if (path != null) {
                    log.info("{}", "  - Next Active File ID after 1000: File ID " + id + " -> " + path);
                    break;
                }
            }
        } catch (Exception e) {
            log.info("{}", "[ERROR] Failed to load FFXI file tables: " + e.getMessage());
            log.error("Unexpected error", e);
        }

        log.info("==================================================");
        log.info("   FFXI Viewer Real DAT Inspection Complete       ");
        log.info("==================================================");
    }

    private static void printDllLookupOffsets(DllLookupOffsets offsets) {
        log.info("  - Extracted DLL lookup offsets:");
        printOffset("equipmentLookupTableOffset", offsets.equipmentLookupTableOffset());
        printOffset("raceConfigLookupTableOffset", offsets.raceConfigLookupTableOffset());
        printOffset("weaponSkillAnimationFileTableOffset", offsets.weaponSkillAnimationFileTableOffset());
        printOffset("danceSkillAnimationFileTableOffset", offsets.danceSkillAnimationFileTableOffset());
        printOffset("actionAnimationFileTableOffset", offsets.actionAnimationFileTableOffset());
        printOffset("fishingRodFileTableOffset", offsets.fishingRodFileTableOffset());
        printOffset("zoneDecryptTable1Offset", offsets.zoneDecryptTable1Offset());
        printOffset("zoneDecryptTable2Offset", offsets.zoneDecryptTable2Offset());
        printOffset("zoneMapTableOffset", offsets.zoneMapTableOffset());
    }

    private static void printOffset(String name, int value) {
        if (value >= 0) {
            log.info("{}", "      " + name + " = " + value + " (0x" + Integer.toHexString(value) + ")");
        } else {
            log.info("{}", "      " + name + " = NOT FOUND");
        }
    }
}
