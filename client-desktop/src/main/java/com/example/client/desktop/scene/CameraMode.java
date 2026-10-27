package com.example.client.desktop.scene;

/**
 * Camera operational modes supported by the unified engine SceneCamera.
 */
public enum CameraMode {
    /**
     * Third-person gameplay camera orbiting the active player/target entity.
     */
    THIRD_PERSON,

    /**
     * Directed in-game cutscene camera with explicitly scripted eye, target, roll, and FOV.
     */
    CUTSCENE,

    /**
     * Free developer inspection orbit camera around arbitrary focal points.
     */
    FREE_ORBIT
}
