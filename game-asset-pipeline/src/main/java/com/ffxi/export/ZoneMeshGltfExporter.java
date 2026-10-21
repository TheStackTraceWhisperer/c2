package com.ffxi.export;

import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.ResourceNode;
import com.ffxi.model.TextureResource;
import com.ffxi.model.ZoneDefResource;
import com.ffxi.model.ZoneMeshChunk;
import com.ffxi.model.ZoneMeshResource;
import com.ffxi.model.ZoneMeshVertex;
import com.ffxi.model.ZoneObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

public final class ZoneMeshGltfExporter {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_VERSION = 2;
    private static final int JSON_CHUNK_TYPE = 0x4E4F534A;
    private static final int BIN_CHUNK_TYPE = 0x004E4942;

    private static final int COMPONENT_TYPE_FLOAT = 5126;
    private static final int COMPONENT_TYPE_UNSIGNED_SHORT = 5123;
    private static final int COMPONENT_TYPE_UNSIGNED_INT = 5125;
    private static final int MODE_TRIANGLES = 4;
    private static final int TARGET_ARRAY_BUFFER = 34962;
    private static final int TARGET_ELEMENT_ARRAY_BUFFER = 34963;

    private static final List<String> SKY_PREFIXES = List.of(
            "sun", "moon", "star", "cld", "clod", "cloud", "sky", "fog", "suny"
    );

    private ZoneMeshGltfExporter() {
    }

    public static ExportSummary export(DatResourceGraph graph, byte[] rawData, Path glbFile) throws IOException {
        return export(graph, rawData, glbFile, null);
    }

    /**
     * @param debugMeshFilter if non-null, only export meshes whose trimmed name is in this set.
     *                        When set, UVs and materials are omitted (geometry-only mode for inspection).
     */
    public static ExportSummary export(DatResourceGraph graph, byte[] rawData, Path glbFile,
            java.util.Set<String> debugMeshFilter) throws IOException {
        boolean geometryOnly = debugMeshFilter != null;

        // Collect TextureResources so we can decode and embed PNG bytes
        Map<String, TextureResource> texturesByName = new LinkedHashMap<>();
        if (!geometryOnly) {
            collectTextureResources(graph.rootDirectory(), texturesByName);
        }

        Map<String, ZoneMeshResource> meshesByName = new LinkedHashMap<>();
        List<ZoneMeshResource> allMeshes = new ArrayList<>();
        collectZoneMeshes(graph.rootDirectory(), allMeshes);
        for (ZoneMeshResource mesh : allMeshes) {
            String rawName = mesh.meshName();
            String name = rawName.trim();
            name = name.replaceAll("[\\x00-\\x1F]", "");

            if (debugMeshFilter != null) {
                final String finalName = name;
                // If any filter starts with this mesh name (e.g. filter "jime_d_m_9" starts with "jime_d_m")
                boolean possibleMatch = debugMeshFilter.stream().anyMatch(f -> f.toLowerCase(Locale.ROOT).startsWith(finalName.toLowerCase(Locale.ROOT)));
                if (!possibleMatch) continue;
            }
            meshesByName.put(name, mesh);
        }

        Map<String, List<ZoneObject>> instancesByMeshName = new HashMap<>();
        collectZoneObjectsMulti(graph.rootDirectory(), instancesByMeshName);

        ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();
        List<BufferViewDef> bufferViews = new ArrayList<>();
        List<AccessorDef> accessors = new ArrayList<>();
        Map<String, byte[]> pngByTextureName = geometryOnly
                ? new HashMap<>()
                : decodePngs(texturesByName, rawData);
        MaterialRegistry materials = new MaterialRegistry(pngByTextureName, binaryBuffer, bufferViews);
        List<MeshDef> meshes = new ArrayList<>();
        Map<String, Integer> meshIndexByName = new LinkedHashMap<>();
        Map<String, Integer> triangleCountByMeshName = new HashMap<>();

        for (Map.Entry<String, ZoneMeshResource> entry : meshesByName.entrySet()) {
            String meshName = entry.getKey();
            ZoneMeshResource mesh = entry.getValue();
            List<PrimitiveDef> primitives = new ArrayList<>();
            int triangleCount = 0;

            for (ZoneMeshChunk chunk : mesh.meshChunks()) {
                PrimitiveBuildResult primitive = geometryOnly
                        ? writePrimitiveGeometryOnly(chunk, binaryBuffer, bufferViews, accessors)
                        : writePrimitive(chunk, meshName, binaryBuffer, bufferViews, accessors, materials);
                if (primitive == null) {
                    continue;
                }
                primitives.add(primitive.primitive());
                triangleCount += primitive.triangleCount();
            }

            if (primitives.isEmpty()) {
                continue;
            }

            meshIndexByName.put(meshName, meshes.size());
            meshes.add(new MeshDef(displayName(meshName, "mesh_" + meshes.size()), primitives));
            triangleCountByMeshName.put(meshName, triangleCount);
        }

        List<NodeDef> nodes = new ArrayList<>();
        // No root transform node — coordinate conversion is applied per-vertex and per-instance.
        // FFXI uses UP=(0,-1,0) with frontFace(CW). To convert to glTF (Y-up, CCW):
        //   position: (-x, -y, z)  [negate X and Y — 180° rotation around Z, preserves handedness]
        //   winding:  swap i1↔i2   [CW→CCW, because negate-XY alone doesn't flip handedness]
        //   UV V:     1-v           [WebGL V=0=bottom → glTF V=0=top]
        int zoneNodeIndex = addNode(nodes, NodeDef.group("zone"));
        int totalInstances = 0;
        int totalTriangles = 0;
        for (Map.Entry<String, Integer> meshEntry : meshIndexByName.entrySet()) {
            String meshName = meshEntry.getKey();
            int meshIndex = meshEntry.getValue();
            int triangleCount = triangleCountByMeshName.getOrDefault(meshName, 0);
            List<ZoneObject> objects = instancesByMeshName.get(meshName);

            // Skip meshes with no ZoneObject references
            if (objects == null || objects.isEmpty()) {
                continue;
            }

            // Group node for all instances of this mesh
            String groupName = displayName(meshName, "mesh");
            int meshGroupIndex = addNode(nodes, NodeDef.group(groupName));
            nodes.get(zoneNodeIndex).children().add(meshGroupIndex);

            for (int instanceIdx = 0; instanceIdx < objects.size(); instanceIdx++) {
                ZoneObject object = objects.get(instanceIdx);
                String instanceName = groupName + "_" + instanceIdx;

                if (debugMeshFilter != null) {
                    boolean match = debugMeshFilter.contains(instanceName) || debugMeshFilter.contains(meshName);
                    if (!match) continue;
                }

                NodeExtras extras = new NodeExtras(
                        object.index(),
                        sanitizeFinite(object.highDefThreshold()),
                        sanitizeFinite(object.midDefThreshold()),
                        sanitizeFinite(object.lowDefThreshold())
                );
                NodeDef node = NodeDef.meshNode(
                        instanceName,
                        meshIndex,
                        // FFXI→glTF coordinate conversion: negate X and Y
                        new float[]{sanitizeFinite(-object.posX()), sanitizeFinite(-object.posY()), sanitizeFinite(object.posZ())},
                        negateXYQuaternion(quaternionFromZYXEuler(object.rotX(), object.rotY(), object.rotZ())),
                        scaleVector(object.scaleX(), object.scaleY(), object.scaleZ()),
                        extras
                );
                int nodeIndex = addNode(nodes, node);
                nodes.get(meshGroupIndex).children().add(nodeIndex);
                totalInstances++;
                totalTriangles += triangleCount;
            }
        }

        String json = buildJson(graph, nodes, meshes, materials, bufferViews, accessors, binaryBuffer.size(), zoneNodeIndex);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] paddedJson = padTo4(jsonBytes, (byte) 0x20);
        byte[] paddedBinary = padTo4(binaryBuffer.toByteArray(), (byte) 0x00);

