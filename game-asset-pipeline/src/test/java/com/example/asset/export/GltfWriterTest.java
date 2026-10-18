package com.example.asset.export;

import com.example.asset.geometry.CollisionTriangle;
import com.example.asset.geometry.TerrainType;
import com.example.asset.geometry.TriFlags;
import com.example.asset.geometry.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GltfWriterTest {

    @Test
    void testWriteGlbBinaryStructure() throws IOException {
        CollisionTriangle tri = new CollisionTriangle(
                new Vector3f(0f, 0f, 0f),
                new Vector3f(1f, 0f, 0f),
                new Vector3f(0f, 1f, 0f),
                new Vector3f(0f, 0f, 1f),
                new TriFlags(0),
                TerrainType.STONE
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GltfWriter.writeGlb(List.of(tri), baos);
        byte[] bytes = baos.toByteArray();

        // Check overall length
        assertTrue(bytes.length >= 20);

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        // Header
        int magic = buf.getInt();
        int version = buf.getInt();
        int totalLength = buf.getInt();

        assertEquals(0x46546C67, magic, "Magic must be 'glTF'");
        assertEquals(2, version, "glTF version must be 2");
        assertEquals(bytes.length, totalLength, "Total length header must match actual byte length");

        // Chunk 0: JSON
        int jsonChunkLength = buf.getInt();
        int jsonChunkType = buf.getInt();
        assertEquals(0x4E4F534A, jsonChunkType, "Chunk type must be JSON");

        byte[] jsonBytes = new byte[jsonChunkLength];
        buf.get(jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();
        assertTrue(json.contains("\"generator\":\"c2-game-asset-pipeline\""));
        assertTrue(json.contains("\"POSITION\":0"));
        assertTrue(json.contains("\"NORMAL\":1"));

        // Chunk 1: BIN
        int binChunkLength = buf.getInt();
        int binChunkType = buf.getInt();
        assertEquals(0x004E4942, binChunkType, "Chunk type must be BIN");

        // 3 vertices * 12 bytes positions + 3 vertices * 12 bytes normals = 72 bytes
        assertEquals(72, binChunkLength);

        // First vertex position should be (0, 0, 0)
        assertEquals(0f, buf.getFloat());
        assertEquals(0f, buf.getFloat());
        assertEquals(0f, buf.getFloat());

        // Second vertex position should be (1, 0, 0)
        assertEquals(1f, buf.getFloat());
        assertEquals(0f, buf.getFloat());
        assertEquals(0f, buf.getFloat());

        // Third vertex position should be (0, 1, 0)
        assertEquals(0f, buf.getFloat());
        assertEquals(1f, buf.getFloat());
        assertEquals(0f, buf.getFloat());
    }

    @Test
    void testWriteToFile(@TempDir Path tempDir) throws IOException {
        Path glbPath = tempDir.resolve("test_mesh.glb");

        CollisionTriangle tri1 = new CollisionTriangle(
                new Vector3f(0f, 0f, 0f),
                new Vector3f(1f, 0f, 0f),
                new Vector3f(1f, 1f, 0f),
                new Vector3f(0f, 0f, 1f),
                new TriFlags(0),
                TerrainType.GRASS
        );
        CollisionTriangle tri2 = new CollisionTriangle(
                new Vector3f(0f, 0f, 0f),
                new Vector3f(1f, 1f, 0f),
                new Vector3f(0f, 1f, 0f),
                new Vector3f(0f, 0f, 1f),
                new TriFlags(0),
                TerrainType.GRASS
        );

        GltfWriter.writeGlb(List.of(tri1, tri2), glbPath);

        assertTrue(Files.exists(glbPath));
        assertTrue(Files.size(glbPath) > 100);
    }
}
