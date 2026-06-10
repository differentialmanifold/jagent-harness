package io.github.differentialmanifold.jagentharness.core.fs;

import java.util.List;

public interface KnowledgeFileStore {

    KnowledgeFile readFile(String path);

    List<KnowledgeFile> listFiles(String prefix);

    KnowledgeFile writeFile(String path, String content, String contentType);

    void deleteFile(String path);
}