        ByteBuffer glb = ByteBuffer.allocate(12 + 8 + paddedJson.length + 8 + paddedBinary.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(GLB_MAGIC);
        glb.putInt(GLB_VERSION);
        glb.putInt(glb.capacity());
        glb.putInt(paddedJson.length);
        glb.putInt(JSON_CHUNK_TYPE);
        glb.put(paddedJson);
        glb.putInt(paddedBinary.length);
        glb.putInt(BIN_CHUNK_TYPE);
        glb.put(paddedBinary);

        if (glbFile.getParent() != null) {
            Files.createDirectories(glbFile.getParent());
        }
        Files.write(glbFile, glb.array());
        return new ExportSummary(meshes.size(), totalInstances, totalTriangles);
    }

    private static PrimitiveBuildResult writePrimitive(
            ZoneMeshChunk chunk,
            String meshName,
            ByteArrayOutputStream binaryBuffer,
            List<BufferViewDef> bufferViews,
            List<AccessorDef> accessors,
            MaterialRegistry materials
    ) {
        if (!hasValidVertices(chunk)) {
            return null;
        }

        List<Integer> expandedIndices = expandTriangles(chunk);
        if (expandedIndices.isEmpty()) {
            return null;
        }

        List<ZoneMeshVertex> vertices = chunk.vertices();
        FloatBounds positionBounds = new FloatBounds();

        int positionOffset = binaryBuffer.size();
        for (ZoneMeshVertex vertex : vertices) {
            // FFXI→glTF coordinate conversion: negate X and Y
            // XIM uses UP=(0,-1,0) with frontFace(CW). Negating X and Y maps
            // FFXI visual-up(-Y)→glTF Y-up, and matches XIM's camera handedness.
            float px = sanitizeFinite(-vertex.px());
            float py = sanitizeFinite(-vertex.py());
            float pz = sanitizeFinite(vertex.pz());
            positionBounds.include(px, py, pz);
            writeFloat(binaryBuffer, px);
            writeFloat(binaryBuffer, py);
            writeFloat(binaryBuffer, pz);
        }
        int positionViewIndex = bufferViews.size();
        bufferViews.add(new BufferViewDef(positionOffset, binaryBuffer.size() - positionOffset, TARGET_ARRAY_BUFFER));
        int positionAccessorIndex = accessors.size();
        accessors.add(new AccessorDef(positionViewIndex, COMPONENT_TYPE_FLOAT, vertices.size(), "VEC3",
                positionBounds.min(), positionBounds.max()));

        int normalOffset = binaryBuffer.size();
        for (ZoneMeshVertex vertex : vertices) {
            writeFloat(binaryBuffer, sanitizeFinite(-vertex.nx()));
            writeFloat(binaryBuffer, sanitizeFinite(-vertex.ny()));
            writeFloat(binaryBuffer, sanitizeFinite(vertex.nz()));
        }
        int normalViewIndex = bufferViews.size();
        bufferViews.add(new BufferViewDef(normalOffset, binaryBuffer.size() - normalOffset, TARGET_ARRAY_BUFFER));
        int normalAccessorIndex = accessors.size();
        accessors.add(new AccessorDef(normalViewIndex, COMPONENT_TYPE_FLOAT, vertices.size(), "VEC3", null, null));

        int uvOffset = binaryBuffer.size();
        for (ZoneMeshVertex vertex : vertices) {
            writeFloat(binaryBuffer, sanitizeFinite(vertex.u()));
            writeFloat(binaryBuffer, sanitizeFinite(vertex.v()));
        }
        int uvViewIndex = bufferViews.size();
        bufferViews.add(new BufferViewDef(uvOffset, binaryBuffer.size() - uvOffset, TARGET_ARRAY_BUFFER));
        int uvAccessorIndex = accessors.size();
        accessors.add(new AccessorDef(uvViewIndex, COMPONENT_TYPE_FLOAT, vertices.size(), "VEC2", null, null));

        // Vertex colors: DAT stores BGRA, glTF COLOR_0 is RGBA normalized float
        // XIM shader: coloredPixel = vec4(2.0 * litFragColor.rgb * basePixel.rgb, 4.0 * litFragColor.a * basePixel.a)
        // We preserve the raw BGRA values from the DAT as-is for faithful data export.
        int colorOffset = binaryBuffer.size();
        for (ZoneMeshVertex vertex : vertices) {
            writeFloat(binaryBuffer, (vertex.colorR() & 0xFF) / 255.0f);
            writeFloat(binaryBuffer, (vertex.colorG() & 0xFF) / 255.0f);
            writeFloat(binaryBuffer, (vertex.colorB() & 0xFF) / 255.0f);
            writeFloat(binaryBuffer, (vertex.colorA() & 0xFF) / 255.0f);
        }
        int colorViewIndex = bufferViews.size();
        bufferViews.add(new BufferViewDef(colorOffset, binaryBuffer.size() - colorOffset, TARGET_ARRAY_BUFFER));
        int colorAccessorIndex = accessors.size();
        accessors.add(new AccessorDef(colorViewIndex, COMPONENT_TYPE_FLOAT, vertices.size(), "VEC4", null, null));

        int maxIndex = expandedIndices.stream().mapToInt(Integer::intValue).max().orElse(-1);
        int indexComponentType = maxIndex < 65535 ? COMPONENT_TYPE_UNSIGNED_SHORT : COMPONENT_TYPE_UNSIGNED_INT;
        int indexOffset = binaryBuffer.size();
        for (Integer index : expandedIndices) {
            if (indexComponentType == COMPONENT_TYPE_UNSIGNED_SHORT) {
                writeUnsignedShort(binaryBuffer, index);
            } else {
                writeInt(binaryBuffer, index);
            }
        }
        int indexLength = binaryBuffer.size() - indexOffset;
        int indexViewIndex = bufferViews.size();
        bufferViews.add(new BufferViewDef(indexOffset, indexLength, TARGET_ELEMENT_ARRAY_BUFFER));
        int indexAccessorIndex = accessors.size();
        accessors.add(new AccessorDef(indexViewIndex, indexComponentType, expandedIndices.size(), "SCALAR", null, null));
        padBinaryTo4(binaryBuffer);

        PrimitiveDef primitive = new PrimitiveDef(positionAccessorIndex, normalAccessorIndex, uvAccessorIndex,
                colorAccessorIndex,
                indexAccessorIndex, materials.materialIndex(chunk.textureName(), chunk.invertCull(), alphaModeFor(meshName, chunk)), MODE_TRIANGLES);
        return new PrimitiveBuildResult(primitive, expandedIndices.size() / 3);
    }

