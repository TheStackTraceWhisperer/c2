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
import java.util.List;

/**
 * Encapsulates an authentic retail FFXI character instance (player avatar, remote player, or NPC),
 * including GPU skinned mesh buffers, texture mappings, and locomotion state controller.
 */
public final class AuthenticCharacterInstance implements AutoCloseable {

    private final LoadedCharacter character;
    private final List<CharacterMeshBuffer> meshBuffers;
    private final CharacterTextureLoader textureLoader;
    private final AuthenticLocomotionController locomotion;

    public AuthenticCharacterInstance(LoadedCharacter character) {
        this.character = character;
        this.meshBuffers = new ArrayList<>(character.meshes().size());
        for (SkeletonMeshMesh mesh : character.meshes()) {
            this.meshBuffers.add(new CharacterMeshBuffer(mesh));
        }
        this.textureLoader = new CharacterTextureLoader(character.textures());
        this.locomotion = new AuthenticLocomotionController(
                character.skeleton(),
                character.slot0Animations(),
                character.slot1Animations()
        );
    }

    public LoadedCharacter getCharacter() {
        return character;
    }

    public AuthenticLocomotionController getLocomotion() {
        return locomotion;
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
        if (shader == null || meshBuffers.isEmpty()) {
            return;
        }

        Matrix4f[] jointMatrices = locomotion.computePoseMatrices();
        shader.bind();

        Matrix4f charModelMat = new Matrix4f()
                .scale(1f, -1f, -1f)
                .translate(character.modelOffset())
                .translate(posX, -character.feetOffsetY() - posY, -posZ)
                .rotateY((float) Math.toRadians(yawDegrees));

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
        for (CharacterMeshBuffer mb : meshBuffers) {
            mb.close();
        }
        meshBuffers.clear();
        textureLoader.close();
    }
}
