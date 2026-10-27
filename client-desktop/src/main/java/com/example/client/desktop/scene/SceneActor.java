package com.example.client.desktop.scene;

import com.example.common.protocol.MovementFlags;
import com.ffxi.viewer.AuthenticCharacterInstance;
import com.ffxi.viewer.renderer.CharacterShader;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;

/**
 * Interactive or ambient actor node in the 3D WorldScene graph.
 * Represents player avatars, NPCs, and monsters with authentic dual-bone skeletal animation.
 */
@Getter
@Setter
public class SceneActor {

    public enum ActorType {
        PLAYER,
        NPC,
        MONSTER
    }

    private final long id;
    private String name;
    private ActorType type = ActorType.PLAYER;
    private boolean isLocalPlayer = false;
    private boolean visible = true;

    // Spatial transform
    private float x = 0.0f;
    private float y = 0.0f;
    private float z = 0.0f;
    private float yaw = 0.0f;
    private float pitch = 0.0f;
    private float roll = 0.0f;

    // Movement & animation
    private int movementFlags = MovementFlags.IDLE;
    private float movementSpeed = 0.0f;
    private AuthenticCharacterInstance visualModel;

    public SceneActor(long id, String name, ActorType type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public void setPosition(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void setRotation(float yaw) {
        this.yaw = yaw;
    }

    /**
     * Updates locomotion animation tracks and skeleton based on elapsed time.
     */
    public void update(float dt) {
        if (visualModel != null) {
            visualModel.update(dt, movementFlags, movementSpeed);
        }
    }

    /**
     * Renders the actor using authentic GPU linear blend skinning and CharacterShader.
     */
    public void render(CharacterShader shader, Matrix4f proj, Matrix4f view) {
        if (!visible || visualModel == null) {
            return;
        }
        visualModel.render(shader, proj, view, x, y, z, yaw);
    }
}
