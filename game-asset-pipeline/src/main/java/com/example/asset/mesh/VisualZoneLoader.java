package com.example.asset.mesh;

import com.example.asset.model.DatSectionHeader;
import com.example.asset.model.SectionType;
import com.example.asset.parser.DatSectionScanner;
import com.example.asset.reader.DatByteReader;
import com.example.asset.texture.DecodedTexture;
import com.example.asset.texture.TextureParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and constructs complete VisualZoneScene instances from DAT binaries.
 */
public final class VisualZoneLoader {

    private VisualZoneLoader() {
    }

    public static VisualZoneScene load(byte[] datBytes) {
        DatByteReader reader = new DatByteReader(datBytes);
        List<DatSectionHeader> sections = DatSectionScanner.scanSections(reader);

        Map<String, DecodedTexture> textures = new HashMap<>();
        List<VisualZoneMesh> meshes = new ArrayList<>();

        // 1. First pass: extract and decode textures
        for (DatSectionHeader header : sections) {
            if (header.sectionType() == SectionType.S20_TEXTURE) {
                DatByteReader payload = DatSectionScanner.extractPayload(reader, header);
                Optional<DecodedTexture> texOpt = TextureParser.parseTexture(payload);
                if (texOpt.isPresent()) {
                    DecodedTexture tex = texOpt.get();
                    String raw = tex.name().trim();
                    String lower = raw.toLowerCase();
                    textures.put(lower, tex);
                    textures.put(raw, tex);

                    // Also index short name if prefixed with "model "
                    if (lower.startsWith("model ")) {
                        String shortName = lower.substring(6).trim();
                        textures.putIfAbsent(shortName, tex);
                    }
                    String[] parts = lower.split("\\s+");
                    if (parts.length > 1) {
                        textures.putIfAbsent(parts[parts.length - 1], tex);
                    }
                }
            }
        }

        // 2. Second pass: decrypt and parse visual zone meshes
        for (DatSectionHeader header : sections) {
            if (header.sectionType() == SectionType.S2E_ZONE_MESH) {
                Optional<VisualZoneMesh> meshOpt = ZoneMeshParser.parseZoneMesh(header, reader);
                meshOpt.ifPresent(meshes::add);
            }
        }

        // 3. Third pass: parse placed ZoneObjects from S1C_ZONE_DEF
        List<ZoneObject> zoneObjects = new ArrayList<>();
        for (DatSectionHeader header : sections) {
            if (header.sectionType() == SectionType.S1C_ZONE_DEF) {
                zoneObjects.addAll(com.example.asset.parser.ZoneDefParser.parseZoneObjects(reader, header));
            }
        }

        return new VisualZoneScene(textures, meshes, zoneObjects);
    }

    public static VisualZoneScene load(InputStream stream) throws IOException {
        return load(stream.readAllBytes());
    }

    public static VisualZoneScene load(Path path) throws IOException {
        return load(Files.readAllBytes(path));
    }
}
