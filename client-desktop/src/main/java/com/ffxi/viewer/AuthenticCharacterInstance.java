package com.ffxi.viewer;

import com.ffxi.model.SkeletonBoundingBox;
import com.ffxi.model.SkeletonJointReference;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.model.SkeletonMeshVertex;
import com.ffxi.model.SkeletonResource;
import com.ffxi.model.Vec3f;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.renderer.CharacterMeshBuffer;
import com.ffxi.viewer.renderer.CharacterShader;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Encapsulates an authentic retail FFXI character instance (player avatar, remote player, or NPC),
 * including GPU skinned mesh buffers, texture mappings, and a per-actor locomotion state controller.
 * GPU buffers and texture atlases may be either exclusively owned by this instance or shared from
 * {@link CharacterAssetCache}.
 */
public final class AuthenticCharacterInstance implements AutoCloseable {

    public static final int JOINT_REF_ABOVE_HEAD = 2;
    public static final int JOINT_REF_RIGHT_FOOT = 8;
    public static final int JOINT_REF_LEFT_FOOT = 9;
    public static final int JOINT_REF_LEFT_HAND = 126;
    public static final int JOINT_REF_RIGHT_HAND = 127;

    public record WorldBounds(float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {
    }

    private final LoadedCharacter character;
    private final List<CharacterMeshBuffer> meshBuffers;
    private final CharacterTextureLoader textureLoader;
    private final AuthenticLocomotionController locomotion;
    private final boolean ownsGpuResources;
    private final Runnable onCloseCallback;
    private float discardThreshold = 0.20f;
    private Matrix4f[] poseOverride;
    private boolean closed;

    public AuthenticCharacterInstance(LoadedCharacter character) {
        this(
                character,
                buildMeshBuffers(character),
                new CharacterTextureLoader(character.textures()),
                true,
                null
        );
    }

    public AuthenticCharacterInstance(
            LoadedCharacter character,
            List<CharacterMeshBuffer> meshBuffers,
            CharacterTextureLoader textureLoader,
            boolean ownsGpuResources
    ) {
        this(character, meshBuffers, textureLoader, ownsGpuResources, null);
    }

    public AuthenticCharacterInstance(
            LoadedCharacter character,
            List<CharacterMeshBuffer> meshBuffers,
            CharacterTextureLoader textureLoader,
            boolean ownsGpuResources,
            Runnable onCloseCallback
    ) {
        this.character = character;
        this.meshBuffers = ownsGpuResources ? meshBuffers : Collections.unmodifiableList(meshBuffers);
        this.textureLoader = textureLoader;
        this.ownsGpuResources = ownsGpuResources;
        this.onCloseCallback = onCloseCallback;
        this.locomotion = new AuthenticLocomotionController(
                character.skeleton(),
                character.slot0Animations(),
                character.slot1Animations()
        );
    }

    private static List<CharacterMeshBuffer> buildMeshBuffers(LoadedCharacter character) {
        List<CharacterMeshBuffer> buffers = new ArrayList<>(character.meshes().size());
        for (SkeletonMeshMesh mesh : character.meshes()) {
            buffers.add(new CharacterMeshBuffer(mesh));
        }
        return buffers;
    }

    public LoadedCharacter getCharacter() {
        return character;
    }

    public AuthenticLocomotionController getLocomotion() {
        return locomotion;
    }

    public void setDiscardThreshold(float discardThreshold) {
        this.discardThreshold = discardThreshold;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setPoseOverride(Matrix4f[] jointMatrices) {
        this.poseOverride = jointMatrices;
    }

    public void clearPoseOverride() {
        this.poseOverride = null;
    }

    public void update(float dt, int movementFlags, float horizontalSpeed) {
        locomotion.update(dt, movementFlags, horizontalSpeed);
    }

    public void render(
            CharacterShader shader,
            Matrix4f proj,
            Matrix4f view,
            float posX,
            float posY,
            float posZ,
            float yawDegrees
    ) {
        if (closed || shader == null || meshBuffers.isEmpty()) {
            return;
        }
        Matrix4f[] activePose = (poseOverride != null && poseOverride.length > 0)
                ? poseOverride
                : locomotion.computePoseMatrices();
        renderPose(shader, proj, view, activePose, posX, posY, posZ, yawDegrees);
    }

    /**
     * Computes the world-space axis-aligned bounding box of this character in its active pose
     * ({@code poseOverride} if set, otherwise {@link AuthenticLocomotionController#computePoseMatrices()}).
     */
    public WorldBounds computePosedWorldBounds(float posX, float posY, float posZ, float yawDegrees) {
        Matrix4f[] joints = (poseOverride != null && poseOverride.length > 0)
                ? poseOverride
                : locomotion.computePoseMatrices();

        float scale = character.effectiveScale();
        Matrix4f modelMat = new Matrix4f()
                .translate(posX, posY - character.feetLocalY() * scale, posZ)
                .rotateY((float) Math.toRadians(yawDegrees - 90.0f))
                .scale(scale);

        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        if (joints != null && joints.length > 0) {
            Vector4f v0 = new Vector4f();
            Vector4f v1 = new Vector4f();
            for (SkeletonMeshMesh mesh : character.meshes()) {
                for (SkeletonMeshVertex v : mesh.resolvedVertices()) {
                    float w0 = v.joint0Weight();
                    int j0 = clampJointIndex(v.jointIndex0(), joints.length);
                    int j1 = clampJointIndex(v.jointIndex1(), joints.length);

                    Vec3f p0 = v.p0();
                    Vec3f p1 = v.p1();
                    float p0x = p0 != null ? p0.x() : 0.0f;
                    float p0y = p0 != null ? p0.y() : 0.0f;
                    float p0z = p0 != null ? p0.z() : 0.0f;
                    float p1x = p1 != null ? p1.x() : 0.0f;
                    float p1y = p1 != null ? p1.y() : 0.0f;
                    float p1z = p1 != null ? p1.z() : 0.0f;

                    v0.set(p0x, p0y, p0z, w0);
                    joints[j0].transform(v0);
                    v1.set(p1x, p1y, p1z, 1.0f - w0);
                    joints[j1].transform(v1);
                    v0.add(v1);
                    modelMat.transform(v0);

                    if (Float.isFinite(v0.x) && Float.isFinite(v0.y) && Float.isFinite(v0.z)) {
                        minX = Math.min(minX, v0.x);
                        maxX = Math.max(maxX, v0.x);
                        minY = Math.min(minY, v0.y);
                        maxY = Math.max(maxY, v0.y);
                        minZ = Math.min(minZ, v0.z);
                        maxZ = Math.max(maxZ, v0.z);
                    }
                }
            }
        }

        if (!Float.isFinite(minX)) {
            float halfRadius = Math.max(0.25f, character.radius() * scale);
            float height = Math.max(0.5f, character.feetOffsetY() * 2.0f * scale);
            return new WorldBounds(
                    posX - halfRadius, posX + halfRadius,
                    posY - height, posY,
                    posZ - halfRadius, posZ + halfRadius
            );
        }
        return new WorldBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static int clampJointIndex(Integer jointIndex, int jointCount) {
        if (jointIndex == null || jointCount <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(jointCount - 1, jointIndex));
    }

    public void renderPose(
            CharacterShader shader,
            Matrix4f proj,
            Matrix4f view,
            Matrix4f[] jointMatrices,
            float posX,
            float posY,
            float posZ,
            float yawDegrees
    ) {
        if (closed || shader == null || meshBuffers.isEmpty() || jointMatrices == null || jointMatrices.length == 0) {
            return;
        }

        shader.bind();

        float scale = character.effectiveScale();
        Matrix4f charModelMat = new Matrix4f()
                .translate(posX, posY - character.feetLocalY() * scale, posZ)
                .rotateY((float) Math.toRadians(yawDegrees - 90.0f))
                .scale(scale);

        shader.setMatrices(proj, view, charModelMat);
        shader.setDiscardThreshold(discardThreshold);
        shader.uploadJointMatrices(jointMatrices);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        for (CharacterMeshBuffer meshBuffer : meshBuffers) {
            GL11.glFrontFace(meshBuffer.mirrored() ? GL11.GL_CW : GL11.GL_CCW);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureLoader.textureId(meshBuffer.textureName()));
            meshBuffer.draw();
        }
        GL11.glFrontFace(GL11.GL_CCW);
    }

    /**
     * Renders the skinned character flat-white from a light-space projection for retail silhouette ground shadows.
     */
    public void renderSilhouette(
            CharacterShader shader,
            Matrix4f lightProj,
            Matrix4f lightView,
            float posX,
            float posY,
            float posZ,
            float yawDegrees
    ) {
        if (closed || shader == null || meshBuffers.isEmpty()) {
            return;
        }
        Matrix4f[] activePose = (poseOverride != null && poseOverride.length > 0)
                ? poseOverride
                : locomotion.computePoseMatrices();
        if (activePose == null || activePose.length == 0) {
            return;
        }

        shader.bind();
        float scale = character.effectiveScale();
        Matrix4f charModelMat = new Matrix4f()
                .translate(posX, posY - character.feetLocalY() * scale, posZ)
                .rotateY((float) Math.toRadians(yawDegrees - 90.0f))
                .scale(scale);

        shader.setMatrices(lightProj, lightView, charModelMat);
        shader.setDiscardThreshold(discardThreshold);
        shader.setSilhouetteMode(true);
        shader.uploadJointMatrices(activePose);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        for (CharacterMeshBuffer meshBuffer : meshBuffers) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureLoader.textureId(meshBuffer.textureName()));
            meshBuffer.draw();
        }

        shader.setSilhouetteMode(false);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glFrontFace(GL11.GL_CCW);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * Resolves a retail {@link SkeletonJointReference} position in skeleton local space for the current pose.
     */
    public Optional<Vector3f> resolveJointReferenceLocalPosition(int referenceIndex) {
        SkeletonResource skeleton = character != null ? character.skeleton() : null;
        if (skeleton == null || referenceIndex < 0 || referenceIndex >= skeleton.jointReferences().size()) {
            return Optional.empty();
        }
        Matrix4f[] jointMatrices = locomotion.computePoseMatrices();
        if (jointMatrices == null || jointMatrices.length == 0) {
            return Optional.empty();
        }
        SkeletonJointReference ref = skeleton.jointReferences().get(referenceIndex);
        if (ref == null || ref.index() < 0 || ref.index() >= jointMatrices.length) {
            return Optional.empty();
        }
        Vec3f offset = ref.positionOffset();
        Vector3f pos = new Vector3f(
                offset != null ? offset.x() : 0.0f,
                offset != null ? offset.y() : 0.0f,
                offset != null ? offset.z() : 0.0f
        );
        jointMatrices[ref.index()].transformPosition(pos);
        if (!Float.isFinite(pos.x) || !Float.isFinite(pos.y) || !Float.isFinite(pos.z)) {
            return Optional.empty();
        }
        return Optional.of(pos);
    }

    /**
     * Resolves a retail {@link SkeletonJointReference} position in world space for the current pose and actor transform.
     */
    public Optional<Vector3f> resolveJointReferenceWorldPosition(
            int referenceIndex,
            float posX,
            float posY,
            float posZ,
            float yawDegrees
    ) {
        Optional<Vector3f> local = resolveJointReferenceLocalPosition(referenceIndex);
        if (local.isEmpty()) {
            return Optional.empty();
        }
        float scale = character.effectiveScale();
        Matrix4f charModelMat = new Matrix4f()
                .translate(posX, posY - character.feetLocalY() * scale, posZ)
                .rotateY((float) Math.toRadians(yawDegrees - 90.0f))
                .scale(scale);
        Vector3f world = charModelMat.transformPosition(new Vector3f(local.get()));
        if (!Float.isFinite(world.x) || !Float.isFinite(world.y) || !Float.isFinite(world.z)) {
            return Optional.empty();
        }
        return Optional.of(world);
    }

    /**
     * Returns the primary retail {@link SkeletonBoundingBox} parsed from the skeleton DAT, if present.
     */
    public Optional<SkeletonBoundingBox> primaryBoundingBox() {
        SkeletonResource skeleton = character != null ? character.skeleton() : null;
        if (skeleton == null || skeleton.boundingBoxes().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(skeleton.boundingBoxes().getFirst());
    }

    /**
     * Computes the world-space vertical offset above the actor's feet ({@code -Y = Up}) where floating
     * nameplates or overhead effects should anchor, preferring retail {@link SkeletonJointReference}
     * ({@link #JOINT_REF_ABOVE_HEAD}), then {@link SkeletonBoundingBox}, then mesh bounds fallback.
     */
    public float resolveNameplateHeightOffset(float fallbackHeight) {
        if (character == null) {
            return fallbackHeight;
        }
        float scale = character.effectiveScale();

        Optional<Vector3f> aboveHead = resolveJointReferenceLocalPosition(JOINT_REF_ABOVE_HEAD);
        if (aboveHead.isPresent()) {
            float offset = (character.feetLocalY() - aboveHead.get().y) * scale;
            if (Float.isFinite(offset) && offset >= 0.4f && offset <= 8.0f) {
                return offset;
            }
        }

        Optional<SkeletonBoundingBox> bbox = primaryBoundingBox();
        if (bbox.isPresent()) {
            float topLocalY = Math.min(bbox.get().yMin(), bbox.get().yMax());
            float offset = (character.feetLocalY() - topLocalY) * scale;
            if (Float.isFinite(offset) && offset >= 0.4f && offset <= 8.0f) {
                return offset + 0.15f;
            }
        }

        float height = character.feetOffsetY() * 2.0f * scale;
        if (!Float.isFinite(height) || height <= 0.25f) {
            return fallbackHeight;
        }
        return height + 0.35f;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (ownsGpuResources) {
            for (CharacterMeshBuffer mb : meshBuffers) {
                mb.close();
            }
            meshBuffers.clear();
            textureLoader.close();
        }
        if (onCloseCallback != null) {
            onCloseCallback.run();
        }
    }
}
