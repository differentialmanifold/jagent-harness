package io.github.differentialmanifold.jagentharness.spring.web.dto;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;

public class VirtualFileContentResponse extends VirtualFileResponse {

    private String content;

    public VirtualFileContentResponse() {}

    public VirtualFileContentResponse(KnowledgeFile file) {
        super(file);
        this.content = file.getContent();
    }

    public String getContent() {
        return content;
    }

    public VirtualFileContentResponse(KnowledgeFile file, String source, boolean readOnly) {
        super(file, source, readOnly);
        this.content = file.getContent();
    }
}
