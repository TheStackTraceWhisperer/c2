package com.example.zone;

import com.example.asset.config.FfxiPathConfig;
import com.example.config.ZoneServerProperties;
import com.ffxi.io.FileTableManager;
import com.ffxi.tables.ZoneConfig;
import com.ffxi.tables.ZoneIdToResourceId;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared loader for FFXI zone binary DAT payloads from retail installations or custom asset directories.
 */
@Slf4j
public final class ZoneDatLoader {

  private static final Map<Path, FileTableManager> FILE_TABLE_CACHE = new ConcurrentHashMap<>();

  private ZoneDatLoader() {}

  public static byte[] loadZoneDatBytes(int zoneId, ZoneServerProperties properties) {
    // 1. Resolve via retail VTABLE/FTABLE index first so every zone maps to its true main-area DAT
    Path ffxiRoot = FfxiPathConfig.resolveFfxiInstallDirectory();
    if (ffxiRoot != null && Files.isDirectory(ffxiRoot)) {
      FileTableManager ftm = getOrLoadFileTableManager(ffxiRoot);
      if (ftm != null) {
        try {
          String zonePath = ZoneIdToResourceId.getMainAreaResourcePath(new ZoneConfig(zoneId), ftm);
          if (zonePath != null && !zonePath.isBlank()) {
            Path resolved = Path.of(zonePath);
            if (Files.isRegularFile(resolved)) {
              log.info("[ZONE-DAT] Loading zone #{} DAT from retail FFXI index: {}",
                zoneId, resolved.toAbsolutePath());
              return Files.readAllBytes(resolved);
            }
          }
        } catch (Exception e) {
          log.warn("[ZONE-DAT] Error resolving indexed DAT for zone #{}: {}", zoneId, e.getMessage());
        }
      }
    }

    // 2. Fallback direct retail ROM paths when VTABLE/FTABLE are unavailable
    String relRom = (zoneId == 230) ? "ROM/1/31.DAT" : (zoneId == 100 ? "ROM/0/120.DAT" : null);
    if (relRom != null) {
      Optional<Path> installedPath = FfxiPathConfig.resolveRomFile(relRom);
      if (installedPath.isPresent() && Files.isRegularFile(installedPath.get())) {
        try {
          log.info("[ZONE-DAT] Loading zone #{} DAT from retail FFXI fallback: {}",
            zoneId, installedPath.get().toAbsolutePath());
          return Files.readAllBytes(installedPath.get());
        } catch (Exception e) {
          log.warn("[ZONE-DAT] Error reading installed DAT at {}", installedPath.get(), e);
        }
      }
    }

    // 3. Check configured custom asset directory if specified
    String configuredDir = properties != null ? properties.getAssetPath() : null;
    if (configuredDir != null && !configuredDir.isBlank()) {
      String filename = switch (zoneId) {
        case 230 -> "south_san_doria_230.DAT";
        case 100 -> "west_ronfaure_100.DAT";
        default -> "zone_" + zoneId + ".DAT";
      };
      List<Path> candidates = List.of(
        Path.of(configuredDir, "zones", filename),
        Path.of(configuredDir, filename)
      );
      for (Path p : candidates) {
        if (Files.isRegularFile(p)) {
          try {
            log.debug("[ZONE-DAT] Reading DAT asset from {}", p.toAbsolutePath());
            return Files.readAllBytes(p);
          } catch (Exception e) {
            log.warn("[ZONE-DAT] Error reading DAT file at {}", p, e);
          }
        }
      }
    }

    return null;
  }

  private static FileTableManager getOrLoadFileTableManager(Path ffxiRoot) {
    Path normalized = ffxiRoot.toAbsolutePath().normalize();
    FileTableManager cached = FILE_TABLE_CACHE.get(normalized);
    if (cached != null) {
      return cached;
    }
    synchronized (FILE_TABLE_CACHE) {
      cached = FILE_TABLE_CACHE.get(normalized);
      if (cached != null) {
        return cached;
      }
      try {
        FileTableManager ftm = new FileTableManager();
        ftm.loadFromPath(normalized);
        if (!ftm.isFullyLoaded()) {
          return null;
        }
        FILE_TABLE_CACHE.put(normalized, ftm);
        return ftm;
      } catch (Exception e) {
        log.debug("[ZONE-DAT] Unable to load FFXI VTABLE/FTABLE from {}: {}", normalized, e.getMessage());
        return null;
      }
    }
  }
}
