package com.example.asset.loader;

import com.example.asset.config.FfxiPathConfig;
import com.example.asset.model.DatSectionHeader;
import com.example.asset.model.SectionType;
import com.example.asset.parser.DatSectionScanner;
import com.example.asset.reader.DatByteReader;
import com.example.asset.skeleton.Skeleton;
import com.example.asset.skeleton.SkeletonAnimation;
import com.example.asset.skeleton.SkeletonAnimationParser;
import com.example.asset.skeleton.AuthenticCharacterModelDecoder;
import com.example.asset.skeleton.SkeletonParser;
import com.example.asset.skeleton.SkinnedMesh;
import com.example.asset.texture.DecodedTexture;
import com.example.asset.texture.TextureParser;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * On-demand ROM file loader with local disk caching and remote fallback to public ROM endpoints.
 * Provides unified extraction of authentic skeletons, animations, skinned meshes, and textures.
 */
@Slf4j
public class RomAssetLoader {

    public static final String DEFAULT_REMOTE_URL = "https://ximv.dev/";

    private static RomAssetLoader instance;

    private final Path cacheDirectory;
    private final String remoteBaseUrl;
    private final boolean remoteFetchEnabled;

    public RomAssetLoader() {
        this(resolveDefaultCacheDir(), DEFAULT_REMOTE_URL, shouldEnableRemoteFetch());
    }

    public RomAssetLoader(Path cacheDirectory, String remoteBaseUrl) {
        this(cacheDirectory, remoteBaseUrl, shouldEnableRemoteFetch());
    }

    public RomAssetLoader(Path cacheDirectory, String remoteBaseUrl, boolean remoteFetchEnabled) {
        this.cacheDirectory = cacheDirectory;
        this.remoteBaseUrl = remoteBaseUrl.endsWith("/") ? remoteBaseUrl : remoteBaseUrl + "/";
        this.remoteFetchEnabled = remoteFetchEnabled;
        try {
            Files.createDirectories(cacheDirectory);
        } catch (IOException e) {
            log.warn("Could not create ROM cache directory {}: {}", cacheDirectory, e.getMessage());
        }
    }

    private static boolean shouldEnableRemoteFetch() {
        String sysProp = System.getProperty("c2.rom.remote.enabled");
        if (sysProp != null && !sysProp.isBlank()) {
            return Boolean.parseBoolean(sysProp);
        }
        // If the retail installation directory is present on disk, disable remote network fetch
        Path retailDir = FfxiPathConfig.resolveFfxiInstallDirectory();
        if (Files.isDirectory(retailDir)) {
            return false;
        }
        return true;
    }

    public boolean isRemoteFetchEnabled() {
        return remoteFetchEnabled;
    }

    public static synchronized RomAssetLoader getInstance() {
        if (instance == null) {
            instance = new RomAssetLoader();
        }
        return instance;
    }

    public Path getCacheDirectory() {
        return cacheDirectory;
    }

    public String getRemoteBaseUrl() {
        return remoteBaseUrl;
    }