    /** Geometry-only variant: emits positions + normals + indices, no UV, no material. */
    private static PrimitiveBuildResult writePrimitiveGeometryOnly(
            ZoneMeshChunk chunk,
            ByteArrayOutputStream binaryBuffer,
            List<BufferViewDef> bufferViews,
            List<AccessorDef> accessors
    ) {
        if (!hasValidVertices(chunk)) {
            return null;
        }

        List<Integer> expandedIndices = expandTriangles(chunk);
        if (expandedIndices.isEmpty()) {
            return null;
        }

        List<ZoneMeshVertex> vertices = chunk.vertices();
        FloatBounds positionBounds = new FloatBounds();

        int positionOffset = binaryBuffer.size();
        for (ZoneMeshVertex vertex : vertices) {
            float px = sanitizeFinite(-vertex.px());
            float py = sanitizeFinite(-vertex.py());
            float pz = sanitizeFinite(vertex.pz());
            positionBounds.include(px, py, pz);
            writeFloat(binaryBuffer, px);
            writeFloat(binaryBuffer, py);
            writeFloat(binaryBuffer, pz);
        }
        int positionViewIndex = bufferViews.size();
        bufferViews.add(new BufferViewDef(positionOffset, binaryBuffer.size() - positionOffset, TARGET_ARRAY_BUFFER));
        int positionAccessorIndex = accessors.size();
        accessors.add(new AccessorDef(positionViewIndex, COMPONENT_TYPE_FLOAT, vertices.size(), "VEC3",
                positionBounds.min(), positionBounds.max()));

        int normalOffset = binaryBuffer.size();
        for (ZoneMeshVertex vertex : vertices) {
            writeFloat(binaryBuffer, sanitizeFinite(-vertex.nx()));
            writeFloat(binaryBuffer, sanitizeFinite(-vertex.ny()));
            writeFloat(binaryBuffer, sanitizeFinite(vertex.nz()));
        }
        int normalViewIndex = bufferViews.size();
        bufferViews.add(new BufferViewDef(normalOffset, binaryBuffer.size() - normalOffset, TARGET_ARRAY_BUFFER));
        int normalAccessorIndex = accessors.size();
        accessors.add(new AccessorDef(normalViewIndex, COMPONENT_TYPE_FLOAT, vertices.size(), "VEC3", null, null));

        int maxIndex = expandedIndices.stream().mapToInt(Integer::intValue).max().orElse(-1);
        int indexComponentType = maxIndex < 65535 ? COMPONENT_TYPE_UNSIGNED_SHORT : COMPONENT_TYPE_UNSIGNED_INT;
        int indexOffset = binaryBuffer.size();
        for (Integer index : expandedIndices) {
            if (indexComponentType == COMPONENT_TYPE_UNSIGNED_SHORT) {
                writeUnsignedShort(binaryBuffer, index);
            } else {
                writeInt(binaryBuffer, index);
            }
        }
        int indexLength = binaryBuffer.size() - indexOffset;
        int indexViewIndex = bufferViews.size();
        bufferViews.add(new BufferViewDef(indexOffset, indexLength, TARGET_ELEMENT_ARRAY_BUFFER));
        int indexAccessorIndex = accessors.size();
        accessors.add(new AccessorDef(indexViewIndex, indexComponentType, expandedIndices.size(), "SCALAR", null, null));
        padBinaryTo4(binaryBuffer);

        // material index -1 signals geometry-only (no material)
        PrimitiveDef primitive = new PrimitiveDef(positionAccessorIndex, normalAccessorIndex, -1,
                -1, indexAccessorIndex, -1, MODE_TRIANGLES);
        return new PrimitiveBuildResult(primitive, expandedIndices.size() / 3);
    }

