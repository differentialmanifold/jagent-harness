package io.github.differentialmanifold.jagentharness.core.fs;

import java.time.Instant;

public class KnowledgeFile {

    public static final String TYPE_FILE = "file";
    public static final String TYPE_DIRECTORY = "directory";

    private String path;
    private String parentPath;
    private String name;
    private String nodeType;
    private String content;
    private String contentType;
    private String contentHash;
    private Instant createdAt;
    private Instant updatedAt;

    public KnowledgeFile() {
    }

    public KnowledgeFile(String path,
                         String nodeType,
                         String content,
                         String contentType,
                         Instant createdAt,
                         Instant updatedAt) {
        this(path, nodeType, content, contentType, null, createdAt, updatedAt);
    }

    public KnowledgeFile(String path,
                         String nodeType,
                         String content,
                         String contentType,
                         String contentHash,
                         Instant createdAt,
                         Instant updatedAt) {
        this.path = KnowledgeFilePaths.normalize(path);
        this.parentPath = KnowledgeFilePaths.parent(this.path);
        this.name = KnowledgeFilePaths.fileName(this.path);
        this.nodeType = nodeType == null || nodeType.trim().isEmpty() ? TYPE_FILE : nodeType.trim();
        this.content = content == null ? "" : content;
        this.contentType = contentType == null || contentType.trim().isEmpty()
                ? "text/markdown"
                : contentType.trim();
        this.contentHash = contentHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = KnowledgeFilePaths.normalize(path);
        this.parentPath = KnowledgeFilePaths.parent(this.path);
        this.name = KnowledgeFilePaths.fileName(this.path);
    }

    public String getParentPath() {
        return parentPath;
    }

    public void setParentPath(String parentPath) {
        this.parentPath = parentPath == null || parentPath.trim().isEmpty()
                ? ""
                : KnowledgeFilePaths.normalize(parentPath);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content == null ? "" : content;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getBytes() {
        return content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
