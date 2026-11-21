package com.ffxi.viewer.debug;

import com.ffxi.model.DatResourceGraph;
import com.ffxi.model.DirectoryResource;
import com.ffxi.model.EndSectionResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatResourceGraphJsonExporterTest {

    @Test
    @DisplayName("toJson serializes DirectoryResource hierarchy and EndSectionResource fields")
    void testToJsonSerializesSyntheticGraph() {
        DirectoryResource root = new DirectoryResource("root", null, 0, 0, null);
        root.addChild(new EndSectionResource("end", "00", 64, 32));
        DatResourceGraph graph = new DatResourceGraph(root, "ROM/test.DAT");

        String json = DatResourceGraphJsonExporter.toJson(graph);

        assertThat(json)
                .contains("\"root\":")
                .contains("\"kind\": \"DirectoryResource\"")
                .contains("\"sectionType\": \"ROOT\"")
                .contains("\"kind\": \"EndSectionResource\"")
                .contains("\"sectionId\": \"end\"")
                .contains("\"sectionSize\": 32");
    }
}
