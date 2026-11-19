package com.ffxi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DirectoryResourceTest {

    @Test
    void cyclicParentAndChildrenDoNotOverflowEqualsHashCodeOrToString() {
        DirectoryResource root = new DirectoryResource("ROOT", "S01_Directory", 0, 0x40, null);
        DirectoryResource child = new DirectoryResource("CHLD", "S01_Directory", 0x10, 0x20, root);
        root.addChild(child);

        // Even an artificial cycle back to root as a child must not overflow
        child.addChild(root);

        assertThatCode(() -> {
            int h1 = root.hashCode();
            int h2 = child.hashCode();
            assertThat(h1).isNotEqualTo(h2);
            assertThat(root).isEqualTo(root);
            assertThat(root).isNotEqualTo(child);
            assertThat(root.toString()).contains("ROOT").contains("parent=null").contains("children=1");
            assertThat(child.toString()).contains("CHLD").contains("parent=ROOT").contains("children=1");
        }).doesNotThrowAnyException();

        DirectoryResource childTwin = new DirectoryResource("CHLD", "S01_Directory", 0x10, 0x20, root);
        assertThat(child).isEqualTo(childTwin);
        assertThat(child.hashCode()).isEqualTo(childTwin.hashCode());

        Set<DirectoryResource> set = new HashSet<>();
        set.add(root);
        set.add(child);
        set.add(childTwin);
        assertThat(set).hasSize(2);
    }

    @Test
    void differentParentsProduceNonEqualDirectories() {
        DirectoryResource p1 = new DirectoryResource("P001", "S01_Directory", 0, 0x20, null);
        DirectoryResource p2 = new DirectoryResource("P002", "S01_Directory", 0x20, 0x20, null);
        DirectoryResource a = new DirectoryResource("SAME", "S01_Directory", 0x40, 0x10, p1);
        DirectoryResource b = new DirectoryResource("SAME", "S01_Directory", 0x40, 0x10, p2);
        DirectoryResource orphan = new DirectoryResource("SAME", "S01_Directory", 0x40, 0x10, null);

        assertThat(a).isNotEqualTo(b).isNotEqualTo(orphan).isNotEqualTo("not a dir");
    }
}
