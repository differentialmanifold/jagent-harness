package io.github.differentialmanifold.jagentharness.spring.web.dto;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFilePaths;

public class VirtualFileResponse {

    private String path;
    private String uri;
    private String name;
    private String directory;
    private String contentType;
    private int bytes;
    private String createdAt;
    private String updatedAt;

    public VirtualFileResponse() {
    }

    public VirtualFileResponse(KnowledgeFile file) {
        this.path = file.getPath();
        this.uri = file.getPath();
        this.name = KnowledgeFilePaths.fileName(file.getPath());
        this.directory = KnowledgeFilePaths.parent(file.getPath());
        this.contentType = file.getContentType();
        this.bytes = file.getBytes();
        this.createdAt = file.getCreatedAt() == null ? null : file.getCreatedAt().toString();
        this.updatedAt = file.getUpdatedAt() == null ? null : file.getUpdatedAt().toString();
    }

    public String getPath() {
        return path;
    }

    public String getUri() {
        return uri;
    }

    public String getName() {
        return name;
    }

    public String getDirectory() {
        return directory;
    }

    public String getContentType() {
        return contentType;
    }

    public int getBytes() {
        return bytes;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }
}
