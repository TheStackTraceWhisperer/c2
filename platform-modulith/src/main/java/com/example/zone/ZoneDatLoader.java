package com.example.zone;

import com.example.asset.config.FfxiPathConfig;
import com.example.config.ZoneServerProperties;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Shared loader for FFXI zone binary DAT payloads from retail installations or custom asset directories.
 */
@Slf4j
public final class ZoneDatLoader {

  private ZoneDatLoader() {}

  public static byte[] loadZoneDatBytes(int zoneId, ZoneServerProperties properties) {
    // 1. Check retail FFXI installation directory
    String relRom = (zoneId == 230) ? "ROM/1/31.DAT" : (zoneId == 100 ? "ROM/1/30.DAT" : null);
    if (relRom != null) {
      Optional<Path> installedPath = FfxiPathConfig.resolveRomFile(relRom);
      if (installedPath.isPresent() && Files.isRegularFile(installedPath.get())) {
        try {
          log.info("[ZONE-DAT] Loading zone #{} DAT from retail FFXI install: {}",
            zoneId, installedPath.get().toAbsolutePath());
          return Files.readAllBytes(installedPath.get());
        } catch (Exception e) {
          log.warn("[ZONE-DAT] Error reading installed DAT at {}", installedPath.get(), e);
        }
      }
    }

    // 2. Check configured custom asset directory if specified
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
}
