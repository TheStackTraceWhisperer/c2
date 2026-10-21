package com.ffxi.export;

import com.ffxi.model.*;

import java.util.List;

public final class DatResourceGraphJsonExporter {
    private DatResourceGraphJsonExporter() {
    }

    public static String toJson(DatResourceGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"root\": ");
        appendNode(graph.rootDirectory(), sb, 1);
        sb.append("\n}\n");
        return sb.toString();
    }

    private static void appendNode(ResourceNode node, StringBuilder sb, int indentLevel) {
        indent(sb, indentLevel).append("{\n");
        appendBaseFields(node, sb, indentLevel + 1);

        if (node instanceof DirectoryResource directory) {
            appendFieldName("children", sb, indentLevel + 1);
            sb.append("[\n");
            List<ResourceNode> children = directory.children();
            for (int i = 0; i < children.size(); i++) {
                appendNode(children.get(i), sb, indentLevel + 2);
                if (i < children.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            indent(sb, indentLevel + 1).append("]\n");
        } else if (node instanceof TextureResource texture) {
            appendNumberField("payloadSize", texture.payloadSize(), sb, indentLevel + 1);
            appendNumberField("textureType", texture.textureType(), sb, indentLevel + 1);
            appendStringField("textureName", texture.textureName(), sb, indentLevel + 1);
            appendNumberField("headerValue", texture.headerValue(), sb, indentLevel + 1);
            appendNumberField("width", texture.width(), sb, indentLevel + 1);
            appendNumberField("height", texture.height(), sb, indentLevel + 1);
            appendNumberField("bitCount", texture.bitCount(), sb, indentLevel + 1);
            appendStringField("dxtType", texture.dxtType(), sb, indentLevel + 1, false);
        } else if (node instanceof ZoneDefResource zoneDef) {
            appendNumberField("mode", zoneDef.mode(), sb, indentLevel + 1);
            appendNumberField("decodeLength", zoneDef.decodeLength(), sb, indentLevel + 1);
            appendNumberField("nodeCount", zoneDef.nodeCount(), sb, indentLevel + 1);
            appendNumberField("keyIndex", zoneDef.keyIndex(), sb, indentLevel + 1);
            appendZoneObjects(zoneDef.zoneObjects(), sb, indentLevel + 1, false);
        } else if (node instanceof ZoneMeshResource zoneMesh) {
            appendNumberField("mode", zoneMesh.mode(), sb, indentLevel + 1);
            appendNumberField("totalSize", zoneMesh.totalSize(), sb, indentLevel + 1);
            appendNumberField("keyIndex", zoneMesh.keyIndex(), sb, indentLevel + 1);
            appendBooleanField("secondPassFlag", zoneMesh.secondPassFlag(), sb, indentLevel + 1);
            appendStringField("meshName", zoneMesh.meshName(), sb, indentLevel + 1);
            appendNumberField("meshChunkCount", zoneMesh.meshChunks().size(), sb, indentLevel + 1);
            appendZoneMeshChunks("meshChunks", zoneMesh.meshChunks(), sb, indentLevel + 1, true);
            appendNumberField("collisionChunkCount", zoneMesh.collisionChunks().size(), sb, indentLevel + 1);
            appendZoneMeshChunks("collisionChunks", zoneMesh.collisionChunks(), sb, indentLevel + 1, false);
        } else if (node instanceof WeightedMeshResource weightedMesh) {
            appendNumberField("payloadSize", weightedMesh.payloadSize(), sb, indentLevel + 1);
            appendNumberField("unknownHeaderValue", weightedMesh.unknownHeaderValue(), sb, indentLevel + 1);
            appendNumberField("meshConfig", weightedMesh.meshConfig(), sb, indentLevel + 1);
            appendBooleanField("alphaDiscardEnabled", weightedMesh.alphaDiscardEnabled(), sb, indentLevel + 1);
            appendNumberField("weightedSectionCount", weightedMesh.weightedSectionCount(), sb, indentLevel + 1);
            appendNumberField("positionCount", weightedMesh.positionCount(), sb, indentLevel + 1);
            appendNumberField("normalCount", weightedMesh.normalCount(), sb, indentLevel + 1);
            appendNumberField("primitiveCount", weightedMesh.primitiveCount(), sb, indentLevel + 1);
            appendStringField("textureName", weightedMesh.textureName(), sb, indentLevel + 1);
            appendWeightedChunks(weightedMesh.chunks(), sb, indentLevel + 1);
            appendVec2fList("texCoords", weightedMesh.texCoords(), sb, indentLevel + 1);
            appendColorList("colors", weightedMesh.colors(), sb, indentLevel + 1);
            appendIntList("positionIndices", weightedMesh.positionIndices(), sb, indentLevel + 1);
            appendIntList("normalIndices", weightedMesh.normalIndices(), sb, indentLevel + 1, false);
        } else if (node instanceof SkeletonResource skeleton) {
            appendNumberField("payloadSize", skeleton.payloadSize(), sb, indentLevel + 1);
            appendNumberField("jointCount", skeleton.jointCount(), sb, indentLevel + 1);
            appendNumberField("referenceCount", skeleton.referenceCount(), sb, indentLevel + 1);
            appendNumberField("boundingBoxCount", skeleton.boundingBoxCount(), sb, indentLevel + 1);
            appendSkeletonJoints(skeleton.joints(), sb, indentLevel + 1);
            appendSkeletonJointRefs(skeleton.jointReferences(), sb, indentLevel + 1);
            appendSkeletonBoundingBoxes(skeleton.boundingBoxes(), sb, indentLevel + 1, false);
        } else if (node instanceof SkeletonMeshResource skeletonMesh) {
            appendBooleanField("clothEffect", skeletonMesh.clothEffect(), sb, indentLevel + 1);
            appendBooleanField("useJointArray", skeletonMesh.useJointArray(), sb, indentLevel + 1);
            appendBooleanField("symmetric", skeletonMesh.symmetric(), sb, indentLevel + 1);
            appendBooleanField("hasNormals", skeletonMesh.hasNormals(), sb, indentLevel + 1);
            appendNumberField("occludeType", skeletonMesh.occludeType(), sb, indentLevel + 1);
            appendNumberField("jointCount", skeletonMesh.jointCount(), sb, indentLevel + 1);
            appendNumberField("singleJointVertexCount", skeletonMesh.singleJointVertexCount(), sb, indentLevel + 1);
            appendNumberField("doubleJointVertexCount", skeletonMesh.doubleJointVertexCount(), sb, indentLevel + 1);
            appendIntList("joints", skeletonMesh.joints(), sb, indentLevel + 1);
            appendSkeletonMeshVertices(skeletonMesh.vertices(), sb, indentLevel + 1);
            appendSkeletonMeshMeshes(skeletonMesh.meshes(), sb, indentLevel + 1, false);
        } else if (node instanceof SkeletonAnimationResource skeletonAnimation) {
            appendNumberField("payloadSize", skeletonAnimation.payloadSize(), sb, indentLevel + 1);
            appendNumberField("unknownHeaderValue", skeletonAnimation.unknownHeaderValue(), sb, indentLevel + 1);
            appendNumberField("jointCount", skeletonAnimation.jointCount(), sb, indentLevel + 1);
            appendNumberField("frameCount", skeletonAnimation.frameCount(), sb, indentLevel + 1);
            appendFloatField("keyFrameDuration", skeletonAnimation.keyFrameDuration(), sb, indentLevel + 1);
            appendNumberField("keyFrameTableOffset", skeletonAnimation.keyFrameTableOffset(), sb, indentLevel + 1, false);
        }

        indent(sb, indentLevel).append("}");
    }

    private static void appendBaseFields(ResourceNode node, StringBuilder sb, int indentLevel) {
        appendStringField("kind", node.getClass().getSimpleName(), sb, indentLevel);
        appendStringField("sectionId", node.sectionId(), sb, indentLevel);

        String sectionType = node.sectionType();
        appendStringField("sectionType", sectionType == null ? "ROOT" : sectionType, sb, indentLevel);
        appendNumberField("sectionStart", node.sectionStart(), sb, indentLevel);
        appendNumberField("sectionSize", node.sectionSize(), sb, indentLevel, !(node instanceof EndSectionResource));
    }

    private static void appendStringField(String name, String value, StringBuilder sb, int indentLevel) {
        appendStringField(name, value, sb, indentLevel, true);
    }

    private static void appendStringField(String name, String value, StringBuilder sb, int indentLevel, boolean trailingComma) {
        appendFieldName(name, sb, indentLevel);
        sb.append('"').append(escape(value)).append('"');
        if (trailingComma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static void appendNumberField(String name, int value, StringBuilder sb, int indentLevel) {
        appendNumberField(name, value, sb, indentLevel, true);
    }

    private static void appendNumberField(String name, int value, StringBuilder sb, int indentLevel, boolean trailingComma) {
        appendFieldName(name, sb, indentLevel);
        sb.append(value);
        if (trailingComma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static void appendBooleanField(String name, boolean value, StringBuilder sb, int indentLevel, boolean trailingComma) {
        appendFieldName(name, sb, indentLevel);
        sb.append(value);
        if (trailingComma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static void appendBooleanField(String name, boolean value, StringBuilder sb, int indentLevel) {
        appendBooleanField(name, value, sb, indentLevel, true);
    }

    private static void appendFloatField(String name, float value, StringBuilder sb, int indentLevel) {
        appendFloatField(name, value, sb, indentLevel, true);
    }

    private static void appendFloatField(String name, float value, StringBuilder sb, int indentLevel, boolean trailingComma) {
        appendFieldName(name, sb, indentLevel);
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            sb.append("null");
        } else {
            sb.append(value);
        }
        if (trailingComma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static StringBuilder appendFieldName(String name, StringBuilder sb, int indentLevel) {
        return indent(sb, indentLevel).append('"').append(name).append("\": ");
    }

    private static void appendZoneObjects(List<ZoneObject> zoneObjects, StringBuilder sb, int indentLevel, boolean trailingComma) {
        appendFieldName("zoneObjects", sb, indentLevel);
        sb.append("[\n");
        for (int i = 0; i < zoneObjects.size(); i++) {
            ZoneObject zoneObject = zoneObjects.get(i);
            indent(sb, indentLevel + 1).append("{\n");
            appendNumberField("index", zoneObject.index(), sb, indentLevel + 2);
            appendStringField("id", zoneObject.id(), sb, indentLevel + 2);
            appendFloatArrayField("position", new float[]{zoneObject.posX(), zoneObject.posY(), zoneObject.posZ()}, sb, indentLevel + 2);
            appendFloatArrayField("rotation", new float[]{zoneObject.rotX(), zoneObject.rotY(), zoneObject.rotZ()}, sb, indentLevel + 2);
            appendFloatArrayField("scale", new float[]{zoneObject.scaleX(), zoneObject.scaleY(), zoneObject.scaleZ()}, sb, indentLevel + 2);
            appendFloatField("highDefThreshold", zoneObject.highDefThreshold(), sb, indentLevel + 2);
            appendFloatField("midDefThreshold", zoneObject.midDefThreshold(), sb, indentLevel + 2);
            boolean hasEffectLink = !zoneObject.effectLink().isBlank();
            boolean hasEnvironmentLink = !zoneObject.environmentLink().isBlank();
            boolean hasFileIdLink = zoneObject.fileIdLink() != 0;
            boolean hasPointLights = zoneObject.pointLight0() != 0
                    || zoneObject.pointLight1() != 0
                    || zoneObject.pointLight2() != 0
                    || zoneObject.pointLight3() != 0;
            appendFloatField("lowDefThreshold", zoneObject.lowDefThreshold(), sb, indentLevel + 2,
                    hasEffectLink || hasEnvironmentLink || hasFileIdLink || hasPointLights);
            if (hasEffectLink) {
                appendStringField("effectLink", zoneObject.effectLink(), sb, indentLevel + 2,
                        hasEnvironmentLink || hasFileIdLink || hasPointLights);
            }
            if (hasEnvironmentLink) {
                appendStringField("environmentLink", zoneObject.environmentLink(), sb, indentLevel + 2,
                        hasFileIdLink || hasPointLights);
            }
            if (hasFileIdLink) {
                appendNumberField("fileIdLink", zoneObject.fileIdLink(), sb, indentLevel + 2, hasPointLights);
            }
            if (hasPointLights) {
                appendIntArrayField("pointLights", new int[]{
                        zoneObject.pointLight0(),
                        zoneObject.pointLight1(),
                        zoneObject.pointLight2(),
                        zoneObject.pointLight3()
                }, sb, indentLevel + 2, false);
            }
            indent(sb, indentLevel + 1).append("}");
            if (i < zoneObjects.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        indent(sb, indentLevel).append("]");
        if (trailingComma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static void appendZoneMeshChunks(String fieldName, List<ZoneMeshChunk> chunks, StringBuilder sb, int indentLevel, boolean trailingComma) {
        appendFieldName(fieldName, sb, indentLevel);
        sb.append("[\n");
        for (int i = 0; i < chunks.size(); i++) {
            ZoneMeshChunk chunk = chunks.get(i);
            indent(sb, indentLevel + 1).append("{\n");
            appendStringField("textureName", chunk.textureName(), sb, indentLevel + 2);
            appendNumberField("vertexCount", chunk.vertices().size(), sb, indentLevel + 2);
            appendNumberField("indexCount", chunk.indices().size(), sb, indentLevel + 2);
            appendBooleanField("vertexBlend", chunk.renderBlend(), sb, indentLevel + 2, false);
            indent(sb, indentLevel + 1).append("}");
            if (i < chunks.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        indent(sb, indentLevel).append("]");
        if (trailingComma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static StringBuilder indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
        return sb;
    }

    private static void appendFloatArrayField(String name, float[] values, StringBuilder sb, int indentLevel) {
        appendFloatArrayField(name, values, sb, indentLevel, true);
    }

    private static void appendFloatArrayField(String name, float[] values, StringBuilder sb, int indentLevel, boolean trailingComma) {
        appendFieldName(name, sb, indentLevel);
        sb.append("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            float value = values[i];
            sb.append(Float.isFinite(value) ? value : "null");
        }
        sb.append("]");
        if (trailingComma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static void appendIntArrayField(String name, int[] values, StringBuilder sb, int indentLevel, boolean trailingComma) {
        appendFieldName(name, sb, indentLevel);
        sb.append("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values[i]);
        }
        sb.append("]");
        if (trailingComma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' || c == '"') {
                out.append('\\');
            }
            if (c >= 0x20) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void appendVec3f(Vec3f v, StringBuilder sb) {
        sb.append('[').append(v.x()).append(", ").append(v.y()).append(", ").append(v.z()).append(']');
    }

    private static void appendQuat4f(Quat4f q, StringBuilder sb) {
        sb.append('[').append(q.x()).append(", ").append(q.y()).append(", ")
                .append(q.z()).append(", ").append(q.w()).append(']');
    }

    private static void appendSkeletonJoints(List<SkeletonJoint> joints, StringBuilder sb, int indentLevel) {
        appendFieldName("joints", sb, indentLevel);
        sb.append("[\n");
        for (int i = 0; i < joints.size(); i++) {
            SkeletonJoint j = joints.get(i);
            indent(sb, indentLevel + 1).append("{");
            sb.append("\"parent\":").append(j.parentIndex()).append(",\"rotation\":");
            appendQuat4f(j.rotation(), sb);
            sb.append(",\"translation\":");
            appendVec3f(j.translation(), sb);
            sb.append("}");
            if (i < joints.size() - 1) sb.append(",");
            sb.append("\n");
        }
        indent(sb, indentLevel).append("],\n");
    }

    private static void appendSkeletonJointRefs(List<SkeletonJointReference> refs, StringBuilder sb, int indentLevel) {
        appendFieldName("jointReferences", sb, indentLevel);
        sb.append("[\n");
        for (int i = 0; i < refs.size(); i++) {
            SkeletonJointReference r = refs.get(i);
            indent(sb, indentLevel + 1).append("{");
            sb.append("\"index\":").append(r.index()).append(",\"positionOffset\":");
            appendVec3f(r.positionOffset(), sb);
            sb.append("}");
            if (i < refs.size() - 1) sb.append(",");
            sb.append("\n");
        }
        indent(sb, indentLevel).append("],\n");
    }

    private static void appendSkeletonBoundingBoxes(List<SkeletonBoundingBox> boxes, StringBuilder sb, int indentLevel, boolean trailingComma) {
        appendFieldName("boundingBoxes", sb, indentLevel);
        sb.append("[\n");
        for (int i = 0; i < boxes.size(); i++) {
            SkeletonBoundingBox b = boxes.get(i);
            indent(sb, indentLevel + 1).append("{");
            sb.append("\"xMin\":").append(b.xMin()).append(",\"xMax\":").append(b.xMax())
                    .append(",\"yMin\":").append(b.yMin()).append(",\"yMax\":").append(b.yMax())
                    .append(",\"zMin\":").append(b.zMin()).append(",\"zMax\":").append(b.zMax()).append("}");
            if (i < boxes.size() - 1) sb.append(",");
            sb.append("\n");
        }
        indent(sb, indentLevel).append("]");
        if (trailingComma) sb.append(",");
        sb.append("\n");
    }

    private static void appendWeightedChunks(List<WeightedMeshChunk> chunks, StringBuilder sb, int indentLevel) {
        appendFieldName("chunks", sb, indentLevel);
        sb.append("[\n");
        for (int i = 0; i < chunks.size(); i++) {
            WeightedMeshChunk c = chunks.get(i);
            indent(sb, indentLevel + 1).append("{");
            sb.append("\"positionCount\":").append(c.positions().size())
                    .append(",\"normalCount\":").append(c.normals().size()).append("}");
            if (i < chunks.size() - 1) sb.append(",");
            sb.append("\n");
        }
        indent(sb, indentLevel).append("],\n");
    }

    private static void appendVec2fList(String name, List<Vec2f> list, StringBuilder sb, int indentLevel) {
        appendFieldName(name, sb, indentLevel);
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            Vec2f v = list.get(i);
            sb.append("[").append(v.x()).append(",").append(v.y()).append("]");
        }
        sb.append("],\n");
    }

    private static void appendColorList(String name, List<BgraColor> list, StringBuilder sb, int indentLevel) {
        appendFieldName(name, sb, indentLevel);
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            BgraColor c = list.get(i);
            sb.append("[").append(c.r()).append(",").append(c.g()).append(",").append(c.b()).append(",").append(c.a()).append("]");
        }
        sb.append("],\n");
    }

    private static void appendIntList(String name, List<Integer> list, StringBuilder sb, int indentLevel) {
        appendIntList(name, list, sb, indentLevel, true);
    }

    private static void appendIntList(String name, List<Integer> list, StringBuilder sb, int indentLevel, boolean trailingComma) {
        appendFieldName(name, sb, indentLevel);
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        sb.append("]");
        if (trailingComma) sb.append(",");
        sb.append("\n");
    }

    private static void appendSkeletonMeshVertices(List<SkeletonMeshVertex> vertices, StringBuilder sb, int indentLevel) {
        appendFieldName("vertices", sb, indentLevel);
        sb.append("[\n");
        for (int i = 0; i < vertices.size(); i++) {
            SkeletonMeshVertex v = vertices.get(i);
            indent(sb, indentLevel + 1).append("{");
            sb.append("\"sourceIndex\":").append(v.sourceIndex());
            sb.append(",\"p0\":"); appendVec3f(v.p0(), sb);
            if (v.p1() != null) { sb.append(",\"p1\":"); appendVec3f(v.p1(), sb); }
            sb.append(",\"n0\":"); appendVec3f(v.n0(), sb);
            sb.append(",\"joint0Weight\":").append(v.joint0Weight());
            sb.append(",\"joint1Weight\":").append(v.joint1Weight());
            sb.append("}");
            if (i < vertices.size() - 1) sb.append(",");
            sb.append("\n");
        }
        indent(sb, indentLevel).append("],\n");
    }

    private static void appendSkeletonMeshMeshes(List<SkeletonMeshMesh> meshes, StringBuilder sb, int indentLevel, boolean trailingComma) {
        appendFieldName("meshes", sb, indentLevel);
        sb.append("[\n");
        for (int i = 0; i < meshes.size(); i++) {
            SkeletonMeshMesh m = meshes.get(i);
            indent(sb, indentLevel + 1).append("{");
            sb.append("\"opcodeName\":\"").append(escape(m.opcodeName())).append("\"");
            sb.append(",\"textureName\":\"").append(escape(m.textureName())).append("\"");
            sb.append(",\"triangleCount\":").append(m.triangleCount());
            sb.append(",\"vertexCount\":").append(m.vertexCount());
            sb.append(",\"textured\":").append(m.textured());
            sb.append(",\"mirrored\":").append(m.mirrored());
            sb.append("}");
            if (i < meshes.size() - 1) sb.append(",");
            sb.append("\n");
        }
        indent(sb, indentLevel).append("]");
        if (trailingComma) sb.append(",");
        sb.append("\n");
    }
}
