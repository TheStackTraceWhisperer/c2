package com.ffxi.viewer.debug;

import com.ffxi.viewer.model.ZoneMeshMath;
import com.ffxi.model.ZoneMeshChunk;
import com.ffxi.model.ZoneMeshVertex;
import com.ffxi.viewer.model.LoadedZone;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Exports zone geometry to OBJ format for comparison with reference export.
 */
public final class ZoneObjExporter {
    private ZoneObjExporter() {
    }

    public static void export(LoadedZone zone, Path objFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(objFile)) {
            writer.write("# Zone " + zone.zoneId() + " - " + zone.zoneName() + "\n");
            writer.write("# Exported from ZoneViewer\n\n");

            int globalVertexOffset = 1;
            int totalTriangles = 0;

            for (int instanceIdx = 0; instanceIdx < zone.chunkInstances().size(); instanceIdx++) {
                LoadedZone.ZoneChunkInstance instance = zone.chunkInstances().get(instanceIdx);
                ZoneMeshChunk chunk = instance.chunk();

                if (!ZoneMeshMath.hasValidVertices(chunk)) {
                    continue;
                }

                List<Integer> expandedIndices = ZoneMeshMath.expandTriangles(chunk);
                if (expandedIndices.isEmpty()) {
                    continue;
                }

                writer.write("o chunk_" + instanceIdx + "\n");

                Matrix4f modelMatrix = instance.modelMatrix();
                List<ZoneMeshVertex> vertices = chunk.vertices();
                
                for (ZoneMeshVertex vertex : vertices) {
                    float px = ZoneMeshMath.sanitizeFinite(-vertex.px());
                    float py = ZoneMeshMath.sanitizeFinite(-vertex.py());
                    float pz = ZoneMeshMath.sanitizeFinite(vertex.pz());
                    
                    Vector4f pos = new Vector4f(px, py, pz, 1.0f);
                    modelMatrix.transform(pos);
                    
                    writer.write(String.format(Locale.ROOT, "v %.6f %.6f %.6f\n", 
                        pos.x, pos.y, pos.z));
                }

                for (ZoneMeshVertex vertex : vertices) {
                    writer.write(String.format(Locale.ROOT, "vt %.6f %.6f\n", 
                        vertex.u(), vertex.v()));
                }

                for (ZoneMeshVertex vertex : vertices) {
                    float nx = ZoneMeshMath.sanitizeFinite(-vertex.nx());
                    float ny = ZoneMeshMath.sanitizeFinite(-vertex.ny());
                    float nz = ZoneMeshMath.sanitizeFinite(vertex.nz());
                    
                    Vector3f normal = new Vector3f(nx, ny, nz);
                    modelMatrix.transformDirection(normal);
                    normal.normalize();
                    
                    writer.write(String.format(Locale.ROOT, "vn %.6f %.6f %.6f\n",
                        normal.x, normal.y, normal.z));
                }

                int triangleCount = expandedIndices.size() / 3;
                for (int i = 0; i < triangleCount; i++) {
                    int v0 = globalVertexOffset + expandedIndices.get(i * 3 + 0);
                    int v1 = globalVertexOffset + expandedIndices.get(i * 3 + 1);
                    int v2 = globalVertexOffset + expandedIndices.get(i * 3 + 2);
                    
                    writer.write(String.format("f %d/%d/%d %d/%d/%d %d/%d/%d\n",
                        v0, v0, v0, v1, v1, v1, v2, v2, v2));
                }

                globalVertexOffset += vertices.size();
                totalTriangles += triangleCount;
            }

            writer.write("\n# Total triangles: " + totalTriangles + "\n");
        }
    }
}
