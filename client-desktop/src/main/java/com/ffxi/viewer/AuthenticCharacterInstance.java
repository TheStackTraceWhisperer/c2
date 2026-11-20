package com.ffxi.viewer;

import com.ffxi.model.SkeletonMeshMesh;
import com.ffxi.viewer.model.LoadedCharacter;
import com.ffxi.viewer.renderer.CharacterMeshBuffer;
import com.ffxi.viewer.renderer.CharacterShader;
import com.ffxi.viewer.renderer.CharacterTextureLoader;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates an authentic retail FFXI character instance (player avatar, remote player, or NPC),
 * including GPU skinned mesh buffers, texture mappings, and a per-actor locomotion state controller.
 * GPU buffers and texture atlases may be either exclusively owned by this instance or shared from
 * {@link CharacterAssetCache}.
 */
public final class AuthenticCharacterInstance implements AutoCloseable {

    private final LoadedCharacter character;
    private final List<CharacterMeshBuffer> meshBuffers;
    private final CharacterTextureLoader textureLoader;
    private final AuthenticLocomotionController locomotion;
    private final boolean ownsGpuResources;
    private boolean closed;

    public AuthenticCharacterInstance(LoadedCharacter character) {
        this(
                character,
                buildMeshBuffers(character),
                new CharacterTextureLoader(character.textures()),
                true
        );
    }

    public AuthenticCharacterInstance(
            LoadedCharacter character,
            List<CharacterMeshBuffer> meshBuffers,
            CharacterTextureLoader textureLoader,
            boolean ownsGpuResources
    ) {
        this.character = character;
        this.meshBuffers = ownsGpuResources ? meshBuffers : Collections.unmodifiableList(meshBuffers);
        this.textureLoader = textureLoader;
        this.ownsGpuResources = ownsGpuResources;
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

        Matrix4f[] jointMatrices = locomotion.computePoseMatrices();
        shader.bind();

        float scale = character.effectiveScale();
        Matrix4f charModelMat = new Matrix4f()
                .translate(posX, posY - character.feetLocalY() * scale, posZ)
                .rotateY((float) Math.toRadians(yawDegrees - 90.0f))
                .scale(scale);

        shader.setMatrices(proj, view, charModelMat);
        shader.setDiscardThreshold(0.0f);
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
    }
}