    private static Path resolveDefaultCacheDir() {
        String sysProp = System.getProperty("c2.rom.cache.dir");
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp);
        }
        String env = System.getenv("C2_ROM_CACHE_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        Path projectCache = Path.of("cache/roms");
        if (Files.exists(projectCache)) {
            return projectCache;
        }
        Path userHome = Path.of(System.getProperty("user.home"), ".c2/roms");
        if (Files.exists(userHome)) {
            return userHome;
        }
        return projectCache;
    }

    /**
     * Loads raw DAT bytes for the given canonical relative path (e.g. "ROM/27/82.DAT").
     */
    public byte[] loadRomBytes(String relativePath) throws IOException {
        String cleanPath = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;

        // 1. Check retail FFXI installation directory ($HOME/Games/final-fantasy-xi-online/...)
        Optional<Path> installedFile = FfxiPathConfig.resolveRomFile(cleanPath);
        if (installedFile.isPresent()) {
            Path path = installedFile.get();
            try {
                byte[] data = Files.readAllBytes(path);
                if (data.length > 0) {
                    log.info("Loaded ROM asset from retail FFXI directory: {}", path);
                    return data;
                }
            } catch (IOException e) {
                log.warn("Failed reading installed FFXI file {}: {}", path, e.getMessage());
            }
        }

        // 2. Check local cache directory
        Path cachedFile = cacheDirectory.resolve(cleanPath);
        if (Files.exists(cachedFile) && Files.size(cachedFile) > 0) {
            return Files.readAllBytes(cachedFile);
        }

        // 3. Check classpath
        String resourcePath = "/" + cleanPath;
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in != null) {
                byte[] data = in.readAllBytes();
                if (data.length > 0) {
                    return data;
                }
            }
        }

        // Check alternate classpath location (/dats/...)
        String datsResource = "/dats/" + cleanPath;
        try (InputStream in = getClass().getResourceAsStream(datsResource)) {
            if (in != null) {
                byte[] data = in.readAllBytes();
                if (data.length > 0) {
                    return data;
                }
            }
        }

        // 4. Remote fallback download (only if enabled)
        if (!remoteFetchEnabled) {
            throw new FileNotFoundException("ROM asset '" + cleanPath + "' not found in retail FFXI installation or local cache (remote fetching disabled)");
        }

        String urlString = remoteBaseUrl + cleanPath;
        log.info("Fetching authentic ROM asset from remote: {}", urlString);
        try {
            URI uri = URI.create(urlString);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
            conn.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
            conn.setRequestProperty("User-Agent", "C2-Engine/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                byte[] data;
                try (InputStream stream = conn.getInputStream()) {
                    data = stream.readAllBytes();
                }
                // Save to local cache
                try {
                    Files.createDirectories(cachedFile.getParent());
                    Files.write(cachedFile, data);
                    log.info("Saved {} bytes to local cache: {}", data.length, cachedFile);
                } catch (IOException ioEx) {
                    log.warn("Failed to write cached file {}: {}", cachedFile, ioEx.getMessage());
                }
                return data;
            } else {
                throw new IOException("Remote server returned HTTP " + responseCode + " for " + urlString);
            }
        } catch (Exception e) {
            throw new IOException("Unable to load ROM asset '" + cleanPath + "': " + e.getMessage(), e);
        }
    }

    /**
     * Loads a DatByteReader for the given relative ROM path.
     */
    public DatByteReader loadRomDat(String relativePath) throws IOException {
        byte[] bytes = loadRomBytes(relativePath);
        return new DatByteReader(bytes);
    }

    /**
     * Loads the authentic skeleton (0x29) from a ROM file if present.
     */
    public Optional<Skeleton> loadSkeleton(String relativePath) {
        try {
            DatByteReader reader = loadRomDat(relativePath);
            List<DatSectionHeader> headers = DatSectionScanner.scanSections(reader);
            for (DatSectionHeader header : headers) {
                if (header.sectionType() == SectionType.S29_SKELETON) {
                    DatByteReader payload = DatSectionScanner.extractPayload(reader, header);
                    return SkeletonParser.parseSkeleton(header, payload);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load skeleton from {}: {}", relativePath, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Loads all authentic S2B (0x2B) animations from a ROM file.
     */
    public Map<String, SkeletonAnimation> loadAnimations(String relativePath) {
        Map<String, SkeletonAnimation> animations = new HashMap<>();
        try {
            DatByteReader reader = loadRomDat(relativePath);
            List<DatSectionHeader> headers = DatSectionScanner.scanSections(reader);
            for (DatSectionHeader header : headers) {
                if (header.sectionType() == SectionType.S2B_SKELETON_ANIMATION) {
                    DatByteReader payload = DatSectionScanner.extractPayload(reader, header);
                    SkeletonAnimationParser.parse(header, payload).ifPresent(anim -> {
                        String name = header.sectionId().trim().toLowerCase();
                        animations.put(name, anim);
                    });
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load animations from {}: {}", relativePath, e.getMessage());
        }
        return Collections.unmodifiableMap(animations);
    }

    /**
     * Loads a specific animation by tag (e.g. "idl0", "wlk0", "run0").
     */
    public Optional<SkeletonAnimation> loadAnimation(String relativePath, String animTag) {
        Map<String, SkeletonAnimation> anims = loadAnimations(relativePath);
        SkeletonAnimation anim = anims.get(animTag.trim().toLowerCase());
        return Optional.ofNullable(anim);
    }

    /**
     * Loads all authentic S2A / S25 skinned meshes from a ROM file.
     */
    public Optional<SkinnedMesh> loadSkinnedMesh(String relativePath) {
        try {
            byte[] bytes = loadRomBytes(relativePath);
            SkinnedMesh mesh = AuthenticCharacterModelDecoder.decodeModelChunk(bytes, null, relativePath);
            return mesh.subMeshes().isEmpty() ? Optional.empty() : Optional.of(mesh);
        } catch (Exception e) {
            log.warn("Failed to load skinned mesh from {}: {}", relativePath, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Loads all decoded textures (0x20) from a ROM file.
     */
    public Map<String, DecodedTexture> loadTextures(String relativePath) {
        Map<String, DecodedTexture> textures = new HashMap<>();
        try {
            DatByteReader reader = loadRomDat(relativePath);
            List<DatSectionHeader> headers = DatSectionScanner.scanSections(reader);
            for (DatSectionHeader header : headers) {
                if (header.sectionType() == SectionType.S20_TEXTURE) {
                    DatByteReader payload = DatSectionScanner.extractPayload(reader, header);
                    TextureParser.parseTexture(payload).ifPresent(tex -> {
                        String name = tex.name() != null ? tex.name().trim().toLowerCase() : header.sectionId().trim().toLowerCase();
                        textures.put(name, tex);
                        // Also index under header ID
                        textures.put(header.sectionId().trim().toLowerCase(), tex);
                    });
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load textures from {}: {}", relativePath, e.getMessage());
        }
        return Collections.unmodifiableMap(textures);
    }
}
