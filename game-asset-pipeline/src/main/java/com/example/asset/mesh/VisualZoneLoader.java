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
                texOpt.ifPresent(tex -> textures.put(tex.name().trim().toLowerCase(), tex));
            }
        }

        // 2. Second pass: decrypt and parse visual zone meshes
        for (DatSectionHeader header : sections) {
            if (header.sectionType() == SectionType.S2E_ZONE_MESH) {
                Optional<VisualZoneMesh> meshOpt = ZoneMeshParser.parseZoneMesh(header, reader);
                meshOpt.ifPresent(meshes::add);
            }
        }

        return new VisualZoneScene(textures, meshes);
    }

    public static VisualZoneScene load(InputStream stream) throws IOException {
        return load(stream.readAllBytes());
    }

    public static VisualZoneScene load(Path path) throws IOException {
        return load(Files.readAllBytes(path));
    }
}
