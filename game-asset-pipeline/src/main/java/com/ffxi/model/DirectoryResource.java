package com.ffxi.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Directory node of the resource graph. equals/hashCode/toString intentionally do not recurse into
 * {@code parent} or {@code children}, since parent&lt;-&gt;children cycles would overflow the stack.
 */
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DirectoryResource other)) {
            return false;
        }
        return sectionStart == other.sectionStart
                && sectionSize == other.sectionSize
                && Objects.equals(sectionId, other.sectionId)
                && Objects.equals(sectionType, other.sectionType)
                && Objects.equals(parentSectionId(), other.parentSectionId())
                && parentStart() == other.parentStart();
    }

    @Override
    public int hashCode() {
        return Objects.hash(sectionId, sectionType, sectionStart, sectionSize, parentSectionId(), parentStart());
    }

    @Override
    public String toString() {
        return "DirectoryResource[sectionId=" + sectionId
                + ", sectionType=" + sectionType
                + ", sectionStart=" + sectionStart
                + ", sectionSize=" + sectionSize
                + ", parent=" + (parent == null ? "null" : parent.sectionId)
                + ", children=" + (children == null ? 0 : children.size()) + "]";
    }

    private String parentSectionId() {
        return parent == null ? null : parent.sectionId;
    }

    private int parentStart() {
        return parent == null ? -1 : parent.sectionStart;
    }
}
