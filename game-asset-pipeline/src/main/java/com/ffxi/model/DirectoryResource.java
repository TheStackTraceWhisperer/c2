package com.ffxi.model;

import java.util.ArrayList;
import java.util.List;

public record DirectoryResource(
        String sectionId,
        String sectionType,
        int sectionStart,
        int sectionSize,
        DirectoryResource parent,
        List<ResourceNode> children
) implements ResourceNode {
    public DirectoryResource(String sectionId, String sectionType, int sectionStart, int sectionSize, DirectoryResource parent) {
        this(sectionId, sectionType, sectionStart, sectionSize, parent, new ArrayList<>());
    }

    public void addChild(ResourceNode node) {
        children.add(node);
    }
}
