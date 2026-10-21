package com.ffxi.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileUtil {

    public static File resolveCaseInsensitive(File baseDir, String relativePath) {
        if (baseDir == null || relativePath == null) return null;
        Path basePath = baseDir.toPath();
        Path resolvedPath = resolveCaseInsensitive(basePath, relativePath);
        return resolvedPath != null ? resolvedPath.toFile() : null;
    }

    /**
     * Resolves a relative path against a base Path (works for any java.nio.file.Path, including Jimfs VFS)
     * in a case-insensitive manner on Unix/Linux filesystems.
     */
    public static Path resolveCaseInsensitive(Path baseDir, String relativePath) {
        if (baseDir == null || relativePath == null) return null;

        Path direct = baseDir.resolve(relativePath);
        if (Files.exists(direct)) {
            return direct;
        }

        String normalized = relativePath.replace('\\', '/');
        String[] parts = normalized.split("/");

        Path current = baseDir;
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                current = current.getParent();
                if (current == null) return null;
                continue;
            }

            Path exactChild = current.resolve(part);
            if (Files.exists(exactChild)) {
                current = exactChild;
                continue;
            }

            Path matchedChild = null;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                for (Path entry : stream) {
                    Path fileName = entry.getFileName();
                    if (fileName != null && fileName.toString().equalsIgnoreCase(part)) {
                        matchedChild = entry;
                        break;
                    }
                }
            } catch (IOException e) {
                return null;
            }

            if (matchedChild != null) {
                current = matchedChild;
            } else {
                return null;
            }
        }

        return Files.exists(current) ? current : null;
    }
}
