package com.example.asset.export;

import com.example.asset.geometry.BoundingBox;
import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.Vector3f;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * High-speed binary glTF 2.0 (.glb) exporter for 3D meshes and collision geometry.
 * Produces valid, standard glTF 2.0 assets openable in Blender, Windows 3D Viewer,
 * or web-based three.js / Babylon.js viewers.
 */
public final class GltfWriter {

    private static final int GLB_MAGIC = 0x46546C67; // "glTF"
    private static final int GLB_VERSION = 2;
    private static final int CHUNK_TYPE_JSON = 0x4E4F534A; // "JSON"
    private static final int CHUNK_TYPE_BIN = 0x004E4942;  // "BIN\0"

    private static final int GL_FLOAT = 5126;
    private static final int GL_ARRAY_BUFFER = 34962;

    private GltfWriter() {
    }

    public static void writeGlb(List<CollisionTriangle> triangles, Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(destination)) {
            writeGlb(triangles, out);
        }
    }

    public static void writeGlb(List<CollisionTriangle> triangles, OutputStream out) throws IOException {
        int vertexCount = triangles.size() * 3;
        int positionsLength = vertexCount * 12;
        int normalsLength = vertexCount * 12;
        int binTotalLength = positionsLength + normalsLength;

        // Pad BIN to 4-byte boundary
        int binPadding = (4 - (binTotalLength % 4)) % 4;
        int paddedBinLength = binTotalLength + binPadding;

        // Build BIN chunk in memory
        ByteBuffer binBuffer = ByteBuffer.allocate(paddedBinLength).order(ByteOrder.LITTLE_ENDIAN);

        BoundingBox.Builder bboxBuilder = BoundingBox.builder();

        // 1. Write positions
        for (CollisionTriangle tri : triangles) {
            writeVec3(binBuffer, tri.v0());
            writeVec3(binBuffer, tri.v1());
            writeVec3(binBuffer, tri.v2());

            bboxBuilder.include(tri.v0());
            bboxBuilder.include(tri.v1());
            bboxBuilder.include(tri.v2());
        }

        // 2. Write normals
        for (CollisionTriangle tri : triangles) {
            writeVec3(binBuffer, tri.normal());
            writeVec3(binBuffer, tri.normal());
            writeVec3(binBuffer, tri.normal());
        }

        // Zero-padding for BIN
        for (int i = 0; i < binPadding; i++) {
            binBuffer.put((byte) 0);
        }

        BoundingBox bbox = bboxBuilder.build();
        Vector3f min = bbox.min();
        Vector3f max = bbox.max();
        if (bbox == BoundingBox.EMPTY) {
            min = Vector3f.ZERO;
            max = Vector3f.ZERO;
        }

        // 3. Build JSON chunk
        String json = String.format(Locale.ROOT,
                "{\"asset\":{\"version\":\"2.0\",\"generator\":\"c2-game-asset-pipeline\"},"
                        + "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                        + "\"nodes\":[{\"mesh\":0}],"
                        + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1},\"mode\":4}]}],"
                        + "\"accessors\":["
                        + "{\"bufferView\":0,\"byteOffset\":0,\"componentType\":%d,\"count\":%d,\"type\":\"VEC3\","
                        + "\"min\":[%.6f,%.6f,%.6f],\"max\":[%.6f,%.6f,%.6f]},"
                        + "{\"bufferView\":1,\"byteOffset\":0,\"componentType\":%d,\"count\":%d,\"type\":\"VEC3\"}"
                        + "],"
                        + "\"bufferViews\":["
                        + "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":%d,\"target\":%d},"
                        + "{\"buffer\":0,\"byteOffset\":%d,\"byteLength\":%d,\"target\":%d}"
                        + "],"
                        + "\"buffers\":[{\"byteLength\":%d}]}",
                GL_FLOAT, vertexCount, min.x(), min.y(), min.z(), max.x(), max.y(), max.z(),
                GL_FLOAT, vertexCount,
                positionsLength, GL_ARRAY_BUFFER,
                positionsLength, normalsLength, GL_ARRAY_BUFFER,
                paddedBinLength
        );

        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int jsonPadding = (4 - (jsonBytes.length % 4)) % 4;
        int paddedJsonLength = jsonBytes.length + jsonPadding;

        int totalGlbLength = 12 // GLB header
                + 8 + paddedJsonLength // JSON chunk header + data
                + 8 + paddedBinLength; // BIN chunk header + data

        ByteBuffer glbHeader = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        glbHeader.putInt(GLB_MAGIC);
        glbHeader.putInt(GLB_VERSION);
        glbHeader.putInt(totalGlbLength);

        ByteBuffer jsonChunkHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        jsonChunkHeader.putInt(paddedJsonLength);
        jsonChunkHeader.putInt(CHUNK_TYPE_JSON);

        ByteBuffer binChunkHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        binChunkHeader.putInt(paddedBinLength);
        binChunkHeader.putInt(CHUNK_TYPE_BIN);

        // Write complete .glb stream
        out.write(glbHeader.array());

        out.write(jsonChunkHeader.array());
        out.write(jsonBytes);
        for (int i = 0; i < jsonPadding; i++) {
            out.write(' '); // JSON chunk must be padded with spaces (0x20) per spec
        }

        out.write(binChunkHeader.array());
        out.write(binBuffer.array());
        out.flush();
    }

    private static void writeVec3(ByteBuffer buffer, Vector3f v) {
        buffer.putFloat(v.x());
        buffer.putFloat(v.y());
        buffer.putFloat(v.z());
    }
}
