package com.ffxi.viewer.debug;

import com.ffxi.viewer.model.ZoneMeshMath;
import com.ffxi.model.ZoneMeshChunk;
import com.ffxi.viewer.model.LoadedZone;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Dumps detailed index expansion information for debugging winding issues.
 */
public final class IndexExpansionDumper {
    private IndexExpansionDumper() {
    }

    public static void dump(LoadedZone zone, Path outputFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            writer.write("# Index Expansion Dump for Zone " + zone.zoneId() + "\n");
            writer.write("# Format: ChunkIndex | IsTriStrip | RawIndices | ExpandedIndices\n\n");

            for (int i = 0; i < Math.min(20, zone.chunkInstances().size()); i++) {
                LoadedZone.ZoneChunkInstance instance = zone.chunkInstances().get(i);
                ZoneMeshChunk chunk = instance.chunk();

                if (!ZoneMeshMath.hasValidVertices(chunk)) {
                    writer.write(String.format("Chunk %d: INVALID\n", i));
                    continue;
                }

                List<Integer> rawIndices = chunk.indices();
                List<Integer> expandedIndices = ZoneMeshMath.expandTriangles(chunk);

                writer.write(String.format("Chunk %d:\n", i));
                writer.write(String.format("  IsTriStrip: %s\n", chunk.isTriStrip()));
                writer.write(String.format("  InvertCull: %s\n", chunk.invertCull()));
                writer.write(String.format("  VertexCount: %d\n", chunk.vertices().size()));
                writer.write(String.format("  RawIndices (%d): ", rawIndices.size()));
                
                for (int j = 0; j < Math.min(20, rawIndices.size()); j++) {
                    writer.write(rawIndices.get(j) + " ");
                }
                if (rawIndices.size() > 20) writer.write("...");
                writer.write("\n");

                writer.write(String.format("  ExpandedIndices (%d): ", expandedIndices.size()));
                for (int j = 0; j < Math.min(30, expandedIndices.size()); j++) {
                    writer.write(expandedIndices.get(j) + " ");
                    if ((j + 1) % 3 == 0 && j + 1 < expandedIndices.size()) {
                        writer.write("| ");
                    }
                }
                if (expandedIndices.size() > 30) writer.write("...");
                writer.write("\n");

                // Show first 3 triangles explicitly
                int triangleCount = Math.min(3, expandedIndices.size() / 3);
                for (int t = 0; t < triangleCount; t++) {
                    int i0 = expandedIndices.get(t * 3 + 0);
                    int i1 = expandedIndices.get(t * 3 + 1);
                    int i2 = expandedIndices.get(t * 3 + 2);
                    writer.write(String.format("    Triangle %d: [%d, %d, %d]\n", t, i0, i1, i2));
                }

                writer.write("\n");
            }
        }
    }
}
