package com.ffxi.viewer;

import com.ffxi.model.SkeletonBoundingBox;
import com.ffxi.model.SkeletonJointReference;
import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.model.SkeletonResource;
import com.ffxi.model.Vec3f;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.renderer.CharacterMeshBuffer;
import com.ffxi.viewer.renderer.CharacterShader;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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

    private final LoadedCharacter character;
    private final List<CharacterMeshBuffer> meshBuffers;
    private final CharacterTextureLoader textureLoader;
    private final AuthenticLocomotionController locomotion;
    private final boolean ownsGpuResources;
    private final Runnable onCloseCallback;
    private float discardThreshold = 0.20f;
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
        renderPose(shader, proj, view, locomotion.computePoseMatrices(), posX, posY, posZ, yawDegrees);
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
