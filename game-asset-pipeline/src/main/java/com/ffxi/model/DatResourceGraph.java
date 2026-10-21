package com.ffxi.model;

public record DatResourceGraph(DirectoryResource rootDirectory, String sourcePath) {
    public DatResourceGraph(DirectoryResource rootDirectory) {
        this(rootDirectory, "");
    }

    public DatResourceGraph {
        sourcePath = sourcePath != null ? sourcePath : "";
    }
}
