package io.github.differentialmanifold.jagentharness.core.fs;

import java.util.List;

public interface KnowledgeFileStore {

    KnowledgeFile readFile(String path);

    List<KnowledgeFile> listFiles(String prefix);

    KnowledgeFile writeFile(String path, String content, String contentType);

    default KnowledgeFile writeFile(String path,
                                    String content,
                                    String contentType,
                                    String expectedContentHash) {
        KnowledgeFile current = readFile(path);
        String actualHash = current == null ? null : current.getContentHash();
        if (!java.util.Objects.equals(expectedContentHash, actualHash)) {
            throw new KnowledgeFileConflictException(path);
        }
        return writeFile(path, content, contentType);
    }

    void deleteFile(String path);
}
