package io.github.differentialmanifold.jagentharness.core.fs;

public class KnowledgeFileConflictException extends RuntimeException {

    public KnowledgeFileConflictException(String path) {
        super("Knowledge file changed: " + path);
    }
}
