package com.ffxi.model;

public record ZoneObject(
        int index,
        String id,
        float posX,
        float posY,
        float posZ,
        float rotX,
        float rotY,
        float rotZ,
        float scaleX,
        float scaleY,
        float scaleZ,
        String effectLink,
        float highDefThreshold,
        float midDefThreshold,
        float lowDefThreshold,
        int flags0,
        int flags1,
        int flags2,
        int flags3,
        int cullingTableLink,
        String environmentLink,
        int fileIdLink,
        int pointLight0,
        int pointLight1,
        int pointLight2,
        int pointLight3
) {
    public int cullingTableIndex() {
        return cullingTableLink;
    }
}
