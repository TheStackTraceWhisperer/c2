package com.example.asset.config;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Authoritative path configuration for retail Final Fantasy XI installations.
 * Defaults to standard Wine / Lutris prefix location on Linux:
 * {@code $HOME/Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI}
 * Configurable via {@code -Dffxi.home}, {@code -Dc2.ffxi.dir}, or {@code FFXI_HOME} / {@code FFXI_DIR} environment variables.
 */
@Slf4j
public final class FfxiPathConfig {

    public static final String DEFAULT_LINUX_INSTALL_RELATIVE_PATH =
            "Games/final-fantasy-xi-online/drive_c/Program Files (x86)/PlayOnline/SquareEnix/FINAL FANTASY XI";

    private FfxiPathConfig() {
    }

    /**
     * Resolves the configured or default retail FFXI root directory.
     */
    public static Path resolveFfxiInstallDirectory() {
        // 1. System property override
        String sysProp = System.getProperty("ffxi.home");
        if (sysProp == null || sysProp.isBlank()) {
            sysProp = System.getProperty("c2.ffxi.dir");
        }
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(expandTilde(sysProp));
        }

        // 2. Environment variable override
        String env = System.getenv("FFXI_HOME");
        if (env == null || env.isBlank()) {
            env = System.getenv("FFXI_DIR");
        }
        if (env != null && !env.isBlank()) {
            return Path.of(expandTilde(env));
        }

        // 3. Default Linux Lutris / Wine install path
        String userHome = System.getProperty("user.home", System.getenv("HOME"));
        if (userHome == null || userHome.isBlank()) {
            userHome = ".";
        }
        return Path.of(userHome, DEFAULT_LINUX_INSTALL_RELATIVE_PATH);
    }

    /**
     * Resolves a relative ROM path (e.g. "ROM/27/82.DAT" or "ROM/1/31.DAT") against the FFXI install directory,
     * supporting case-insensitive directory and file lookups.
     */
    public static Optional<Path> resolveRomFile(String relativeRomPath) {
        Path installDir = resolveFfxiInstallDirectory();
        if (!Files.isDirectory(installDir)) {
            return Optional.empty();
        }

        String clean = relativeRomPath.startsWith("/") ? relativeRomPath.substring(1) : relativeRomPath;
        Path direct = installDir.resolve(clean);
        if (Files.isRegularFile(direct)) {
            return Optional.of(direct);
        }

        // Case-insensitive segment-by-segment resolution for Wine filesystems
        Path current = installDir;
        String[] segments = clean.replace('\\', '/').split("/");

        for (String segment : segments) {
            Path next = findChildCaseInsensitive(current, segment);
            if (next == null) {
                return Optional.empty();
            }
            current = next;
        }

        if (Files.isRegularFile(current)) {
            return Optional.of(current);
        }
        return Optional.empty();
    }

    private static Path findChildCaseInsensitive(Path parent, String name) {
        if (!Files.isDirectory(parent)) {
            return null;
        }
        Path direct = parent.resolve(name);
        if (Files.exists(direct)) {
            return direct;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                if (entry.getFileName().toString().equalsIgnoreCase(name)) {
                    return entry;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static String expandTilde(String path) {
        if (path.startsWith("~" + File.separator)) {
            String userHome = System.getProperty("user.home", System.getenv("HOME"));
            return userHome + path.substring(1);
        }
        return path;
    }
}
