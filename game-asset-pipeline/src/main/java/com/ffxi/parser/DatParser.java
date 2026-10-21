package com.ffxi.parser;

import com.ffxi.crypto.ZoneDecryptor;
import com.ffxi.io.ByteReader;
import com.ffxi.model.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DatParser {

    public static List<SectionHeader> parseSections(ByteReader byteReader) {
        List<SectionHeader> headers = new ArrayList<>();
        if (byteReader == null) return headers;
        ByteReader workingReader = copyReader(byteReader);

        while (workingReader.hasMore()) {
            if (workingReader.remaining() < 0x10) break;

            SectionHeader header = new SectionHeader();
            header.read(workingReader);
            headers.add(header);

            if (header.getSectionType() == SectionType.S1C_ZoneDef) {
                int currentPos = workingReader.getPosition();
                ZoneDecryptor.decryptZoneObjects(header, workingReader);
                workingReader.setPosition(currentPos);
            } else if (header.getSectionType() == SectionType.S2E_ZoneMesh) {
                int currentPos = workingReader.getPosition();
                ZoneDecryptor.decryptZoneMesh(header, workingReader);
                workingReader.setPosition(currentPos);
            }

            int nextOffset = header.getNextSectionOffset();
            if (nextOffset <= header.getSectionStartPosition() || nextOffset > workingReader.getBytes().length) {
                break;
            }
            workingReader.setPosition(nextOffset);
        }

        return headers;
    }

    public static DatResourceGraph parseResourceGraph(ByteReader byteReader) {
        DirectoryResource root = new DirectoryResource("__root__", null, 0, 0, null);
        if (byteReader == null) {
            return new DatResourceGraph(root);
        }
        boolean zoneResourceDat = parseSections(byteReader).stream()
                .anyMatch(header -> header.getSectionType() == SectionType.S1C_ZoneDef
                        || header.getSectionType() == SectionType.S2E_ZoneMesh);
        ByteReader workingReader = copyReader(byteReader);

        DirectoryResource currentDirectory = root;
        while (workingReader.hasMore()) {
            if (workingReader.remaining() < 0x10) {
                break;
            }

            SectionHeader header = new SectionHeader();
            header.read(workingReader);

            ResourceNode node;
            switch (header.getSectionType()) {
                case S01_Directory -> {
                    DirectoryResource directory = new DirectoryResource(
                            header.getSectionId(),
                            sectionTypeName(header),
                            header.getSectionStartPosition(),
                            header.getSectionSize(),
                            currentDirectory
                    );
                    currentDirectory.addChild(directory);
                    currentDirectory = directory;
                    node = directory;
                }
                case S00_End -> {
                    node = new EndSectionResource(
                            header.getSectionId(),
                            sectionTypeName(header),
                            header.getSectionStartPosition(),
                            header.getSectionSize()
                    );
                    currentDirectory.addChild(node);
                    if (currentDirectory.parent() != null) {
                        currentDirectory = currentDirectory.parent();
                    }
                }
                case S20_Texture -> {
                    node = parseTextureSection(header, workingReader);
                    if (node != null) currentDirectory.addChild(node);
                }
                case S1C_ZoneDef -> {
                    int currentPos = workingReader.getPosition();
                    ZoneDecryptor.decryptZoneObjects(header, workingReader);
                    workingReader.setPosition(currentPos);
                    node = parseZoneDefSection(header, workingReader);
                    currentDirectory.addChild(node);
                }
                case S2E_ZoneMesh -> {
                    int currentPos = workingReader.getPosition();
                    ZoneDecryptor.decryptZoneMesh(header, workingReader);
                    workingReader.setPosition(currentPos);
                    node = parseZoneMeshSection(header, workingReader);
                    currentDirectory.addChild(node);
                }
                case S25_WeightedMesh -> {
                    node = parseWeightedMeshSection(header, workingReader, zoneResourceDat);
                    currentDirectory.addChild(node);
                }
                case S29_Skeleton -> {
                    node = parseSkeletonSection(header, workingReader);
                    currentDirectory.addChild(node);
                }
                case S2A_SkeletonMesh -> {
                    node = parseSkeletonMeshSection(header, workingReader);
                    currentDirectory.addChild(node);
                }
                case S2B_SkeletonAnimation -> {
                    node = parseSkeletonAnimationSection(header, workingReader);
                    currentDirectory.addChild(node);
                }
                default -> {
                    // Skip unsupported sections in graph mode for now.
                    node = null;
                }
            }

            int nextOffset = header.getNextSectionOffset();
            if (nextOffset <= header.getSectionStartPosition() || nextOffset > workingReader.getBytes().length) {
                break;
            }
            workingReader.setPosition(nextOffset);
        }

        return new DatResourceGraph(root, byteReader.getResourceName());
    }

    private static String sectionTypeName(SectionHeader header) {
        return header.getSectionType() == null ? null : header.getSectionType().name();
    }

    private static TextureResource parseTextureSection(SectionHeader header, ByteReader byteReader) {
        // XIM: byteReader.offsetFrom(sectionHeader, 0x10) → position to data start
        byteReader.setPosition(header.getDataStartPosition());

        // XIM: val type = byteReader.next8()
        //      if type != 0x91 && type != 0xA1 && type != 0xB1 → warn, return null
        int type = byteReader.next8();
        if (type != 0x91 && type != 0xA1 && type != 0xB1) {
            System.err.printf("[WARN] [%s] Don't know how to handle texture type: 0x%02X%n", header.getSectionId(), type);
            return null;
        }

        // XIM: val textureName = byteReader.nextString(0x10)
        String textureName = byteReader.nextString(0x10);

        byteReader.next32(); // 0x28?
        int width = byteReader.next32();
        int height = byteReader.next32();
        byteReader.next16(); // 0x01?
        int bitCount = byteReader.next16();

        // XIM: expectZero × 5
        for (int i = 0; i < 5; i++) {
            int zero = byteReader.next32();
            if (zero != 0) {
                throw new IllegalStateException("[" + header.getSectionId() + "] Expected zero dword at texture header, got 0x" + Integer.toHexString(zero));
            }
        }
        byteReader.next32(); // 0x10 or 0x20?

        // XIM: 0xB1 reads one extra dword before pixel data
        String dxtType = "";
        if (type == 0xB1) {
            byteReader.next32(); // 1?
        } else if (type == 0xA1) {
            dxtType = byteReader.nextString(4);
            byteReader.next32(); // size hint
            byteReader.next32(); // size hint
        }

        int payloadSize = Math.max(0, header.getSectionSize() - 0x10);
        return new TextureResource(
                header.getSectionId(),
                sectionTypeName(header),
                header.getSectionStartPosition(),
                header.getSectionSize(),
                payloadSize,
                type,
                textureName,
                -1,
                width,
                height,
                bitCount,
                dxtType
        );
    }

    private static WeightedMeshResource parseWeightedMeshSection(SectionHeader header, ByteReader byteReader,
            boolean zoneResourceDat) {
        int sectionEnd = getSectionEnd(header, byteReader);
        int payloadSize = Math.max(0, header.getSectionSize() - 0x10);
        int dataStart = header.getDataStartPosition();

        byteReader.setPosition(dataStart);
        requireCanRead(byteReader, sectionEnd, 0x20, "weighted mesh header", header.getSectionId());
        int unknownHeaderValue = byteReader.next16();
        if (unknownHeaderValue != 1) {
            throw new IllegalStateException("[" + header.getSectionId() + "] Expected weighted mesh unk1 == 1, got "
                    + unknownHeaderValue);
        }

        int meshConfig = byteReader.next8();
        boolean alphaDiscard = (meshConfig & 0x80) != 0;
        int weightedSectionCount = meshConfig & 0x0F;
        int offsetExtension = byteReader.next8() * 0x10000;
        int positionCount = byteReader.next16();
        int normalCount = byteReader.next16();
        int indexOffset = byteReader.next16() + offsetExtension + dataStart;
        int primitiveCount = byteReader.next16();
        int vertexCount = primitiveCount * 3;
        int colorOffset = byteReader.next16() + dataStart;
        int uvOffset = byteReader.next16() + offsetExtension + dataStart;
        String textureName = byteReader.nextString(0x10);

        List<WeightedMeshChunk> chunks = new ArrayList<>(weightedSectionCount);
        for (int i = 0; i < weightedSectionCount; i++) {
            List<Vec3f> positions = new ArrayList<>(positionCount);
            List<Vec3f> normals = new ArrayList<>(normalCount);
            for (int j = 0; j < positionCount; j++) {
                requireCanRead(byteReader, sectionEnd, 12, "weighted mesh position", header.getSectionId());
                positions.add(readVec3f(byteReader));
            }
            for (int j = 0; j < normalCount; j++) {
                requireCanRead(byteReader, sectionEnd, 4, "weighted mesh packed normal", header.getSectionId());
                int packedNormals = byteReader.next32();
                float x = ((packedNormals << 0x16) >> 0x16) / 512f;
                float y = ((packedNormals << 0x0C) >> 0x16) / 512f;
                float z = ((packedNormals << 0x02) >> 0x16) / 512f;
                normals.add(new Vec3f(x, y, z));
            }
            chunks.add(new WeightedMeshChunk(positions, normals));
        }

        byteReader.setPosition(colorOffset);
        List<BgraColor> colors = new ArrayList<>(vertexCount);
        int colorScaling = zoneResourceDat ? 2 : 1;
        for (int i = 0; i < vertexCount; i++) {
            requireCanRead(byteReader, sectionEnd, 4, "weighted mesh color", header.getSectionId());
            colors.add(readBgraColor(byteReader).multiply(colorScaling));
        }

        byteReader.setPosition(uvOffset);
        List<Vec2f> texCoords = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            requireCanRead(byteReader, sectionEnd, 8, "weighted mesh uv", header.getSectionId());
            texCoords.add(readVec2f(byteReader));
        }

        byteReader.setPosition(indexOffset);
        List<Integer> positionIndices = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            requireCanRead(byteReader, sectionEnd, 2, "weighted mesh position index", header.getSectionId());
            int positionIndex = byteReader.next16();
            if (positionIndex > positionCount) {
                throw new IllegalStateException(
                        "[" + header.getSectionId() + "] Position index too large: " + positionIndex);
            }
            positionIndices.add(positionIndex);
        }

        List<Integer> normalIndices = new ArrayList<>(vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            requireCanRead(byteReader, sectionEnd, 2, "weighted mesh normal index", header.getSectionId());
            int normalIndex = byteReader.next16();
            if (normalIndex > normalCount) {
                throw new IllegalStateException(
                        "[" + header.getSectionId() + "] Normal index too large: " + normalIndex);
            }
            normalIndices.add(normalIndex);
        }

        return new WeightedMeshResource(
                header.getSectionId(),
                sectionTypeName(header),
                header.getSectionStartPosition(),
                header.getSectionSize(),
                payloadSize,
                unknownHeaderValue,
                meshConfig,
                alphaDiscard,
                weightedSectionCount,
                positionCount,
                normalCount,
                primitiveCount,
                indexOffset,
                colorOffset,
                uvOffset,
                textureName,
                offsetExtension,
                zoneResourceDat,
                chunks,
                colors,
                texCoords,
                positionIndices,
                normalIndices
        );
    }

    private static SkeletonResource parseSkeletonSection(SectionHeader header, ByteReader byteReader) {
        int payloadSize = Math.max(0, header.getSectionSize() - 0x10);
        int sectionEnd = getSectionEnd(header, byteReader);
        int dataStart = header.getDataStartPosition();

        requireCanReadAt(dataStart + 2, sectionEnd, 1, "skeleton joint count", header.getSectionId());
        byteReader.setPosition(dataStart);
        int unknownHeaderValue = byteReader.next16();
        int jointCount = byteReader.next8();

        byteReader.setPosition(dataStart + 4);
        List<SkeletonJoint> joints = new ArrayList<>(jointCount);
        for (int i = 0; i < jointCount; i++) {
            requireCanRead(byteReader, sectionEnd, 30, "skeleton joint", header.getSectionId());
            int maybeParentIndex = byteReader.next8();
            int parentIndex = maybeParentIndex == i ? -1 : maybeParentIndex;
            int padByte = byteReader.next8();
            Quat4f rotation = readQuat4f(byteReader);
            Vec3f translation = readVec3f(byteReader);
            joints.add(new SkeletonJoint(parentIndex, padByte, rotation, translation));
        }

        requireCanRead(byteReader, sectionEnd, 4, "skeleton references header", header.getSectionId());
        int referenceCount = byteReader.next16();
        int referenceHeaderValue = byteReader.next16();

        List<SkeletonJointReference> jointReferences = new ArrayList<>(referenceCount);
        for (int i = 0; i < referenceCount; i++) {
            requireCanRead(byteReader, sectionEnd, 26, "skeleton joint reference", header.getSectionId());
            int start = byteReader.getPosition();
            int jointIndex = byteReader.next16();
            Vec3f unknownVector = readVec3f(byteReader);
            Vec3f positionOffset = readVec3f(byteReader);
            jointReferences.add(new SkeletonJointReference(jointIndex, unknownVector, positionOffset, start));
        }

        List<SkeletonBoundingBox> boundingBoxes = new ArrayList<>();
        while (canRead(byteReader, sectionEnd, 4)) {
            Float yMax = nextSkeletonBoundingFloat(byteReader);
            if (yMax == null) {
                break;
            }
            requireCanRead(byteReader, sectionEnd, 20, "skeleton bounding box", header.getSectionId());
            Float yMin = nextSkeletonBoundingFloat(byteReader);
            Float xMax = nextSkeletonBoundingFloat(byteReader);
            Float xMin = nextSkeletonBoundingFloat(byteReader);
            Float zMax = nextSkeletonBoundingFloat(byteReader);
            Float zMin = nextSkeletonBoundingFloat(byteReader);
            if (yMin == null || xMax == null || xMin == null || zMax == null || zMin == null) {
                break;
            }
            boundingBoxes.add(new SkeletonBoundingBox(yMax, yMin, xMax, xMin, zMax, zMin));
        }

        return new SkeletonResource(
                header.getSectionId(),
                sectionTypeName(header),
                header.getSectionStartPosition(),
                header.getSectionSize(),
                payloadSize,
                unknownHeaderValue,
                jointCount,
                jointCount * 30,
                referenceCount,
                referenceHeaderValue,
                boundingBoxes.size(),
                joints,
                jointReferences,
                boundingBoxes
        );
    }

    private static SkeletonMeshResource parseSkeletonMeshSection(SectionHeader header, ByteReader byteReader) {
        int payloadSize = Math.max(0, header.getSectionSize() - 0x10);
        int sectionEnd = getSectionEnd(header, byteReader);
        int dataStart = header.getDataStartPosition();
        byteReader.setPosition(dataStart);

        requireCanRead(byteReader, sectionEnd, 0x22, "skeleton mesh header", header.getSectionId());
        int flags1 = byteReader.next8();
        int flags2 = byteReader.next8();
        int flags3 = byteReader.next8();
        boolean clothEffect = (flags3 & 0x01) != 0;
        boolean useJointArray = (flags3 & 0x80) != 0;
        boolean hasNormals = !clothEffect;
        int flags4 = byteReader.next8();
        int occludeType = flags4;
        int flags5 = byteReader.next8();
        boolean symmetric = flags5 == 0x01;
        int flags6 = byteReader.next8();

        int instructionOffset = dataStart + (2 * byteReader.next32());
        int meshCountHint = byteReader.next8();
        int instructionCountHint = byteReader.next8();

        int jointArrayOffset = dataStart + (2 * byteReader.next32());
        int jointCount = byteReader.next16();

        int vertexCountsOffset = dataStart + (2 * byteReader.next32());
        int vertexCountsEntryCount = byteReader.next16();

        int vertexJointMappingOffset = dataStart + (2 * byteReader.next32());
        int vertexJointMappingCount = byteReader.next16();

        int vertexDataOffset = dataStart + (2 * byteReader.next32());
        int vertexDataSizeHint = byteReader.next16();

        int endOffset = dataStart + (2 * byteReader.next32());
        int endOffsetDataSizeHint = byteReader.next16();

        SkeletonMeshClothProperties clothProperties = null;
        if (clothEffect) {
            clothProperties = parseSkeletonMeshClothData(byteReader, header, sectionEnd);
        }

        byteReader.setPosition(jointArrayOffset);
        List<Integer> joints = new ArrayList<>(jointCount);
        for (int i = 0; i < jointCount; i++) {
            requireCanRead(byteReader, sectionEnd, 2, "skeleton mesh joint", header.getSectionId());
            joints.add(byteReader.next16());
        }

        byteReader.setPosition(vertexCountsOffset);
        requireCanRead(byteReader, sectionEnd, vertexCountsEntryCount * 2, "skeleton mesh vertex counts",
                header.getSectionId());
        if (vertexCountsEntryCount != 2) {
            throw new IllegalStateException("[" + header.getSectionId() + "] Expected only 2 types of counts");
        }
        int singleJointVertexCount = byteReader.next16();
        int doubleJointVertexCount = byteReader.next16();

        List<SkeletonMeshWorkingVertex> vertices = new ArrayList<>(singleJointVertexCount + doubleJointVertexCount);
        for (int i = 0; i < singleJointVertexCount + doubleJointVertexCount; i++) {
            vertices.add(new SkeletonMeshWorkingVertex(i));
        }

        byteReader.setPosition(vertexJointMappingOffset);
        parseSkeletonMeshJointRefs(byteReader, sectionEnd, header.getSectionId(), joints, vertices, singleJointVertexCount,
                doubleJointVertexCount, useJointArray);

        byteReader.setPosition(vertexDataOffset);
        parseSkeletonMeshPositionsAndNormals(byteReader, sectionEnd, header.getSectionId(), vertices,
                singleJointVertexCount, doubleJointVertexCount, hasNormals);

        byteReader.setPosition(instructionOffset);
        String currentTextureName = "";
        SkeletonMeshRenderProperties currentRenderProperties = defaultRenderProperties();
        List<SkeletonMeshMesh> meshes = new ArrayList<>();

        while (true) {
            requireCanRead(byteReader, sectionEnd, 2, "skeleton mesh opcode", header.getSectionId());
            int opCode = byteReader.next16();
            if (opCode == 0xFFFF) {
                break;
            } else if (opCode == 0x8010) {
                currentRenderProperties = readSkeletonMeshRenderProperties(byteReader, sectionEnd, header.getSectionId());
            } else if (opCode == 0x8000) {
                requireCanRead(byteReader, sectionEnd, 0x10, "skeleton mesh texture name", header.getSectionId());
                currentTextureName = byteReader.nextString(0x10);
            } else if (opCode == 0x5453) {
                parseSkeletonMeshTriStrip(byteReader, sectionEnd, header.getSectionId(), vertices, currentTextureName,
                        currentRenderProperties, symmetric, useJointArray, joints, meshes);
            } else if (opCode == 0x0054) {
                parseSkeletonMeshTriMesh(byteReader, sectionEnd, header.getSectionId(), vertices, currentTextureName,
                        currentRenderProperties, symmetric, useJointArray, joints, meshes);
            } else if (opCode == 0x0043) {
                parseSkeletonMeshUntexturedTriMesh(byteReader, sectionEnd, header.getSectionId(), vertices,
                        currentTextureName, currentRenderProperties, symmetric, useJointArray, joints, meshes);
            } else if (opCode == 0x4353) {
                parseSkeletonMeshSingleColorUntexturedTriStrip(byteReader, sectionEnd, header.getSectionId(), vertices,
                        currentTextureName, currentRenderProperties, symmetric, useJointArray, joints, meshes);
            } else {
                throw new IllegalStateException(
                        "[" + header.getSectionId() + "] Unknown op-code ["
                                + Integer.toHexString(opCode) + "] @ " + byteReader);
            }
        }

        List<SkeletonMeshVertex> parsedVertices = vertices.stream().map(SkeletonMeshWorkingVertex::toModel).toList();
        return new SkeletonMeshResource(
                header.getSectionId(),
                sectionTypeName(header),
                header.getSectionStartPosition(),
                header.getSectionSize(),
                payloadSize,
                flags1,
                flags2,
                flags3,
                flags4,
                flags5,
                flags6,
                clothEffect,
                useJointArray,
                symmetric,
                hasNormals,
                occludeType,
                instructionOffset,
                meshCountHint,
                instructionCountHint,
                jointArrayOffset,
                jointCount,
                vertexCountsOffset,
                vertexCountsEntryCount,
                vertexJointMappingOffset,
                vertexJointMappingCount,
                vertexDataOffset,
                vertexDataSizeHint,
                endOffset,
                endOffsetDataSizeHint,
                clothProperties,
                joints,
                singleJointVertexCount,
                doubleJointVertexCount,
                parsedVertices,
                meshes
        );
    }

    private static SkeletonMeshClothProperties parseSkeletonMeshClothData(ByteReader byteReader, SectionHeader header,
            int sectionEnd) {
        requireCanRead(byteReader, sectionEnd, 0x24, "skeleton mesh cloth header", header.getSectionId());
        int lockedSingleJointVertices = byteReader.next16();
        int lockedDoubleJointVertices = byteReader.next16();
        int clothLinkDataOffset = header.getDataStartPosition() + (2 * byteReader.next32());
        int clothLinkDataSize = byteReader.next16();
        int adjacentLinksSize = byteReader.next16();
        int diagonalLinksSize = byteReader.next16();
        if (adjacentLinksSize + diagonalLinksSize != clothLinkDataSize) {
            throw new IllegalStateException("[" + header.getSectionId()
                    + "] Expected cloth sub-sizes to add up to total size");
        }
        float adjacentSpringFactor = byteReader.nextFloat();
        float diagonalSpringFactor = byteReader.nextFloat();
        float exponentialSpringFactor = byteReader.nextFloat();
        float gravityFactor = byteReader.nextFloat();
        float movementFactor = byteReader.nextFloat();

        byteReader.setPosition(clothLinkDataOffset);
        List<SkeletonMeshClothLink> adjacentLinks = new ArrayList<>(adjacentLinksSize / 4);
        for (int i = 0; i < adjacentLinksSize / 4; i++) {
            adjacentLinks.add(readSkeletonMeshClothLink(byteReader, sectionEnd, header.getSectionId()));
        }
        List<SkeletonMeshClothLink> diagonalLinks = new ArrayList<>(diagonalLinksSize / 4);
        for (int i = 0; i < diagonalLinksSize / 4; i++) {
            diagonalLinks.add(readSkeletonMeshClothLink(byteReader, sectionEnd, header.getSectionId()));
        }

        return new SkeletonMeshClothProperties(
                lockedSingleJointVertices,
                lockedDoubleJointVertices,
                clothLinkDataOffset,
                clothLinkDataSize,
                adjacentLinksSize,
                diagonalLinksSize,
                adjacentSpringFactor,
                diagonalSpringFactor,
                exponentialSpringFactor,
                gravityFactor,
                movementFactor,
                adjacentLinks,
                diagonalLinks
        );
    }

    private static SkeletonMeshClothLink readSkeletonMeshClothLink(ByteReader byteReader, int sectionEnd,
            String sectionId) {
        requireCanRead(byteReader, sectionEnd, 8, "skeleton mesh cloth link", sectionId);
        int index0AndFlag = byteReader.next16();
        int index1AndFlag = byteReader.next16();
        float baseDistance = byteReader.nextFloat();
        return new SkeletonMeshClothLink(
                new SkeletonMeshClothLinkVertex(index0AndFlag & 0x7FFF, (index0AndFlag & 0x8000) != 0),
                new SkeletonMeshClothLinkVertex(index1AndFlag & 0x7FFF, (index1AndFlag & 0x8000) != 0),
                baseDistance
        );
    }

    private static void parseSkeletonMeshJointRefs(ByteReader byteReader, int sectionEnd, String sectionId,
            List<Integer> joints, List<SkeletonMeshWorkingVertex> vertices, int singleJointedCount,
            int doubleJointedCount, boolean useJointArray) {
        for (int i = 0; i < singleJointedCount; i++) {
            requireCanRead(byteReader, sectionEnd, 4, "skeleton mesh single-joint refs", sectionId);
            SkeletonMeshWorkingVertex vertex = vertices.get(i);
            vertex.jointRef0 = unpackSkeletonMeshJointRef(byteReader.next16());
            vertex.jointIndex0 = useJointArray ? joints.get(vertex.jointRef0.index()) : vertex.jointRef0.index();
            vertex.jointRef1 = unpackSkeletonMeshJointRef(byteReader.next16());
        }
        for (int i = 0; i < doubleJointedCount; i++) {
            requireCanRead(byteReader, sectionEnd, 4, "skeleton mesh double-joint refs", sectionId);
            SkeletonMeshWorkingVertex vertex = vertices.get(singleJointedCount + i);
            vertex.jointRef0 = unpackSkeletonMeshJointRef(byteReader.next16());
            vertex.jointIndex0 = useJointArray ? joints.get(vertex.jointRef0.index()) : vertex.jointRef0.index();
            vertex.jointRef1 = unpackSkeletonMeshJointRef(byteReader.next16());
            vertex.jointIndex1 = useJointArray ? joints.get(vertex.jointRef1.index()) : vertex.jointRef1.index();
        }
    }

    private static void parseSkeletonMeshPositionsAndNormals(ByteReader byteReader, int sectionEnd, String sectionId,
            List<SkeletonMeshWorkingVertex> vertices, int singleJointedCount, int doubleJointedCount,
            boolean hasNormals) {
        for (int i = 0; i < singleJointedCount; i++) {
            SkeletonMeshWorkingVertex vertex = vertices.get(i);
            requireCanRead(byteReader, sectionEnd, hasNormals ? 24 : 12, "skeleton mesh single-joint vertex", sectionId);
            vertex.p0 = readVec3f(byteReader);
            if (hasNormals) {
                vertex.n0 = readVec3f(byteReader);
            }
        }

        for (int i = 0; i < doubleJointedCount; i++) {
            SkeletonMeshWorkingVertex vertex = vertices.get(singleJointedCount + i);
            requireCanRead(byteReader, sectionEnd, hasNormals ? 56 : 32, "skeleton mesh double-joint vertex", sectionId);
            float p0x = byteReader.nextFloat();
            float p1x = byteReader.nextFloat();
            float p0y = byteReader.nextFloat();
            float p1y = byteReader.nextFloat();
            float p0z = byteReader.nextFloat();
            float p1z = byteReader.nextFloat();
            vertex.p0 = new Vec3f(p0x, p0y, p0z);
            vertex.p1 = new Vec3f(p1x, p1y, p1z);
            vertex.joint0Weight = byteReader.nextFloat();
            vertex.joint1Weight = byteReader.nextFloat();
            if (hasNormals) {
                float n0x = byteReader.nextFloat();
                float n1x = byteReader.nextFloat();
                float n0y = byteReader.nextFloat();
                float n1y = byteReader.nextFloat();
                float n0z = byteReader.nextFloat();
                float n1z = byteReader.nextFloat();
                vertex.n0 = new Vec3f(n0x, n0y, n0z);
                vertex.n1 = new Vec3f(n1x, n1y, n1z);
            }
        }
    }

    private static SkeletonMeshJointRef unpackSkeletonMeshJointRef(int data) {
        return new SkeletonMeshJointRef(data & 0x7F, (data >> 0x7) & 0x7F, (data >> 0xE) & 0x3);
    }

    private static SkeletonMeshRenderProperties defaultRenderProperties() {
        return new SkeletonMeshRenderProperties(new BgraColor(0x80, 0x80, 0x80, 0x80), 0f, 0f, 0, 0, 0, 0, 1f, 0, 0,
                0, 0f, 0, 0f, false);
    }

    private static SkeletonMeshRenderProperties readSkeletonMeshRenderProperties(ByteReader byteReader, int sectionEnd,
            String sectionId) {
        requireCanRead(byteReader, sectionEnd, 0x28, "skeleton mesh render properties", sectionId);
        BgraColor tFactor = readBgraColor(byteReader);
        float f0 = byteReader.nextFloat();
        float f1 = byteReader.nextFloat();
        int flag0 = byteReader.next8();
        int displayType = byteReader.next8();
        int flag2 = byteReader.next8();
        int flag3 = byteReader.next8();
        float ambientMultiplier = byteReader.nextFloat();
        int unknown0 = byteReader.next32();
        int unknown1 = byteReader.next32();
        int unknown2 = byteReader.next16();
        float f4 = byteReader.nextFloat();
        int unknown3 = byteReader.next16();
        float specularHighlightPower = byteReader.nextFloat();
        boolean specularHighlightEnabled = byteReader.nextFloat() == 1.0f;
        return new SkeletonMeshRenderProperties(tFactor, f0, f1, flag0, displayType, flag2, flag3, ambientMultiplier,
                unknown0, unknown1, unknown2, f4, unknown3, specularHighlightPower, specularHighlightEnabled);
    }

    private static void parseSkeletonMeshTriStrip(ByteReader byteReader, int sectionEnd, String sectionId,
            List<SkeletonMeshWorkingVertex> vertices, String textureName,
            SkeletonMeshRenderProperties renderProperties, boolean symmetric, boolean useJointArray, List<Integer> joints,
            List<SkeletonMeshMesh> meshes) {
        requireCanRead(byteReader, sectionEnd, 2, "skeleton mesh tri-strip count", sectionId);
        int triangleCount = byteReader.next16();
        int vertexCount = triangleCount + 2;
        List<SkeletonMeshFaceVertex> meshVertices = new ArrayList<>(vertexCount);
        // XIM layout for first 3 vertices: [idx0][idx1][idx2][u0][v0][u1][v1][u2][v2]
        int idx0 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
        int idx1 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
        int idx2 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
        requireCanRead(byteReader, sectionEnd, 24, "skeleton mesh strip initial uvs", sectionId);
        float u0 = byteReader.nextFloat(); float v0 = byteReader.nextFloat();
        float u1 = byteReader.nextFloat(); float v1 = byteReader.nextFloat();
        float u2 = byteReader.nextFloat(); float v2 = byteReader.nextFloat();
        meshVertices.add(new SkeletonMeshFaceVertex(idx0, u0, v0, new BgraColor(0x80, 0x80, 0x80, 0x80)));
        meshVertices.add(new SkeletonMeshFaceVertex(idx1, u1, v1, new BgraColor(0x80, 0x80, 0x80, 0x80)));
        meshVertices.add(new SkeletonMeshFaceVertex(idx2, u2, v2, new BgraColor(0x80, 0x80, 0x80, 0x80)));
        // Subsequent vertices: [idx][u][v] interleaved
        for (int i = 1; i < triangleCount; i++) {
            meshVertices.add(readTexturedSkeletonMeshVertex(byteReader, sectionEnd, sectionId, vertices));
        }
        addSkeletonMeshWithMirror(meshes, 0x5453, "TriStrip", textureName, triangleCount, vertexCount, true, false,
                false, renderProperties, meshVertices, symmetric, vertices, useJointArray, joints);
    }

    private static void parseSkeletonMeshTriMesh(ByteReader byteReader, int sectionEnd, String sectionId,
            List<SkeletonMeshWorkingVertex> vertices, String textureName,
            SkeletonMeshRenderProperties renderProperties, boolean symmetric, boolean useJointArray, List<Integer> joints,
            List<SkeletonMeshMesh> meshes) {
        requireCanRead(byteReader, sectionEnd, 2, "skeleton mesh tri-mesh count", sectionId);
        int triangleCount = byteReader.next16();
        int vertexCount = triangleCount * 3;
        List<SkeletonMeshFaceVertex> meshVertices = new ArrayList<>(vertexCount);
        // XIM layout per triangle: [idx0][idx1][idx2][u0][v0][u1][v1][u2][v2]
        // All 3 indices first, then all 3 UV pairs
        for (int i = 0; i < triangleCount; i++) {
            int idx0 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
            int idx1 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
            int idx2 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
            requireCanRead(byteReader, sectionEnd, 24, "skeleton mesh tri uvs", sectionId);
            float u0 = byteReader.nextFloat(); float v0 = byteReader.nextFloat();
            float u1 = byteReader.nextFloat(); float v1 = byteReader.nextFloat();
            float u2 = byteReader.nextFloat(); float v2 = byteReader.nextFloat();
            meshVertices.add(new SkeletonMeshFaceVertex(idx0, u0, v0, new BgraColor(0x80, 0x80, 0x80, 0x80)));
            meshVertices.add(new SkeletonMeshFaceVertex(idx1, u1, v1, new BgraColor(0x80, 0x80, 0x80, 0x80)));
            meshVertices.add(new SkeletonMeshFaceVertex(idx2, u2, v2, new BgraColor(0x80, 0x80, 0x80, 0x80)));
        }
        addSkeletonMeshWithMirror(meshes, 0x0054, "TriMesh", textureName, triangleCount, vertexCount, true, false,
                false, renderProperties, meshVertices, symmetric, vertices, useJointArray, joints);
    }

    private static void parseSkeletonMeshUntexturedTriMesh(ByteReader byteReader, int sectionEnd, String sectionId,
            List<SkeletonMeshWorkingVertex> vertices, String textureName,
            SkeletonMeshRenderProperties renderProperties, boolean symmetric, boolean useJointArray, List<Integer> joints,
            List<SkeletonMeshMesh> meshes) {
        requireCanRead(byteReader, sectionEnd, 2, "skeleton mesh untextured tri-mesh count", sectionId);
        int triangleCount = byteReader.next16();
        int vertexCount = triangleCount * 3;
        List<SkeletonMeshFaceVertex> meshVertices = new ArrayList<>(vertexCount);
        for (int i = 0; i < triangleCount; i++) {
            int v0 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
            int v1 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
            int v2 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
            requireCanRead(byteReader, sectionEnd, 4, "skeleton mesh tri color", sectionId);
            BgraColor color = readBgraColor(byteReader);
            meshVertices.add(new SkeletonMeshFaceVertex(v0, 0f, 0f, color));
            meshVertices.add(new SkeletonMeshFaceVertex(v1, 0f, 0f, color));
            meshVertices.add(new SkeletonMeshFaceVertex(v2, 0f, 0f, color));
        }
        addSkeletonMeshWithMirror(meshes, 0x0043, "TriMesh", textureName, triangleCount, vertexCount, false, true, true,
                renderProperties, meshVertices, symmetric, vertices, useJointArray, joints);
    }

    private static void parseSkeletonMeshSingleColorUntexturedTriStrip(ByteReader byteReader, int sectionEnd,
            String sectionId, List<SkeletonMeshWorkingVertex> vertices, String textureName,
            SkeletonMeshRenderProperties renderProperties, boolean symmetric, boolean useJointArray, List<Integer> joints,
            List<SkeletonMeshMesh> meshes) {
        requireCanRead(byteReader, sectionEnd, 2, "skeleton mesh untextured tri-strip count", sectionId);
        int triangleCount = byteReader.next16();
        int vertexCount = triangleCount + 2;
        int v0 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
        int v1 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
        int v2 = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
        requireCanRead(byteReader, sectionEnd, 4, "skeleton mesh strip color", sectionId);
        BgraColor color = readBgraColor(byteReader);
        List<SkeletonMeshFaceVertex> meshVertices = new ArrayList<>(vertexCount);
        meshVertices.add(new SkeletonMeshFaceVertex(v0, 0f, 0f, color));
        meshVertices.add(new SkeletonMeshFaceVertex(v1, 0f, 0f, color));
        meshVertices.add(new SkeletonMeshFaceVertex(v2, 0f, 0f, color));
        for (int i = 1; i < triangleCount; i++) {
            int vertexIndex = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
            meshVertices.add(new SkeletonMeshFaceVertex(vertexIndex, 0f, 0f, color));
        }
        addSkeletonMeshWithMirror(meshes, 0x4353, "TriStrip", textureName, triangleCount, vertexCount, false, true,
                true, renderProperties, meshVertices, symmetric, vertices, useJointArray, joints);
    }

    private static SkeletonMeshFaceVertex readTexturedSkeletonMeshVertex(ByteReader byteReader, int sectionEnd,
            String sectionId, List<SkeletonMeshWorkingVertex> vertices) {
        int vertexIndex = readSkeletonMeshVertexIndex(byteReader, sectionEnd, sectionId, vertices);
        requireCanRead(byteReader, sectionEnd, 8, "skeleton mesh uv", sectionId);
        float u = byteReader.nextFloat();
        float v = byteReader.nextFloat();
        return new SkeletonMeshFaceVertex(vertexIndex, u, v, new BgraColor(0x80, 0x80, 0x80, 0x80));
    }

    private static int readSkeletonMeshVertexIndex(ByteReader byteReader, int sectionEnd, String sectionId,
            List<SkeletonMeshWorkingVertex> vertices) {
        requireCanRead(byteReader, sectionEnd, 2, "skeleton mesh vertex index", sectionId);
        int vertexIndex = byteReader.next16();
        if (vertexIndex < 0 || vertexIndex >= vertices.size()) {
            throw new IllegalStateException("[" + sectionId + "] Vertex index out of range: " + vertexIndex);
        }
        return vertexIndex;
    }

    private static void addSkeletonMeshWithMirror(List<SkeletonMeshMesh> meshes, int opcode, String opcodeName,
            String textureName, int triangleCount, int vertexCount, boolean textured, boolean untextured,
            boolean singleColor, SkeletonMeshRenderProperties renderProperties,
            List<SkeletonMeshFaceVertex> meshVertices, boolean symmetric, List<SkeletonMeshWorkingVertex> vertices,
            boolean useJointArray, List<Integer> joints) {
        List<SkeletonMeshVertex> resolvedVertices = resolveSkeletonMeshVertices(meshVertices, vertices);
        meshes.add(new SkeletonMeshMesh(opcode, opcodeName, textureName, triangleCount, vertexCount, textured,
                untextured, singleColor, false, renderProperties, meshVertices, resolvedVertices));
        if (!symmetric) {
            return;
        }

        List<SkeletonMeshWorkingVertex> mirroredVertices = new ArrayList<>(vertices.size());
        for (SkeletonMeshWorkingVertex vertex : vertices) {
            mirroredVertices.add(flipSkeletonMeshVertex(vertex, useJointArray, joints));
        }
        List<SkeletonMeshVertex> mirroredResolvedVertices = resolveSkeletonMeshVertices(meshVertices, mirroredVertices);
        meshes.add(new SkeletonMeshMesh(opcode, opcodeName, textureName, triangleCount, vertexCount, textured,
                untextured, singleColor, true, renderProperties, meshVertices, mirroredResolvedVertices));
    }

    private static List<SkeletonMeshVertex> resolveSkeletonMeshVertices(List<SkeletonMeshFaceVertex> meshVertices,
            List<SkeletonMeshWorkingVertex> vertices) {
        List<SkeletonMeshVertex> resolvedVertices = new ArrayList<>(meshVertices.size());
        for (SkeletonMeshFaceVertex faceVertex : meshVertices) {
            resolvedVertices.add(vertices.get(faceVertex.vertexIndex()).toModel());
        }
        return resolvedVertices;
    }

    private static SkeletonMeshWorkingVertex flipSkeletonMeshVertex(SkeletonMeshWorkingVertex original,
            boolean useJointArray, List<Integer> joints) {
        SkeletonMeshWorkingVertex mirrored = new SkeletonMeshWorkingVertex(original.sourceIndex);
        mirrored.p0 = flipSkeletonMeshVector(original.p0, original.jointRef0);
        mirrored.p1 = flipSkeletonMeshVector(original.p1, original.jointRef1);
        mirrored.n0 = flipSkeletonMeshVector(original.n0, original.jointRef0);
        mirrored.n1 = flipSkeletonMeshVector(original.n1, original.jointRef1);
        mirrored.jointIndex0 = original.jointRef0 == null ? null
                : (useJointArray ? joints.get(original.jointRef0.flippedIndex()) : original.jointRef0.flippedIndex());
        mirrored.jointIndex1 = original.jointRef1 == null ? null
                : (useJointArray ? joints.get(original.jointRef1.flippedIndex()) : original.jointRef1.flippedIndex());
        mirrored.joint0Weight = original.joint0Weight;
        mirrored.joint1Weight = original.joint1Weight;
        mirrored.jointRef0 = original.jointRef0;
        mirrored.jointRef1 = original.jointRef1;
        return mirrored;
    }

    private static Vec3f flipSkeletonMeshVector(Vec3f original, SkeletonMeshJointRef flipType) {
        if (original == null) {
            return null;
        }
        if (flipType == null) {
            return original;
        }
        return switch (flipType.flipAxis()) {
            case 1 -> new Vec3f(-original.x(), original.y(), original.z());
            case 2 -> new Vec3f(original.x(), -original.y(), original.z());
            case 3 -> new Vec3f(original.x(), original.y(), -original.z());
            default -> original;
        };
    }

    private static SkeletonAnimationResource parseSkeletonAnimationSection(SectionHeader header, ByteReader byteReader) {
        int payloadSize = Math.max(0, header.getSectionSize() - 0x10);
        int sectionEnd = getSectionEnd(header, byteReader);
        int dataStart = header.getDataStartPosition();
        byteReader.setPosition(dataStart);
        requireCanRead(byteReader, sectionEnd, 10, "skeleton animation header", header.getSectionId());

        int unknownHeaderValue = byteReader.next16();
        int jointCount = byteReader.next16();
        int frameCount = byteReader.next16();
        float keyFrameDuration = byteReader.nextFloat();
        int keyFrameTableOffset = byteReader.getPosition();
        List<JointAnimation> jointAnimations = new ArrayList<>(jointCount);

        for (int i = 0; i < jointCount; i++) {
            requireCanRead(byteReader, sectionEnd, 84, "skeleton animation joint", header.getSectionId());
            int jointIndex = byteReader.next32();
            int[] rotationOffsets = byteReader.next32(4);
            float[] rotationConsts = byteReader.nextFloat(4);
            int[] translationOffsets = byteReader.next32(3);
            float[] translationConsts = byteReader.nextFloat(3);
            int[] scaleOffsets = byteReader.next32(3);
            float[] scaleConsts = byteReader.nextFloat(3);

            if (hasNegativeOffset(rotationOffsets) || hasNegativeOffset(translationOffsets) || hasNegativeOffset(scaleOffsets)) {
                continue;
            }

            float[][] rotationFrames = readAnimationSequences(rotationOffsets, rotationConsts, frameCount, byteReader,
                    keyFrameTableOffset, sectionEnd, header.getSectionId(), true);
            float[][] translationFrames = readAnimationSequences(translationOffsets, translationConsts, frameCount, byteReader,
                    keyFrameTableOffset, sectionEnd, header.getSectionId(), false);
            float[][] scaleFrames = readAnimationSequences(scaleOffsets, scaleConsts, frameCount, byteReader,
                    keyFrameTableOffset, sectionEnd, header.getSectionId(), false);

            List<AnimationKeyFrame> frames = new ArrayList<>(frameCount);
            for (int frame = 0; frame < frameCount; frame++) {
                frames.add(new AnimationKeyFrame(
                        rotationFrames[0][frame], rotationFrames[1][frame], rotationFrames[2][frame], rotationFrames[3][frame],
                        translationFrames[0][frame], translationFrames[1][frame], translationFrames[2][frame],
                        scaleFrames[0][frame], scaleFrames[1][frame], scaleFrames[2][frame]
                ));
            }
            jointAnimations.add(new JointAnimation(jointIndex, frames));
        }

        return new SkeletonAnimationResource(
                header.getSectionId(),
                sectionTypeName(header),
                header.getSectionStartPosition(),
                header.getSectionSize(),
                payloadSize,
                unknownHeaderValue,
                jointCount,
                frameCount,
                keyFrameDuration,
                keyFrameTableOffset,
                jointAnimations
        );
    }

    private static boolean hasNegativeOffset(int[] offsets) {
        for (int offset : offsets) {
            if (offset < 0) {
                return true;
            }
        }
        return false;
    }

    private static float[][] readAnimationSequences(int[] offsets, float[] constValues, int frameCount, ByteReader byteReader,
            int keyFrameDataOffset, int sectionEnd, String sectionId, boolean wrapConstants) {
        float[][] sequences = new float[offsets.length][frameCount];
        for (int i = 0; i < offsets.length; i++) {
            if (offsets[i] == 0) {
                float constantValue = wrapConstants ? constValues[i] % 10_000.0f : constValues[i];
                for (int frame = 0; frame < frameCount; frame++) {
                    sequences[i][frame] = constantValue;
                }
            } else {
                sequences[i] = readAnimationSequence(offsets[i], frameCount, byteReader, keyFrameDataOffset, sectionEnd, sectionId);
            }
        }
        return sequences;
    }

    private static float[] readAnimationSequence(int offset, int frameCount, ByteReader byteReader, int keyFrameDataOffset,
            int sectionEnd, String sectionId) {
        int sequencePosition = keyFrameDataOffset + offset * 4;
        requireCanReadAt(sequencePosition, sectionEnd, frameCount * 4, "skeleton animation keyframe sequence", sectionId);
        int originalPosition = byteReader.getPosition();
        byteReader.setPosition(sequencePosition);
        float[] frames = byteReader.nextFloat(frameCount);
        byteReader.setPosition(originalPosition);
        return frames;
    }

    private static ZoneDefResource parseZoneDefSection(SectionHeader header, ByteReader byteReader) {
        int sectionEnd = getSectionEnd(header, byteReader);
        byteReader.setPosition(header.getDataStartPosition());
        requireCanRead(byteReader, sectionEnd, 0x20, "zone definition header", header.getSectionId());
        int decryptInfo = byteReader.next32();
        int keyNodeData = byteReader.next32();
        int nodeCount = keyNodeData & 0x00FFFFFF;
        int keyIndex = (keyNodeData >>> 24) & 0xFF;
        int collisionMeshOffset = byteReader.next32();
        int zoneBlocksX = byteReader.next8();
        int zoneBlocksZ = byteReader.next8();
        int blockWidth = byteReader.next8();
        int blockLength = byteReader.next8();
        int subBlocksX = blockWidth / 4;
        int subBlocksZ = blockLength / 4;
        int spacePartitioningTreeOffset = byteReader.next32();
        int cullingTablesOffset = byteReader.next32();
        int pointLightOffset = byteReader.next32();
        int unknownHeaderValue = byteReader.next32();
        int zoneObjectsOffset = byteReader.getPosition();

        byteReader.setPosition(cullingTablesOffset + header.getDataStartPosition());
        ZoneCullingTables zoneCullingTables = parseZoneCullingTables(byteReader, sectionEnd, header.getSectionId());

        byteReader.setPosition(zoneObjectsOffset);
        List<ZoneObject> zoneObjects = parseZoneObjects(byteReader, sectionEnd, nodeCount, zoneCullingTables.offsetToIndex());

        byteReader.setPosition(spacePartitioningTreeOffset + header.getDataStartPosition());
        ZoneSpacePartitionNode spacePartitioningTreeRoot =
                parseZoneSpacePartitionNode(byteReader, sectionEnd, header, zoneCullingTables.offsetToIndex());

        byteReader.setPosition(pointLightOffset + header.getDataStartPosition());
        List<String> pointLightLinks = readZonePointLights(byteReader, sectionEnd, header.getSectionId());

        ZoneCollisionMap collisionMap = null;
        if (collisionMeshOffset > 0) {
            int absCollOffset = header.getDataStartPosition() + collisionMeshOffset;
            if (absCollOffset < sectionEnd) {
                try {
                    collisionMap = parseCollisionMeshData(byteReader, header, sectionEnd,
                            zoneBlocksX, zoneBlocksZ, blockWidth, blockLength, subBlocksX, subBlocksZ, absCollOffset);
                } catch (Exception e) {
                    System.err.printf("[WARN] Failed to parse collision mesh for %s: %s%n", header.getSectionId(), e.getMessage());
                }
            }
        }

        return new ZoneDefResource(
                header.getSectionId(),
                sectionTypeName(header),
                header.getSectionStartPosition(),
                header.getSectionSize(),
                decryptInfo,
                unknownHeaderValue,
                nodeCount,
                keyIndex,
                zoneObjects,
                collisionMeshOffset,
                zoneBlocksX,
                zoneBlocksZ,
                blockWidth,
                blockLength,
                subBlocksX,
                subBlocksZ,
                spacePartitioningTreeOffset,
                cullingTablesOffset,
                pointLightOffset,
                zoneCullingTables.tables(),
                spacePartitioningTreeRoot,
                pointLightLinks,
                collisionMap
        );
    }

    private static ZoneCollisionMap parseCollisionMeshData(
            ByteReader byteReader, SectionHeader header, int sectionEnd,
            int zoneBlocksX, int zoneBlocksZ, int blockWidth, int blockLength,
            int subBlocksX, int subBlocksZ, int absOffset) {
        byteReader.setPosition(absOffset);

        byteReader.next32();
        byteReader.next32();
        int pairCount = byteReader.next32();
        int pairsAbsOff = byteReader.next32() + header.getDataStartPosition();
        int mapAbsOff = byteReader.next32() + header.getDataStartPosition();
        byteReader.next32();
        byteReader.next32();
        byteReader.next32();

        java.util.Map<Integer, float[]> transformCache = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, List<ZoneCollisionTriangle>> meshCache = new java.util.LinkedHashMap<>();

        List<ZoneCollisionObjectGroup> groupList = new ArrayList<>();
        java.util.Map<Integer, Integer> groupByOffset = new java.util.LinkedHashMap<>();

        byteReader.setPosition(pairsAbsOff);
        for (int i = 0; i < pairCount; i++) {
            int groupFileOffset = byteReader.getPosition();
            groupByOffset.put(groupFileOffset, i);

            int countFlags = byteReader.next32();
            int groupSize = countFlags & 0x7FF;

            List<ZoneCollisionObject> objects = new ArrayList<>(groupSize);
            for (int j = 0; j < groupSize; j++) {
                int matrixAbsOff = byteReader.next32() + header.getDataStartPosition();
                int meshAbsOff = byteReader.next32() + header.getDataStartPosition();

                float[] transform = transformCache.computeIfAbsent(matrixAbsOff,
                        off -> readCollisionTransform(byteReader, off));
                List<ZoneCollisionTriangle> triangles = meshCache.computeIfAbsent(meshAbsOff,
                        off -> readCollisionMesh(byteReader, off, header));

                objects.add(new ZoneCollisionObject(triangles, transform));
            }
            byteReader.next32();

            groupList.add(new ZoneCollisionObjectGroup(objects));
        }

        int totalX = zoneBlocksX * subBlocksX;
        int totalZ = zoneBlocksZ * subBlocksZ;
        ZoneCollisionObjectGroup[][] grid = new ZoneCollisionObjectGroup[totalZ][totalX];

        byteReader.setPosition(mapAbsOff);
        int found = 0;
        outer:
        for (int z = 0; z < totalZ; z++) {
            for (int x = 0; x < totalX; x++) {
                int rawOffset = byteReader.next32();
                if (rawOffset == 0) {
                    continue;
                }
                found++;
                int fileOffset = rawOffset + header.getDataStartPosition();
                Integer groupIndex = groupByOffset.get(fileOffset);
                if (groupIndex != null) {
                    grid[z][x] = groupList.get(groupIndex);
                }
                if (found >= pairCount) {
                    break outer;
                }
            }
        }

        return new ZoneCollisionMap(zoneBlocksX, zoneBlocksZ, blockWidth, blockLength, subBlocksX, subBlocksZ, grid);
    }

    private static float[] readCollisionTransform(ByteReader byteReader, int absOffset) {
        int saved = byteReader.getPosition();
        byteReader.setPosition(absOffset);
        float[] matrix = new float[16];
        for (int i = 0; i < 16; i++) {
            matrix[i] = byteReader.nextFloat();
        }
        byteReader.setPosition(saved);
        return matrix;
    }

    private static List<ZoneCollisionTriangle> readCollisionMesh(
            ByteReader byteReader, int absOffset, SectionHeader header) {
        int saved = byteReader.getPosition();
        byteReader.setPosition(absOffset);

        int positionOffset = byteReader.next32() + header.getDataStartPosition();
        int directionOffset = byteReader.next32() + header.getDataStartPosition();
        int indexOffset = byteReader.next32() + header.getDataStartPosition();
        int numTris = byteReader.next16();
        byteReader.next16();

        List<ZoneCollisionTriangle> triangles = new ArrayList<>(numTris);

        byteReader.setPosition(indexOffset);
        for (int i = 0; i < numTris; i++) {
            int rawP0 = byteReader.next16();
            int rawP1 = byteReader.next16();
            int rawP2 = byteReader.next16();
            int rawD = byteReader.next16();

            int pOff0 = positionOffset + (rawP0 & 0x7FFF) * 12;
            int pOff1 = positionOffset + (rawP1 & 0x3FFF) * 12;
            int pOff2 = positionOffset + (rawP2 & 0x3FFF) * 12;
            int dOff = directionOffset + (rawD & 0x7FFF) * 12;

            int nextPos = byteReader.getPosition();

            float[] p0 = readVec3At(byteReader, pOff0);
            float[] p1 = readVec3At(byteReader, pOff1);
            float[] p2 = readVec3At(byteReader, pOff2);
            float[] d0 = readVec3At(byteReader, dOff);

            triangles.add(new ZoneCollisionTriangle(
                    p0[0], p0[1], p0[2],
                    p1[0], p1[1], p1[2],
                    p2[0], p2[1], p2[2],
                    d0[0], d0[1], d0[2]
            ));

            byteReader.setPosition(nextPos);
        }

        byteReader.setPosition(saved);
        return triangles;
    }

    private static float[] readVec3At(ByteReader byteReader, int absOffset) {
        int saved = byteReader.getPosition();
        byteReader.setPosition(absOffset);
        float[] v = {byteReader.nextFloat(), byteReader.nextFloat(), byteReader.nextFloat()};
        byteReader.setPosition(saved);
        return v;
    }

    private static List<ZoneObject> parseZoneObjects(ByteReader byteReader, int sectionEnd, int nodeCount,
            java.util.Map<Integer, Integer> cullingTableIndicesByOffset) {
        List<ZoneObject> zoneObjects = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            requireCanRead(byteReader, sectionEnd, 0x64, "zone object", "ZoneDef");
            String id = readTrimmedFixedString(byteReader, sectionEnd, 0x10);
            float posX = byteReader.nextFloat();
            float posY = byteReader.nextFloat();
            float posZ = byteReader.nextFloat();
            float rotX = byteReader.nextFloat();
            float rotY = byteReader.nextFloat();
            float rotZ = byteReader.nextFloat();
            float scaleX = byteReader.nextFloat();
            float scaleY = byteReader.nextFloat();
            float scaleZ = byteReader.nextFloat();
            String effectLink = readTrimmedFixedString(byteReader, sectionEnd, 4);
            float highDefThreshold = byteReader.nextFloat();
            float midDefThreshold = byteReader.nextFloat();
            float lowDefThreshold = byteReader.nextFloat();
            int flags0 = byteReader.next8();
            int flags1 = byteReader.next8();
            int flags2 = byteReader.next8();
            int flags3 = byteReader.next8();
            int cullingTableOffset = byteReader.next32();
            String environmentLink = readTrimmedFixedString(byteReader, sectionEnd, 4);
            int rawFileIdLink = byteReader.next32();
            int pointLight0 = mapZonePointLight(byteReader.next32());
            int pointLight1 = mapZonePointLight(byteReader.next32());
            int pointLight2 = mapZonePointLight(byteReader.next32());
            int pointLight3 = mapZonePointLight(byteReader.next32());
            int cullingTableIndex = cullingTableOffset == 0 ? -1
                    : cullingTableIndicesByOffset.getOrDefault(cullingTableOffset, -1);
            zoneObjects.add(new ZoneObject(
                    i,
                    id,
                    posX,
                    posY,
                    posZ,
                    rotX,
                    rotY,
                    rotZ,
                    scaleX,
                    scaleY,
                    scaleZ,
                    effectLink,
                    highDefThreshold,
                    midDefThreshold,
                    lowDefThreshold,
                    flags0,
                    flags1,
                    flags2,
                    flags3,
                    cullingTableIndex,
                    environmentLink,
                    rawFileIdLink == 0 ? -1 : rawFileIdLink,
                    pointLight0,
                    pointLight1,
                    pointLight2,
                    pointLight3
            ));
        }
        return zoneObjects;
    }

    private static int mapZonePointLight(int index) {
        return index > 0 ? index - 1 : index;
    }

    private static ZoneCullingTables parseZoneCullingTables(ByteReader byteReader, int sectionEnd, String sectionId) {
        requireCanRead(byteReader, sectionEnd, 8, "zone culling tables header", sectionId);
        int indexTableCount = byteReader.next32();
        if (indexTableCount == 0) {
            expectZero(byteReader.next32(), sectionId, "zone culling tables");
            return new ZoneCullingTables(List.of(), java.util.Map.of());
        }

        List<List<Integer>> tables = new ArrayList<>(indexTableCount);
        java.util.Map<Integer, Integer> offsetToIndex = new java.util.HashMap<>();
        for (int i = 0; i < indexTableCount; i++) {
            int offset = byteReader.getPosition();
            offsetToIndex.put(offset, i);
            requireCanRead(byteReader, sectionEnd, 4, "zone culling table", sectionId);
            int currentTableCount = byteReader.next32();
            List<Integer> table = new ArrayList<>(currentTableCount);
            for (int j = 0; j < currentTableCount; j++) {
                requireCanRead(byteReader, sectionEnd, 4, "zone culling table entry", sectionId);
                table.add(byteReader.next32());
            }
            tables.add(List.copyOf(table));
        }
        return new ZoneCullingTables(tables, java.util.Map.copyOf(offsetToIndex));
    }

    private static ZoneSpacePartitionNode parseZoneSpacePartitionNode(ByteReader byteReader, int sectionEnd,
            SectionHeader header, java.util.Map<Integer, Integer> cullingTableIndicesByOffset) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            requireCanRead(byteReader, sectionEnd, 12, "zone space tree vector", header.getSectionId());
            Vec3f point = readVec3f(byteReader);
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
            maxZ = Math.max(maxZ, point.z());
        }
        requireCanRead(byteReader, sectionEnd, 24, "zone space tree node header", header.getSectionId());
        int idxRef = byteReader.next32();
        int indexCount = byteReader.next32();
        boolean leafNode = indexCount > 0;
        int idxFileRef = leafNode ? idxRef + header.getDataStartPosition() : 0;
        int[] childOffsets = new int[4];
        for (int i = 0; i < 4; i++) {
            childOffsets[i] = byteReader.next32();
        }
        expectZero(byteReader.next32(), header.getSectionId(), "zone space tree");
        expectZero(byteReader.next32(), header.getSectionId(), "zone space tree");

        List<ZoneSpacePartitionNode> children = new ArrayList<>(4);
        for (int childOffset : childOffsets) {
            if (childOffset == 0) {
                children.add(null);
                continue;
            }
            byteReader.setPosition(childOffset + header.getDataStartPosition());
            children.add(parseZoneSpacePartitionNode(byteReader, sectionEnd, header, cullingTableIndicesByOffset));
        }

        List<Integer> containedObjects = new ArrayList<>();
        if (leafNode) {
            byteReader.setPosition(idxFileRef);
            for (int i = 0; i < indexCount; i++) {
                requireCanRead(byteReader, sectionEnd, 4, "zone leaf object index", header.getSectionId());
                containedObjects.add(byteReader.next32());
            }
        }

        return new ZoneSpacePartitionNode(
                leafNode,
                List.copyOf(containedObjects),
                new ZoneSpacePartitionBounds(minX, minY, minZ, maxX, maxY, maxZ),
                children
        );
    }

    private static List<String> readZonePointLights(ByteReader byteReader, int sectionEnd, String sectionId) {
        List<String> pointLightLinks = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            requireCanRead(byteReader, sectionEnd, 4, "zone point light id", sectionId);
            String idLink = byteReader.nextString(4);
            if (isZeroString(idLink)) {
                break;
            }
            pointLightLinks.add(idLink);
            for (int j = 0; j < 0x12; j++) {
                requireCanRead(byteReader, sectionEnd, 4, "zone point light reserved entry", sectionId);
                expectZero(byteReader.next32(), sectionId, "zone point light reserved entry");
            }
        }
        return pointLightLinks;
    }

    private static ZoneMeshResource parseZoneMeshSection(SectionHeader header, ByteReader byteReader) {
        int sectionEnd = getSectionEnd(header, byteReader);

        // Mirror XIM ZoneMeshSection.read() exactly:
        // offsetFromDataStart → read decryptInfo(4) + keyConfigData(4) + unkString(8) + name(16) = 0x20 bytes
        byteReader.setPosition(header.getDataStartPosition());
        // metadata low 24 bits = totalSize, high 8 bits = mode.
        // Read defensively: if we can't read 4 bytes the section is truncated.
        boolean metadataValid = canRead(byteReader, sectionEnd, 4);
        int metadata = metadataValid ? byteReader.next32() : 0;
        // Use unsigned extraction — mode occupies the high byte and could be >= 0x80
        int totalSize = metadataValid ? (metadata & 0x00FFFFFF) : -1;
        int mode = metadataValid ? ((metadata >>> 24) & 0xFF) : -1;

        // XIM reference: val configData = keyConfigData and 0x000000FF
        //   meshTypeFlag = configData and 0x1  → 0=TriMesh, 1=TriStrip
        //   vertexBlendEnabled = (configData and 0x2) != 0
        // The full int32 keyConfigData may have its high bit set (e.g. 0x8232C201),
        // which would make it negative in Java. Do NOT use ">= 0" to guard against
        // the read-failed sentinel; instead check canRead() first, then mask to low byte.
        boolean keyConfigValid = canRead(byteReader, sectionEnd, 4);
        int keyConfigData = keyConfigValid ? byteReader.next32() : 0;
        // Only the low byte is the config byte (meshType + vertexBlend flags)
        int configByte = keyConfigData & 0xFF;
        boolean meshTypeIsStrip = (configByte & 0x1) != 0;
        boolean vertexBlendEnabled = (configByte & 0x2) != 0;
        boolean secondPassFlag = false; // determined by decryptor; keep for header compat

        // skip 8-byte author string + 16-byte name = 24 bytes to reach defStart
        if (canRead(byteReader, sectionEnd, 24)) {
            byteReader.setPosition(byteReader.getPosition() + 8); // unkString
        }
        // XIM: name = byteReader.nextString(0x10).trimEnd()
        String meshName = readTrimmedFixedString(byteReader, sectionEnd, 0x10);
        if (meshName.isEmpty()) meshName = header.getSectionId() != null ? header.getSectionId().trim() : "";

        // defStart = byteReader.position = dataStartPosition + 0x20
        int defStart = byteReader.getPosition();

        List<ZoneMeshChunk> meshChunks = new ArrayList<>();
        List<ZoneMeshChunk> collisionChunks = new ArrayList<>();

        // Minimum section: meshCount0(4) + bbox0(6*4=24) + dataOffset(4) = 0x20 bytes
        if (defStart + 0x20 > sectionEnd) {
            throw new IllegalStateException("Section too short for header at " + header.getSectionId());
        }

        int meshCount0 = byteReader.next32();
        // boundingBox0: 6 floats
        for (int i = 0; i < 6; i++) byteReader.nextFloat();

        // XIM: val section1DataStart = defStart + byteReader.next32()
        int dataOffset = byteReader.next32();
        int section1DataStart = defStart + dataOffset;
        System.err.printf("[ZONE-PARSE] meshCount0=%d dataOffset=0x%08x defStart=0x%08x section1DataStart=0x%08x%n", 
            meshCount0, dataOffset, defStart, section1DataStart);

        // "hit"-type models: meshCount0 == 0 → no mesh data
        // XIM: throws if not at sectionEnd
        if (meshCount0 == 0) {
            if (byteReader.getPosition() != sectionEnd) {
                throw new IllegalStateException("Expected to be at section end after hit-model header");
            }
            return buildZoneMeshResource(header, mode, totalSize, -1, secondPassFlag, meshName, meshChunks, collisionChunks);
        }

        int meshCount1 = byteReader.next32();
        // boundingBox1: 6 floats
        for (int i = 0; i < 6; i++) byteReader.nextFloat();
        byteReader.next32(); // unknown

        // XIM: throws if position != section1DataStart
        if (byteReader.getPosition() != section1DataStart) {
            throw new IllegalStateException("Position " + byteReader.getPosition() + " != section1DataStart " + section1DataStart);
        }

        for (int meshIndex = 0; meshIndex < meshCount1; meshIndex++) {
            ZoneMeshChunkParseResult result = parseZoneMeshChunk(byteReader, byteReader.getPosition(), sectionEnd, vertexBlendEnabled, meshTypeIsStrip);
            if (result == null) {
                throw new IllegalStateException("Null mesh chunk at index " + meshIndex);
            }
            // align to 4-byte boundary after each mesh (XIM: byteReader.align0x04())
            int aligned = (result.nextOffset() + 3) & ~3;
            meshChunks.add(result.chunk());
            byteReader.setPosition(aligned);
        }

        return buildZoneMeshResource(header, mode, totalSize, -1, secondPassFlag, meshName, meshChunks, collisionChunks);
    }

    private static ZoneMeshResource buildZoneMeshResource(SectionHeader header, int mode, int totalSize,
            int keyIndex, boolean secondPassFlag, String meshName,
            List<ZoneMeshChunk> meshChunks, List<ZoneMeshChunk> collisionChunks) {
        return new ZoneMeshResource(
                header.getSectionId(),
                sectionTypeName(header),
                header.getSectionStartPosition(),
                header.getSectionSize(),
                mode,
                totalSize,
                keyIndex,
                secondPassFlag,
                meshName,
                meshChunks,
                collisionChunks
        );
    }

    private static ZoneMeshChunkParseResult parseZoneMeshChunk(ByteReader byteReader, int meshOffset,
            int sectionEnd, boolean sectionVertexBlend, boolean sectionIsTriStrip) {
        if (meshOffset + 0x14 > sectionEnd) {
            throw new IllegalStateException("Mesh chunk at " + meshOffset + " extends past section end " + sectionEnd);
        }

        byteReader.setPosition(meshOffset);
        // XIM: val textureName = byteReader.nextString(0x10) — no trimming
        String textureName = byteReader.nextString(0x10);
        int numVerts = byteReader.next16();
        int meshFlags = byteReader.next16();

        // XIM: blendEnabled (flags & 0x8000) = render alpha blend state (not vertex layout)
        //      backFaceCulling = (flags & 0x2000) == 0
        //      vertexBlendEnabled (from section keyConfigData & 0x2) = vertex layout (3 vs 2 positions)
        boolean renderBlend = (meshFlags & 0x8000) != 0;
        boolean invertCull = (meshFlags & 0x2000) != 0;
        // Use section-level flag for vertex layout; stride: blend=48, non-blend=36
        int vertexStride = sectionVertexBlend ? 48 : 36;

        if ((long) byteReader.getPosition() + ((long) numVerts * vertexStride) > sectionEnd) {
            throw new IllegalStateException("Vertex data for " + numVerts + " verts (stride " + vertexStride + ") extends past section end");
        }

        List<ZoneMeshVertex> vertices = new ArrayList<>(numVerts);
        for (int vertexIndex = 0; vertexIndex < numVerts; vertexIndex++) {
            float px = byteReader.nextFloat();
            float py = byteReader.nextFloat();
            float pz = byteReader.nextFloat();
            float p1x = 0f, p1y = 0f, p1z = 0f;
            if (sectionVertexBlend) {
                p1x = byteReader.nextFloat();
                p1y = byteReader.nextFloat();
                p1z = byteReader.nextFloat();
            }
            float nx = byteReader.nextFloat();
            float ny = byteReader.nextFloat();
            float nz = byteReader.nextFloat();
            int colorB = byteReader.next8();
            int colorG = byteReader.next8();
            int colorR = byteReader.next8();
            int colorA = byteReader.next8();
            float u = byteReader.nextFloat();
            float v = byteReader.nextFloat();
            vertices.add(new ZoneMeshVertex(px, py, pz, p1x, p1y, p1z, nx, ny, nz, colorB, colorG, colorR, colorA, u, v));
        }

        // XIM: val numIndicies = byteReader.next16(); val unk1 = byteReader.next16()
        int numIndices = byteReader.next16();
        byteReader.next16(); // unk1

        List<Integer> indices = new ArrayList<>(numIndices);
        for (int i = 0; i < numIndices; i++) {
            indices.add(byteReader.next16());
        }

        return new ZoneMeshChunkParseResult(
                new ZoneMeshChunk(textureName, renderBlend, invertCull, sectionIsTriStrip, vertices, indices),
                byteReader.getPosition()
        );
    }

    private static String sanitizeZeroTerminatedPrefix(String value) {
        if (value == null) return "";
        int end = value.indexOf('\0');
        return end >= 0 ? value.substring(0, end) : value;
    }

    private static ByteReader copyReader(ByteReader byteReader) {
        ByteReader copy = new ByteReader(byteReader.getBytes().clone(), byteReader.getResourceName());
        copy.setPosition(byteReader.getPosition());
        return copy;
    }

    private static int getSectionEnd(SectionHeader header, ByteReader byteReader) {
        return Math.min(header.getNextSectionOffset(), byteReader.getBytes().length);
    }

    private static boolean canRead(ByteReader byteReader, int sectionEnd, int size) {
        return byteReader.getPosition() >= 0 && byteReader.getPosition() + size <= sectionEnd;
    }

    private static void requireCanRead(ByteReader byteReader, int sectionEnd, int size, String label, String sectionId) {
        if (!canRead(byteReader, sectionEnd, size)) {
            throw new IllegalStateException("[" + sectionId + "] Cannot read " + label + " at 0x"
                    + Integer.toHexString(byteReader.getPosition()));
        }
    }

    private static void requireCanReadAt(int position, int sectionEnd, int size, String label, String sectionId) {
        if (position < 0 || position + size > sectionEnd) {
            throw new IllegalStateException("[" + sectionId + "] Cannot read " + label + " at 0x"
                    + Integer.toHexString(position));
        }
    }

    private static int read16IfAvailable(ByteReader byteReader, int sectionEnd) {
        if (!canRead(byteReader, sectionEnd, 2)) {
            return -1;
        }
        return byteReader.next16();
    }

    private static int read8IfAvailable(ByteReader byteReader, int sectionEnd) {
        if (!canRead(byteReader, sectionEnd, 1)) {
            return -1;
        }
        return byteReader.next8();
    }

    private static float readFloatIfAvailable(ByteReader byteReader, int sectionEnd) {
        if (!canRead(byteReader, sectionEnd, 4)) {
            return Float.NaN;
        }
        return byteReader.nextFloat();
    }

    private static String readFixedString(ByteReader byteReader, int sectionEnd, int length) {
        if (length <= 0 || !canRead(byteReader, sectionEnd, 1)) {
            return "";
        }
        int readable = Math.min(length, sectionEnd - byteReader.getPosition());
        if (readable <= 0) {
            return "";
        }
        String raw = byteReader.nextString(readable);
        return sanitizeZeroTerminatedPrefix(raw);
    }

    private static String readTrimmedFixedString(ByteReader byteReader, int sectionEnd, int length) {
        return readFixedString(byteReader, sectionEnd, length).trim();
    }

    private static int read32IfAvailable(ByteReader byteReader, int sectionEnd) {
        if (!canRead(byteReader, sectionEnd, 4)) {
            return -1;
        }
        return byteReader.next32();
    }

    private static Vec2f readVec2f(ByteReader byteReader) {
        return new Vec2f(byteReader.nextFloat(), byteReader.nextFloat());
    }

    private static Vec3f readVec3f(ByteReader byteReader) {
        return new Vec3f(byteReader.nextFloat(), byteReader.nextFloat(), byteReader.nextFloat());
    }

    private static Quat4f readQuat4f(ByteReader byteReader) {
        return new Quat4f(byteReader.nextFloat(), byteReader.nextFloat(), byteReader.nextFloat(), byteReader.nextFloat());
    }

    private static BgraColor readBgraColor(ByteReader byteReader) {
        return new BgraColor(byteReader.next8(), byteReader.next8(), byteReader.next8(), byteReader.next8());
    }

    private static Float nextSkeletonBoundingFloat(ByteReader byteReader) {
        float value = byteReader.nextFloat();
        return Float.floatToRawIntBits(value) == 0xCDCDCDCD ? null : value;
    }

    private static void expectZero(int value, String sectionId, String label) {
        if (value != 0) {
            throw new IllegalStateException("[" + sectionId + "] Expected zero for " + label + ", got 0x"
                    + Integer.toHexString(value));
        }
    }

    private static boolean isZeroString(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != 0) {
                return false;
            }
        }
        return true;
    }

    private static final class SkeletonMeshWorkingVertex {
        private final int sourceIndex;
        private SkeletonMeshJointRef jointRef0;
        private Integer jointIndex0;
        private SkeletonMeshJointRef jointRef1;
        private Integer jointIndex1;
        private Vec3f p0 = new Vec3f(0f, 0f, 0f);
        private Vec3f p1 = new Vec3f(0f, 0f, 0f);
        private Vec3f n0 = new Vec3f(0f, 0f, 0f);
        private Vec3f n1 = new Vec3f(0f, 0f, 0f);
        private float joint0Weight = 1.0f;
        private float joint1Weight = 0.0f;

        private SkeletonMeshWorkingVertex(int sourceIndex) {
            this.sourceIndex = sourceIndex;
        }

        private SkeletonMeshVertex toModel() {
            return new SkeletonMeshVertex(sourceIndex, jointRef0, jointIndex0, jointRef1, jointIndex1, p0, p1, n0, n1,
                    joint0Weight, joint1Weight);
        }
    }

    private record ZoneCullingTables(List<List<Integer>> tables, java.util.Map<Integer, Integer> offsetToIndex) {
    }

    private record ZoneMeshChunkParseResult(ZoneMeshChunk chunk, int nextOffset) {
    }
}
