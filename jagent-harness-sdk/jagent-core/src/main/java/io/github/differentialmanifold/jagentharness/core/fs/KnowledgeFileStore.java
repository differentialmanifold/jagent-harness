package io.github.differentialmanifold.jagentharness.core.fs;

import java.util.List;

public interface KnowledgeFileStore {

    KnowledgeFile readFile(String path);

    List<KnowledgeFile> listFiles(String prefix);

    KnowledgeFile writeFile(String path, String content, String contentType);

    void deleteFile(String path);

    default KnowledgeFile readFile(KnowledgeScope scope, String path) {
        return requireGlobal(scope) ? readFile(path) : null;
    }

    default List<KnowledgeFile> listFiles(KnowledgeScope scope, String prefix) {
        return requireGlobal(scope) ? listFiles(prefix) : java.util.Collections.<KnowledgeFile>emptyList();
    }

    default KnowledgeFile writeFile(KnowledgeScope scope, String path, String content, String contentType) {
        if (!requireGlobal(scope)) {
            throw new UnsupportedOperationException("Project-scoped knowledge files are not supported by this store");
        }
        return writeFile(path, content, contentType);
    }

    default void deleteFile(KnowledgeScope scope, String path) {
        if (!requireGlobal(scope)) {
            throw new UnsupportedOperationException("Project-scoped knowledge files are not supported by this store");
        }
        deleteFile(path);
    }

    default boolean requireGlobal(KnowledgeScope scope) {
        return scope == null || scope.isGlobal();
    }
}
