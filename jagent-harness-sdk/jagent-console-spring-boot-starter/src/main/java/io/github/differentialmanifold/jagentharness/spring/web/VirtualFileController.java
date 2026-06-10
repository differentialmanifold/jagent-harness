package io.github.differentialmanifold.jagentharness.spring.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFilePaths;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.spring.web.dto.VirtualFileImportResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.VirtualFileContentResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.VirtualFileResponse;
import io.github.differentialmanifold.jagentharness.spring.web.dto.VirtualFileWriteRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/vfs")
public class VirtualFileController {

    private final KnowledgeFileStore knowledgeFileStore;

    public VirtualFileController(KnowledgeFileStore knowledgeFileStore) {
        this.knowledgeFileStore = knowledgeFileStore;
    }

    @GetMapping("/files")
    public List<VirtualFileResponse> listFiles(@RequestParam(value = "prefix", required = false) String prefix) {
        List<VirtualFileResponse> responses = new ArrayList<VirtualFileResponse>();
        for (KnowledgeFile file : knowledgeFileStore.listFiles(prefix)) {
            responses.add(new VirtualFileResponse(file));
        }
        responses.sort((left, right) -> left.getPath().compareTo(right.getPath()));
        return responses;
    }

    @GetMapping("/files/content")
    public VirtualFileContentResponse readFile(@RequestParam("path") String path) {
        KnowledgeFile file = knowledgeFileStore.readFile(path);
        if (file == null) {
            throw new IllegalArgumentException("Knowledge file not found: " + path);
        }
        return new VirtualFileContentResponse(file);
    }

    @PutMapping("/files")
    public VirtualFileResponse writeFile(@RequestBody VirtualFileWriteRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        validateKnowledgePath(request.getPath());
        return new VirtualFileResponse(knowledgeFileStore.writeFile(
                request.getPath(),
                request.getContent(),
                request.getContentType()));
    }

    @DeleteMapping("/files")
    public void deleteFile(@RequestParam("path") String path) {
        validateKnowledgePath(path);
        knowledgeFileStore.deleteFile(path);
    }

    @GetMapping(value = "/skills/export", produces = "application/zip")
    public ResponseEntity<byte[]> exportSkills() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
        try {
            for (KnowledgeFile file : knowledgeFileStore.listFiles("skills")) {
                String path = normalizeSkillPath(file.getPath());
                ZipEntry entry = new ZipEntry(path);
                zip.putNextEntry(entry);
                zip.write(file.getContent().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"skills.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(output.toByteArray());
    }

    @PostMapping(value = "/skills/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VirtualFileImportResponse importSkills(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Skills zip file is required.");
        }

        int imported = 0;
        ZipInputStream zip = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String path = normalizeImportedSkillPath(entry.getName());
                    if (path != null) {
                        String content = readZipEntry(zip);
                        knowledgeFileStore.writeFile(path, content, contentType(path));
                        imported += 1;
                    }
                }
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }

        return new VirtualFileImportResponse(imported);
    }

    private void validateKnowledgePath(String path) {
        String normalized = KnowledgeFilePaths.normalize(path);
        if ("AGENTS.md".equals(normalized)) {
            return;
        }
        if (normalized.startsWith("skills/")) {
            validateSkillFilePath(normalized);
            return;
        }
        throw new IllegalArgumentException("Unsupported knowledge file path: " + path);
    }

    private void validateSkillFilePath(String normalized) {
        String[] parts = normalized.split("/");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Skill file path must be under skills/{skill}/: " + normalized);
        }
        if ("SKILL.md".equals(parts[parts.length - 1]) && parts.length != 3) {
            throw new IllegalArgumentException("SKILL.md must be directly under skills/{skill}/: " + normalized);
        }
    }

    private String normalizeImportedSkillPath(String zipEntryName) {
        String name = zipEntryName == null ? "" : zipEntryName.trim().replace('\\', '/');
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.isEmpty() || name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) {
            return null;
        }
        if (!name.startsWith("skills/")) {
            name = "skills/" + name;
        }
        return normalizeSkillPath(name);
    }

    private String normalizeSkillPath(String path) {
        String normalized = KnowledgeFilePaths.normalize(path);
        if (!normalized.startsWith("skills/")) {
            throw new IllegalArgumentException("Unsupported knowledge file path: " + path);
        }
        validateSkillFilePath(normalized);
        return normalized;
    }

    private String readZipEntry(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private String contentType(String path) {
        String normalized = path.toLowerCase();
        if (normalized.endsWith(".md") || normalized.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (normalized.endsWith(".json")) {
            return "application/json";
        }
        if (normalized.endsWith(".yml") || normalized.endsWith(".yaml")) {
            return "application/yaml";
        }
        return "text/plain";
    }
}