    private static boolean hasValidVertices(ZoneMeshChunk chunk) {
        List<ZoneMeshVertex> vertices = chunk.vertices();
        List<Integer> indices = chunk.indices();
        if (vertices.isEmpty() || indices.isEmpty()) {
            return false;
        }
        if (!chunk.isTriStrip() && indices.size() % 3 != 0) {
            return false;
        }
        for (Integer index : indices) {
            if (index == null || index < 0 || index >= vertices.size()) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> expandTriangles(ZoneMeshChunk chunk) {
        List<Integer> indices = chunk.indices();
        List<Integer> expanded = new ArrayList<>();
        if (chunk.isTriStrip()) {
            for (int i = 2; i < indices.size(); i++) {
                // WebGL TRIANGLE_STRIP winding rules (from spec):
                //   Even k: winding test uses [v[k], v[k+1], v[k+2]]
                //   Odd k:  winding test uses [v[k+1], v[k], v[k+2]]  (first two swapped)
                // XIM renders all with frontFace(CW). To convert to glTF CCW, swap
                // last two of each CW triple:
                //   Even CW [k, k+1, k+2]   → CCW [k, k+2, k+1]   = [i-2, i, i-1]
                //   Odd CW  [k+1, k, k+2]   → CCW [k+1, k+2, k]   = [i-1, i, i-2]
                int a, b, c;
                if ((i & 1) == 0) {
                    // Even: CW [i-2, i-1, i] → CCW [i-2, i, i-1]
                    a = indices.get(i - 2);
                    b = indices.get(i);
                    c = indices.get(i - 1);
                } else {
                    // Odd: GPU CW [i-1, i-2, i] → CCW [i-1, i, i-2]
                    a = indices.get(i - 1);
                    b = indices.get(i);
                    c = indices.get(i - 2);
                }
                if (a == b || b == c || a == c) continue;
                expanded.add(a);
                expanded.add(b);
                expanded.add(c);
            }
            return expanded;
        }
        // TriMesh: XIM frontFace(CW), glTF needs CCW → swap last two of each triple
        for (int i = 0; i + 2 < indices.size(); i += 3) {
            int i0 = indices.get(i);
            int i1 = indices.get(i + 1);
            int i2 = indices.get(i + 2);
            if (i0 == i1 || i1 == i2 || i0 == i2) continue;
            // CW [i0, i1, i2] → CCW [i0, i2, i1]
            expanded.add(i0);
            expanded.add(i2);
            expanded.add(i1);
        }
        return expanded;
    }

    private static String buildJson(
            DatResourceGraph graph,
            List<NodeDef> nodes,
            List<MeshDef> meshes,
            MaterialRegistry materials,
            List<BufferViewDef> bufferViews,
            List<AccessorDef> accessors,
            int binaryByteLength,
            int rootNodeIndex
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"asset\":{");
        sb.append("\"version\":\"2.0\",");
        sb.append("\"generator\":\"ffxi-xim-analysis ZoneMeshGltfExporter\"");
        if (!graph.sourcePath().isBlank()) {
            sb.append(",\"extras\":{\"sourcePath\":\"").append(escapeJson(graph.sourcePath())).append("\"}");
        }
        sb.append("},");
        sb.append("\"scene\":0,");
        sb.append("\"scenes\":[{\"nodes\":[").append(rootNodeIndex).append("]}],");
        appendNodes(sb, nodes);
        sb.append(',');
        appendMeshes(sb, meshes);
        if (!materials.materials().isEmpty()) {
            sb.append(',');
            appendMaterials(sb, materials.materials());
        }
        if (!materials.textures().isEmpty()) {
            sb.append(',');
            appendTextures(sb, materials.textures());
        }
        if (!materials.images().isEmpty()) {
            sb.append(',');
            appendImages(sb, materials.images());
        }
        sb.append(',');
        appendBuffers(sb, binaryByteLength);
        sb.append(',');
        appendBufferViews(sb, bufferViews);
        sb.append(',');
        appendAccessors(sb, accessors);
        sb.append('}');
        return sb.toString();
    }

    private static void appendNodes(StringBuilder sb, List<NodeDef> nodes) {
        sb.append("\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            NodeDef node = nodes.get(i);
            sb.append('{');
            sb.append("\"name\":\"").append(escapeJson(node.name())).append('"');
            if (!node.children().isEmpty()) {
                sb.append(",\"children\":[");
                appendIntArray(sb, node.children());
                sb.append(']');
            }
            if (node.meshIndex() != null) {
                sb.append(",\"mesh\":").append(node.meshIndex());
            }
            if (node.translation() != null) {
                sb.append(",\"translation\":[");
                appendFloatArray(sb, node.translation());
                sb.append(']');
            }
            if (node.rotation() != null) {
                sb.append(",\"rotation\":[");
                appendFloatArray(sb, node.rotation());
                sb.append(']');
            }
            if (node.scale() != null) {
                sb.append(",\"scale\":[");
                appendFloatArray(sb, node.scale());
                sb.append(']');
            }
            if (node.extras() != null) {
                sb.append(",\"extras\":{");
                sb.append("\"zoneObjectIndex\":").append(node.extras().zoneObjectIndex()).append(',');
                sb.append("\"highDefThreshold\":").append(formatFloat(node.extras().highDefThreshold())).append(',');
                sb.append("\"midDefThreshold\":").append(formatFloat(node.extras().midDefThreshold())).append(',');
                sb.append("\"lowDefThreshold\":").append(formatFloat(node.extras().lowDefThreshold()));
                sb.append('}');
            }
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendMeshes(StringBuilder sb, List<MeshDef> meshes) {
        sb.append("\"meshes\":[");
        for (int i = 0; i < meshes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            MeshDef mesh = meshes.get(i);
            sb.append('{');
            sb.append("\"name\":\"").append(escapeJson(mesh.name())).append("\",");
            sb.append("\"primitives\":[");
            for (int j = 0; j < mesh.primitives().size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                PrimitiveDef primitive = mesh.primitives().get(j);
                sb.append('{');
                sb.append("\"attributes\":{");
                sb.append("\"POSITION\":").append(primitive.positionAccessor());
                sb.append(",\"NORMAL\":").append(primitive.normalAccessor());
                if (primitive.uvAccessor() >= 0) {
                    sb.append(",\"TEXCOORD_0\":").append(primitive.uvAccessor());
                }
                if (primitive.colorAccessor() >= 0) {
                    sb.append(",\"COLOR_0\":").append(primitive.colorAccessor());
                }
                sb.append("},");
                sb.append("\"indices\":").append(primitive.indicesAccessor());
                if (primitive.materialIndex() >= 0) {
                    sb.append(",\"material\":").append(primitive.materialIndex());
                }
                sb.append(",\"mode\":").append(primitive.mode());
                sb.append('}');
            }
            sb.append("]}");
        }
        sb.append(']');
    }

    private static void appendMaterials(StringBuilder sb, List<MaterialDef> materials) {
        sb.append("\"materials\":[");
        for (int i = 0; i < materials.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            MaterialDef material = materials.get(i);
            sb.append('{');
            sb.append("\"name\":\"").append(escapeJson(material.name())).append("\",");
            sb.append("\"pbrMetallicRoughness\":{");
            if (material.textureIndex() != null) {
                sb.append("\"baseColorTexture\":{\"index\":").append(material.textureIndex()).append("},");
            }
            sb.append("\"metallicFactor\":0.0,");
            sb.append("\"roughnessFactor\":1.0");
            sb.append("},");
            sb.append("\"doubleSided\":").append(material.doubleSided());
            if (material.alphaMode() != AlphaMode.OPAQUE) {
                sb.append(",\"alphaMode\":\"").append(material.alphaMode().name()).append("\"");
                if (material.alphaMode() == AlphaMode.MASK) {
                    sb.append(",\"alphaCutoff\":0.375");
                }
            }
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendTextures(StringBuilder sb, List<TextureDef> textures) {
        sb.append("\"textures\":[");
        for (int i = 0; i < textures.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            TextureDef texture = textures.get(i);
            sb.append('{');
            sb.append("\"source\":").append(texture.sourceImageIndex());
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendImages(StringBuilder sb, List<ImageDef> images) {
        sb.append("\"images\":[");
        for (int i = 0; i < images.size(); i++) {
            if (i > 0) sb.append(',');
            ImageDef image = images.get(i);
            sb.append('{');
            if (image.bufferView() != null) {
                sb.append("\"bufferView\":").append(image.bufferView()).append(',');
                sb.append("\"mimeType\":\"image/png\"");
            } else {
                sb.append("\"uri\":\"").append(escapeJson(image.uri())).append("\"");
            }
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendBuffers(StringBuilder sb, int binaryByteLength) {
        sb.append("\"buffers\":[{\"byteLength\":").append(binaryByteLength).append("}]");
    }

    private static void appendBufferViews(StringBuilder sb, List<BufferViewDef> bufferViews) {
        sb.append("\"bufferViews\":[");
        for (int i = 0; i < bufferViews.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            BufferViewDef bufferView = bufferViews.get(i);
            sb.append('{');
            sb.append("\"buffer\":0,");
            sb.append("\"byteOffset\":").append(bufferView.byteOffset()).append(',');
            sb.append("\"byteLength\":").append(bufferView.byteLength());
            if (bufferView.target() != null) {
                sb.append(",\"target\":").append(bufferView.target());
            }
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendAccessors(StringBuilder sb, List<AccessorDef> accessors) {
        sb.append("\"accessors\":[");
        for (int i = 0; i < accessors.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            AccessorDef accessor = accessors.get(i);
            sb.append('{');
            sb.append("\"bufferView\":").append(accessor.bufferViewIndex()).append(',');
            sb.append("\"componentType\":").append(accessor.componentType()).append(',');
            sb.append("\"count\":").append(accessor.count()).append(',');
            sb.append("\"type\":\"").append(accessor.type()).append('"');
            if (accessor.min() != null) {
                sb.append(",\"min\":[");
                appendFloatArray(sb, accessor.min());
                sb.append(']');
            }
            if (accessor.max() != null) {
                sb.append(",\"max\":[");
                appendFloatArray(sb, accessor.max());
                sb.append(']');
            }
            sb.append('}');
        }
        sb.append(']');
    }

    private static int addNode(List<NodeDef> nodes, NodeDef node) {
        nodes.add(node);
        return nodes.size() - 1;
    }

    // XIM: discardThreshold = if (name.startsWith("_")) { 0.375f } else { null }
    //      blendEnabled → alpha blend; name starts with "_" → alpha mask; otherwise opaque
    private static AlphaMode alphaModeFor(String meshName, ZoneMeshChunk chunk) {
        if (chunk.renderBlend()) return AlphaMode.BLEND;
        if (meshName != null && meshName.startsWith("_")) return AlphaMode.MASK;
        return AlphaMode.OPAQUE;
    }

    private static String displayName(String meshName, String fallback) {
        String sanitized = sanitizeName(meshName, fallback);
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private static String sanitizeName(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw.trim();
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '\\') {
                sanitized.append('_');
            } else {
                sanitized.append(c);
            }
        }
        int end = sanitized.length();
        while (end > 0 && sanitized.charAt(end - 1) == '_') {
            end--;
        }
        return end > 0 ? sanitized.substring(0, end) : fallback;
    }

    private static void collectZoneMeshes(ResourceNode node, List<ZoneMeshResource> meshes) {
        if (node instanceof ZoneMeshResource mesh) {
            meshes.add(mesh);
            return;
        }
        if (node instanceof DirectoryResource directory) {
            for (ResourceNode child : directory.children()) {
                collectZoneMeshes(child, meshes);
            }
        }
    }

    private static void collectZoneObjectsMulti(ResourceNode node, Map<String, List<ZoneObject>> map) {
        if (node instanceof ZoneDefResource zoneDef) {
            for (ZoneObject object : zoneDef.zoneObjects()) {
                String meshId = object.id().trim().replaceAll("[\\x00-\\x1F]", "");
                map.computeIfAbsent(meshId, ignored -> new ArrayList<>()).add(object);
            }
            return;
        }
        if (node instanceof DirectoryResource directory) {
            for (ResourceNode child : directory.children()) {
                collectZoneObjectsMulti(child, map);
            }
        }
    }

    private static float[] quaternionFromZYXEuler(float rotX, float rotY, float rotZ) {
        float hx = sanitizeFinite(rotX) * 0.5f;
        float hy = sanitizeFinite(rotY) * 0.5f;
        float hz = sanitizeFinite(rotZ) * 0.5f;

        float cx = (float) Math.cos(hx);
        float sx = (float) Math.sin(hx);
        float cy = (float) Math.cos(hy);
        float sy = (float) Math.sin(hy);
        float cz = (float) Math.cos(hz);
        float sz = (float) Math.sin(hz);

        float qx = cz * cy * sx - sz * sy * cx;
        float qy = cz * sy * cx + sz * cy * sx;
        float qz = sz * cy * cx - cz * sy * sx;
        float qw = cz * cy * cx + sz * sy * sx;
        float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (length < 1.0e-6f) {
            return new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        }
        return new float[]{qx / length, qy / length, qz / length, qw / length};
    }

    /**
     * Transforms a quaternion when negating X+Y (keeping Z) in world space.
     * Negating X and Y = 180° rotation around Z axis.
     * Under q_T = [0,0,1,0] (180° Z rotation):
     *   q_new = q_T * q * q_T^-1 = [-qx, -qy, qz, qw]
     *
     * This maps FFXI left-handed Y-down (East=+X, visual-up=-Y)
     * to glTF right-handed Y-up (East=+X in Blender, up=+Z in Blender).
     */
    private static float[] negateXYQuaternion(float[] q) {
        return new float[]{-q[0], -q[1], q[2], q[3]};
    }

    private static float[] vector3(float x, float y, float z) {
        return new float[]{sanitizeFinite(x), sanitizeFinite(y), sanitizeFinite(z)};
    }

    private static float[] scaleVector(float x, float y, float z) {
        return new float[]{sanitizeFinite(x), sanitizeFinite(y), sanitizeFinite(z)};
    }

    private static float sanitizeFinite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    private static void writeFloat(ByteArrayOutputStream out, float value) {
        writeInt(out, Float.floatToIntBits(value));
    }

    private static void writeUnsignedShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void padBinaryTo4(ByteArrayOutputStream out) {
        while ((out.size() & 3) != 0) {
            out.write(0);
        }
    }

    private static byte[] padTo4(byte[] bytes, byte padByte) {
        int paddedLength = (bytes.length + 3) & ~3;
        if (paddedLength == bytes.length) {
            return bytes;
        }
        byte[] padded = new byte[paddedLength];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        for (int i = bytes.length; i < padded.length; i++) {
            padded[i] = padByte;
        }
        return padded;
    }

    private static void appendIntArray(StringBuilder sb, List<Integer> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
    }

    private static void appendFloatArray(StringBuilder sb, float[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(formatFloat(values[i]));
        }
    }

    private static String formatFloat(float value) {
        return Float.toString(sanitizeFinite(value));
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }

    // ── Texture helpers ─────────────────────────────────────────────────────

    private static void collectTextureResources(ResourceNode node, Map<String, TextureResource> map) {
        if (node instanceof TextureResource tr) {
            String name = tr.textureName();
            if (name != null && !name.isBlank()) {
                map.putIfAbsent(name.trim(), tr);
                map.putIfAbsent(name.trim().replaceAll("_+$", ""), tr);
            }
            return;
        }
        if (node instanceof DirectoryResource dir) {
            for (ResourceNode child : dir.children()) collectTextureResources(child, map);
        }
    }

    /** Decodes all available TextureResources to PNG byte arrays, keyed by texture name. */
    private static Map<String, byte[]> decodePngs(Map<String, TextureResource> texturesByName, byte[] rawData) {
        Map<String, byte[]> result = new HashMap<>();
        if (rawData == null) return result;
        for (Map.Entry<String, TextureResource> entry : texturesByName.entrySet()) {
            try {
                byte[] rgba = TextureDecoder.decode(rawData, entry.getValue());
                if (rgba == null) continue;
                int w = entry.getValue().width();
                int h = entry.getValue().height();
                if (w <= 0 || h <= 0) continue;
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                for (int i = 0; i < w * h; i++) {
                    int r = rgba[i * 4] & 0xFF;
                    int g = rgba[i * 4 + 1] & 0xFF;
                    int b = rgba[i * 4 + 2] & 0xFF;
                    int a = rgba[i * 4 + 3] & 0xFF;
                    img.setRGB(i % w, i / w, (a << 24) | (r << 16) | (g << 8) | b);
                }
                ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
                ImageIO.write(img, "PNG", pngOut);
                result.put(entry.getKey(), pngOut.toByteArray());
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private record PrimitiveBuildResult(PrimitiveDef primitive, int triangleCount) {
    }

    private record PrimitiveDef(
            int positionAccessor,
            int normalAccessor,
            int uvAccessor,
            int colorAccessor,
            int indicesAccessor,
            int materialIndex,
            int mode
    ) {
    }

    private record MeshDef(String name, List<PrimitiveDef> primitives) {
    }

    private record BufferViewDef(int byteOffset, int byteLength, Integer target) {
    }

    private record AccessorDef(
            int bufferViewIndex,
            int componentType,
            int count,
            String type,
            float[] min,
            float[] max
    ) {
    }

    private record ImageDef(String uri, Integer bufferView) {
        static ImageDef external(String uri) { return new ImageDef(uri, null); }
        static ImageDef embedded(int bufferView) { return new ImageDef(null, bufferView); }
    }

    private record TextureDef(int sourceImageIndex) {
    }

    private record MaterialDef(String name, Integer textureIndex, boolean doubleSided, AlphaMode alphaMode) {
    }

    private enum AlphaMode { OPAQUE, MASK, BLEND }

    private record NodeExtras(int zoneObjectIndex, float highDefThreshold, float midDefThreshold, float lowDefThreshold) {
    }

    private record NodeDef(
            String name,
            Integer meshIndex,
            float[] translation,
            float[] rotation,
            float[] scale,
            NodeExtras extras,
            List<Integer> children
    ) {
        private static NodeDef group(String name) {
            return new NodeDef(name, null, null, null, null, null, new ArrayList<>());
        }

        private static NodeDef meshNode(
                String name,
                Integer meshIndex,
                float[] translation,
                float[] rotation,
                float[] scale,
                NodeExtras extras
        ) {
            return new NodeDef(name, meshIndex, translation, rotation, scale, extras, new ArrayList<>());
        }
    }

    private static final class MaterialRegistry {
        private final Map<String, byte[]> pngByTextureName;
        private final ByteArrayOutputStream binaryBuffer;
        private final List<BufferViewDef> bufferViews;
        private final Map<String, Integer> materialIndexByTextureName = new LinkedHashMap<>();
        private final Map<String, Integer> imageIndexByTextureName = new LinkedHashMap<>();
        private final Map<String, Integer> textureIndexByTextureName = new LinkedHashMap<>();
        private final List<MaterialDef> materials = new ArrayList<>();
        private final List<TextureDef> textures = new ArrayList<>();
        private final List<ImageDef> images = new ArrayList<>();

        MaterialRegistry(Map<String, byte[]> pngByTextureName, ByteArrayOutputStream binaryBuffer,
                List<BufferViewDef> bufferViews) {
            this.pngByTextureName = pngByTextureName;
            this.binaryBuffer = binaryBuffer;
            this.bufferViews = bufferViews;
        }

        private int materialIndex(String textureName, boolean doubleSided, AlphaMode alphaMode) {
            // Material key includes doubleSided and alphaMode so variants don't collide
            String normalized = textureName == null ? "" : textureName.trim();
            String key = normalized + (doubleSided ? "|ds" : "") + "|" + alphaMode.name();
            Integer existing = materialIndexByTextureName.get(key);
            if (existing != null) return existing;

            Integer textureIndex = null;
            String suffix = (doubleSided ? "_ds" : "") + (alphaMode != AlphaMode.OPAQUE ? "_" + alphaMode.name().toLowerCase() : "");
            String materialName = (normalized.isBlank() ? "default_material" : normalized) + suffix;
            if (!normalized.isBlank()) {
                Integer imageIndex = imageIndexByTextureName.get(normalized);
                if (imageIndex == null) {
                    imageIndex = images.size();
                    images.add(createImageDef(normalized));
                    imageIndexByTextureName.put(normalized, imageIndex);
                }
                textureIndex = textureIndexByTextureName.get(normalized);
                if (textureIndex == null) {
                    textureIndex = textures.size();
                    textures.add(new TextureDef(imageIndex));
                    textureIndexByTextureName.put(normalized, textureIndex);
                }
            }

            int materialIndex = materials.size();
            materials.add(new MaterialDef(materialName, textureIndex, doubleSided, alphaMode));
            materialIndexByTextureName.put(key, materialIndex);
            return materialIndex;
        }

        private ImageDef createImageDef(String normalizedName) {
            // Try to find PNG bytes (exact match or trailing-underscore-stripped match)
            byte[] pngBytes = pngByTextureName.get(normalizedName);
            if (pngBytes == null) {
                String stripped = normalizedName.replaceAll("_+$", "");
                pngBytes = pngByTextureName.get(stripped);
            }
            if (pngBytes != null && pngBytes.length > 0) {
                // Embed PNG bytes into the binary buffer as a bufferView (no target = image data)
                padBuffer(binaryBuffer);
                int offset = binaryBuffer.size();
                binaryBuffer.writeBytes(pngBytes);
                bufferViews.add(new BufferViewDef(offset, pngBytes.length, null));
                return ImageDef.embedded(bufferViews.size() - 1);
            }
            // Fall back to external URI reference
            return ImageDef.external(sanitizeName(normalizedName, "texture") + ".png");
        }

        /** Pads binaryBuffer to a 4-byte boundary with zeros (required between geometry and image data). */
        private static void padBuffer(ByteArrayOutputStream buf) {
            int rem = buf.size() % 4;
            if (rem != 0) {
                for (int i = 0; i < 4 - rem; i++) buf.write(0);
            }
        }

        private List<MaterialDef> materials() { return materials; }
        private List<TextureDef> textures() { return textures; }
        private List<ImageDef> images() { return images; }
    }

    private static final class FloatBounds {
        private final float[] min = new float[]{Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        private final float[] max = new float[]{Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};

        private void include(float x, float y, float z) {
            min[0] = Math.min(min[0], x);
            min[1] = Math.min(min[1], y);
            min[2] = Math.min(min[2], z);
            max[0] = Math.max(max[0], x);
            max[1] = Math.max(max[1], y);
            max[2] = Math.max(max[2], z);
        }

        private float[] min() {
            return new float[]{sanitizeFinite(min[0]), sanitizeFinite(min[1]), sanitizeFinite(min[2])};
        }

        private float[] max() {
            return new float[]{sanitizeFinite(max[0]), sanitizeFinite(max[1]), sanitizeFinite(max[2])};
        }
    }

    public record ExportSummary(int meshDefinitions, int totalInstances, int totalTriangles) {
    }
}
