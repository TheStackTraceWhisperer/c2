package com.ffxi.model;

public record EndSectionResource(String sectionId, String sectionType, int sectionStart, int sectionSize) implements ResourceNode {
}
